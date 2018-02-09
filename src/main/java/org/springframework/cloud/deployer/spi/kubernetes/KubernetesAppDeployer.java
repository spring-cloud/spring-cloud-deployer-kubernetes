/*
 * Copyright 2015-2018 the original author or authors.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetSpec;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 */
public class KubernetesAppDeployer extends AbstractKubernetesDeployer implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";
	private String STATEFULSETS_ENDPOINT;
	private KubernetesHttpClient httpClient;

	private ObjectMapper objectMapper = new ObjectMapper();



	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties, KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties, KubernetesClient client,
		ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;

		if (client != null) {
			this.httpClient = new KubernetesHttpClient(client);
			this.STATEFULSETS_ENDPOINT = String.format("apis/apps/v1beta1/namespaces/%s/statefulsets",
				client.getNamespace());
		}

		this.containerFactory = containerFactory;

	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		String appId = createDeploymentId(request);
		logger.debug(String.format("Deploying app: %s", appId));

		try {
			AppStatus status = status(appId);
			if (!status.getState().equals(DeploymentState.unknown)) {
				throw new IllegalStateException(String.format("App '%s' is already deployed", appId));
			}

			int externalPort = configureExternalPort(request);

			String indexedProperty = request.getDeploymentProperties().get(INDEXED_PROPERTY_KEY);
			boolean indexed = (indexedProperty != null) ? Boolean.valueOf(indexedProperty).booleanValue() : false;

			if (indexed) {
				Map<String, String> idMap = createIdMap(appId, request);
				logger.debug(String.format("Creating Service: %s on %d with", appId, externalPort));
				createService(appId, request, idMap, externalPort);

				String statefulSetJson = createStatefulSet(appId, request, idMap, externalPort);

				Response response = httpClient.post(STATEFULSETS_ENDPOINT, statefulSetJson);
				if (!response.isSuccessful()) {
					throw new RuntimeException(String.format(
						"Create StatefulSet failed. response code %d, message %s" ,response.code(),response.message()));
				}
			}
			else {
				Map<String, String> idMap = createIdMap(appId, request);
				logger.debug(String.format("Creating Service: %s on {}", appId, externalPort));
				createService(appId, request, idMap, externalPort);
				if (properties.isCreateDeployment()) {
					logger.debug(String.format("Creating Deployment: %s", appId));
					createDeployment(appId, request, idMap, externalPort);
				}
				else {
					logger.debug(String.format("Creating Replication Controller: %s", appId));
					createReplicationController(appId, request, idMap, externalPort);
				}
			}

			return appId;
		}
		catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void undeploy(String appId) {
		logger.debug(String.format("Undeploying app: %s", appId));
		AppStatus status = status(appId);
		if (status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(String.format("App '%s' is not deployed", appId));
		}
		List<Service> apps = client.services().withLabel(SPRING_APP_KEY, appId).list().getItems();
		if (apps != null) {
			for (Service app : apps) {
				String appIdToDelete = app.getMetadata().getName();
				logger.debug(String.format("Deleting Resources for: %s", appIdToDelete));

				Service svc = client.services().withName(appIdToDelete).get();
				try {
					if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
						int tries = 0;
						int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
						while (tries++ < maxWait) {
							if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null
								&& svc.getStatus().getLoadBalancer().getIngress() != null && svc.getStatus()
								.getLoadBalancer().getIngress().isEmpty()) {
								if (tries % 6 == 0) {
									logger.warn("Waiting for LoadBalancer to complete before deleting it ...");
								}
								logger.debug(String.format("Waiting for LoadBalancer, try %d", tries));
								try {
									Thread.sleep(10000L);
								}
								catch (InterruptedException e) {
								}
								svc = client.services().withName(appIdToDelete).get();
							}
							else {
								break;
							}
						}
						logger.debug(String.format("LoadBalancer Ingress: %s",
							svc.getStatus().getLoadBalancer().getIngress().toString()));
					}
					Boolean svcDeleted = client.services().withName(appIdToDelete).delete();
					logger.debug(String.format("Deleted Service for: %s %b", appIdToDelete, svcDeleted));
					Boolean rcDeleted = client.replicationControllers().withName(appIdToDelete).delete();
					if (rcDeleted) {
						logger.debug(
							String.format("Deleted Replication Controller for: %s %b", appIdToDelete, rcDeleted));
					}
					Boolean deplDeleted = client.extensions().deployments().withName(appIdToDelete).delete();
					if (deplDeleted) {
						logger.debug(String.format("Deleted Deployment for: %s %b", appIdToDelete, deplDeleted));
					}
					Response response = httpClient.delete(STATEFULSETS_ENDPOINT,appIdToDelete);
					Boolean statefulSetDeleted = response.isSuccessful();
					if (statefulSetDeleted) {
						logger
							.debug(String.format("Deleted StatefulSet for: %s %b", appIdToDelete, statefulSetDeleted));
					}

					Map<String, String> selector = new HashMap<>();
					selector.put(SPRING_APP_KEY, appIdToDelete);
					FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> podsToDelete = client.pods()
						.withLabels(selector);
					if (podsToDelete != null && podsToDelete.list().getItems() != null) {
						Boolean podDeleted = podsToDelete.delete();
						logger.debug(String.format("Deleted Pods for: %s %b", appIdToDelete, podDeleted));
					}
					else {
						logger.debug(String.format("No Pods to delete for: %s", appIdToDelete));
					}

					FilterWatchListDeletable<PersistentVolumeClaim, PersistentVolumeClaimList, Boolean, Watch, Watcher<PersistentVolumeClaim>> pvcsToDelete = client
						.persistentVolumeClaims().withLabels(selector);
					if (pvcsToDelete != null && pvcsToDelete.list().getItems() != null) {
						Boolean pvcDeleted = pvcsToDelete.delete();
						if (pvcDeleted) {
							logger.debug(String.format("Deleted pvcs for: %s %b", appIdToDelete, pvcDeleted));
						}
						else {
							logger.debug(String.format("No pvcs to delete for: %s", appIdToDelete));
						}
					}

				}
				catch (RuntimeException e) {
					logger.error(e.getMessage(), e);
					throw e;
				}
			}
		}
	}

	@Override
	public AppStatus status(String appId) {
		Map<String, String> selector = new HashMap<>();
		ServiceList services = client.services().withLabel(SPRING_APP_KEY, appId).list();
		selector.put(SPRING_APP_KEY, appId);
		PodList podList = client.pods().withLabels(selector).list();
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Building AppStatus for app: %s", appId));
			if (podList != null && podList.getItems() != null) {
				logger.debug(String.format("Pods for appId %s: %d", appId, podList.getItems().size()));
				for (Pod pod : podList.getItems()) {
					logger.debug(String.format("Pod: %s", pod.getMetadata().getName()));
				}
			}
		}
		AppStatus status = buildAppStatus(appId, podList, services);
		logger.debug(String.format("Status for app: %s is %s", appId, status));

		return status;
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		return super.createRuntimeEnvironmentInfo(AppDeployer.class, this.getClass());
	}

	protected int configureExternalPort(final AppDeploymentRequest request) {
		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
		}

		return externalPort;
	}

	protected String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		// Kubernetes does not allow . in the name and does not allow uppercase in the name
		return deploymentId.replace('.', '-').toLowerCase();
	}

	private Deployment createDeployment(String appId, AppDeploymentRequest request, Map<String, String> idMap,
		int externalPort) {

		int replicas = getCountFromRequest(request);

		Deployment d = new DeploymentBuilder().withNewMetadata().withName(appId).withLabels(idMap)
			.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata().withNewSpec().withReplicas(replicas)
			.withNewTemplate().withNewMetadata().withLabels(idMap).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
			.endMetadata().withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), false)).endTemplate()
			.endSpec().build();

		return client.extensions().deployments().create(d);
	}

	private int getCountFromRequest(AppDeploymentRequest request) {
		String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
		return (countProperty != null) ? Integer.parseInt(countProperty) : 1;
	}

	@Deprecated
	private ReplicationController createReplicationController(String appId, AppDeploymentRequest request,
		Map<String, String> idMap, int externalPort) {

		int replicas = getCountFromRequest(request);

		ReplicationController rc = new ReplicationControllerBuilder().withNewMetadata().withName(appId)
			.withLabels(idMap).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata().withNewSpec()
			.withReplicas(replicas).withSelector(idMap).withNewTemplate().withNewMetadata().withLabels(idMap)
			.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata()
			.withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), false)).endTemplate().endSpec()
			.build();

		return client.replicationControllers().create(rc);
	}

	/**
	 * Create a StatefulSet
	 * @return as a Json string since the Model does not support 'podManagementPolicy' and we need to set it to
	 * Parallel.
	 */
	protected String createStatefulSet(String appId, AppDeploymentRequest request, Map<String, String> idMap,
		int externalPort) {

		int replicas = getCountFromRequest(request);

		logger.debug(String.format("Creating StatefulSet: %s on %d with %d replicas", appId, externalPort, replicas));

		Map<String, Quantity> storageResource = Collections.singletonMap("storage", new Quantity(getStatefulSetStorage(request)));

		String storageClassName = getStatefulSetStorageClassName(request);

		PersistentVolumeClaimBuilder persistentVolumeClaimBuilder = new PersistentVolumeClaimBuilder()
				.withNewSpec().withStorageClassName(storageClassName).withAccessModes(Arrays.asList("ReadWriteOnce"))
				.withNewResources().addToLimits(storageResource).addToRequests(storageResource)
				.endResources().endSpec().withNewMetadata().withName(appId).withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata();

		PodSpec podSpec = createPodSpec(appId, request, Integer.valueOf(externalPort), false);

		podSpec.getVolumes().add(new VolumeBuilder().withName("config").withNewEmptyDir().endEmptyDir().build());

		podSpec.getContainers().get(0).getVolumeMounts()
			.add(new VolumeMountBuilder().withName("config").withMountPath("/config").build());

		podSpec.getInitContainers().add(createInitContainer());

		StatefulSetSpec spec = new StatefulSetSpecBuilder().withNewSelector().addToMatchLabels(idMap)
			.addToMatchLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endSelector()
			.withVolumeClaimTemplates(persistentVolumeClaimBuilder.build()).withServiceName(appId)
			.withReplicas(replicas).withNewTemplate().withNewMetadata().withLabels(idMap)
			.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
			.endMetadata().withSpec(podSpec).endTemplate().build();

		StatefulSet statefulSet = new StatefulSetBuilder().withNewMetadata().withName(appId).withLabels(idMap)
			.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata().withSpec(spec).build();

		Map<String, Object> statefulSetMap = null;
		try {
			String ssString = objectMapper.writeValueAsString(statefulSet);
			statefulSetMap = objectMapper.readValue(ssString, HashMap.class);
		}
		catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		catch (IOException e) {
			e.printStackTrace();
		}

		Map<String, Object> specMap = (Map) statefulSetMap.get("spec");
		specMap.put("podManagementPolicy", "Parallel");

		try {
			return objectMapper.writeValueAsString(statefulSetMap);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	protected void createService(String appId, AppDeploymentRequest request, Map<String, String> idMap,
		int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		boolean isCreateLoadBalancer = false;
		String createLoadBalancer = request.getDeploymentProperties()
			.get("spring.cloud.deployer.kubernetes.createLoadBalancer");
		String createNodePort = request.getDeploymentProperties()
			.get("spring.cloud.deployer.kubernetes.createNodePort");

		if (createLoadBalancer != null && createNodePort != null) {
			throw new IllegalArgumentException("Cannot create NodePort and LoadBalancer at the same time.");
		}

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

		ServicePort servicePort = new ServicePort();
		servicePort.setPort(externalPort);

		if (createNodePort != null) {
			spec.withType("NodePort");
			if (!"true".equals(createNodePort.toLowerCase())) {
				try {
					Integer nodePort = Integer.valueOf(createNodePort);
					servicePort.setNodePort(nodePort);
				}
				catch (NumberFormatException e) {
					throw new IllegalArgumentException(
						String.format("Invalid value: %s: provided port is not valid.", createNodePort));
				}
			}
		}

		spec.withSelector(idMap).addNewPortLike(servicePort).endPort();

		Map<String, String> annotations = getServiceAnnotations(request.getDeploymentProperties());

		client.services().inNamespace(client.getNamespace()).createNew().withNewMetadata().withName(appId)
			.withLabels(idMap).withAnnotations(annotations).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
			.endMetadata().withSpec(spec.build()).done();
	}

	/**
	 * Get the service annotations for the deployment request.
	 *
	 * @param properties The deployment request deployment properties.
	 * @return map of annottaions
	 */
	protected Map<String, String> getServiceAnnotations(Map<String, String> properties) {
		Map<String, String> annotations = new HashMap<>();

		String annotationsProperty = properties.getOrDefault("spring.cloud.deployer.kubernetes.serviceAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = this.properties.getServiceAnnotations();
		}

		if (StringUtils.hasText(annotationsProperty)) {
			String[] annotationPairs = annotationsProperty.split(",");
			for (String annotationPair : annotationPairs) {
				String[] annotation = annotationPair.split(":");
				Assert.isTrue(annotation.length == 2, format("Invalid annotation value: '{}'", annotationPair));
				annotations.put(annotation[0].trim(), annotation[1].trim());
			}
		}

		return annotations;
	}

	/**
	 * For StatefulSets, create an init container to parse ${HOSTNAME} to get the `instance.index` and write it to
	 * config/application.properties on a shared volume so the main container has it. Using the legacy annotation
	 * configuration since the current client version does not directly support InitContainers.
	 *
	 * Since 1.8 the annotation method has been removed, and the initContainer API is supported since 1.6
	 *
	 * @return a container definition with the above mentioned configuration
	 */
	private Container createInitContainer() {
		List<String> command = new LinkedList<>();

		String commandString = String
				.format("%s && %s", setIndexProperty("INSTANCE_INDEX"), setIndexProperty("spring.application.index"));

		command.add("sh");
		command.add("-c");
		command.add(commandString);
		return new ContainerBuilder().withName("index-provider")
				.withImage("busybox")
				.withImagePullPolicy("IfNotPresent")
				.withCommand(command)
				.withVolumeMounts(new VolumeMountBuilder().withName("config").withMountPath("/config").build())
				.build();
	}

	private String setIndexProperty(String name) {
		return String
			.format("echo %s=\"$(expr $HOSTNAME | grep -o \"[[:digit:]]*$\")\" >> /config/application" + ".properties",
				name);
	}

}
