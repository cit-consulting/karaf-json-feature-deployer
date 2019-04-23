package ru.citc.karaf.deployer.feature.json;

import org.apache.felix.fileinstall.ArtifactListener;
import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.features.DeploymentEvent;
import org.apache.karaf.features.DeploymentListener;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.util.tracker.BaseActivator;
import org.apache.karaf.util.tracker.annotation.RequireService;
import org.apache.karaf.util.tracker.annotation.Services;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Hashtable;

@Services(requires = @RequireService(FeaturesService.class))
public class Activator extends BaseActivator {
    private DeploymentListener deploymentListener;
    private FeatureDeploymentListener featureListener;

    @Override
    protected void doStart() {
        final FeaturesService service = getTrackedService(FeaturesService.class);
        if (service != null) {
            final JsonFeatureURLHandler handler = new JsonFeatureURLHandler();
            final Hashtable<String, Object> props = new Hashtable<>();
            props.put("url.handler.protocol", JsonFeatureURLHandler.PREFIX);
            register(URLStreamHandlerService.class, handler, props);


            featureListener = new FeatureDeploymentListener(service, bundleContext);
            register(new Class[]{ArtifactUrlTransformer.class, ArtifactListener.class}, featureListener);

            final DeploymentListener deploymentListener = new DeploymentFinishedListener(featureListener);
            service.registerListener(deploymentListener);
        }
    }

    protected void doStop() {
        super.doStop();
        if (deploymentListener != null) {
            FeaturesService service = getTrackedService(FeaturesService.class);
            if (service != null) {
                service.unregisterListener(deploymentListener);
            }
            deploymentListener = null;
        }
        if (featureListener != null) {
            featureListener.stop();
            featureListener = null;
        }
    }

    private static final class DeploymentFinishedListener implements DeploymentListener {
        private final Logger logger = LoggerFactory.getLogger(getClass());

        private final FeatureDeploymentListener listener;
        private boolean started;

        DeploymentFinishedListener(final FeatureDeploymentListener listener) {
            this.listener = listener;
        }

        @Override
        public void deploymentEvent(final DeploymentEvent e) {
            if (e == DeploymentEvent.DEPLOYMENT_FINISHED && !started) {
                synchronized (logger) {
                    logger.info("Deployment finished. Registering FeatureDeploymentListener");
                    try {
                        listener.start();
                        started = true;
                    } catch (Exception ex) {
                        logger.error(ex.getMessage(), ex);
                    }
                }
            }
        }
    }
}
