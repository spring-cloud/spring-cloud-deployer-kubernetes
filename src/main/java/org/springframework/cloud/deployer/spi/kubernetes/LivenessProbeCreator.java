/*
 * Copyright 2018-2019 the original author or authors.
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

import org.springframework.util.StringUtils;

/**
 * Creates a Liveness Probe.
 *
 * @author Chris Schaefer
 * @author Ilayaperumal Gopinathan
 */
class LivenessProbeCreator extends ProbeCreator {
	private static final String PROBE_PROPERTY_PREFIX = KUBERNETES_DEPLOYER_PREFIX + ".liveness";

	public LivenessProbeCreator(KubernetesDeployerProperties kubernetesDeployerProperties,
			ContainerConfiguration containerConfiguration) {
		super(kubernetesDeployerProperties, containerConfiguration);
	}

	@Override
	public Integer getPort() {
		String probePortKey = PROBE_PROPERTY_PREFIX + "ProbePort";
		String probePortValue = getDeploymentPropertyValue(probePortKey);

		if (StringUtils.hasText(probePortValue)) {
			return Integer.parseInt(probePortValue);
		}

		if (getKubernetesDeployerProperties().getLivenessProbePort() != null) {
			return getKubernetesDeployerProperties().getLivenessProbePort();
		}

		if (getDefaultPort() != null) {
			return getDefaultPort();
		}

		return null;
	}

	@Override
	protected String getProbePath() {
		String probePathKey = PROBE_PROPERTY_PREFIX + "ProbePath";
		String probePathValue =  getDeploymentPropertyValue(probePathKey);

		if (StringUtils.hasText(probePathValue)) {
			return probePathValue;
		}

		if (getKubernetesDeployerProperties().getLivenessProbePath() != null) {
			return getKubernetesDeployerProperties().getLivenessProbePath();
		}

		if (useBoot1ProbePath()) {
			return BOOT_1_LIVENESS_PROBE_PATH;
		}

		return BOOT_2_LIVENESS_PROBE_PATH;
	}

	@Override
	protected int getTimeout() {
		String probeTimeoutKey = PROBE_PROPERTY_PREFIX + "ProbeTimeout";
		String probeTimeoutValue = getDeploymentPropertyValue(probeTimeoutKey);

		if (StringUtils.hasText(probeTimeoutValue)) {
			return Integer.valueOf(probeTimeoutValue);
		}

		return getKubernetesDeployerProperties().getLivenessProbeTimeout();
	}

	@Override
	protected int getInitialDelay() {
		String probeDelayKey = PROBE_PROPERTY_PREFIX + "ProbeDelay";
		String probeDelayValue = getDeploymentPropertyValue(probeDelayKey);

		if (StringUtils.hasText(probeDelayValue)) {
			return Integer.valueOf(probeDelayValue);
		}

		return getKubernetesDeployerProperties().getLivenessProbeDelay();
	}

	@Override
	protected int getPeriod() {
		String probePeriodKey = PROBE_PROPERTY_PREFIX + "ProbePeriod";
		String probePeriodValue = getDeploymentPropertyValue(probePeriodKey);

		if (StringUtils.hasText(probePeriodValue)) {
			return Integer.valueOf(probePeriodValue);
		}

		return getKubernetesDeployerProperties().getLivenessProbePeriod();
	}
}
