/*
 * Copyright 2016 the original author or authors.
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
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.extensions.Job;
import io.fabric8.kubernetes.api.model.extensions.JobSpec;
import io.fabric8.kubernetes.api.model.extensions.JobSpecBuilder;
import io.fabric8.kubernetes.api.model.extensions.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * A task launcher that targets Kubernetes.
 *
 * @author Thomas Risberg
 */
public class KubernetesTaskLauncher extends AbstractKubernetesDeployer implements TaskLauncher {

	private KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

	private final KubernetesClient client;

	private final ContainerFactory containerFactory;

	private final Map<String, Object> running = new ConcurrentHashMap<>();

	@Autowired
	public KubernetesTaskLauncher(KubernetesDeployerProperties properties,
	                             KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesTaskLauncher(KubernetesDeployerProperties properties,
	                             KubernetesClient client, ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
		this.containerFactory = containerFactory;
	}

	@Override
	public String launch(AppDeploymentRequest request) {
		String appId = createDeploymentId(request);
		TaskStatus status = status(appId);
		if (!status.getState().equals(LaunchState.unknown)) {
			if (status.getState().equals(LaunchState.launching) || status.getState().equals(LaunchState.running)) {
				throw new IllegalStateException("Task " + appId + " is already active with a state of " + status);
			}
			deleteJob(appId);
		}
		Map<String, String> idMap = createIdMap(appId, request);

		logger.debug("Launching job: {}", appId);
		try {
			createJob(appId, request, idMap);
			return appId;
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void cancel(String id) {
		logger.debug("Cancelling job: {}", id);
		//ToDo: what does cancel mean? Kubernetes doesn't have stop - just delete
		delete(id);
	}

//	@Override //TODO: should be part of interface
	public void delete(String id) {
		logger.debug("Deleting job: {}", id);
		deleteJob(id);
	}

	@Override
	public TaskStatus status(String id) {
		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, id);
		PodList list = client.pods().withLabels(selector).list();
		TaskStatus status = buildTaskStatus(properties, id, list);
		logger.debug("Status for task: {} is {}", id, status);

		return status;
	}

	private Container createContainer(String appId, AppDeploymentRequest request) {
		Container container = containerFactory.create(appId, request, null, 1);
		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(properties, request));
		container.setResources(req);
		return container;
	}

	private PodSpec createPodSpec(String appId, AppDeploymentRequest request) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		podSpec.addToContainers(createContainer(appId, request));
		podSpec.withRestartPolicy("Never");

		return podSpec.build();
	}

	private void createJob(String appId, AppDeploymentRequest request, Map<String, String> idMap) {
		Map<String, String> jobLabelMap = new HashMap<>();
		jobLabelMap.put("job-name", appId);
		jobLabelMap.put(SPRING_MARKER_KEY, SPRING_MARKER_VALUE);
		JobSpec spec = new JobSpecBuilder()
			.withTemplate(new PodTemplateSpec(
					new ObjectMetaBuilder()
							.withLabels(jobLabelMap)
							.addToLabels(idMap)
							.build(),
					createPodSpec(appId, request))).build();
		client.extensions().jobs()
				.inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
					.withName(appId)
					.withLabels(jobLabelMap)
					.addToLabels(idMap)
				.endMetadata()
				.withSpec(spec)
				.done();
	}

	private void deleteJob(String id) {
		try {
			Boolean jobDeleted = client.extensions().jobs().inNamespace(client.getNamespace()).withName(id).delete();
			if (jobDeleted) {
				logger.debug("Deleted job successfully: {}", id);
			}
			else {
				logger.debug("Delete failed for job: {}", id);
			}
			Map<String, String> selector = new HashMap<>();
			selector.put(SPRING_APP_KEY, id);
			PodList list = client.pods().withLabels(selector).list();
			for (Pod p : list.getItems()) {
				logger.debug("Deleting pod: {}", p.getMetadata().getName());
				client.pods().inNamespace(client.getNamespace()).withName(p.getMetadata().getName()).delete();
			}
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	TaskStatus buildTaskStatus(KubernetesDeployerProperties properties, String id, PodList list) {
		Job job = client.extensions().jobs().inNamespace(client.getNamespace()).withName(id).get();
		if (job == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}
		JobStatus jobStatus = job.getStatus();
		if (jobStatus == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}

		if (jobStatus.getActive() != null) {
			if (jobStatus.getActive().intValue() > 0) {
				return new TaskStatus(id, LaunchState.running, new HashMap<>());
			}
			else {
				return new TaskStatus(id, LaunchState.launching, new HashMap<>());
			}
		}

		if (list == null || list.getItems().isEmpty()) {
			return new TaskStatus(id, LaunchState.launching, new HashMap<>());
		}

		//ToDo: needs tweaking if we launch multiple pods per task
		if (jobStatus.getSucceeded().intValue() == list.getItems().size()) {
			return new TaskStatus(id, LaunchState.complete, new HashMap<>());
		}
		else {
			return new TaskStatus(id, LaunchState.failed, new HashMap<>());
		}
	}

}
