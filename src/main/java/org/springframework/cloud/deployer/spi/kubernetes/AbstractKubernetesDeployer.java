/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.cloud.deployer.spi.util.RuntimeVersionUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Abstract base class for a deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 * @author David Turanski
 * @author Chris Schaefer
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
	 * @param spiClass the SPI interface class
	 * @param implementationClass the SPI implementation class
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
	 *
	 * @param appId the application id
	 * @param request The {@link AppDeploymentRequest}
	 * @return the built id map of labels
	 */
	protected Map<String, String> createIdMap(String appId, AppDeploymentRequest request) {
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

		String imagePullSecret = getImagePullSecret(request);

		if (imagePullSecret != null) {
			podSpec.addNewImagePullSecret(imagePullSecret);
		}

		boolean hostNetwork = getHostNetwork(request);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration(appId, request)
				.withProbeCredentialsSecret(getProbeCredentialsSecret(request))
				.withExternalPort(port)
				.withHostNetwork(hostNetwork);

		Container container = containerFactory.create(containerConfiguration);

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
		podSpec.withTolerations(getTolerations(request));
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

		String deploymentServiceAcccountName = getDeploymentServiceAccountName(request);

		if (deploymentServiceAcccountName != null) {
			podSpec.withServiceAccountName(deploymentServiceAcccountName);
		}

		return podSpec.build();
	}

	private List<Toleration> getTolerations(AppDeploymentRequest request) {
		List<Toleration> tolerations = new ArrayList<>();

		String tolerationDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.tolerations", "");
		if (!StringUtils.isEmpty(tolerationDeploymentProperty)) {
			try {
				YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
				String tmpYaml = "{ tolerations: " + tolerationDeploymentProperty + " }";
				properties.setResources(new ByteArrayResource(tmpYaml.getBytes()));
				Properties yaml = properties.getObject();
				MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
				KubernetesDeployerProperties deployerProperties = new Binder(source)
						.bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
				tolerations.addAll(deployerProperties.getTolerations());
			} catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume '%s'", tolerationDeploymentProperty), e);
			}
		}

		tolerations.addAll(properties.getTolerations().stream()
				.filter(toleration -> tolerations.stream()
						.noneMatch(existing -> existing.getKey().equals(toleration.getKey()) && existing.getValue().equals(toleration.getValue())))
				.collect(Collectors.toList()));

		return tolerations;
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
	 * @param request the {@link AppDeploymentRequest}
	 * @return the configured volumes
	 */
	protected List<Volume> getVolumes(AppDeploymentRequest request) {
		List<Volume> volumes = new ArrayList<>();

		String volumeDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.volumes", "");
		if (!StringUtils.isEmpty(volumeDeploymentProperty)) {
			try {
				YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
				String tmpYaml = "{ volumes: " + volumeDeploymentProperty + " }";
				properties.setResources(new ByteArrayResource(tmpYaml.getBytes()));
				Properties yaml = properties.getObject();
				MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
				KubernetesDeployerProperties deployerProperties = new Binder(source)
						.bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
				volumes.addAll(deployerProperties.getVolumes());
			} catch (Exception e) {
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
	 *
	 * @param request The deployment properties.
	 * @return the resource limits to use
	 */
	protected Map<String, Quantity> deduceResourceLimits(AppDeploymentRequest request) {
		String memory = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.limits.memory");

		if (StringUtils.isEmpty(memory)) {
			memory = properties.getLimits().getMemory();
		}

		String cpu = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.limits.cpu");

		if (StringUtils.isEmpty(cpu)) {
			cpu = properties.getLimits().getCpu();
		}

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memory));
		limits.put("cpu", new Quantity(cpu));

		logger.debug("Using limits - cpu: " + cpu + " mem: " + memory);

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
				logger.warn("Parsing of pull policy " + pullPolicyOverride + " failed, using default \"IfNotPresent\".");
				pullPolicy = ImagePullPolicy.IfNotPresent;
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
	 * @param request The deployment properties.
	 * @return the resource requests to use
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

	protected String getStatefulSetStorageClassName(AppDeploymentRequest request) {
		String storageClassName = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storageClassName");
		if (storageClassName == null && properties.getStatefulSet() != null && properties.getStatefulSet().getVolumeClaimTemplate() != null) {
			storageClassName = properties.getStatefulSet().getVolumeClaimTemplate().getStorageClassName();
		}
		return storageClassName;
	}

	protected String getStatefulSetStorage(AppDeploymentRequest request) {
		String storage = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storage");
		if (storage == null && properties.getStatefulSet() != null && properties.getStatefulSet().getVolumeClaimTemplate() != null) {
			storage = properties.getStatefulSet().getVolumeClaimTemplate().getStorage();
		}
		long storageAmount = ByteSizeUtils.parseToMebibytes(storage);
		return storageAmount + "Mi";
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

	private String getImagePullSecret(AppDeploymentRequest request) {
		String imagePullSecret = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.imagePullSecret", "");

		if(StringUtils.isEmpty(imagePullSecret)) {
			imagePullSecret = properties.getImagePullSecret();
		}

		return imagePullSecret;
	}

	private String getDeploymentServiceAccountName(AppDeploymentRequest request) {
		String deploymentServiceAccountName =
				request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.deploymentServiceAccountName");

		if (StringUtils.isEmpty(deploymentServiceAccountName)) {
			deploymentServiceAccountName = properties.getDeploymentServiceAccountName();
		}

		return deploymentServiceAccountName;
	}

	private Secret getProbeCredentialsSecret(AppDeploymentRequest request) {
		Secret secret = null;
		String probeCredentialsSecret = "spring.cloud.deployer.kubernetes.probeCredentialsSecret";

		if (request.getDeploymentProperties().containsKey(probeCredentialsSecret)) {
			String secretName = request.getDeploymentProperties().get(probeCredentialsSecret);
			secret = client.secrets().withName(secretName).get();
		}

		return secret;
	}
}
