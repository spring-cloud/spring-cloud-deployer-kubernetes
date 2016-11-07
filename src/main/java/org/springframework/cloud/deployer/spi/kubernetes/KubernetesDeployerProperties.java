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

import java.util.Collection;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
@ConfigurationProperties(prefix = "spring.cloud.deployer.kubernetes")
public class KubernetesDeployerProperties {

	/**
	 * Encapsulates volumes to be mounted.
	 */
	public static class HostVolumeMount {

		private String name;

		private String hostPath;

		private String containerPath;

		private boolean readOnly;

		public HostVolumeMount(String name, String hostPath, String containerPath, boolean readOnly) {
			this.name = name;
			this.hostPath = hostPath;
			this.containerPath = containerPath;
			this.readOnly = readOnly;
		}

		public String getName() {
			return name;
		}

		public void setName(String mountName) {
			this.name = mountName;
		}

		public String getHostPath() {
			return hostPath;
		}

		public void setHostPath(String hostPath) {
			this.hostPath = hostPath;
		}

		public String getContainerPath() {
			return containerPath;
		}

		public void setContainerPath(String containerPath) {
			this.containerPath = containerPath;
		}

		public boolean isReadOnly() {
			return readOnly;
		}

		public void setReadOnly(boolean readOnly) {
			this.readOnly = readOnly;
		}

	}

	/**
	 * Encapsulates resources for Kubernetes Container resource requests and limits
	 */
	public static class Resources {

		private String cpu;

		private String memory;

		public Resources() {
		}

		public Resources(String cpu, String memory) {
			this.cpu = cpu;
			this.memory = memory;
		}

		public String getCpu() {
			return cpu;
		}

		public void setCpu(String cpu) {
			this.cpu = cpu;
		}

		public String getMemory() {
			return memory;
		}

		public void setMemory(String memory) {
			this.memory = memory;
		}
	}

	private static String KUBERNETES_NAMESPACE =
			System.getenv("KUBERNETES_NAMESPACE") != null ? System.getenv("KUBERNETES_NAMESPACE") : "default";

	/**
	 * Namespace to use.
	 */
	private String namespace = KUBERNETES_NAMESPACE;

	/**
	 * Secrets for a access a private registry to pull images.
	 */
	private String imagePullSecret;

	/**
	 * Delay in seconds when the Kubernetes liveness check of the app container
	 * should start checking its health status.
	 */
	// See http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int livenessProbeDelay = 10;

	/**
	 * Period in seconds for performing the Kubernetes liveness check of the app container.
	 */
	// See http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int livenessProbePeriod = 60;

	/**
	 * Timeout in seconds for the Kubernetes liveness check of the app container.
	 * If the health check takes longer than this value to return it is assumed as 'unavailable'.
	 */
	// see http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int livenessProbeTimeout = 2;

	/**
	 * Path that app container has to respond to for liveness check.
	 */
	// See http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private String livenessProbePath = "/health";

	/**
	 * Delay in seconds when the readiness check of the app container
	 * should start checking if the module is fully up and running.
	 */
	// see http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int readinessProbeDelay = 10;

	/**
	 * Period in seconds to perform the readiness check of the app container.
	 */
	// see http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int readinessProbePeriod = 10;

	/**
	 * Timeout in seconds that the app container has to respond to its
	 * health status during the readiness check.
	 */
	// see http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private int readinessProbeTimeout = 2;

	/**
	 * Path that app container has to respond to for readiness check.
	 */
	// See http://kubernetes.io/v1.0/docs/user-guide/production-pods.html#liveness-and-readiness-probes-aka-health-checks}
	private String readinessProbePath = "/info";

	/**
	 * Memory to allocate for a Pod.
	 *
	 * @deprecated Use spring.cloud.deployer.kubernetes.limits.memory
	 */
	@Deprecated
	private String memory = "512Mi";

	/**
	 * CPU to allocate for a Pod.
	 *
	 * @deprecated Use spring.cloud.deployer.kubernetes.limits.cpu
	 */
	@Deprecated
	private String cpu = "500m";

	/**
	 * Memory and CPU limits (i.e. maximum needed values) to allocate for a Pod.
	 */
	private Resources limits = new Resources();

	/**
	 * Memory and CPU requests (i.e. guaranteed needed values) to allocate for a Pod.
	 */
	private Resources requests = new Resources();

	/**
	 * Environment variables to set for any deployed app container. To be used for service binding.
	 */
	private String[] environmentVariables = new String[]{};

	/**
	 * Entry point style used for the Docker image. To be used to determine how to pass in properties.
	 */
	private EntryPointStyle entryPointStyle = EntryPointStyle.exec;

	/**
	 * Create a "LoadBalancer" for the service created for each app. This facilitates assignment of external IP to app.
	 */
	private boolean createLoadBalancer = false;

	/**
	 * Time to wait for load balancer to be available before attempting delete of service (in minutes).
	 */
	private int minutesToWaitForLoadBalancer = 5;

	/**
	 * Maximum allowed restarts for app that fails due to an error or excessive resource use.
	 */
	private int maxTerminatedErrorRestarts = 2;

	/**
	 * Maximum allowed restarts for app that is in a CrashLoopBackOff.
	 */
	private int maxCrashLoopBackOffRestarts = 4;

	/**
	 * The image pull policy to use for Pod deployments in Kubernetes.
	 */
	private ImagePullPolicy imagePullPolicy = ImagePullPolicy.IfNotPresent;

	private Collection<HostVolumeMount> hostVolumeMounts;

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getImagePullSecret() {
		return imagePullSecret;
	}

	public void setImagePullSecret(String imagePullSecret) {
		this.imagePullSecret = imagePullSecret;
	}

	public int getLivenessProbeDelay() {
		return livenessProbeDelay;
	}

	public void setLivenessProbeDelay(int livenessProbeDelay) {
		this.livenessProbeDelay = livenessProbeDelay;
	}

	public int getLivenessProbePeriod() {
		return livenessProbePeriod;
	}

	public void setLivenessProbePeriod(int livenessProbePeriod) {
		this.livenessProbePeriod = livenessProbePeriod;
	}

	public int getLivenessProbeTimeout() {
		return livenessProbeTimeout;
	}

	public void setLivenessProbeTimeout(int livenessProbeTimeout) {
		this.livenessProbeTimeout = livenessProbeTimeout;
	}

	public String getLivenessProbePath() {
		return livenessProbePath;
	}

	public void setLivenessProbePath(String livenessProbePath) {
		this.livenessProbePath = livenessProbePath;
	}

	public int getReadinessProbeDelay() {
		return readinessProbeDelay;
	}

	public void setReadinessProbeDelay(int readinessProbeDelay) {
		this.readinessProbeDelay = readinessProbeDelay;
	}

	public int getReadinessProbePeriod() {
		return readinessProbePeriod;
	}

	public void setReadinessProbePeriod(int readinessProbePeriod) {
		this.readinessProbePeriod = readinessProbePeriod;
	}

	public int getReadinessProbeTimeout() {
		return readinessProbeTimeout;
	}

	public void setReadinessProbeTimeout(int readinessProbeTimeout) {
		this.readinessProbeTimeout = readinessProbeTimeout;
	}

	public String getReadinessProbePath() {
		return readinessProbePath;
	}

	public void setReadinessProbePath(String readinessProbePath) {
		this.readinessProbePath = readinessProbePath;
	}

	/**
	 * @deprecated Use {@link #getLimits()}
	 */
	@Deprecated
	public String getMemory() {
		return memory;
	}

	/**
	 * @deprecated Use {@link #setLimits(Resources)}
	 */
	@Deprecated
	public void setMemory(String memory) {
		this.memory = memory;
	}

	/**
	 * @deprecated Use {@link #getLimits()}
	 */
	@Deprecated
	public String getCpu() {
		return cpu;
	}

	/**
	 * @deprecated Use {@link #setLimits(Resources)}
	 */
	@Deprecated
	public void setCpu(String cpu) {
		this.cpu = cpu;
	}

	public String[] getEnvironmentVariables() {
		return environmentVariables;
	}

	public void setEnvironmentVariables(String[] environmentVariables) {
		this.environmentVariables = environmentVariables;
	}

	public EntryPointStyle getEntryPointStyle() {
		return entryPointStyle;
	}

	public void setEntryPointStyle(EntryPointStyle entryPointStyle) {
		this.entryPointStyle = entryPointStyle;
	}

	public boolean isCreateLoadBalancer() {
		return createLoadBalancer;
	}

	public void setCreateLoadBalancer(boolean createLoadBalancer) {
		this.createLoadBalancer = createLoadBalancer;
	}

	public int getMinutesToWaitForLoadBalancer() {
		return minutesToWaitForLoadBalancer;
	}

	public void setMinutesToWaitForLoadBalancer(int minutesToWaitForLoadBalancer) {
		this.minutesToWaitForLoadBalancer = minutesToWaitForLoadBalancer;
	}

	public int getMaxTerminatedErrorRestarts() {
		return maxTerminatedErrorRestarts;
	}

	public void setMaxTerminatedErrorRestarts(int maxTerminatedErrorRestarts) {
		this.maxTerminatedErrorRestarts = maxTerminatedErrorRestarts;
	}

	public int getMaxCrashLoopBackOffRestarts() {
		return maxCrashLoopBackOffRestarts;
	}

	public void setMaxCrashLoopBackOffRestarts(int maxCrashLoopBackOffRestarts) {
		this.maxCrashLoopBackOffRestarts = maxCrashLoopBackOffRestarts;
	}

	public ImagePullPolicy getImagePullPolicy() {
		return imagePullPolicy;
	}

	public void setImagePullPolicy(ImagePullPolicy imagePullPolicy) {
		this.imagePullPolicy = imagePullPolicy;
	}
		
	public Resources getLimits() {
		return limits;
	}

	public void setLimits(Resources limits) {
		this.limits = limits;
	}

	public Resources getRequests() {
		return requests;
	}

	public void setRequests(Resources requests) {
		this.requests = requests;
	}

	public Collection<HostVolumeMount> getHostVolumeMounts() {
		return hostVolumeMounts;
	}

	public void setHostVolumeMounts(Collection<HostVolumeMount> hostVolumeMounts) {
		this.hostVolumeMounts = hostVolumeMounts;
	}

}
