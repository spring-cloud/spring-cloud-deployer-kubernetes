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


import java.util.HashMap;
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
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
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
	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		String appId = createDeploymentId(request);
		Map<String, String> idMap = createIdMap(appId, request);

		logger.debug("Deploying app: {}", appId);

		AppStatus status = status(appId);
		if (!status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(String.format("App '%s' is already deployed", appId));
		}

		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
		}
		logger.debug("Creating service: {} on {}", appId, externalPort);
		createService(appId, idMap, externalPort);

		logger.debug("Creating repl controller: {} on {}", appId, externalPort);
		createReplicationController(appId, request, idMap, externalPort);

		return appId;
	}


	@Override
	public void undeploy(String appId) {
		logger.debug("Undeploying module: {}", appId);

		try {
			if ("LoadBalancer".equals(client.services().withName(appId).get().getSpec().getType())) {
				Service svc = client.services().withName(appId).get();
				int tries = 0;
				while (tries++ < 30) {
					if (svc.getStatus().getLoadBalancer() != null &&
							svc.getStatus().getLoadBalancer().getIngress() != null &&
							svc.getStatus().getLoadBalancer().getIngress().isEmpty()) {
						logger.debug("Waiting for LoadBalancer, try {}", tries);
						try {
							Thread.sleep(10000L);
						} catch (InterruptedException e) {
						}
						svc = client.services().withName(appId).get();
					} else {
						break;
					}
				}
				logger.debug("LoadBalancer Ingress: {}", svc.getStatus().getLoadBalancer().getIngress());
			}
			Boolean svcDeleted = client.services().withName(appId).delete();
			logger.debug("Deleted service for: {} {}", appId, svcDeleted);
			Boolean rcDeleted = client.replicationControllers().withName(appId).delete();
			logger.debug("Deleted replication controller for: {} {}", appId, rcDeleted);
			Map<String, String> selector = new HashMap<>();
			selector.put(SPRING_APP_KEY, appId);
			Boolean podDeleted = client.pods().withLabels(selector).delete();
			logger.debug("Deleted pods for: {} {}", appId, podDeleted);
		} catch (KubernetesClientException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public AppStatus status(String appId) {
		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, appId);
		PodList list = client.pods().withLabels(selector).list();
		AppStatus status = buildAppStatus(appId, list);
		logger.debug("Status for app: {} is {}", appId, status);

		return status;
	}

	private ReplicationController createReplicationController(
			String appId, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort) {
		String countProperty = request.getEnvironmentProperties().get(COUNT_PROPERTY_KEY);
		int count = (countProperty != null) ? Integer.parseInt(countProperty) : 1;
		ReplicationController rc = new ReplicationControllerBuilder()
				.withNewMetadata()
				.withName(appId)
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
				.withSpec(createPodSpec(appId, request, externalPort))
				.endTemplate()
				.endSpec()
				.build();

		return client.replicationControllers().create(rc);
	}

	private PodSpec createPodSpec(String appId, AppDeploymentRequest request, int port) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		Container container = containerFactory.create(appId, request, port);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(request));
		container.setResources(req);

		podSpec.addToContainers(container);
		return podSpec.build();
	}

	private void createService(String appId, Map<String, String> idMap, int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		if (properties.isCreateLoadBalancer()) {
			spec.withType("LoadBalancer");
		}
		spec.withSelector(idMap)
				.addNewPort()
					.withPort(externalPort)
				.endPort();

		client.services().inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
					.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
					.endMetadata()
				.withSpec(spec.build())
				.done();
	}

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 */
	private Map<String, String> createIdMap(String appId, AppDeploymentRequest request) {
		//TODO: handling of app and group ids
		Map<String, String> map = new HashMap<>();
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getEnvironmentProperties().get(GROUP_PROPERTY_KEY);
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		map.put(SPRING_DEPLOYMENT_KEY, createDeploymentId(request));
		return map;
	}

	private String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getEnvironmentProperties().get(GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		// Kubernetes does not allow . in the name
		return deploymentId.replace('.', '-');
	}

	private AppStatus buildAppStatus(String id, PodList list) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);

		if (list == null) {
			statusBuilder.with(new KubernetesAppInstanceStatus(id, null, properties));
		} else {
			for (Pod pod : list.getItems()) {
				statusBuilder.with(new KubernetesAppInstanceStatus(id, pod, properties));
			}
		}
		return statusBuilder.build();
	}

	private Map<String, Quantity> deduceResourceLimits(AppDeploymentRequest request) {
		String memOverride = request.getEnvironmentProperties().get("spring.cloud.deployer.kubernetes.memory");
		if (memOverride == null)
			memOverride = properties.getMemory();

		String cpuOverride = request.getEnvironmentProperties().get("spring.cloud.deployer.kubernetes.cpu");
		if (cpuOverride == null)
			cpuOverride = properties.getCpu();

		logger.debug("Using limits - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memOverride));
		limits.put("cpu", new Quantity(cpuOverride));
		return limits;
	}

}
