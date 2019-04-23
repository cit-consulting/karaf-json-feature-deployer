package ru.citc.karaf.deployer.feature.json;

import org.apache.felix.utils.version.VersionRange;
import org.apache.karaf.features.internal.model.Feature;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class FeaturesDescriptor {
    static final String VERSION_SEPARATOR = "/";
    static final String FEATURE_VERSION = "version";
    private static final String FIELD_FEATURES_REQUIREMENTS_VERSION = "featuresRequirementsVersion";
    private static final String FIELD_REPOSITORIES = "repositories";
    private static final String FIELD_FEATURES = "features";
    private static final String FEATURE_REQ_PREFIX = "feature:";
    private static final String FEATURE_NAME = "name";
    private static final VersionRange DEFAULT_VERSION_RANGE = new VersionRange(Feature.DEFAULT_VERSION);
    private final Set<String> repos = new LinkedHashSet<>();
    private final Map<String, Set<String>> featuresRequirements = new LinkedHashMap<>();

    private FeaturesDescriptor() {
    }

    static FeaturesDescriptor fromJson(final JSONObject jsonObject) {
        final Object descriptorVersion = jsonObject.get(FIELD_FEATURES_REQUIREMENTS_VERSION);
        if (descriptorVersion == null) {
            throw new IllegalArgumentException("Invalid descriptor: 'featuresRequirementsVersion' are required.");
        }
        if (new BigDecimal(String.valueOf(descriptorVersion)).intValue() != 1) {
            throw new IllegalArgumentException("Unsupported descriptor version " + descriptorVersion);
        }
        final FeaturesDescriptor instance = new FeaturesDescriptor();
        final List<String> repositories = (List<String>) jsonObject.getOrDefault(FIELD_REPOSITORIES, Collections.emptyList());
        for (String repo : repositories) {
            instance.repos.add(repo);
        }
        final Map<String, List<Map<String, String>>> featuresReqs = (Map<String, List<Map<String, String>>>) jsonObject.get(FIELD_FEATURES);
        for (Map.Entry<String, List<Map<String, String>>> entry : featuresReqs.entrySet()) {
            final Set<String> regionFeatures = instance.featuresRequirements
                    .computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>());
            for (Map<String, String> featureObject : entry.getValue()) {
                final String name = featureObject.get(FEATURE_NAME);
                if (name == null || name.trim().isEmpty()) {
                    throw new IllegalArgumentException("Name is required for feature spec. Source: " + featureObject);
                }
                final String version = featureObject.get(FEATURE_VERSION);
                regionFeatures.add(FEATURE_REQ_PREFIX + name + VERSION_SEPARATOR
                        + (version == null ? DEFAULT_VERSION_RANGE : new VersionRange(version, true)));
            }
        }
        return instance;
    }

    Set<String> getRepositories() {
        return Collections.unmodifiableSet(repos);
    }

    Map<String, Set<String>> getRequirements() {
        return Collections.unmodifiableMap(featuresRequirements);
    }

    JSONObject toJson() {
        final JSONObject result = new JSONObject();
        result.put(FIELD_FEATURES_REQUIREMENTS_VERSION, "1.0");
        final JSONArray reposArray = new JSONArray();
        reposArray.addAll(repos);
        result.put(FIELD_REPOSITORIES, reposArray);
        final JSONObject allFeatures = new JSONObject();
        result.put(FIELD_FEATURES, allFeatures);
        for (Map.Entry<String, Set<String>> regionToFeatures : featuresRequirements.entrySet()) {
            final JSONArray regionFeatures = new JSONArray();
            allFeatures.put(regionToFeatures.getKey(), regionFeatures);
            for (String fetureReqSpec : regionToFeatures.getValue()) {
                final String[] nameToVersion = fetureReqSpec.substring(FEATURE_REQ_PREFIX.length()).split(VERSION_SEPARATOR, 2);
                final JSONObject featureObject = new JSONObject();
                featureObject.put(FEATURE_NAME, nameToVersion[0]);
                final VersionRange versionRange = new VersionRange(nameToVersion[1]);
                if (!versionRange.equals(DEFAULT_VERSION_RANGE)) {
                    if (versionRange.isPointVersion()) {
                        featureObject.put(FEATURE_VERSION, versionRange.getFloor().toString());
                    } else {
                        featureObject.put(FEATURE_VERSION, versionRange.toString());
                    }
                }
                regionFeatures.add(featureObject);
            }
        }
        return result;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FeaturesDescriptor that = (FeaturesDescriptor) o;
        return repos.equals(that.repos) &&
                featuresRequirements.equals(that.featuresRequirements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repos, featuresRequirements);
    }

    @Override
    public String toString() {
        return "FeaturesDescriptor{" +
                "version=1,repos=" + repos +
                ", featuresRequirements=" + featuresRequirements +
                '}';
    }
}
