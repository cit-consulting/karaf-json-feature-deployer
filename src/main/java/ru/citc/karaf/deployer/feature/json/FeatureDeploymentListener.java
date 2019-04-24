package ru.citc.karaf.deployer.feature.json;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.bundle.core.BundleState;
import org.apache.karaf.bundle.core.BundleStateService;
import org.apache.karaf.features.DeploymentEvent;
import org.apache.karaf.features.DeploymentListener;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

final class FeatureDeploymentListener implements ArtifactUrlTransformer, BundleListener, BundleStateService, DeploymentListener {
    private static final String DESCRIPTOR_SUFFIX = ".features.json";
    private static final long DEPLOYMEMNT_START_TIMEOUT = 15_000L;

    private final FeaturesService featuresService;
    private final BundleContext bundleContext;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<Long, BundleState> states = new ConcurrentHashMap<>();
    private final Object deploymentMutex = new Object();
    private final Object deploymentStartMutex = new Object();
    private volatile boolean deploymentStarted;

    FeatureDeploymentListener(final FeaturesService featuresService, final BundleContext bundleContext) {
        this.featuresService = featuresService;
        this.bundleContext = bundleContext;
    }

    void start() {
        featuresService.registerListener(this);
        bundleContext.addBundleListener(this);
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.STARTING
                    || bundle.getState() == Bundle.ACTIVE) {
                bundleChanged(new BundleEvent(BundleEvent.RESOLVED, bundle));
            }
        }
    }

    void stop() {
        bundleContext.removeBundleListener(this);
        featuresService.unregisterListener(this);
    }

    @Override
    public void deploymentEvent(final DeploymentEvent event) {
        logger.debug("Feature deployment event: {}", event);
        if (event == DeploymentEvent.DEPLOYMENT_FINISHED) {
            synchronized (deploymentMutex) {
                deploymentStarted = false;
                deploymentMutex.notifyAll();
            }
        } else if (event == DeploymentEvent.DEPLOYMENT_STARTED) {
            synchronized (deploymentStartMutex) {
                deploymentStarted = true;
                deploymentStartMutex.notifyAll();
            }
        }
    }

    public URL transform(final URL artifact) {
        try {
            return new URL(JsonFeatureURLHandler.PREFIX, null, artifact.toString());
        } catch (Exception e) {
            logger.error("Unable to build feature bundle", e);
            return null;
        }
    }

    public void bundleChanged(final BundleEvent event) {
        final Bundle bundle = event.getBundle();
        final long bundleId = bundle.getBundleId();
        if (event.getType() != BundleEvent.RESOLVED && event.getType() != BundleEvent.UNINSTALLED
                || bundleId == bundleContext.getBundle().getBundleId()) {
            return;
        }

        synchronized (deploymentMutex) {
            final File storedDescriptorFile = requireNonNull(
                    bundleContext.getDataFile("bundle_" + bundleId + DESCRIPTOR_SUFFIX),
                    "OSGI file system required");
            final FeaturesDescriptor storedDescriptor;
            if (storedDescriptorFile.exists()) {
                try (Reader reader = new InputStreamReader(new FileInputStream(storedDescriptorFile), StandardCharsets.UTF_8)) {
                    final JSONParser parser = new JSONParser();
                    storedDescriptor = FeaturesDescriptor.fromJson((JSONObject) parser.parse(reader));
                } catch (IOException | ParseException ioe) {
                    logger.error("Can't read previous state file {}. Stop processing.", storedDescriptorFile, ioe);
                    return;
                }
            } else {
                storedDescriptor = null;
            }
            final Map<String, Set<String>> featureReqs;
            final Set<URI> requiredReposUris;
            try {
                requiredReposUris = Arrays.stream(featuresService.listRequiredRepositories())
                        .map(Repository::getURI)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                featureReqs = featuresService.listRequirements();
            } catch (Exception e) {
                logger.error("Can't access current Karaf Features state. Stop processing.", e);
                return;
            }
            boolean hasChanges = false;
            if (storedDescriptor != null) {
                final Map<String, Set<String>> reqsToRemove = storedDescriptor.getRequirements();
                for (Map.Entry<String, Set<String>> featuresByRegion : featureReqs.entrySet()) {
                    final String region = featuresByRegion.getKey();
                    if (reqsToRemove.containsKey(region)) {
                        hasChanges |= featuresByRegion.getValue().removeAll(reqsToRemove.get(region));
                    }
                }
                for (String repository : storedDescriptor.getRepositories()) {
                    final URI repoUri = toRepoUri(repository);
                    if (repoUri != null) {
                        hasChanges |= requiredReposUris.remove(repoUri);
                    }
                }
            }
            final FeaturesDescriptor actualDescriptor;
            if (event.getType() == BundleEvent.RESOLVED) {
                final URL descriptorUrl = bundle.getResource(JsonFeatureURLHandler.JSON_FEATURE_DESCRIPTOR_PATH);
                if (descriptorUrl == null) {
                    actualDescriptor = null;
                    logger.debug("JSON features descriptor not found in: {}", bundle);
                } else {
                    try (Reader reader = new InputStreamReader(descriptorUrl.openStream(), StandardCharsets.UTF_8)) {
                        final JSONParser parser = new JSONParser();
                        actualDescriptor = FeaturesDescriptor.fromJson((JSONObject) parser.parse(reader));
                        for (String repository : actualDescriptor.getRepositories()) {
                            final URI reposUri = toRepoUri(repository);
                            if (reposUri == null) {
                                logger.warn("Can't resolve repo spec {}", reposUri);
                            } else {
                                hasChanges |= requiredReposUris.add(reposUri);
                            }
                        }
                        for (Map.Entry<String, Set<String>> newFeatureToRegion : actualDescriptor.getRequirements().entrySet()) {
                            final Set<String> regionFeatures = featureReqs.computeIfAbsent(newFeatureToRegion.getKey(), key -> new LinkedHashSet<>());
                            hasChanges |= regionFeatures.addAll(newFeatureToRegion.getValue());
                        }
                    } catch (IOException | ParseException e) {
                        logger.error("Can't read JSON feature descriptor: {}", descriptorUrl, e);
                        return;
                    }
                }
            } else {
                states.remove(bundleId);
                actualDescriptor = null;
            }

            try {
                if (hasChanges) {
                    logger.info("Request deployment for: {}", bundle);
                    states.put(bundleId, BundleState.Starting);
                    featuresService.updateReposAndRequirements(requiredReposUris, featureReqs,
                            EnumSet.noneOf(FeaturesService.Option.class));
                    //TODO Karaf 4.2.5 not throw exception on unsatisfied requirements nor start deployment process
                    synchronized (deploymentStartMutex) {
                        deploymentStartMutex.wait(DEPLOYMEMNT_START_TIMEOUT);
                        if (deploymentStarted) {
                            logger.debug("Deployment started for {}", bundle);
                            deploymentMutex.wait();
                        }
                    }
                    // Because Karaf does not throw any exception on resolution fail (why?) we needs to check actual
                    // requirements
                    if (actualDescriptor != null) {
                        actualDescriptor.ensureSatisfied(featuresService.listRequirements());
                        states.put(bundleId, BundleState.Active);
                        logger.info("Feature deployment finished for: {}", bundle);
                    }

                } else {
                    logger.debug("No deployment required for: {}", bundle);
                }
                saveState(storedDescriptorFile, actualDescriptor);
            } catch (Exception e) {
                logger.error("Can't apply requirements for {}.", bundle, e);
                if (event.getType() == BundleEvent.RESOLVED) {
                    states.put(bundleId, BundleState.Failure);
                }
            }
        }
    }

    public boolean canHandle(final File artifact) {
        return artifact.getName().toLowerCase(Locale.ENGLISH).endsWith(DESCRIPTOR_SUFFIX);
    }

    @Override
    public String getName() {
        return "JSON Feature Deployer";
    }

    @Override
    public String getDiag(final Bundle bundle) {
        return "";
    }

    @Override
    public BundleState getState(final Bundle bundle) {
        return states.getOrDefault(bundle.getBundleId(), BundleState.Unknown);
    }

    private void saveState(final File descriptorFile, final FeaturesDescriptor actualDescriptor) {
        if (actualDescriptor == null) {
            if (descriptorFile.exists() && !descriptorFile.delete()) {
                logger.warn("Can't delete old state descriptor file {}", descriptorFile);
            }
        } else {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(descriptorFile, false),
                    StandardCharsets.UTF_8)) {
                actualDescriptor.toJson().writeJSONString(writer);
            } catch (IOException e) {
                logger.warn("Can't save new state descriptor {}", actualDescriptor, e);
            }
        }
    }

    private URI toRepoUri(final String repositorySpec) {
        URI repoUri;
        try {
            repoUri = new URI(repositorySpec);
            if (!repoUri.isAbsolute()) {
                repoUri = null;
            }
        } catch (URISyntaxException e) {
            repoUri = null;
        }
        if (repoUri == null) {
            try {
                repoUri = featuresService.getRepositoryUriFor(repositorySpec, null);
            } catch (Exception e) {
                logger.warn("Can't get repo by name {}", repositorySpec, e);
            }
        }
        return repoUri;
    }
}
