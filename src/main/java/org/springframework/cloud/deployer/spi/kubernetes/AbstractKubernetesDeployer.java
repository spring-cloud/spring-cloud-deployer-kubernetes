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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hashids.Hashids;

import org.springframework.boot.bind.RelaxedNames;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;

/**
 * Abstract base class for a deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 */
public class AbstractKubernetesDeployer {

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";
	protected static final String SPRING_GROUP_KEY = "spring-group-id";
	protected static final String SPRING_APP_KEY = "spring-app-id";
	protected static final String SPRING_MARKER_KEY = "role";
	protected static final String SPRING_MARKER_VALUE = "spring-app";

	protected static final Log logger = LogFactory.getLog(AbstractKubernetesDeployer.class);

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 */
	protected Map<String, String> createIdMap(String appId, AppDeploymentRequest request, Integer instanceIndex) {
		//TODO: handling of app and group ids
		Map<String, String> map = new HashMap<>();
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		String appInstanceId = instanceIndex == null ? appId : appId + "-" + instanceIndex;
		map.put(SPRING_DEPLOYMENT_KEY, appInstanceId);
		return map;
	}

	protected AppStatus buildAppStatus(KubernetesDeployerProperties properties, String id, PodList list) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);
		if (list == null) {
			statusBuilder.with(new KubernetesAppInstanceStatus(id, null, properties));
		} else {
			for (Pod pod : list.getItems()) {
				statusBuilder.with(new KubernetesAppInstanceStatus(id, pod, properties));
			}
		}
		return statusBuilder.build();
	}

	protected Map<String, Quantity> deduceResourceLimits(KubernetesDeployerProperties properties, AppDeploymentRequest request) {
		String memOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.memory");
		if (memOverride == null)
			memOverride = properties.getMemory();

		String cpuOverride = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.cpu");
		if (cpuOverride == null)
			cpuOverride = properties.getCpu();

		logger.debug("Using limits - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memOverride));
		limits.put("cpu", new Quantity(cpuOverride));
		return limits;
	}

	/**
	 * Get the image pull policy for the deployment request. If it is not present use the server default. If an override
	 * for the deployment is present but not parseable, fall back to a default value.
	 *
	 * @param properties The server properties.
	 * @param request The deployment request.
	 * @return The image pull policy to use for the container in the request.
	 */
	ImagePullPolicy deduceImagePullPolicy(KubernetesDeployerProperties properties, AppDeploymentRequest request) {
		String pullPolicyOverride =
				request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.imagePullPolicy");

		ImagePullPolicy pullPolicy;
		if (pullPolicyOverride == null) {
			pullPolicy = properties.getImagePullPolicy();
		} else {
			pullPolicy = ImagePullPolicy.relaxedValueOf(pullPolicyOverride);
			if (pullPolicy == null) {
				logger.warn("Parsing of pull policy " + pullPolicyOverride + " failed, using default \"Always\".");
				pullPolicy = ImagePullPolicy.Always;
			}
		}
		logger.debug("Using imagePullPolicy " + pullPolicy);
		return pullPolicy;
	}

}
