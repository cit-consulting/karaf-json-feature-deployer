package ru.citc.karaf.deployer.feature.json;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class FeaturesDescriptorTest {
    private JSONParser parser = new JSONParser();

    @Test
    public void parseFull() throws IOException, ParseException {
        final FeaturesDescriptor descriptor = FeaturesDescriptor.fromJson(readDescriptor("/full.features.json"));
        assertNotNull(descriptor);
        assertThat(descriptor.getRepositories(), containsInAnyOrder(
                "mvn:org.apache.cxf.karaf/apache-cxf/3.3.1/xml/features",
                "mvn:org.apache.camel.karaf/apache-camel/2.18.5/xml/features",
                "spring-legacy"));
        final Map<String, Set<String>> reqs = descriptor.getRequirements();
        assertNotNull(reqs);
        assertThat(reqs, allOf(hasKey("root"), hasKey("root/camel218")));
        Set<String> rootFeatures = reqs.get("root");
        assertNotNull(rootFeatures);
        assertThat(rootFeatures, containsInAnyOrder(
                "feature:transaction/0",
                "feature:connector/0"));
        Set<String> camel218Features = reqs.get("root/camel218");
        assertNotNull(camel218Features);
        assertThat(camel218Features, containsInAnyOrder(
                "feature:spring-dm/0",
                "feature:camel-spring-dm/[2.18.5,2.18.5]",
                "feature:camel-spring/[2.18.5,2.18.5]"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseNoVersion() throws IOException, ParseException {
        FeaturesDescriptor.fromJson((JSONObject) parser.parse(new StringReader("{}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseInvalidVersion() throws IOException, ParseException {
        FeaturesDescriptor.fromJson((JSONObject) parser.parse(new StringReader("{\"featuresRequirementsVersion\": \"2.0\"}")));
    }

    @Test
    public void parseSerializeToJson() throws IOException, ParseException {
        final JSONObject origJson = readDescriptor("/full.features.json");
        final FeaturesDescriptor orig = FeaturesDescriptor.fromJson(origJson);
        assertEquals(origJson, orig.toJson());
    }

    private JSONObject readDescriptor(final String resourcePath) throws IOException, ParseException {
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream(resourcePath), StandardCharsets.UTF_8)) {
            return (JSONObject) parser.parse(reader);
        }
    }
}