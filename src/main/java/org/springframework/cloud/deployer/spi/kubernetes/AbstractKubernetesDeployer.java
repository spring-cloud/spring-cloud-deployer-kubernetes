/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.cloud.deployer.spi.util.RuntimeVersionUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Abstract base class for a deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 * @author David Turanski
 */
public class AbstractKubernetesDeployer {

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";
	protected static final String SPRING_GROUP_KEY = "spring-group-id";
	protected static final String SPRING_APP_KEY = "spring-app-id";
	protected static final String SPRING_MARKER_KEY = "role";
	protected static final String SPRING_MARKER_VALUE = "spring-app";

	protected final Log logger = LogFactory.getLog(getClass().getName());

	protected ContainerFactory containerFactory;

	protected KubernetesClient client;

	protected KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

	/**
	 * Create the RuntimeEnvironmentInfo.
	 *
	 * @return the Kubernetes runtime environment info
	 */
	protected RuntimeEnvironmentInfo createRuntimeEnvironmentInfo(Class spiClass, Class implementationClass) {
		return new RuntimeEnvironmentInfo.Builder()
				.spiClass(spiClass)
				.implementationName(implementationClass.getSimpleName())
				.implementationVersion(RuntimeVersionUtils.getVersion(implementationClass))
				.platformType("Kubernetes")
				.platformApiVersion(client.getApiVersion())
				.platformClientVersion(RuntimeVersionUtils.getVersion(client.getClass()))
				.platformHostVersion("unknown")
				.addPlatformSpecificInfo("master-url", String.valueOf(client.getMasterUrl()))
				.addPlatformSpecificInfo("namespace", client.getNamespace())
				.build();
	}

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 */
	protected Map<String, String> createIdMap(String appId, AppDeploymentRequest request) {
		//TODO: handling of app and group ids
		Map<String, String> map = new HashMap<>();
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		map.put(SPRING_DEPLOYMENT_KEY, appId);
		return map;
	}

	protected AppStatus buildAppStatus(String id, PodList podList, ServiceList services) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);
		Service service = null;
		if (podList != null && podList.getItems() != null) {
			for (Pod pod : podList.getItems()) {
				for (Service svc : services.getItems()) {
					if (svc.getMetadata().getName()
							.equals(pod.getMetadata().getLabels().get(SPRING_DEPLOYMENT_KEY))) {
						service = svc;
						break;
					}
				}
				statusBuilder.with(new KubernetesAppInstanceStatus(pod, service, properties));
			}
		}
		return statusBuilder.build();
	}


	/**
	 * Create a PodSpec to be used for app and task deployments
	 *
	 * @param appId the app ID
	 * @param request app deployment request
	 * @param port port to use for app or null if none
	 * @param neverRestart use restart policy of Never
	 * @return the PodSpec
	 */
	protected PodSpec createPodSpec(String appId, AppDeploymentRequest request, Integer port,
		boolean neverRestart) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		boolean hostNetwork = getHostNetwork(request);
		Container container = containerFactory.create(appId, request, port, hostNetwork);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(request));
		req.setRequests(deduceResourceRequests(request));
		container.setResources(req);
		ImagePullPolicy pullPolicy = deduceImagePullPolicy(request);
		container.setImagePullPolicy(pullPolicy.name());

		//
		Map<String, String> nodeSelectors = getNodeSelectors(request.getDeploymentProperties());
		if (nodeSelectors.size() > 0) {
			podSpec.withNodeSelector(nodeSelectors);
		}
		// only add volumes with corresponding volume mounts
		podSpec.withVolumes(getVolumes(request).stream()
				.filter(volume -> container.getVolumeMounts().stream()
						.anyMatch(volumeMount -> volumeMount.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		if (hostNetwork) {
			podSpec.withHostNetwork(true);
		}
		podSpec.addToContainers(container);

		if (neverRestart){
			podSpec.withRestartPolicy("Never");
		}

		return podSpec.build();
	}

	/**
	 * Volume deployment properties are specified in YAML format:
	 *
	 * <code>
	 *     spring.cloud.deployer.kubernetes.volumes=[{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},
	 *     	{name: 'testpvc', persistentVolumeClaim: { claimName: 'testClaim', readOnly: 'true' }},
	 *     	{name: 'testnfs', nfs: { server: '10.0.0.1:111', path: '/test/nfs' }}]
	 * </code>
	 *
	 * Volumes can be specified as deployer properties as well as app deployment properties.
	 * Deployment properties override deployer properties.
	 *
	 * @param request
	 * @return the configured volumes
	 */
	protected List<Volume> getVolumes(AppDeploymentRequest request) {
		List<Volume> volumes = new ArrayList<>();

		String volumeDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.volumes", "");
		if (!StringUtils.isEmpty(volumeDeploymentProperty)) {
			YamlConfigurationFactory<KubernetesDeployerProperties> volumeYamlConfigurationFactory =
					new YamlConfigurationFactory<>(KubernetesDeployerProperties.class);
			volumeYamlConfigurationFactory.setYaml("{ volumes: " + volumeDeploymentProperty + " }");
			try {
				volumeYamlConfigurationFactory.afterPropertiesSet();
				volumes.addAll(
						volumeYamlConfigurationFactory.getObject().getVolumes());
			}
			catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume '%s'", volumeDeploymentProperty), e);
			}
		}
		// only add volumes that have not already been added, based on the volume's name
		// i.e. allow provided deployment volumes to override deployer defined volumes
		volumes.addAll(properties.getVolumes().stream()
				.filter(volume -> volumes.stream()
						.noneMatch(existingVolume -> existingVolume.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		return volumes;
	}

	/**
	 * Get the resource limits for the deployment request. A Pod can define its maximum needed resources by setting the
	 * limits and Kubernetes can provide more resources if any are free.
	 * <p>
	 * Falls back to the server properties if not present in the deployment request.
	 * <p>
	 * Also supports the deprecated properties {@code spring.cloud.deployer.kubernetes.memory/cpu}.
	 *
	 * @param request    The deployment properties.
	 */
	protected Map<String, Quantity> deduceResourceLimits(AppDeploymentRequest request) {
		String memDeployer = getCommonDeployerMemory(request);
		String memOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.memory");
		if (memDeployer != null) {
			if (memOverride == null) {
				memOverride = memDeployer;
			}
			else {
				logger.warn(String.format("Both " + AppDeployer.MEMORY_PROPERTY_KEY +
								"=%s and spring.cloud.deployer.kubernetes.memory=%s specified, the latter will take precedence.",
						memDeployer, memOverride));
			}
		}
		String memLimitsOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.limits.memory");
		if (memLimitsOverride != null) {
			// Non-deprecated value has priority
			memOverride = memLimitsOverride;
		}

		// Use server property if there is no request setting
		if (memOverride == null) {
			if (properties.getLimits().getMemory() != null) {
				// Non-deprecated value has priority
				memOverride = properties.getLimits().getMemory();
			} else {
				memOverride = properties.getMemory();
			}
		}

		String cpuDeployer = request.getDeploymentProperties().get(AppDeployer.CPU_PROPERTY_KEY);
		String cpuOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.cpu");
		if (cpuDeployer != null) {
			if (cpuOverride == null) {
				cpuOverride = cpuDeployer;
			}
			else {
				logger.warn(String.format("Both " + AppDeployer.CPU_PROPERTY_KEY +
								"=%s and spring.cloud.deployer.kubernetes.cpu=%s specified, the latter will take precedence.",
						cpuDeployer, cpuOverride));
			}
		}
		String cpuLimitsOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.limits.cpu");
		if (cpuLimitsOverride != null) {
			// Non-deprecated value has priority
			cpuOverride = cpuLimitsOverride;
		}

		// Use server property if there is no request setting
		if (cpuOverride == null) {
			if (properties.getLimits().getCpu() != null) {
				// Non-deprecated value has priority
				cpuOverride = properties.getLimits().getCpu();
			} else {
				cpuOverride = properties.getCpu();
			}
		}

		logger.debug("Using limits - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memOverride));
		limits.put("cpu", new Quantity(cpuOverride));
		return limits;
	}

	/**
	 * Get the image pull policy for the deployment request. If it is not present use the server default. If an override
	 * for the deployment is present but not parseable, fall back to a default value.
	 *
	 * @param request The deployment request.
	 * @return The image pull policy to use for the container in the request.
	 */
	protected ImagePullPolicy deduceImagePullPolicy(AppDeploymentRequest request) {
		String pullPolicyOverride =
				request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.imagePullPolicy");

		ImagePullPolicy pullPolicy;
		if (pullPolicyOverride == null) {
			pullPolicy = properties.getImagePullPolicy();
		} else {
			pullPolicy = ImagePullPolicy.relaxedValueOf(pullPolicyOverride);
			if (pullPolicy == null) {
				logger.warn("Parsing of pull policy " + pullPolicyOverride + " failed, using default \"Always\".");
				pullPolicy = ImagePullPolicy.Always;
			}
		}
		logger.debug("Using imagePullPolicy " + pullPolicy);
		return pullPolicy;
	}

	/**
	 * Get the resource requests for the deployment request. Resource requests are guaranteed by the Kubernetes
	 * runtime.
	 * Falls back to the server properties if not present in the deployment request.
	 *
	 * @param request    The deployment properties.
	 */
	protected Map<String, Quantity> deduceResourceRequests(AppDeploymentRequest request) {
		String memOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.requests.memory");
		if (memOverride == null) {
			memOverride = properties.getRequests().getMemory();
		}

		String cpuOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.requests.cpu");
		if (cpuOverride == null) {
			cpuOverride = properties.getRequests().getCpu();
		}

		logger.debug("Using requests - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> requests = new HashMap<String, Quantity>();
		if (memOverride != null) {
			requests.put("memory", new Quantity(memOverride));
		}
		if (cpuOverride != null) {
			requests.put("cpu", new Quantity(cpuOverride));
		}
		return requests;
	}

	/**
	 * Get the hostNetwork setting for the deployment request.
	 *
	 * @param request The deployment request.
	 * @return Whether host networking is requested
	 */
	protected boolean getHostNetwork(AppDeploymentRequest request) {
		String hostNetworkOverride =
				request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.hostNetwork");
		boolean hostNetwork;
		if (StringUtils.isEmpty(hostNetworkOverride)) {
			hostNetwork = properties.isHostNetwork();
		}
		else {
			hostNetwork = Boolean.valueOf(hostNetworkOverride);
		}
		logger.debug("Using hostNetwork " + hostNetwork);
		return hostNetwork;
	}

	/**
	 * Get the nodeSelectors setting for the deployment request.
	 *
	 * @param properties The deployment request deployment properties.
	 * @return map of nodeSelectors
	 */
	protected Map<String, String> getNodeSelectors(Map<String, String> properties) {
		Map<String, String> nodeSelectors = new HashMap<>();

		String nodeSelectorsProperty = properties
				.getOrDefault(KubernetesDeployerProperties.KUBERNETES_DEPLOYMENT_NODE_SELECTOR, "");

		if (StringUtils.hasText(nodeSelectorsProperty)) {
			String[] nodeSelectorPairs = nodeSelectorsProperty.split(",");
			for (String nodeSelectorPair : nodeSelectorPairs) {
				String[] nodeSelector = nodeSelectorPair.split(":");
				Assert.isTrue(nodeSelector.length == 2, format("Invalid nodeSelector value: '{}'", nodeSelectorPair));
				nodeSelectors.put(nodeSelector[0].trim(), nodeSelector[1].trim());
			}
		}

		return nodeSelectors;
	}

	private String getCommonDeployerMemory(AppDeploymentRequest request) {
		String mem = request.getDeploymentProperties().get(AppDeployer.MEMORY_PROPERTY_KEY);
		if (mem == null) {
			return null;
		}
		long memAmount = ByteSizeUtils.parseToMebibytes(mem);
		return memAmount + "Mi";
	}
}
