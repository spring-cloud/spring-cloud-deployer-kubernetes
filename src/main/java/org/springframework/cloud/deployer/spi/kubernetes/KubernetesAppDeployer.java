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
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 */
public class KubernetesAppDeployer extends AbstractKubernetesDeployer implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";

	private KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

	private final KubernetesClient client;

	private final ContainerFactory containerFactory;

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client, ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
		this.containerFactory = containerFactory;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		String appId = createDeploymentId(request);
		logger.debug("Deploying app: {}", appId);

		try {
			AppStatus status = status(appId);
			if (!status.getState().equals(DeploymentState.unknown)) {
				throw new IllegalStateException(String.format("App '%s' is already deployed", appId));
			}

			int externalPort = 8080;
			Map<String, String> parameters = request.getDefinition().getProperties();
			if (parameters.containsKey(SERVER_PORT_KEY)) {
				externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
			}

			String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
			int count = (countProperty != null) ? Integer.parseInt(countProperty) : 1;

			String indexedProperty = request.getDeploymentProperties().get(INDEXED_PROPERTY_KEY);
			boolean indexed = (indexedProperty != null) ? Boolean.valueOf(indexedProperty).booleanValue() : false;

			if (indexed) {
				for (int index=0 ; index < count ; index++) {
					String indexedId = appId + "-" + index;
					Map<String, String> idMap = createIdMap(appId, request, index);
					logger.debug("Creating service: {} on {} with index {}", appId, externalPort, index);
					createService(indexedId, request, idMap, externalPort);
					logger.debug("Creating repl controller: {} on {} with index {}", appId, externalPort, index);
					createReplicationController(indexedId, request, idMap, externalPort, 1, index);
				}
			}
			else {
				Map<String, String> idMap = createIdMap(appId, request, null);
				logger.debug("Creating service: {} on {}", appId, externalPort);
				createService(appId, request, idMap, externalPort);
				logger.debug("Creating repl controller: {} on {}", appId, externalPort);
				createReplicationController(appId, request, idMap, externalPort, count, null);
			}

			return appId;
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}


	@Override
	public void undeploy(String appId) {
		logger.debug("Undeploying app: {}", appId);

		List<ReplicationController> apps =
			client.replicationControllers().withLabel(SPRING_APP_KEY, appId).list().getItems();
		for (ReplicationController rc : apps) {
			String appIdToDelete = rc.getMetadata().getName();
			logger.debug("Deleting svc, rc and pods for: {}", appIdToDelete);

			Service svc = client.services().withName(appIdToDelete).get();
			try {
				if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
					int tries = 0;
					int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
					while (tries++ < maxWait) {
						if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null &&
								svc.getStatus().getLoadBalancer().getIngress() != null &&
								svc.getStatus().getLoadBalancer().getIngress().isEmpty()) {
							if (tries % 6 == 0) {
								logger.warn("Waiting for LoadBalancer to complete before deleting it ...");
							}
							logger.debug("Waiting for LoadBalancer, try {}", tries);
							try {
								Thread.sleep(10000L);
							} catch (InterruptedException e) {
							}
							svc = client.services().withName(appIdToDelete).get();
						} else {
							break;
						}
					}
					logger.debug("LoadBalancer Ingress: {}", svc.getStatus().getLoadBalancer().getIngress());
				}
				Boolean svcDeleted = client.services().withName(appIdToDelete).delete();
				logger.debug("Deleted service for: {} {}", appIdToDelete, svcDeleted);
				Boolean rcDeleted = client.replicationControllers().withName(appIdToDelete).delete();
				logger.debug("Deleted replication controller for: {} {}", appIdToDelete, rcDeleted);
				Map<String, String> selector = new HashMap<>();
				selector.put(SPRING_APP_KEY, appIdToDelete);
				Boolean podDeleted = client.pods().withLabels(selector).delete();
				logger.debug("Deleted pods for: {} {}", appIdToDelete, podDeleted);
			} catch (RuntimeException e) {
				logger.error(e.getMessage(), e);
				throw e;
			}
		}
	}

	@Override
	public AppStatus status(String appId) {
		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, appId);
		PodList list = client.pods().withLabels(selector).list();
		if (logger.isDebugEnabled()) {
			logger.debug("Building AppStatus for app: {}", appId);
			logger.debug("Pods for appId {}: {}", appId, list.getItems().size());
			for (Pod pod : list.getItems()) {
				logger.debug("Pod: {}", pod.getMetadata().getName());
			}
		}
		AppStatus status = buildAppStatus(properties, appId, list);
		logger.debug("Status for app: {} is {}", appId, status);

		return status;
	}

	private ReplicationController createReplicationController(
			String appId, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort, int replicas, Integer instanceIndex) {
		ReplicationController rc = new ReplicationControllerBuilder()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
						.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withNewSpec()
					.withReplicas(replicas)
					.withSelector(idMap)
					.withNewTemplate()
						.withNewMetadata()
							.withLabels(idMap)
								.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
						.endMetadata()
						.withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), instanceIndex))
					.endTemplate()
				.endSpec()
				.build();

		return client.replicationControllers().create(rc);
	}

	private PodSpec createPodSpec(String appId, AppDeploymentRequest request, Integer port, Integer instanceIndex) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		Container container = containerFactory.create(appId, request, port, instanceIndex);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(properties, request));
		container.setResources(req);

		podSpec.addToContainers(container);
		return podSpec.build();
	}

	private void createService(String appId, AppDeploymentRequest request, Map<String, String> idMap, int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		boolean isCreateLoadBalancer = false;
		String createLoadBalancer = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.createLoadBalancer");
		if (createLoadBalancer == null) {
			isCreateLoadBalancer = properties.isCreateLoadBalancer();
		}
		else {
			if ("true".equals(createLoadBalancer.toLowerCase())) {
				isCreateLoadBalancer = true;
			}
		}
		if (isCreateLoadBalancer) {
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

}
