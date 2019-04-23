package ru.citc.karaf.deployer.feature.json;

import org.apache.karaf.util.DeployerUtils;
import org.apache.karaf.util.StreamUtils;
import org.osgi.framework.Constants;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

final class JsonFeatureURLHandler extends AbstractURLStreamHandlerService {
    static final String PREFIX = "featurejson";
    static final String FEATURE_JSON_PATH = "org.apache.karaf.features.json";
    static final String JSON_FEATURE_DESCRIPTOR_PATH = "META-INF/" + FEATURE_JSON_PATH + "/features.json";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static byte[] transform(final URL url) throws Exception {
        // Heuristicly retrieve name and version
        String name = url.getPath();
        final int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        final String[] nameVersionStr = DeployerUtils.extractNameVersionType(name);

        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        manifest.getMainAttributes().putValue(Constants.BUNDLE_SYMBOLICNAME, nameVersionStr[0]);
        manifest.getMainAttributes().putValue(Constants.BUNDLE_VERSION, nameVersionStr[1]);

        // Put content
        try (ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
             JarOutputStream out = new JarOutputStream(os)) {

            ZipEntry e = new ZipEntry(JarFile.MANIFEST_NAME);
            out.putNextEntry(e);
            manifest.write(out);
            out.closeEntry();
            e = new ZipEntry("META-INF/");
            out.putNextEntry(e);
            e = new ZipEntry("META-INF/" + FEATURE_JSON_PATH + "/");
            out.putNextEntry(e);
            out.closeEntry();
            e = new ZipEntry(JSON_FEATURE_DESCRIPTOR_PATH);
            out.putNextEntry(e);
            try (InputStream fis = url.openStream()) {
                StreamUtils.copy(fis, out);
            }
            out.closeEntry();
            out.close();
            return os.toByteArray();
        }
    }

    @Override
    public URLConnection openConnection(final URL url) throws IOException {
        final String subUri = url.getPath();
        if (subUri == null || new URL(subUri).getPath().isEmpty()) {
            throw new MalformedURLException("Path cannot be null or empty. Syntax: " + PREFIX + " uri");
        }

        logger.debug("Features JSON URL is: [{}]", subUri);
        return new Connection(url);
    }

    static final class Connection extends URLConnection {
        private final Logger logger = LoggerFactory.getLogger(getClass());

        Connection(final URL url) {
            super(url);
        }

        @Override
        public void connect() {
        }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                final String subUri = url.getPath();
                final byte[] content = transform(new URL(subUri));
                return new ByteArrayInputStream(content);
            } catch (Exception e) {
                logger.error("Error opening features JS url", e);
                throw new IOException("Error opening features xml url", e);
            }
        }
    }
}
