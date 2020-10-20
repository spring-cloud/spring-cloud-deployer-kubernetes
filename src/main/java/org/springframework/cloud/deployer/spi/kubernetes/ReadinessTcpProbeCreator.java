/*
 * Copyright 2020 the original author or authors.
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

import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.util.StringUtils;

/**
 * Creates a TCP readiness probe
 *
 * @author Chris Schaefer
 */
class ReadinessTcpProbeCreator extends TcpProbeCreator {
	private final String propertyPrefix;

	ReadinessTcpProbeCreator(KubernetesDeployerProperties kubernetesDeployerProperties, ContainerConfiguration containerConfiguration) {
		super(kubernetesDeployerProperties, containerConfiguration);
		this.propertyPrefix = (containerConfiguration.getAppDeploymentRequest() instanceof ScheduleRequest) ?
				"spring.cloud.scheduler.kubernetes.readiness" : "spring.cloud.deployer.kubernetes.readiness";
	}

	@Override
	int getInitialDelay() {
		String probeDelayKey = this.propertyPrefix + "TcpProbeDelay";
		String probeDelayValue = getDeploymentPropertyValue(probeDelayKey);

		if (StringUtils.hasText(probeDelayValue)) {
			return Integer.valueOf(probeDelayValue);
		}

		return getKubernetesDeployerProperties().getReadinessTcpProbeDelay();
	}

	@Override
	int getPeriod() {
		String probePeriodKey = this.propertyPrefix + "TcpProbePeriod";
		String probePeriodValue = getDeploymentPropertyValue(probePeriodKey);

		if (StringUtils.hasText(probePeriodValue)) {
			return Integer.valueOf(probePeriodValue);
		}

		return getKubernetesDeployerProperties().getReadinessTcpProbePeriod();
	}

	@Override
	Integer getPort() {
		String probePortKey = this.propertyPrefix + "TcpProbePort";
		String probePortValue = getDeploymentPropertyValue(probePortKey);

		if (StringUtils.hasText(probePortValue)) {
			if (!probePortValue.chars().allMatch(Character :: isDigit)) {
				throw new IllegalArgumentException("ReadinessTcpProbePort must contain all digits");
			}

			return Integer.parseInt(probePortValue);
		}

		if (getKubernetesDeployerProperties().getReadinessTcpProbePort() != null) {
			return getKubernetesDeployerProperties().getReadinessTcpProbePort();
		}

		throw new IllegalArgumentException("A readinessTcpProbePort property must be set.");
	}
}
