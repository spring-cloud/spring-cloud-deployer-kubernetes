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
	private Service service;
	private KubernetesDeployerProperties properties;
	private ContainerStatus containerStatus;
	private RunningPhaseDeploymentStateResolver runningPhaseDeploymentStateResolver;

	public KubernetesAppInstanceStatus(Pod pod, Service service, KubernetesDeployerProperties properties) {
		this.pod = pod;
		this.service = service;
		this.properties = properties;
		// we assume one container per pod
		if (pod != null && pod.getStatus().getContainerStatuses().size() == 1) {
			this.containerStatus = pod.getStatus().getContainerStatuses().get(0);
		}
		else {
			this.containerStatus = null;
		}
		this.runningPhaseDeploymentStateResolver = new DefaultRunningPhaseDeploymentStateResolver(properties);
	}

	/**
	 * Override the default {@link RunningPhaseDeploymentStateResolver} implementation.
	 *
	 * @param runningPhaseDeploymentStateResolver
	 */
	public void setRunningPhaseDeploymentStateResolver(
		RunningPhaseDeploymentStateResolver runningPhaseDeploymentStateResolver) {
		this.runningPhaseDeploymentStateResolver = runningPhaseDeploymentStateResolver;
	}

	@Override
	public String getId() {
		return pod == null ? "N/A" : pod.getMetadata().getName();
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
			return runningPhaseDeploymentStateResolver.resolve(containerStatus);
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
		if (containerStatus != null) {
			result.put("container.restartCount", "" + containerStatus.getRestartCount());
			if (containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null) {
				result.put("container.lastState.terminated.exitCode",
					"" + containerStatus.getLastState().getTerminated().getExitCode());
				result.put("container.lastState.terminated.reason",
					containerStatus.getLastState().getTerminated().getReason());
			}
			if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
				result.put("container.state.terminated.exitCode",
					"" + containerStatus.getState().getTerminated().getExitCode());
				result.put("container.state.terminated.reason", containerStatus.getState().getTerminated().getReason());
			}
		}
		return result;
	}
}





