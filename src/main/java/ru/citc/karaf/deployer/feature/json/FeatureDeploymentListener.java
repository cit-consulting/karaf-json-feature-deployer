package ru.citc.karaf.deployer.feature.json;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
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
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

final class FeatureDeploymentListener implements ArtifactUrlTransformer, BundleListener {
    private static final String DESCRIPTOR_SUFFIX = ".features.json";
    private final FeaturesService featuresService;
    private final BundleContext bundleContext;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    FeatureDeploymentListener(final FeaturesService featuresService, final BundleContext bundleContext) {
        this.featuresService = featuresService;
        this.bundleContext = bundleContext;
    }

    void start() {
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
    }

    public URL transform(final URL artifact) throws Exception {
        try {
            return new URL(JsonFeatureURLHandler.PREFIX, null, artifact.toString());
        } catch (Exception e) {
            logger.error("Unable to build feature bundle", e);
            return null;
        }
    }

    public void bundleChanged(final BundleEvent event) {
        if (event.getType() != BundleEvent.RESOLVED
                && event.getType() != BundleEvent.UNINSTALLED) {
            return;
        }
        final Bundle bundle = event.getBundle();
        synchronized (logger) {
            final File storedDescriptorFile = requireNonNull(
                    bundleContext.getDataFile("bundle_" + bundle.getBundleId() + DESCRIPTOR_SUFFIX),
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
            final FeaturesDescriptor newFeaturesDescriptor;
            if (event.getType() == BundleEvent.RESOLVED) {
                final URL descriptorUrl = bundle.getResource(JsonFeatureURLHandler.JSON_FEATURE_DESCRIPTOR_PATH);
                if (descriptorUrl == null) {
                    newFeaturesDescriptor = null;
                    logger.debug("JSON features descriptor not found. Skip bundle {}", bundle.getBundleId());
                } else {
                    try (Reader reader = new InputStreamReader(descriptorUrl.openStream(), StandardCharsets.UTF_8)) {
                        final JSONParser parser = new JSONParser();
                        newFeaturesDescriptor = FeaturesDescriptor.fromJson((JSONObject) parser.parse(reader));
                        for (String repository : newFeaturesDescriptor.getRepositories()) {
                            final URI reposUri = toRepoUri(repository);
                            if (reposUri == null) {
                                logger.warn("Can't resolve repo spec {}", reposUri);
                            } else {
                                hasChanges |= requiredReposUris.add(reposUri);
                            }
                        }
                        for (Map.Entry<String, Set<String>> newFeatureToRegion : newFeaturesDescriptor.getRequirements().entrySet()) {
                            final Set<String> regionFeatures = featureReqs.computeIfAbsent(newFeatureToRegion.getKey(), key -> new LinkedHashSet<>());
                            hasChanges |= regionFeatures.addAll(newFeatureToRegion.getValue());
                        }
                    } catch (IOException | ParseException e) {
                        logger.error("Can't read JSON feature descriptor: {}", descriptorUrl, e);
                        return;
                    }
                }
            } else {
                newFeaturesDescriptor = null;
            }

            try {
                if (hasChanges) {
                    featuresService.updateReposAndRequirements(requiredReposUris, featureReqs, EnumSet.noneOf(FeaturesService.Option.class));
                } else {
                    logger.debug("No actual changes in updated descriptor. Bundle id: {}", bundle.getBundleId());
                }
                if (newFeaturesDescriptor == null) {
                    if (storedDescriptorFile.exists() && !storedDescriptorFile.delete()) {
                        logger.warn("Can't delete old state descriptor file {}", storedDescriptorFile);
                    }
                } else {
                    try (Writer writer = new OutputStreamWriter(new FileOutputStream(storedDescriptorFile, false), StandardCharsets.UTF_8)) {
                        newFeaturesDescriptor.toJson().writeJSONString(writer);
                    } catch (IOException e) {
                        logger.warn("Can't save new state descriptor {}", storedDescriptor, e);
                    }
                }
            } catch (Exception e) {
                logger.error("Fail when applying new feature requirements {}", featureReqs, e);
                if (event.getType() == BundleEvent.RESOLVED) {
                    
                }
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

    public boolean canHandle(final File artifact) {
        return artifact.getName().toLowerCase(Locale.ENGLISH).endsWith(DESCRIPTOR_SUFFIX);
    }
}
