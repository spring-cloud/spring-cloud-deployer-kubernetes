/*
 * Copyright 2018 the original author or authors.
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

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * The class responsible for creating Kubernetes Client based on the deployer properties.
 *
 * @author Ilayaperumal Gopinathan
 */
public class KubernetesClientFactory {

	public static KubernetesClient getKubernetesClient(KubernetesDeployerProperties kubernetesDeployerProperties) {
		Config config = kubernetesDeployerProperties.getFabric8();
		// Since namespace is used as a deployer as well as a client property, merging it from the deployer if it is not
		// already set in the client properties.
		if (config.getNamespace() == null) {
			config.setNamespace(kubernetesDeployerProperties.getNamespace());
		}
		return new DefaultKubernetesClient(config);
	}

}
