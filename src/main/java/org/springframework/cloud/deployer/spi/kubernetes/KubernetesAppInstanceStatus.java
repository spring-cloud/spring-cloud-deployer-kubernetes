/*
 * Copyright 2015-2017 the original author or authors.
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

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the status of a module.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author David Turanski
 */
public class KubernetesAppInstanceStatus implements AppInstanceStatus {

	private static Log logger = LogFactory.getLog(KubernetesAppInstanceStatus.class);
	private final Pod pod;
	private final Service service;
	private final KubernetesDeployerProperties properties;
	private final List<ContainerStatus> containerStatusList;

	public KubernetesAppInstanceStatus(Pod pod, Service service, KubernetesDeployerProperties properties) {
		this.pod = pod;
		this.service = service;
		this.properties = properties;

		if (pod != null) {
			this.containerStatusList = pod.getStatus().getContainerStatuses();
		}
		else {
			this.containerStatusList = null;
		}
	}

	@Override
	public String getId() {
		return pod == null ? "N/A" : pod.getMetadata().getName();
	}

	@Override
	public DeploymentState getState() {
		return pod != null && containerStatusList != null ? mapState() : DeploymentState.unknown;
	}

	/**
	 * Maps Kubernetes phases/states onto Spring Cloud Deployer states
	 */
	private DeploymentState mapState() {

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("%s - Phase [ %s ]", pod.getMetadata().getName(), pod.getStatus().getPhase()));
			for (ContainerStatus containerStatus : containerStatusList) {
				logger.debug(String.format("%s - ContainerStatus container [ %s ] : [%s]", pod.getMetadata().getName(),
					containerStatus.getName(), containerStatus.getState()));
			}
		}

		switch (pod.getStatus().getPhase()) {

		case "Pending":
			return DeploymentState.deploying;

		case "Running":
			return determineCompositeStateForRunningPod(containerStatusList);

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
			result.put("pod.name", pod.getMetadata().getName());
			result.put("pod.startTime", pod.getStatus().getStartTime());
			result.put("pod.ip", pod.getStatus().getPodIP());
			result.put("host.ip", pod.getStatus().getHostIP());
			result.put("phase", pod.getStatus().getPhase());
			result.put(AbstractKubernetesDeployer.SPRING_APP_KEY.replace('-', '.'),
				pod.getMetadata().getLabels().get(AbstractKubernetesDeployer.SPRING_APP_KEY));
			result.put(AbstractKubernetesDeployer.SPRING_DEPLOYMENT_KEY.replace('-', '.'),
				pod.getMetadata().getLabels().get(AbstractKubernetesDeployer.SPRING_DEPLOYMENT_KEY));
		}
		if (service != null) {
			result.put("service.name", service.getMetadata().getName());
			if ("LoadBalancer".equals(service.getSpec().getType())) {
				if (service.getStatus() != null && service.getStatus().getLoadBalancer() != null
					&& service.getStatus().getLoadBalancer().getIngress() != null && !service.getStatus()
					.getLoadBalancer().getIngress().isEmpty()) {
					String externalIp = service.getStatus().getLoadBalancer().getIngress().get(0).getIp();
					result.put("service.external.ip", externalIp);
					List<ServicePort> ports = service.getSpec().getPorts();
					int port = 0;
					if (ports != null && ports.size() > 0) {
						port = ports.get(0).getPort();
						result.put("service.external.port", String.valueOf(port));
					}
					if (externalIp != null) {
						result.put("url", "http://" + externalIp + (port > 0 && port != 80 ? ":" + port : ""));
					}

				}
			}
		}

		if (!CollectionUtils.isEmpty(containerStatusList)) {
			for (ContainerStatus containerStatus : containerStatusList) {
				String prefix =
					containerStatusList.size() == 1 ? "container" : "container." + containerStatus.getName();

				result.put(prefix + ".restartCount", "" + containerStatus.getRestartCount());
				if (containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null) {
					result.put(prefix + ".lastState.terminated.exitCode",
						"" + containerStatus.getLastState().getTerminated().getExitCode());
					result.put(prefix + ".lastState.terminated.reason",
						containerStatus.getLastState().getTerminated().getReason());
				}
				if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
					result.put(prefix + ".state.terminated.exitCode",
						"" + containerStatus.getState().getTerminated().getExitCode());
					result.put(prefix + ".state.terminated.reason",
						containerStatus.getState().getTerminated().getReason());
				}
			}
		}
		return result;
	}

	protected DeploymentState determineCompositeStateForRunningPod(List<ContainerStatus> containerStatusList) {
		// We only report a module as running if the main container is also ready to service requests.
		// We also implement the Readiness check as part of the container to ensure ready means
		// that the module is up and running and not only that the JVM has been created and the
		// Spring module is still starting up.
		DeploymentState deploymentState = DeploymentState.unknown;
		if (containerStatusList.get(0).getReady()) {

			// Are all side cars running?
			for (ContainerStatus containerStatus : containerStatusList.subList(1, containerStatusList.size())) {
				if (containerStatus.getState() == null || containerStatus.getState().getRunning() == null) {
					return DeploymentState.partial;
				}
			}

			return DeploymentState.deployed;
		}
		else {
			for (ContainerStatus containerStatus : containerStatusList) {
				// if any container is being killed repeatedly due to OOM or using too much CPU
				// if we are being restarted repeatedly due to the same error, consider the app crashed
				if (containerStatus.getRestartCount() > properties.getMaxTerminatedErrorRestarts()
					&& containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null
					&& (containerStatus.getLastState().getTerminated().getExitCode() == 137
					|| containerStatus.getLastState().getTerminated().getExitCode() == 143)) {
					return DeploymentState.failed;
				}

				// if we are being restarted repeatedly due to the same error, consider the app crashed
				else if (containerStatus.getRestartCount() > properties.getMaxTerminatedErrorRestarts()
					&& containerStatus.getLastState() != null && containerStatus.getState() != null
					&& containerStatus.getLastState().getTerminated() != null
					&& containerStatus.getLastState().getTerminated().getReason() != null && containerStatus
					.getLastState().getTerminated().getReason().contains("Error")
					&& containerStatus.getState().getTerminated() != null
					&& containerStatus.getState().getTerminated().getReason() != null && containerStatus.getState()
					.getTerminated().getReason().contains("Error") && containerStatus.getLastState().getTerminated()
					.getExitCode().equals(containerStatus.getState().getTerminated().getExitCode())) {
					return DeploymentState.failed;
				}
				// if we are being restarted repeatedly and we're in a CrashLoopBackOff, consider the app crashed
				else if (containerStatus.getRestartCount() > properties.getMaxCrashLoopBackOffRestarts()
					&& containerStatus.getLastState() != null && containerStatus.getState() != null
					&& containerStatus.getLastState().getTerminated() != null
					&& containerStatus.getState().getWaiting() != null
					&& containerStatus.getState().getWaiting().getReason() != null && containerStatus.getState()
					.getWaiting().getReason().contains("CrashLoopBackOff")) {
					return DeploymentState.failed;
				}
			}

			for (ContainerStatus containerStatus : containerStatusList) {

				int undeployedCount = 0;
				if (containerStatus.getRestartCount() == 0 && containerStatus.getState() != null
					&& containerStatus.getState().getTerminated() != null) {
					undeployedCount++;
				}
				// if all containers were terminated and not restarted, we consider this undeployed
				if (undeployedCount == containerStatusList.size()) {
					return DeploymentState.undeployed;
				}
				else if (undeployedCount > 0 && undeployedCount < containerStatusList.size()) {
					return DeploymentState.partial;
				}
			}
			// Main container not ready, no other issues.
			return DeploymentState.deploying;
		}
	}
}
