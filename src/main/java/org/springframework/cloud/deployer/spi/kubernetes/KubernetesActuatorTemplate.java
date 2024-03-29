/*
 * Copyright 2022 the original author or authors.
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

import org.springframework.cloud.deployer.spi.app.AbstractActuatorTemplate;
import org.springframework.cloud.deployer.spi.app.AppAdmin;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.web.client.RestTemplate;

/**
 * @author David Turanski
 */

public class KubernetesActuatorTemplate extends AbstractActuatorTemplate {

	public KubernetesActuatorTemplate(RestTemplate restTemplate, AppDeployer appDeployer,
			AppAdmin appAdmin) {
		super(restTemplate, appDeployer, appAdmin);
	}

	protected String actuatorUrlForInstance(AppInstanceStatus appInstanceStatus) {
		return String.format("http://%s:%d/%s", appInstanceStatus.getAttributes().get("pod.ip"),
					Integer.valueOf(appInstanceStatus.getAttributes().get("actuator.port")),
							appInstanceStatus.getAttributes().get("actuator.path"));
	}
}
