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

import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.deployer.resource.docker.DockerResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Donovan Muller
 * @author David Turanski
 */
@ConfigurationProperties(prefix = "spring.cloud.deployer.kubernetes")
public class KubernetesDeployerProperties {

	/**
	 * Constants for app deployment properties that don't have a deployer level default property.
	 */
	public static final String KUBERNETES_DEPLOYMENT_NODE_SELECTOR = "spring.cloud.deployer.kubernetes.deployment.nodeSelector";

	/**
	 * Side car container properties
	 */

	public static class Probe {

		public enum HandlerType {socket, http}

		/**
		 * the handler type ('socket','http'}.
		 */
		private HandlerType type = HandlerType.socket;

		/**
		 * the endpoint path (if http).
		 */
		private String path;
		/**
		 * the initial delay is seconds;
		 */
		private Integer delay = 10;
		/**
		 * the probe timeout in seconds;
		 */
		private Integer timeout = 2;
		/**
		 * the probe period in seconds;
		 */
		private Integer period = 10;

		/**
		 * the probe port. Must match an exposed port. The first exposed port by default.
		 */
		private Integer port;

		public HandlerType getType() {
			return type;
		}

		public void setType(HandlerType type) {
			this.type = type;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public Integer getDelay() {
			return delay;
		}

		public void setDelay(Integer delay) {
			this.delay = delay;
		}

		public Integer getTimeout() {
			return timeout;
		}

		public void setTimeout(Integer timeout) {
			this.timeout = timeout;
		}

		public Integer getPeriod() {
			return period;
		}

		public void setPeriod(Integer period) {
			this.period = period;
		}

		public Integer getPort() {
			return port;
		}

		public void setPort(Integer port) {
			this.port = port;
		}
	}

	public static class Sidecar {

		/**
		 * The docker image to use for the sidecar container.
		 */
		private DockerResource image;

		/**
		 * Volume mounts that the sidecar requires.
		 */
		private List<VolumeMount> volumeMounts = new ArrayList<>();

		/**
		 * The sidecar container command.
		 */
		private String[] command;

		/**
		 * The command args.
		 */
		private String[] args = new String[]{};

		/**
		 * Environment variables to set for the sidecar.
		 */
		private String[] environmentVariables = new String[]{};

		/**
		 * The ports to expose for the sidecar.
		 */
		private Integer[] ports = new Integer[]{};

		/**
		 * The liveness Probe.
		 */
		private Probe livenessProbe = new Probe();

		public DockerResource getImage() {
			return image;
		}

		public void setImage(DockerResource image) {
			this.image = image;
		}

		public List<VolumeMount> getVolumeMounts() {
			return volumeMounts;
		}

		public void setVolumeMounts(List<VolumeMount> volumeMounts) {
			this.volumeMounts = volumeMounts;
		}

		public String[] getCommand() {
			return command;
		}

		public void setCommand(String[] command) {
			this.command = command;
		}


		public String[] getEnvironmentVariables() {
			return environmentVariables;
		}

		public void setEnvironmentVariables(String[] environmentVariables) {
			this.environmentVariables = environmentVariables;
		}

		public String[] getArgs() {
			return args;
		}
		public void setArgs(String[] args) {
			this.args = args;
		}

		public Integer[] getPorts() {
			return ports;
		}

		public void setPorts(Integer[] ports) {
			this.ports = ports;
		}

		public Probe getLivenessProbe() {
			return livenessProbe;
		}

		public void setLivenessProbe(Probe livenessProbe) {
			this.livenessProbe = livenessProbe;
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
	 * Service annotations to set for the service created for each app.
	 */
	private String serviceAnnotations = null;

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

	/**
	 * Volume mounts that a container is requesting.
	 * This can be specified as a deployer property or as an app deployment property.
	 * Deployment properties will override deployer properties.
	 */
	private List<VolumeMount> volumeMounts = new ArrayList<>();

	/**
	 * The volumes that a Kubernetes instance supports.
	 * See http://kubernetes.io/docs/user-guide/volumes/#types-of-volumes
	 * This can be specified as a deployer property or as an app deployment property.
	 * Deployment properties will override deployer properties.
	 */
	private List<Volume> volumes = new ArrayList<>();

	/**
	 * The hostNetwork setting for the deployments.
	 * See https://kubernetes.io/docs/api-reference/v1/definitions/#_v1_podspec
	 * This can be specified as a deployer property or as an app deployment property.
	 * Deployment properties will override deployer properties.
	 */
	private boolean hostNetwork = false;

	/**
	 * Create a "Deployment" with a "Replica Set" instead of a "Replication Controller".
	 * See https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
	 */
	private boolean createDeployment = false;

	/**
	 * Side cars
	 */
	private Map<String, Sidecar> sidecars = new HashMap<>();

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

	public String getServiceAnnotations() {
		return serviceAnnotations;
	}

	public void setServiceAnnotations(String serviceAnnotations) {
		this.serviceAnnotations = serviceAnnotations;
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

	public List<VolumeMount> getVolumeMounts() {
		return volumeMounts;
	}

	public void setVolumeMounts(List<VolumeMount> volumeMounts) {
		this.volumeMounts = volumeMounts;
	}

	public List<Volume> getVolumes() {
		return volumes;
	}

	public void setVolumes(List<Volume> volumes) {
		this.volumes = volumes;
	}

	public boolean isHostNetwork() {
		return hostNetwork;
	}

	public void setHostNetwork(boolean hostNetwork) {
		this.hostNetwork = hostNetwork;
	}

	public boolean isCreateDeployment() {
		return createDeployment;
	}

	public Map<String,Sidecar> getSidecars() {
		return sidecars;
	}

	public void setSidecars(Map<String, Sidecar> sidecars) {
		this.sidecars = sidecars;
	}

	public void setCreateDeployment(boolean createDeployment) {
		this.createDeployment = createDeployment;
	}


}
