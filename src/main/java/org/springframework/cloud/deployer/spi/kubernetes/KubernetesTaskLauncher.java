/*
 * Copyright 2016-2018 the original author or authors.
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.JobList;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.batch.Job;
import io.fabric8.kubernetes.api.model.batch.JobSpec;
import io.fabric8.kubernetes.api.model.batch.JobSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.JobStatus;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.util.StringUtils;

/**
 * A task launcher that targets Kubernetes.
 *
 * @author Thomas Risberg
 * @author David Turanski
 * @author Leonardo Diniz
 * @author Chris Schaefer
 */
public class KubernetesTaskLauncher extends AbstractKubernetesDeployer implements TaskLauncher {

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
			throw new IllegalStateException("Task " + appId + " already exists with a state of " + status);
		}

		if (this.maxConcurrentExecutionsReached()) {
			throw new IllegalStateException(
				String.format("Cannot launch task %s. The maximum concurrent task executions is at its limit [%d].",
					request.getDefinition().getName(), this.getMaximumConcurrentTasks())
			);
		}

		Map<String, String> idMap = createIdMap(appId, request);
		logger.debug(String.format("Launching pod for task: %s", appId));

		try {
			Map<String, String> podLabelMap = new HashMap<>();
			podLabelMap.put("task-name", request.getDefinition().getName());
			podLabelMap.put(SPRING_MARKER_KEY, SPRING_MARKER_VALUE);

			PodSpec podSpec = createPodSpec(appId, request, null, true);

			Map<String, String> jobAnnotations = getJobAnnotations(request);

			if (properties.isCreateJob()) {
				launchJob(appId, podSpec, podLabelMap, idMap, jobAnnotations);
			}
			else {
				launchPod(appId, podSpec, podLabelMap, idMap, jobAnnotations);
			}

			return appId;
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void cancel(String id) {
		logger.debug(String.format("Cancelling task: %s", id));
		//ToDo: what does cancel mean? Kubernetes doesn't have stop - just cleanup
		cleanup(id);
	}

	@Override
	public void cleanup(String id) {
		try {
			if (properties.isCreateJob()) {
				deleteJob(id);
			} else {
				deletePod(id);
			}
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void destroy(String appName) {
		for (String id : getIdsForTasks(Optional.of(appName), properties.isCreateJob())) {
			cleanup(id);
		}
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		return super.createRuntimeEnvironmentInfo(TaskLauncher.class, this.getClass());
	}

	@Override
	public TaskStatus status(String id) {
		TaskStatus status = buildTaskStatus(id);
		logger.debug(String.format("Status for task: %s is %s", id, status));

		return status;
	}

	@Override
	public int getMaximumConcurrentTasks() {
		return this.properties.getMaximumConcurrentTasks();
	}

	@Override
	public int getRunningTaskExecutionCount() {
		List<String> taskIds = getIdsForTasks(Optional.empty(), false);
		AtomicInteger executionCount = new AtomicInteger();

		taskIds.forEach(id-> {
			if (buildPodStatus(id).getState() == LaunchState.running) {
				executionCount.incrementAndGet();
			}
		});

		return executionCount.get();
	}

	private boolean maxConcurrentExecutionsReached() {
		return this.getRunningTaskExecutionCount() >= this.getMaximumConcurrentTasks();
	}

	protected String createDeploymentId(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();
		Hashids hashids = new Hashids(name, 0, "abcdefghijklmnopqrstuvwxyz1234567890");
		String hashid = hashids.encode(System.currentTimeMillis());
		String deploymentId = name + "-" + hashid;
		// Kubernetes does not allow . in the name and does not allow uppercase in the name
		return deploymentId.replace('.', '-').toLowerCase();
	}


	private void launchPod(String appId, PodSpec podSpec, Map<String, String> labelMap, Map<String, String> idMap,
						   Map<String, String> annotations) {
		client.pods()
				.createNew()
				.withNewMetadata()
				.withName(appId)
				.withLabels(labelMap)
				.withAnnotations(annotations)
				.addToLabels(idMap)
				.endMetadata()
				.withSpec(podSpec)
				.done();
	}

	private void launchJob(String appId, PodSpec podSpec, Map<String, String> podLabelMap, Map<String, String> idMap,
						   Map<String, String> annotations) {
		ObjectMeta objectMeta = new ObjectMetaBuilder().withLabels(podLabelMap).addToLabels(idMap).build();
		PodTemplateSpec podTemplateSpec = new PodTemplateSpec(objectMeta, podSpec);

		JobSpec jobSpec = new JobSpecBuilder()
				.withTemplate(podTemplateSpec)
				.build();

		client.batch().jobs()
				.createNew()
				.withNewMetadata()
				.withName(appId)
				.withLabels(Collections.singletonMap("task-name", podLabelMap.get("task-name")))
				.addToLabels(idMap)
				.withAnnotations(annotations)
				.endMetadata()
				.withSpec(jobSpec)
				.done();
	}

	private List<String> getIdsForTasks(Optional<String> taskName, boolean isCreateJob) {
		List<String> ids = new ArrayList<>();
		try {
			KubernetesResourceList<?> resourceList = getTaskResources(taskName, isCreateJob);

			for (HasMetadata hasMetadata : resourceList.getItems()) {
				ids.add(hasMetadata.getMetadata().getName());
			}
		}
		catch (KubernetesClientException kce) {
			logger.warn(String.format("Failed to retrieve pods for task: %s", taskName), kce);
		}

		return ids;
	}

	private KubernetesResourceList<?>  getTaskResources(Optional<String> taskName, boolean isCreateJob) {
		KubernetesResourceList<?> resourceList;
		if (taskName.isPresent()) {
			if (isCreateJob) {
				resourceList = client.batch().jobs().withLabel("task-name", taskName.get()).list();
			}
			else {
				resourceList = client.pods().withLabel("task-name", taskName.get()).list();
			}
		} else {
			if (isCreateJob) {
				resourceList = client.batch().jobs().withLabel("task-name").list();
			}
			else {
				resourceList = client.pods().withLabel("task-name").list();
			}
		}
		return resourceList;
	}

	private Map<String, String> getJobAnnotations(AppDeploymentRequest request) {
		String annotationsProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.jobAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = properties.getJobAnnotations();
		}

		return PropertyParserUtils.getAnnotations(annotationsProperty);
	}

	TaskStatus buildTaskStatus(String id) {

		if(properties.isCreateJob()){
			Job job = getJob(id);

			if (job == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}

			JobStatus jobStatus = job.getStatus();

			if (jobStatus == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}

			boolean failed = jobStatus.getFailed() != null && jobStatus.getFailed() > 0;
			boolean succeeded = jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0;
			if (failed) {
				return new TaskStatus(id, LaunchState.failed, new HashMap<>());
			}
			if (succeeded) {
				return new TaskStatus(id, LaunchState.complete, new HashMap<>());
			}
			return new TaskStatus(id, LaunchState.launching, new HashMap<>());

		} else {
			return buildPodStatus(id);
		}
	}

	private TaskStatus buildPodStatus(String id) {
		Pod pod = getPodByName(id);
		if (pod == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}

		PodStatus podStatus = pod.getStatus();
		if (podStatus == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}

		String phase = podStatus.getPhase();

		switch (phase) {
		case "Pending":
			return new TaskStatus(id, LaunchState.launching, new HashMap<>());
		case "Failed":
			return new TaskStatus(id, LaunchState.failed, new HashMap<>());
		case "Succeeded":
			return new TaskStatus(id, LaunchState.complete, new HashMap<>());
		default:
			return new TaskStatus(id, LaunchState.running, new HashMap<>());
		}
	}


	private void deleteJob(String id) {
		FilterWatchListDeletable<Job, JobList, Boolean, Watch, Watcher<Job>> jobsToDelete = client.batch().jobs()
				.withLabel(SPRING_APP_KEY, id);

		if (jobsToDelete != null && jobsToDelete.list().getItems() != null) {
			logger.debug(String.format("Deleting Job for task: %s", id));
			boolean jobDeleted = jobsToDelete.delete();
			logger.debug(String.format("Job deleted for: %s - %b", id, jobDeleted));
		}
	}

	private void deletePod(String id) {
		FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> podsToDelete = client.pods()
				.withLabel(SPRING_APP_KEY, id);

		if (podsToDelete != null && podsToDelete.list().getItems() != null) {
			logger.debug(String.format("Deleting Pod for task: %s", id));
			boolean podsDeleted = podsToDelete.delete();
			logger.debug(String.format("Pod deleted for: %s - %b", id, podsDeleted));
		}
	}

	private Job getJob(String jobName) {
		List<Job> jobs = client.batch().jobs().withLabel(SPRING_APP_KEY, jobName).list().getItems();

		for (Job job : jobs) {
			if (jobName.equals(job.getMetadata().getName())) {
				return job;
			}
		}

		return null;
	}

	private Pod getPodByName(String name) {
		PodResource podResource = client.pods().withName(name);
		return podResource == null? null: client.pods().withName(name).get();
	}


}
