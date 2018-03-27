/*
 * Copyright 2016-2017 the original author or authors.
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

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * A task launcher that targets Kubernetes.
 *
 * @author Thomas Risberg
 * @author David Turanski
 * @author Leonardo Diniz
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
		Map<String, String> idMap = createIdMap(appId, request);

		logger.debug(String.format("Launching pod for task: %s", appId));
		try {
			Map<String, String> podLabelMap = new HashMap<>();
			podLabelMap.put("task-name", request.getDefinition().getName());
			podLabelMap.put(SPRING_MARKER_KEY, SPRING_MARKER_VALUE);
			PodSpec podSpec = createPodSpec(appId, request, null, true);
			Map<String, String> podAnnotationMap = getPodAnnotations(request);
			if (properties.isCreateJob()){
				launchJob(appId, podSpec, podLabelMap, idMap, podAnnotationMap);
			} else {
				launchPod(appId, podSpec, podLabelMap, idMap);
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
			boolean deleted;
			String workload;
			if (properties.isCreateJob()) {
				workload = "job";
				logger.debug(String.format("Deleting %s for task: %s", workload, id));
				deleted = client.extensions().jobs().inNamespace(client.getNamespace()).withName(id).delete();
			} else {
				workload = "pod";
				logger.debug(String.format("Deleting %s for task: %s", workload, id));
				deleted = client.pods().inNamespace(client.getNamespace()).withName(id).delete();
			}

			if (deleted) {
				logger.debug(String.format("Deleted %s successfully: %s", workload, id));
			} else {
				logger.debug(String.format("Delete failed for %s: %s", workload, id));
			}
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void destroy(String appName) {
		for (String id : getIdsForTaskName(appName)) {
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

	protected String createDeploymentId(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();
		Hashids hashids = new Hashids(name, 0, "abcdefghijklmnopqrstuvwxyz1234567890");
		String hashid = hashids.encode(System.currentTimeMillis());
		String deploymentId = name + "-" + hashid;
		// Kubernetes does not allow . in the name and does not allow uppercase in the name
		return deploymentId.replace('.', '-').toLowerCase();
	}


	private void launchPod(String appId, PodSpec podSpec, Map<String, String> labelMap, Map<String, String> idMap) {
		client.pods()
				.inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
				.withName(appId)
				.withLabels(labelMap)
				.addToLabels(idMap)
				.endMetadata()
				.withSpec(podSpec)
				.done();
	}


	private void launchJob(String appId, PodSpec podSpec, Map<String, String> podLabelMap, Map<String, String> idMap, Map<String, String> podAnnotationMap) {
		JobSpec jobSpec = new JobSpecBuilder()
				.withTemplate(new PodTemplateSpec(
						new ObjectMetaBuilder()
								.withLabels(podLabelMap)
								.addToLabels(idMap)
								.addToAnnotations(podAnnotationMap)
								.build(),
								podSpec)).build();
		client.extensions().jobs()
				.inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
				.withName(appId)
				.addToLabels(idMap)
				.endMetadata()
				.withSpec(jobSpec)
				.done();
	}



	private List<String> getIdsForTaskName(String taskName) {
		List<String> ids = new ArrayList<>();
		try {
			KubernetesResourceList resourceList;
			if(properties.isCreateJob()){
				resourceList = client.extensions().jobs().inNamespace(client.getNamespace()).withLabel("task-name", taskName).list();
			} else {
				resourceList = client.pods().inNamespace(client.getNamespace()).withLabel("task-name", taskName).list();
			}
			for (HasMetadata hasMetadata : (List<HasMetadata>)resourceList.getItems()) {
				ids.add(hasMetadata.getMetadata().getName());
			}

		}
		catch (KubernetesClientException kce) {
			logger.warn(String.format("Failed to retrieve pods for task: %s", taskName), kce);
		}
		return ids;
	}

	TaskStatus buildTaskStatus(String id) {
		String phase;
		if(properties.isCreateJob()){
			Job job = client.extensions().jobs().inNamespace(client.getNamespace()).withName(id).get();
			if (job == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}
			JobStatus jobStatus = job.getStatus();
			if (jobStatus == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}
			boolean failed = jobStatus.getFailed() != null && jobStatus.getFailed() > 0;
			boolean succeeded = jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0;
			phase = failed ? "Failed" : succeeded ? "Succeeded" : null;
		} else {
			Pod pod = client.pods().inNamespace(client.getNamespace()).withName(id).get();
			if (pod == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}
			PodStatus podStatus = pod.getStatus();
			if (podStatus == null) {
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
			}
			phase = podStatus.getPhase();
		}
		if (phase != null) {
			if (phase.equals("Pending")) {
				return new TaskStatus(id, LaunchState.launching, new HashMap<>());
			}
			else if (phase.equals("Failed")) {
				return new TaskStatus(id, LaunchState.failed, new HashMap<>());
			}
			else if (phase.equals("Succeeded")) {
				return new TaskStatus(id, LaunchState.complete, new HashMap<>());
			}
			else {
				return new TaskStatus(id, LaunchState.running, new HashMap<>());
			}
		}
		else {
			return new TaskStatus(id, LaunchState.launching, new HashMap<>());
		}
	}

	private Map<String, String> getPodAnnotations(AppDeploymentRequest request) {
		String annotationsProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.podAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = properties.getPodAnnotations();
		}

		return getAnnotations(annotationsProperty);
	}

	private Map<String, String> getAnnotations(String annotation) {
		Map<String, String> annotations = new HashMap<>();

		if (StringUtils.hasText(annotation)) {
			String[] annotationPairs = annotation.split(",");
			for (String annotationPair : annotationPairs) {
				String[] splitAnnotation = annotationPair.split(":");
				Assert.isTrue(splitAnnotation.length == 2, format("Invalid annotation value: %s", annotationPair));
				annotations.put(splitAnnotation[0].trim(), splitAnnotation[1].trim());
			}
		}

		return annotations;
	}

}
