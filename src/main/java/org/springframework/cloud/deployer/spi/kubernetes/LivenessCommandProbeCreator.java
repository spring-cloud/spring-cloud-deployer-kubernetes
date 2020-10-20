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
import org.springframework.cloud.deployer.spi.util.CommandLineTokenizer;
import org.springframework.util.StringUtils;

/**
 * Creates a command based liveness probe
 *
 * @author Chris Schaefer
 */
class LivenessCommandProbeCreator extends CommandProbeCreator {
	private final String propertyPrefix;

	LivenessCommandProbeCreator(KubernetesDeployerProperties kubernetesDeployerProperties, ContainerConfiguration containerConfiguration) {
		super(kubernetesDeployerProperties, containerConfiguration);
		this.propertyPrefix = (containerConfiguration.getAppDeploymentRequest() instanceof ScheduleRequest) ?
				"spring.cloud.scheduler.kubernetes.liveness" : "spring.cloud.deployer.kubernetes.liveness";
	}

	@Override
	int getInitialDelay() {
		String probeDelayKey = this.propertyPrefix + "CommandProbeDelay";
		String probeDelayValue = getDeploymentPropertyValue(probeDelayKey);

		if (StringUtils.hasText(probeDelayValue)) {
			return Integer.valueOf(probeDelayValue);
		}

		return getKubernetesDeployerProperties().getLivenessCommandProbeDelay();
	}

	@Override
	int getPeriod() {
		String probePeriodKey = this.propertyPrefix + "CommandProbePeriod";
		String probePeriodValue = getDeploymentPropertyValue(probePeriodKey);

		if (StringUtils.hasText(probePeriodValue)) {
			return Integer.valueOf(probePeriodValue);
		}

		return getKubernetesDeployerProperties().getLivenessCommandProbePeriod();
	}

	@Override
	String[] getCommand() {
		String probeCommandKey = this.propertyPrefix + "CommandProbeCommand";
		String probeCommandValue = getDeploymentPropertyValue(probeCommandKey);

		if (StringUtils.hasText(probeCommandValue)) {
			return new CommandLineTokenizer(probeCommandValue).getArgs().toArray(new String[0]);
		}

		if (getKubernetesDeployerProperties().getLivenessCommandProbeCommand() != null) {
			return new CommandLineTokenizer(getKubernetesDeployerProperties().getLivenessCommandProbeCommand())
					.getArgs().toArray(new String[0]);
		}

		throw new IllegalArgumentException("A livenessCommandProbeCommand property must be set.");
	}
}
