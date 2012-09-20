package biz.aQute.resolve.internal;

import java.io.*;
import java.util.*;

import org.osgi.framework.*;
import org.osgi.framework.namespace.*;
import org.osgi.resource.*;
import org.osgi.service.repository.*;

import aQute.bnd.build.model.*;
import aQute.bnd.build.model.clauses.*;
import aQute.bnd.deployer.repository.*;
import aQute.bnd.header.*;
import aQute.bnd.osgi.resource.*;

public class FrameworkResourceRepository implements Repository {

    private final CapabilityIndex capIndex = new CapabilityIndex();
    private final Resource framework;
    private final EE ee;
    private final List<ExportedPackage> runSystemPackages;

    public FrameworkResourceRepository(Resource frameworkResource, EE ee, List<ExportedPackage> runSystemPackages) {
        this.framework = frameworkResource;
        this.ee = ee;
        this.runSystemPackages = runSystemPackages;
        capIndex.addResource(frameworkResource);

        // Add EEs
        capIndex.addCapability(createEECapability(ee));
        for (EE compat : ee.getCompatible()) {
            capIndex.addCapability(createEECapability(compat));
        }

        // Add system.bundle alias
        Version frameworkVersion = Utils.findIdentityVersion(frameworkResource);
        capIndex.addCapability(new CapReqBuilder(BundleNamespace.BUNDLE_NAMESPACE).addAttribute(BundleNamespace.BUNDLE_NAMESPACE, Constants.SYSTEM_BUNDLE_SYMBOLICNAME)
                .addAttribute(BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE, frameworkVersion).setResource(frameworkResource).buildCapability());
        capIndex.addCapability(new CapReqBuilder(HostNamespace.HOST_NAMESPACE).addAttribute(HostNamespace.HOST_NAMESPACE, Constants.SYSTEM_BUNDLE_SYMBOLICNAME)
                .addAttribute(HostNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE, frameworkVersion).setResource(frameworkResource).buildCapability());

        // Add JRE packages
        loadJREPackages();

        // Add -runsystempackages
        addRunSystemPackages();
    }

    public void addFrameworkCapability(CapReqBuilder builder) {
        Capability cap = builder.setResource(framework).buildCapability();
        capIndex.addCapability(cap);
    }

    private Capability createEECapability(EE ee) {
        CapReqBuilder builder = new CapReqBuilder(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);
        builder.addAttribute(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE, ee.getEEName());
        builder.setResource(framework);
        return builder.buildCapability();
    }

    private void loadJREPackages() {
        InputStream stream = FrameworkResourceRepository.class.getResourceAsStream(ee.name() + ".properties");
        if (stream != null) {
            try {
                Properties properties = new Properties();
                properties.load(stream);

                Parameters params = new Parameters(properties.getProperty("org.osgi.framework.system.packages", ""));
                for (String packageName : params.keySet()) {
                    CapReqBuilder builder = new CapReqBuilder(PackageNamespace.PACKAGE_NAMESPACE);
                    builder.addAttribute(PackageNamespace.PACKAGE_NAMESPACE, packageName);
                    builder.addAttribute(PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE, new Version(0, 0, 0));
                    Capability cap = builder.setResource(framework).buildCapability();
                    capIndex.addCapability(cap);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Error loading JRE package properties", e);
            }
        }
    }

    private void addRunSystemPackages() {
    	if (runSystemPackages == null)
    		return;

        for (ExportedPackage exported : runSystemPackages) {
            CapReqBuilder builder = new CapReqBuilder(PackageNamespace.PACKAGE_NAMESPACE);
            builder.addAttribute(PackageNamespace.PACKAGE_NAMESPACE, exported.getName());
            builder.addAttribute(PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE, Version.parseVersion(exported.getVersionString()));
            Capability cap = builder.setResource(framework).buildCapability();
            capIndex.addCapability(cap);
        }
    }

    public Map<Requirement,Collection<Capability>> findProviders(Collection< ? extends Requirement> requirements) {
        Map<Requirement,Collection<Capability>> result = new HashMap<Requirement,Collection<Capability>>();
        for (Requirement requirement : requirements) {
            List<Capability> matches = new LinkedList<Capability>();
            result.put(requirement, matches);

            capIndex.appendMatchingCapabilities(requirement, matches);
        }
        return result;
    }

}
