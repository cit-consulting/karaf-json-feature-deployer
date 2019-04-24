package ru.citc.karaf.deployer.feature.json;

import org.apache.felix.fileinstall.ArtifactListener;
import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.bundle.core.BundleStateService;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.util.tracker.BaseActivator;
import org.apache.karaf.util.tracker.annotation.RequireService;
import org.apache.karaf.util.tracker.annotation.Services;
import org.osgi.service.url.URLStreamHandlerService;

import java.util.Dictionary;
import java.util.Hashtable;

@Services(requires = @RequireService(FeaturesService.class))
public class Activator extends BaseActivator {
    private FeatureDeploymentListener featureListener;

    @Override
    protected void doStart() {
        final FeaturesService service = getTrackedService(FeaturesService.class);
        if (service != null) {
            final URLStreamHandlerService handler = new JsonFeatureURLHandler();
            final Dictionary<String, Object> props = new Hashtable<>();
            props.put("url.handler.protocol", JsonFeatureURLHandler.PREFIX);
            register(URLStreamHandlerService.class, handler, props);
            featureListener = new FeatureDeploymentListener(service, bundleContext);
            register(new Class[]{ArtifactUrlTransformer.class, ArtifactListener.class, BundleStateService.class},
                    featureListener);
            featureListener.start();
        }
    }

    protected void doStop() {
        super.doStop();
        if (featureListener != null) {
            featureListener.stop();
            featureListener = null;
        }
    }
}
