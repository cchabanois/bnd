package biz.aQute.resolve.internal;

import static org.osgi.framework.namespace.BundleNamespace.*;
import static org.osgi.framework.namespace.PackageNamespace.*;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.Map.Entry;

import org.osgi.framework.*;
import org.osgi.framework.namespace.*;
import org.osgi.namespace.contract.*;
import org.osgi.resource.*;
import org.osgi.resource.Resource;
import org.osgi.service.log.*;
import org.osgi.service.repository.*;
import org.osgi.service.resolver.*;

import aQute.bnd.build.*;
import aQute.bnd.build.model.*;
import aQute.bnd.header.*;
import aQute.bnd.osgi.*;
import aQute.bnd.osgi.resource.*;
import aQute.bnd.service.*;
import aQute.libg.filters.*;
import aQute.libg.filters.Filter;

public class BndrunResolveContext extends ResolveContext {

    private static final String CONTRACT_OSGI_FRAMEWORK = "OSGiFramework";
    private static final String IDENTITY_INITIAL_RESOURCE = "<<INITIAL>>";

    private static final String SYMBOLICNAME_ATTRIBUTE = "bundle-symbolic-name";

    private final List<Repository> repos = new LinkedList<Repository>();
    private final Map<Requirement,List<Capability>> optionalRequirements = new HashMap<Requirement,List<Capability>>();
    private final Map<String,ArrayList<Capability>> cache = new HashMap<String, ArrayList<Capability>>();

    private final BndEditModel runModel;
    private final Registry registry;
    private final LogService log;

    private boolean initialised = false;

    private Resource frameworkResource = null;
    private Version frameworkResourceVersion = null;
    private FrameworkResourceRepository frameworkResourceRepo;
    private Comparator<Capability> capComparator = new CapabilityComparator();

    private Resource inputRequirementsResource = null;
    private EE ee;

    public BndrunResolveContext(BndEditModel runModel, Registry registry, LogService log) {
        this.runModel = runModel;
        this.registry = registry;
        this.log = log;
    }

    protected synchronized void init() {
        if (initialised)
            return;

        loadEE();
        loadRepositories();
        findFramework();
        constructInputRequirements();

        initialised = true;
    }

    private void loadEE() {
        EE tmp = runModel.getEE();
        ee = (tmp != null) ? tmp : EE.JavaSE_1_6;
    }

    private void loadRepositories() {
        // Get all of the repositories from the plugin registry
        List<Repository> allRepos = registry.getPlugins(Repository.class);

        // Reorder/filter if specified by the run model
        List<String> repoNames = runModel.getRunRepos();
        if (repoNames == null) {
            // No filter, use all
            repos.addAll(allRepos);
        } else {
            // Map the repository names...
            Map<String,Repository> repoNameMap = new HashMap<String,Repository>(allRepos.size());
            for (Repository repo : allRepos)
                repoNameMap.put(repo.toString(), repo);

            // Create the result list
            for (String repoName : repoNames) {
                Repository repo = repoNameMap.get(repoName);
                if (repo != null)
                    repos.add(repo);
            }
        }
    }

    private void findFramework() {
        String header = runModel.getRunFw();
        if (header == null)
            return;

        // Get the identity and version of the requested JAR
        Parameters params = new Parameters(header);
        if (params.size() > 1)
            throw new IllegalArgumentException("Cannot specify more than one OSGi Framework.");
        Entry<String,Attrs> entry = params.entrySet().iterator().next();
        String identity = entry.getKey();

        String versionStr = entry.getValue().get("version");

        // Construct a filter & requirement to find matches
        Filter filter = new SimpleFilter(IdentityNamespace.IDENTITY_NAMESPACE, identity);
        if (versionStr != null)
            filter = new AndFilter().addChild(filter).addChild(new LiteralFilter(Filters.fromVersionRange(versionStr)));
        Requirement frameworkReq = new CapReqBuilder(IdentityNamespace.IDENTITY_NAMESPACE).addDirective(Namespace.REQUIREMENT_FILTER_DIRECTIVE, filter.toString()).buildSyntheticRequirement();

        // Iterate over repos looking for matches
        for (Repository repo : repos) {
            Map<Requirement,Collection<Capability>> providers = repo.findProviders(Collections.singletonList(frameworkReq));
            Collection<Capability> frameworkCaps = providers.get(frameworkReq);
            if (frameworkCaps != null) {
                for (Capability frameworkCap : frameworkCaps) {
                    if (findFrameworkContractCapability(frameworkCap.getResource()) != null) {
                        Version foundVersion = toVersion(frameworkCap.getAttributes().get(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE));
                        if (foundVersion != null) {
                            if (frameworkResourceVersion == null || (foundVersion.compareTo(frameworkResourceVersion) > 0)) {
                                frameworkResource = frameworkCap.getResource();
                                frameworkResourceVersion = foundVersion;
                                frameworkResourceRepo = new FrameworkResourceRepository(frameworkResource, ee, runModel.getSystemPackages());
                            }
                        }
                    }
                }
            }
        }
    }

    private void constructInputRequirements() {
        List<Requirement> requires = runModel.getRunRequires();
        String resourceBsn = getResourceBsn(runModel);
        if ((requires == null || requires.isEmpty()) && resourceBsn == null) {
            inputRequirementsResource = null;
        } else {
            ResourceBuilder resBuilder = new ResourceBuilder();
            CapReqBuilder identity = new CapReqBuilder(IdentityNamespace.IDENTITY_NAMESPACE).addAttribute(IdentityNamespace.IDENTITY_NAMESPACE, IDENTITY_INITIAL_RESOURCE);
            resBuilder.addCapability(identity);

            if (requires != null) {
	            for (Requirement req : requires) {
	                resBuilder.addRequirement(req);
	            }
            }

            if (resourceBsn != null) {
            	CapReqBuilder resourceReq = new CapReqBuilder(IdentityNamespace.IDENTITY_NAMESPACE).addAttribute(IdentityNamespace.IDENTITY_NAMESPACE, resourceBsn);
                resourceReq.addDirective(Namespace.REQUIREMENT_FILTER_DIRECTIVE, new SimpleFilter(IdentityNamespace.IDENTITY_NAMESPACE, resourceBsn).toString());
            	resBuilder.addRequirement(resourceReq);
            	
            }

            inputRequirementsResource = resBuilder.build();
        }
    }

    private static String getResourceBsn(BndEditModel runModel) {
        if (runModel.getBndResource() != null && runModel.getBndResource().getName().endsWith(".bnd") && runModel.getWorkspace() != null) {
            File projectDir = runModel.getBndResource().getParentFile();
            try {
				Project project = runModel.getWorkspace().getProject(projectDir.getName());
				if (project == null)
					return null;
				if (runModel.isProjectFile()) 
					return project.getSubBuilders().iterator().next().getBsn();
				Builder builder = project.getSubBuilder(runModel.getBndResource());
				return builder.getBsn();
            }
			catch (Exception e) {
			}
        }
    	return null;
    }

    public static boolean isInputRequirementResource(Resource resource) {
        Capability id = resource.getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE).get(0);
        return IDENTITY_INITIAL_RESOURCE.equals(id.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE));
    }

    private static Version toVersion(Object object) throws IllegalArgumentException {
        if (object == null)
            return null;

        if (object instanceof Version)
            return (Version) object;

        if (object instanceof String)
            return Version.parseVersion((String) object);

        throw new IllegalArgumentException(MessageFormat.format("Cannot convert type {0} to Version.", object.getClass().getName()));
    }

    private static Capability findFrameworkContractCapability(Resource resource) {
        List<Capability> contractCaps = resource.getCapabilities(ContractNamespace.CONTRACT_NAMESPACE);
        if (contractCaps != null)
            for (Capability cap : contractCaps) {
                if (CONTRACT_OSGI_FRAMEWORK.equals(cap.getAttributes().get(ContractNamespace.CONTRACT_NAMESPACE)))
                    return cap;
            }
        return null;
    }

    public void addRepository(Repository repo) {
        repos.add(repo);
    }

    @Override
    public Collection<Resource> getMandatoryResources() {
        init();
        if (frameworkResource == null)
            throw new IllegalStateException(MessageFormat.format("Could not find OSGi framework matching {0}.", runModel.getRunFw()));

        List<Resource> resources = new ArrayList<Resource>();
        resources.add(frameworkResource);

        if (inputRequirementsResource != null)
            resources.add(inputRequirementsResource);
        return resources;
    }

    @Override
    public List<Capability> findProviders(Requirement requirement) {
        init();
        ArrayList<Capability> result = cache.get(requirement.toString());
        if (result != null) {
        	return new ArrayList<Capability>(result);
        }

        result = new ArrayList<Capability>();

        // The selected OSGi framework always has the first chance to provide the capabilities
        if (frameworkResourceRepo != null) {
            Map<Requirement,Collection<Capability>> providers = frameworkResourceRepo.findProviders(Collections.singleton(requirement));
            Collection<Capability> capabilities = providers.get(requirement);
            if (capabilities != null && !capabilities.isEmpty()) {
                result.addAll(capabilities);
                // scoreResource
            }
        }

        int score = 0;
        for (Repository repo : repos) {
            Map<Requirement,Collection<Capability>> providers = repo.findProviders(Collections.singleton(requirement));
            Collection<Capability> capabilities = providers.get(requirement);
            if (capabilities != null && !capabilities.isEmpty()) {
                result.ensureCapacity(result.size() + capabilities.size());
                for (Capability capability : capabilities) {
                    // filter out OSGi frameworks & other forbidden resource
                    if (!isPermitted(capability.getResource()))
                        continue;
            		result.add(new CapabilityWrapper(capability, score));
                }
                // for (Capability capability : capabilities)
                // scoreResource(capability.getResource(), score);
            }
            score--;
        }

        if (Namespace.RESOLUTION_OPTIONAL.equals(requirement.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE))) {
            // Only return the framework's capabilities when asked for optional resources.
            List<Capability> fwkCaps = new ArrayList<Capability>(result.size());
            for (Capability capability : result) {
                if (capability.getResource() == frameworkResource)
                    fwkCaps.add(capability);
            }

            // If the framework couldn't provide the requirement then save the list of potential providers
            // to the side, in order to work out the optional resources later.
            if (fwkCaps.isEmpty())
                optionalRequirements.put(requirement, result);

            return fwkCaps;
        }
        Collections.sort(result, getCapabilityComparator());
        cache.put(requirement.toString(), result);
        return new ArrayList<Capability>(result);
    }

    private boolean isPermitted(Resource resource) {
        // OSGi frameworks cannot be selected as ordinary resources
        Capability fwkCap = findFrameworkContractCapability(resource);
        if (fwkCap != null) {
            return false;
        }

        // Remove osgi.core and any ee JAR
        List<Capability> idCaps = resource.getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
        if (idCaps == null || idCaps.isEmpty()) {
            log.log(LogService.LOG_ERROR, "Resource is missing an identity capability (osgi.identity).");
            return false;
        }
        if (idCaps.size() > 1) {
            log.log(LogService.LOG_ERROR, "Resource has more than one identity capability (osgi.identity).");
            return false;
        }
        String identity = (String) idCaps.get(0).getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE);
        if (identity == null) {
            log.log(LogService.LOG_ERROR, "Resource is missing an identity capability (osgi.identity).");
            return false;
        }

        if ("osgi.core".equals(identity))
            return false;

        if (identity.startsWith("ee."))
            return false;

        return true;
    }

    @Override
    public int insertHostedCapability(List<Capability> capabilities, HostedCapability hostedCapability) {
        capabilities.add(hostedCapability);
        Collections.sort(capabilities, getCapabilityComparator());
        return capabilities.indexOf(hostedCapability);
    }

    @Override
    public boolean isEffective(Requirement requirement) {
        String effective = requirement.getDirectives().get(Namespace.REQUIREMENT_EFFECTIVE_DIRECTIVE);
        return effective == null || Namespace.EFFECTIVE_RESOLVE.equals(effective);
    }

    @Override
    public Map<Resource,Wiring> getWirings() {
        return Collections.emptyMap();
    }

    public boolean isInputRequirementsResource(Resource resource) {
        return resource == inputRequirementsResource;
    }

    public boolean isFrameworkResource(Resource resource) {
        return resource == frameworkResource;
    }

    public Map<Requirement,List<Capability>> getOptionalRequirements() {
        return optionalRequirements;
    }

    private Comparator<Capability> getCapabilityComparator() {
        return capComparator;
    }

	Resource getFrameworkResource() {
		return frameworkResource;
	}

	protected boolean isInputRequirement(Capability capability) {
		List<Requirement> reqs = inputRequirementsResource.getRequirements(IdentityNamespace.IDENTITY_NAMESPACE);
		if (reqs == null
				|| (capability.getAttributes().get(SYMBOLICNAME_ATTRIBUTE) == null && capability.getAttributes().get(
						IdentityNamespace.IDENTITY_NAMESPACE) == null)) {
			return false;
		}

		Hashtable<String,Object> id_prop = new Hashtable<String,Object>();
		String bsn = (String) capability.getAttributes().get(SYMBOLICNAME_ATTRIBUTE);
		Version version = getVersion(capability, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
		if (bsn == null) {
			bsn = (String) capability.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE);
			version = getVersion(capability, IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE);
		}
		id_prop.put(IdentityNamespace.IDENTITY_NAMESPACE, bsn);
		if (version != null) {
			id_prop.put(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE, version.toString());
		}
		for (Requirement req : reqs) {
			if (IdentityNamespace.IDENTITY_NAMESPACE.equals(req.getNamespace())) {
				String filterStr = req.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
				try {
					if (FrameworkUtil.createFilter(filterStr).match(id_prop))
						return true;
				}
				catch (InvalidSyntaxException e) {}
			}
		}
		return false;
	}

	static Version getVersion(Capability cap, String attr) {
		Object versionatt = cap.getAttributes().get(attr);
		if (versionatt instanceof Version)
			return (Version) versionatt;
		else if (versionatt instanceof String)
			return Version.parseVersion((String) versionatt);
		else
			return Version.emptyVersion;
	}

	private static class CapabilityWrapper implements Capability {

		private final Capability	capability;
		private final Integer		score;

		public CapabilityWrapper(Capability capability, Integer score) {
			this.capability = capability;
			this.score = score;
		}

		public String getNamespace() {
			return capability.getNamespace();
		}

		public Map<String,String> getDirectives() {
			return capability.getDirectives();
		}

		public Map<String,Object> getAttributes() {
			return capability.getAttributes();

		}

		public Resource getResource() {
			return capability.getResource();
		}

		public Integer getScore() {
			return score;
		}

		public String toString() {
			return capability.toString();
		}

		@Override
		public boolean equals(Object obj) {
			return capability.equals(obj);
		}

		@Override
		public int hashCode() {
			return capability.hashCode();
		}
	}

	private class CapabilityComparator implements Comparator<Capability> {

		public CapabilityComparator() {}

		public int compare(Capability o1, Capability o2) {

			Resource res1 = o1.getResource();
			Resource res2 = o2.getResource();

			// prefer framework bundle
			if (o1.getResource() == getFrameworkResource())
				return -1;
			if (o2.getResource() == getFrameworkResource())
				return +1;

			// prefer input requirements
			if (isInputRequirement(o1) || isInputRequirementResource(o1.getResource())) {
				if (!isInputRequirement(o2))
					return -1;
			}
			if (isInputRequirement(o2) || isInputRequirementResource(o2.getResource())) {
				if (!isInputRequirement(o1))
					return +1;
			}

			Map<Resource,Wiring> wirings = getWirings();
			Wiring w1 = wirings.get(res1);
			Wiring w2 = wirings.get(res2);

			// prefer wired
			if (w1 != null && w2 == null)
				return -1;
			if (w1 == null && w2 != null)
				return +1;

			// prefer higher package version
			String ns1 = o1.getNamespace();
			String ns2 = o2.getNamespace();
			if (PACKAGE_NAMESPACE.equals(ns1) && PACKAGE_NAMESPACE.equals(ns2)) {
				Version v1 = getVersion(o1, PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE);
				Version v2 = getVersion(o2, PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE);
				if (!v1.equals(v2))
					return v2.compareTo(v1);
			}

			// prefer higher resource version
			if (BUNDLE_NAMESPACE.equals(ns1) && BUNDLE_NAMESPACE.equals(ns2)) {
				Version v1 = getVersion(o1, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
				Version v2 = getVersion(o2, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
				if (!v1.equals(v2))
					return v2.compareTo(v1);
			} else if (IdentityNamespace.IDENTITY_NAMESPACE.equals(ns1)
					&& IdentityNamespace.IDENTITY_NAMESPACE.equals(ns2)) {
				Version v1 = getVersion(o1, IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE);
				Version v2 = getVersion(o2, IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE);
				if (!v1.equals(v2))
					return v2.compareTo(v1);
			}

			// same package version, higher bundle version
			if (PACKAGE_NAMESPACE.equals(ns1) && PACKAGE_NAMESPACE.equals(ns2)) {
				String bsn1 = (String) o1.getAttributes().get(SYMBOLICNAME_ATTRIBUTE);
				String bsn2 = (String) o2.getAttributes().get(SYMBOLICNAME_ATTRIBUTE);
				if (bsn1 != null && bsn1.equals(bsn2)) {
					Version v1 = getVersion(o1, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
					Version v2 = getVersion(o2, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
					if (!v1.equals(v2))
						return v2.compareTo(v1);
				}
			}

			// obey repository order
			if (o1 instanceof CapabilityWrapper && o2 instanceof CapabilityWrapper) {
				CapabilityWrapper cw1 = (CapabilityWrapper) o1;
				CapabilityWrapper cw2 = (CapabilityWrapper) o2;
				int res = cw2.getScore().compareTo(cw1.getScore());
				if (res != 0) {
					return res;
				}
			}

			// prefer the resource with most capabilities
			return res2.getCapabilities(null).size() - res1.getCapabilities(null).size();
		}
	}
	private class BundleVersionComparator implements Comparator<Capability> {

		public int compare(Capability o1, Capability o2) {

			String ns1 = o1.getNamespace();
			String ns2 = o2.getNamespace();

			// same package version, higher bundle version
			String bsn1 = (String) o1.getAttributes().get(SYMBOLICNAME_ATTRIBUTE);
			String bsn2 = (String) o2.getAttributes().get(SYMBOLICNAME_ATTRIBUTE);
			if (bsn1 != null && bsn1.equals(bsn2)) {
				Version v1 = getVersion(o1, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
				Version v2 = getVersion(o2, BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
				if (!v1.equals(v2))
					return v2.compareTo(v1);
			}
			return 0;
		}
	}
}
