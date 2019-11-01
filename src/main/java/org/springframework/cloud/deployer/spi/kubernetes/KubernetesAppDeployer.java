/*
 * Copyright 2015-2019 the original author or authors.
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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppScaleRequest;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import static java.lang.String.format;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @author Chris Schaefer
 * @author Christian Tzolov
 */
public class KubernetesAppDeployer extends AbstractKubernetesDeployer implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";
	protected static final String STATEFUL_SET_IMAGE_NAME = "busybox";

	protected final Log logger = LogFactory.getLog(getClass().getName());

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties, KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties, KubernetesClient client,
		ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
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
			boolean indexed = (indexedProperty != null) ? Boolean.valueOf(indexedProperty) : false;
			logPossibleDownloadResourceMessage(request.getResource());
			Map<String, String> idMap = createIdMap(appId, request);

			if (indexed) {
				logger.debug(String.format("Creating Service: %s on %d with", appId, externalPort));
				createService(appId, request, idMap, externalPort);

				logger.debug(String.format("Creating StatefulSet: %s", appId));
				createStatefulSet(appId, request, idMap, externalPort);
			}
			else {
				logger.debug(String.format("Creating Service: %s on %d", appId, externalPort));
				createService(appId, request, idMap, externalPort);

				logger.debug(String.format("Creating Deployment: %s", appId));
				createDeployment(appId, request, idMap, externalPort);
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
			// ensure objects for this appId are deleted in the event a previous deployment failed.
			// allows for log inspection prior to making an undeploy request.
			deleteAllObjects(appId);

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

					deleteAllObjects(appIdToDelete);
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
	public String getLog(String appId) {
		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, appId);
		PodList podList = client.pods().withLabels(selector).list();
		StringBuilder logAppender = new StringBuilder();
		for (Pod pod : podList.getItems()) {
			logAppender.append(this.client.pods().withName(pod.getMetadata().getName()).tailingLines(500).getLog());
		}
		return logAppender.toString();
	}

	@Override
	public void scale(AppScaleRequest appScaleRequest) {
		String deploymentId = appScaleRequest.getDeploymentId();
		logger.debug(String.format("Scale app: %s to: %s", deploymentId, appScaleRequest.getCount()));

		ScalableResource scalableResource = this.client.apps().deployments().withName(deploymentId);
		if (scalableResource.get() == null) {
			scalableResource = this.client.apps().statefulSets().withName(deploymentId);
		}
		if (scalableResource.get() == null) {
			throw new IllegalStateException(String.format("App '%s' is not deployed", deploymentId));
		}
		scalableResource.scale(appScaleRequest.getCount(), true);
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

		Map<String, String> annotations = getPodAnnotations(request);
		Map<String, String> deploymentLabels = getDeploymentLabels(request);

		PodSpec podSpec = createPodSpec(appId, request, externalPort, false);

		Deployment d = new DeploymentBuilder().withNewMetadata().withName(appId).withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).addToLabels(deploymentLabels).endMetadata()
				.withNewSpec().withNewSelector().addToMatchLabels(idMap).endSelector().withReplicas(replicas)
				.withNewTemplate().withNewMetadata().withLabels(idMap).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.addToLabels(deploymentLabels).withAnnotations(annotations).endMetadata().withSpec(podSpec).endTemplate()
				.endSpec().build();

		return client.apps().deployments().create(d);
	}

	private int getCountFromRequest(AppDeploymentRequest request) {
		String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
		return (countProperty != null) ? Integer.parseInt(countProperty) : 1;
	}

	/**
	 * Create a StatefulSet
	 *
	 * @param appId the application id
	 * @param request the {@link AppDeploymentRequest}
	 * @param idMap the map of labels to use
	 * @param externalPort the external port of the container
	 */
	protected void createStatefulSet(String appId, AppDeploymentRequest request, Map<String, String> idMap,
											int externalPort) {
		int replicas = getCountFromRequest(request);

		logger.debug(String.format("Creating StatefulSet: %s on %d with %d replicas", appId, externalPort, replicas));

		Map<String, Quantity> storageResource = Collections.singletonMap("storage",
				new Quantity(getStatefulSetStorage(request)));

		String storageClassName = getStatefulSetStorageClassName(request);

		PersistentVolumeClaimBuilder persistentVolumeClaimBuilder = new PersistentVolumeClaimBuilder().withNewSpec().
				withStorageClassName(storageClassName).withAccessModes(Collections.singletonList("ReadWriteOnce"))
				.withNewResources().addToLimits(storageResource).addToRequests(storageResource).endResources()
				.endSpec().withNewMetadata().withName(appId).withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata();

		PodSpec podSpec = createPodSpec(appId, request, externalPort, false);

		podSpec.getVolumes().add(new VolumeBuilder().withName("config").withNewEmptyDir().endEmptyDir().build());

		podSpec.getContainers().get(0).getVolumeMounts()
			.add(new VolumeMountBuilder().withName("config").withMountPath("/config").build());

		String statefulSetInitContainerImageName = getStatefulSetInitContainerImageName(request, properties);

		podSpec.getInitContainers().add(createStatefulSetInitContainer(statefulSetInitContainerImageName));

		StatefulSetSpec spec = new StatefulSetSpecBuilder().withNewSelector().addToMatchLabels(idMap)
				.addToMatchLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endSelector()
				.withVolumeClaimTemplates(persistentVolumeClaimBuilder.build()).withServiceName(appId)
				.withPodManagementPolicy("Parallel").withReplicas(replicas).withNewTemplate().withNewMetadata()
				.withLabels(idMap).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata().withSpec(podSpec)
				.endTemplate().build();

		StatefulSet statefulSet = new StatefulSetBuilder().withNewMetadata().withName(appId).withLabels(idMap)
				.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE).endMetadata().withSpec(spec).build();

		client.apps().statefulSets().create(statefulSet);
	}

	protected void createService(String appId, AppDeploymentRequest request, Map<String, String> idMap,
		int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		boolean isCreateLoadBalancer = false;
		String createLoadBalancer = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
			"spring.cloud.deployer.kubernetes.createLoadBalancer");
		String createNodePort = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
			"spring.cloud.deployer.kubernetes.createNodePort");
		String additionalServicePorts = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.servicePorts");

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
		servicePort.setName("port-" + externalPort);

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

		Set<ServicePort> servicePorts = new HashSet<>();
		servicePorts.add(servicePort);

		if (StringUtils.hasText(additionalServicePorts)) {
			servicePorts.addAll(addAdditionalServicePorts(additionalServicePorts));
		}

		spec.withSelector(idMap).addAllToPorts(servicePorts);

		Map<String, String> annotations = getServiceAnnotations(request);

		client.services().createNew().withNewMetadata().withName(appId)
			.withLabels(idMap).withAnnotations(annotations).addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
			.endMetadata().withSpec(spec.build()).done();
	}

	private Set<ServicePort> addAdditionalServicePorts(String additionalServicePorts) {
		Set<ServicePort> ports = new HashSet<>();

		String[] servicePorts = additionalServicePorts.split(",");
		for (String servicePort : servicePorts) {
			Integer port = Integer.parseInt(servicePort.trim());
			ServicePort extraServicePort = new ServicePort();
			extraServicePort.setPort(port);
			extraServicePort.setName("port-" + port);

			ports.add(extraServicePort);
		}

		return ports;
	}

	private Map<String, String> getPodAnnotations(AppDeploymentRequest request) {
		String annotationsValue = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.podAnnotations", "");
		if (StringUtils.isEmpty(annotationsValue)) {
			annotationsValue = properties.getPodAnnotations();
		}
		return PropertyParserUtils.getAnnotations(annotationsValue);
	}

	private Map<String, String> getServiceAnnotations(AppDeploymentRequest request) {
		String annotationsProperty = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.serviceAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = properties.getServiceAnnotations();
		}

		return PropertyParserUtils.getAnnotations(annotationsProperty);
	}

	protected Map<String, String> getDeploymentLabels(AppDeploymentRequest request) {
		Map<String, String> labels = new HashMap<>();

		String deploymentLabels = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.deploymentLabels", "");

		if (StringUtils.hasText(deploymentLabels)) {
			String[] deploymentLabel = deploymentLabels.split(",");

			for (String label : deploymentLabel) {
				String[] labelPair = label.split(":");
				Assert.isTrue(labelPair.length == 2,
						format("Invalid label format, expected 'labelKey:labelValue', got: '%s'", labelPair));
				labels.put(labelPair[0].trim(), labelPair[1].trim());
			}
		}

		return labels;
	}

	/**
	 * For StatefulSets, create an init container to parse ${HOSTNAME} to get the `instance.index` and write it to
	 * config/application.properties on a shared volume so the main container has it. Using the legacy annotation
	 * configuration since the current client version does not directly support InitContainers.
	 *
	 * Since 1.8 the annotation method has been removed, and the initContainer API is supported since 1.6
	 *
	 * @param imageName the image name to use in the init container
	 * @return a container definition with the above mentioned configuration
	 */
	private Container createStatefulSetInitContainer(String imageName) {
		List<String> command = new LinkedList<>();

		String commandString = String
				.format("%s && %s", setIndexProperty("INSTANCE_INDEX"), setIndexProperty("spring.application.index"));

		command.add("sh");
		command.add("-c");
		command.add(commandString);
		return new ContainerBuilder().withName("index-provider")
				.withImage(imageName)
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

	private void deleteAllObjects(String appIdToDelete) {
		Map<String, String> labels = Collections.singletonMap(SPRING_APP_KEY, appIdToDelete);

		deleteService(labels);
		deleteReplicationController(labels);
		deleteDeployment(labels);
		deleteStatefulSet(labels);
		deletePod(labels);
		deletePvc(labels);
	}

	private void deleteService(Map<String, String> labels) {
		FilterWatchListDeletable<Service, ServiceList, Boolean, Watch, Watcher<Service>> servicesToDelete =
				client.services().withLabels(labels);

		if (servicesToDelete != null && servicesToDelete.list().getItems() != null) {
			boolean servicesDeleted = servicesToDelete.delete();
			logger.debug(String.format("Service deleted for: %s - %b", labels, servicesDeleted));
		}
	}

	private void deleteReplicationController(Map<String, String> labels) {
		FilterWatchListDeletable<ReplicationController, ReplicationControllerList, Boolean, Watch,
				Watcher<ReplicationController>> replicationControllersToDelete = client.replicationControllers()
				.withLabels(labels);

		if (replicationControllersToDelete != null && replicationControllersToDelete.list().getItems() != null) {
			boolean replicationControllersDeleted = replicationControllersToDelete.delete();
			logger.debug(String.format("ReplicationController deleted for: %s - %b", labels,
					replicationControllersDeleted));
		}
	}

	private void deleteDeployment(Map<String, String> labels) {
		FilterWatchListDeletable<Deployment, DeploymentList, Boolean, Watch, Watcher<Deployment>> deploymentsToDelete =
				client.apps().deployments().withLabels(labels);

		if (deploymentsToDelete != null && deploymentsToDelete.list().getItems() != null) {
			boolean deploymentsDeleted = deploymentsToDelete.delete();
			logger.debug(String.format("Deployment deleted for: %s - %b", labels, deploymentsDeleted));
		}
	}

	private void deleteStatefulSet(Map<String, String> labels) {
		FilterWatchListDeletable<StatefulSet, StatefulSetList, Boolean, Watch, Watcher<StatefulSet>> ssToDelete =
				client.apps().statefulSets().withLabels(labels);

		if (ssToDelete != null && ssToDelete.list().getItems() != null) {
			boolean ssDeleted = ssToDelete.delete();
			logger.debug(String.format("StatefulSet deleted for: %s - %b", labels, ssDeleted));
		}
	}

	private void deletePod(Map<String, String> labels) {
		FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> podsToDelete = client.pods()
				.withLabels(labels);

		if (podsToDelete != null && podsToDelete.list().getItems() != null) {
			boolean podsDeleted = podsToDelete.delete();
			logger.debug(String.format("Pod deleted for: %s - %b", labels, podsDeleted));
		}
	}

	private void deletePvc(Map<String, String> labels) {
		FilterWatchListDeletable<PersistentVolumeClaim, PersistentVolumeClaimList, Boolean, Watch,
				Watcher<PersistentVolumeClaim>> pvcsToDelete = client.persistentVolumeClaims()
				.withLabels(labels);

		if (pvcsToDelete != null && pvcsToDelete.list().getItems() != null) {
			boolean pvcsDeleted = pvcsToDelete.delete();
			logger.debug(String.format("PVC deleted for: %s - %b", labels, pvcsDeleted));
		}
	}

	private String getStatefulSetInitContainerImageName(AppDeploymentRequest request, KubernetesDeployerProperties
														  kubernetesDeployerProperties) {
		String statefulSetInitContainerImageName = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.statefulSetInitContainerImageName", "");

		if (StringUtils.hasText(statefulSetInitContainerImageName)) {
			return statefulSetInitContainerImageName;
		}

		statefulSetInitContainerImageName = kubernetesDeployerProperties.getStatefulSetInitContainerImageName();

		if (StringUtils.hasText(statefulSetInitContainerImageName)) {
			return statefulSetInitContainerImageName;
		}

		return STATEFUL_SET_IMAGE_NAME;
	}
}
