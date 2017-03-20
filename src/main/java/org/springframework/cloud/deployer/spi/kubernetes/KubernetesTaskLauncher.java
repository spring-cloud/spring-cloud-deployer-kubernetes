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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hashids.Hashids;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A task launcher that targets Kubernetes.
 *
 * @author Thomas Risberg
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
		Map<String, String> idMap = createIdMap(appId, request, null);

		logger.debug(String.format("Launching pod for task: %s", appId));
		try {
			createPod(appId, request, idMap);
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
		logger.debug(String.format("Deleting pod for task: %s", id));
		deletePod(id);
	}

	@Override
	public void destroy(String appName) {
		for (String id : getPodIdsForTaskName(appName)) {
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

	private void createPod(String appId, AppDeploymentRequest request, Map<String, String> idMap) {
		Map<String, String> podLabelMap = new HashMap<>();
		podLabelMap.put("task-name", request.getDefinition().getName());
		podLabelMap.put(SPRING_MARKER_KEY, SPRING_MARKER_VALUE);
		PodSpec spec = createPodSpec(appId, request, null, null, true);
		client.pods()
				.inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
				.withName(appId)
				.withLabels(podLabelMap)
				.addToLabels(idMap)
				.endMetadata()
				.withSpec(spec)
				.done();
	}

	private List<String> getPodIdsForTaskName(String taskName) {
		PodList pods = client.pods().inNamespace(client.getNamespace()).withLabel("task-name", taskName).list();
		List<String> ids = new ArrayList<>();
		for (Pod pod : pods.getItems()) {
			ids.add(pod.getMetadata().getName());
		}
		return ids;
	}

	private void deletePod(String id) {
		try {
			Boolean podDeleted = client.pods().inNamespace(client.getNamespace()).withName(id).delete();
			if (podDeleted) {
				logger.debug(String.format("Deleted pod successfully: %s", id));
			}
			else {
				logger.debug(String.format("Delete failed for pod: %s", id));
			}
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	TaskStatus buildTaskStatus(String id) {
		Pod pod = client.pods().inNamespace(client.getNamespace()).withName(id).get();
		if (pod == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}
		PodStatus podStatus = pod.getStatus();
		if (podStatus == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}
		if (podStatus.getPhase() != null) {
			if (podStatus.getPhase().equals("Pending")) {
				return new TaskStatus(id, LaunchState.launching, new HashMap<>());
			}
			else if (podStatus.getPhase().equals("Failed")) {
				return new TaskStatus(id, LaunchState.failed, new HashMap<>());
			}
			else if (podStatus.getPhase().equals("Succeeded")) {
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

}
