/*
 * Copyright 2015-2016 the original author or authors.
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


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
public class KubernetesAppDeployer implements AppDeployer {

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";
	protected static final String SPRING_GROUP_KEY = "spring-group-id";
	protected static final String SPRING_APP_KEY = "spring-app-id";
	private static final String SPRING_MARKER_KEY = "role";
	private static final String SPRING_MARKER_VALUE = "spring-app";
	private static final String SERVER_PORT_KEY = "server.port";

	private static final Logger logger = LoggerFactory.getLogger(KubernetesAppDeployer.class);

	private KubernetesAppDeployerProperties properties = new KubernetesAppDeployerProperties();

	private final KubernetesClient client;

	private final ContainerFactory containerFactory;

	private List<Logger> loggers = new ArrayList<>();

	@Autowired
	public KubernetesAppDeployer(KubernetesAppDeployerProperties properties,
	                             KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesAppDeployerProperties properties,
	                             KubernetesClient client, ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
		this.containerFactory = containerFactory;
		this.registerCustomerLogger(logger);
	}

	public void registerCustomerLogger(Logger logger) {
		this.loggers.add(logger);
	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		Map<String, String> idMap = createIdMap(request);

		String appName = request.getDefinition().getName();
		logger.debug("Deploying app: {}", KubernetesUtils.createKubernetesName(appName));

		AppStatus status = status(appName);
		if (!status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(String.format("App '%s' is already deployed", appName));
		}

		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
		}
		logger.debug("Creating service: {} on {}", KubernetesUtils.createKubernetesName(appName), externalPort);
		createService(appName, request, idMap, externalPort);

		logger.debug("Creating repl controller: {} on {}", KubernetesUtils.createKubernetesName(appName), externalPort);
		createReplicationController(appName, request, idMap, externalPort);

		return KubernetesUtils.createKubernetesName(appName);
	}


	/**
	 *
	 * @param id
	 */
	@Override
	public void undeploy(String id) {

		logger.debug("Undeploying module: {}", id);

		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, id);
		try {
			client.services().withLabels(selector).delete();
			client.replicationControllers().withLabels(selector).delete();
			client.pods().withLabels(selector).delete();
		} catch (KubernetesClientException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public AppStatus status(String id) {

		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, id);
		PodList list = client.pods().withLabels(selector).list();
		AppStatus status = buildModuleStatus(id, list);
		logger.debug("Status for app: {} is {}", id, status);

		return status;
	}

	private ReplicationController createReplicationController(
			String id, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort) {
		String countProperty = request.getDefinition().getProperties().get(COUNT_PROPERTY_KEY);
		int count = (countProperty != null) ? Integer.parseInt(countProperty) : 1;
		ReplicationController rc = new ReplicationControllerBuilder()
				.withNewMetadata()
				.withName(KubernetesUtils.createKubernetesName(id)) // does not allow . in the name
				.withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withNewSpec()
				.withReplicas(count)
				.withSelector(idMap)
				.withNewTemplate()
				.withNewMetadata()
				.withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withSpec(createPodSpec(request, externalPort))
				.endTemplate()
				.endSpec()
				.build();

		return client.replicationControllers().create(rc);
	}

	private PodSpec createPodSpec(AppDeploymentRequest request, int port) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		Container container = containerFactory.create(request, port);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(request));
		container.setResources(req);

		podSpec.addToContainers(container);
		return podSpec.build();
	}

	private void createService(String id, AppDeploymentRequest request, Map<String, String> idMap, int externalPort) {
		client.services().inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
					.withName(KubernetesUtils.createKubernetesName(id)) // does not allow . in the name
					.withLabels(idMap)
					.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
					.endMetadata()
				.withNewSpec()
					.withSelector(idMap)
					.addNewPort()
						.withPort(externalPort)
						.endPort()
					.endSpec()
				.done();
	}

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 */
	private Map<String, String> createIdMap(AppDeploymentRequest request) {
		//TODO: handling of app and group ids
		Map<String, String> map = new HashMap<>();
		String appId = KubernetesUtils.createKubernetesName(request.getDefinition().getName());
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getEnvironmentProperties().get(GROUP_PROPERTY_KEY);
		if (groupId == null) groupId = "default";//TODO: testing
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		String deploymentId = null;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		map.put(SPRING_DEPLOYMENT_KEY, deploymentId);
		return map;
	}

	private AppStatus buildModuleStatus(String id, PodList list) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);
		String moduleId = id;

		if (list == null) {
			statusBuilder.with(new KubernetesAppInstanceStatus(moduleId, null));
		} else {
			for (Pod pod : list.getItems()) {
				statusBuilder.with(new KubernetesAppInstanceStatus(moduleId, pod));
			}
		}
		return statusBuilder.build();
	}

	private Map<String, Quantity> deduceResourceLimits(AppDeploymentRequest request) {
		String memOverride = request.getDefinition().getProperties().get("kubernetes.memory");
		if (memOverride == null)
			memOverride = properties.getMemory();

		String cpuOverride = request.getDefinition().getProperties().get("kubernetes.cpu");
		if (cpuOverride == null)
			cpuOverride = properties.getCpu();

		logger.debug("Using limits - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memOverride));
		limits.put("cpu", new Quantity(cpuOverride));
		return limits;
	}

}
