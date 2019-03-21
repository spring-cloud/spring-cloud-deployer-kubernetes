/*
 * Copyright 2015-2016 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;

/**
 * Represents the status of a module.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
public class KubernetesAppInstanceStatus implements AppInstanceStatus {

	private static Log logger = LogFactory.getLog(KubernetesAppInstanceStatus.class);
	private final Pod pod;
	private final String moduleId;
	private KubernetesDeployerProperties properties;
	private ContainerStatus containerStatus;

	public KubernetesAppInstanceStatus(String moduleId, Pod pod, KubernetesDeployerProperties properties) {
		this.moduleId = moduleId;
		this.pod = pod;
		this.properties = properties;
		// we assume one container per pod
		if (pod != null && pod.getStatus().getContainerStatuses().size() == 1) {
			this.containerStatus = pod.getStatus().getContainerStatuses().get(0);
		} else {
			this.containerStatus = null;
		}
	}

	@Override
	public String getId() {
		return String.format("%s:%s", moduleId, pod.getMetadata().getName());
	}

	@Override
	public DeploymentState getState() {
		return pod != null && containerStatus != null ? mapState() : DeploymentState.unknown;
	}

	/**
	 * Maps Kubernetes phases/states onto Spring Cloud Deployer states
	 */
	private DeploymentState mapState() {
		logger.debug(String.format("%s - Phase [ %s ]", pod.getMetadata().getName(), pod.getStatus().getPhase()));
		logger.debug(String.format("%s - ContainerStatus [ %s ]", pod.getMetadata().getName(), containerStatus));
		switch (pod.getStatus().getPhase()) {
			
			case "Pending":
				return DeploymentState.deploying;
				
			// We only report a module as running if the container is also ready to service requests.
			// We also implement the Readiness check as part of the container to ensure ready means
			// that the module is up and running and not only that the JVM has been created and the
			// Spring module is still starting up
			case "Running":
				// we assume we only have one container
				if (containerStatus.getReady()) {
					return DeploymentState.deployed;
				}
				// if we are being killed repeatedly due to OOM or using too much CPU
				else if (containerStatus.getRestartCount() > properties.getMaxTerminatedErrorRestarts() &&
							containerStatus.getLastState() != null &&
							containerStatus.getLastState().getTerminated() != null &&
							(containerStatus.getLastState().getTerminated().getExitCode() == 137 ||
							 containerStatus.getLastState().getTerminated().getExitCode() == 143)) {
						return DeploymentState.failed;
				}
				// if we are being restarted repeatedly due to the same error, consider the app crashed
				else if (containerStatus.getRestartCount() > properties.getMaxTerminatedErrorRestarts() &&
							containerStatus.getLastState() != null &&
							containerStatus.getState() != null &&
							containerStatus.getLastState().getTerminated() != null &&
							containerStatus.getState().getTerminated() != null &&
							containerStatus.getLastState().getTerminated().getReason().contains("Error") &&
							containerStatus.getState().getTerminated().getReason().contains("Error") &&
							containerStatus.getLastState().getTerminated().getExitCode().equals(
									containerStatus.getState().getTerminated().getExitCode())) {
						return DeploymentState.failed;
				}
				// if we are being restarted repeatedly and we're in a CrashLoopBackOff, consider the app crashed
				else if (containerStatus.getRestartCount() > properties.getMaxCrashLoopBackOffRestarts() &&
							containerStatus.getLastState() != null &&
							containerStatus.getState() != null &&
							containerStatus.getLastState().getTerminated() != null &&
							containerStatus.getState().getWaiting() != null &&
							containerStatus.getState().getWaiting().getReason() != null &&
							containerStatus.getState().getWaiting().getReason().contains("CrashLoopBackOff")) {
						return DeploymentState.failed;
				}
				// if we were terminated and not restarted, we consider this undeployed
				else if (containerStatus.getRestartCount() == 0 &&
						containerStatus.getState() != null &&
						containerStatus.getState().getTerminated() != null) {
					return DeploymentState.undeployed;
				}
				else {
					return DeploymentState.deploying;
				}

			case "Failed":
				return DeploymentState.failed;

			case "Unknown":
				return DeploymentState.unknown;

			default: 
				return DeploymentState.unknown;
		}
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> result = new HashMap<>();

		if (pod != null) {
			result.put("pod_starttime", pod.getStatus().getStartTime());
			result.put("pod_ip", pod.getStatus().getPodIP());
			result.put("host_ip", pod.getStatus().getHostIP());
			result.put("phase", pod.getStatus().getPhase());
		}
		if (containerStatus != null) {
			result.put("container_restart_count", "" + containerStatus.getRestartCount());
			if (containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null) {
				result.put("container_last_termination_exit_code", "" + containerStatus.getLastState().getTerminated().getExitCode());
				result.put("container_last_termination_reason", containerStatus.getLastState().getTerminated().getReason());
			}
			if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
				result.put("container_termination_exit_code", "" + containerStatus.getState().getTerminated().getExitCode());
				result.put("container_termination_reason", containerStatus.getState().getTerminated().getReason());
			}
		}
		return result;
	}
}
