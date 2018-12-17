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

/**
 * Creates a Readiness Probe.
 *
 * @author Chris Schaefer
 */
class ReadinessProbeCreator extends ProbeCreator {
	private static final String PROBE_PROPERTY_PREFIX = KUBERNETES_DEPLOYER_PREFIX + ".readiness";

	public ReadinessProbeCreator(KubernetesDeployerProperties kubernetesDeployerProperties,
			ContainerConfiguration containerConfiguration) {
		super(kubernetesDeployerProperties, containerConfiguration);
	}

	@Override
	public Integer getPort() {
		String probePortKey = PROBE_PROPERTY_PREFIX + "ProbePort";

		if (getDeploymentProperties().containsKey(probePortKey)) {
			return Integer.parseInt(getDeploymentProperties().get(probePortKey));
		}

		if (getKubernetesDeployerProperties().getReadinessProbePort() != null) {
			return getKubernetesDeployerProperties().getReadinessProbePort();
		}

		if (getDefaultPort() != null) {
			return getDefaultPort();
		}

		return null;
	}

	@Override
	protected String getProbePath() {
		String probePathKey = PROBE_PROPERTY_PREFIX + "ProbePath";

		if (getDeploymentProperties().containsKey(probePathKey)) {
			return getDeploymentProperties().get(probePathKey);
		}

		if (getKubernetesDeployerProperties().getReadinessProbePath() != null) {
			return getKubernetesDeployerProperties().getReadinessProbePath();
		}

		if (useBoot1ProbePath()) {
			return BOOT_1_READINESS_PROBE_PATH;
		}

		return BOOT_2_READINESS_PROBE_PATH;
	}

	@Override
	protected int getTimeout() {
		String probeTimeoutKey = PROBE_PROPERTY_PREFIX + "ProbeTimeout";

		if (getDeploymentProperties().containsKey(probeTimeoutKey)) {
			return Integer.valueOf(getDeploymentProperties().get(probeTimeoutKey));
		}

		return getKubernetesDeployerProperties().getReadinessProbeTimeout();
	}

	@Override
	protected int getInitialDelay() {
		String probeDelayKey = PROBE_PROPERTY_PREFIX + "ProbeDelay";

		if (getDeploymentProperties().containsKey(probeDelayKey)) {
			return Integer.valueOf(getDeploymentProperties().get(probeDelayKey));
		}

		return getKubernetesDeployerProperties().getReadinessProbeDelay();
	}

	@Override
	protected int getPeriod() {
		String probePeriodKey = PROBE_PROPERTY_PREFIX + "ProbePeriod";

		if (getDeploymentProperties().containsKey(probePeriodKey)) {
			return Integer.valueOf(getDeploymentProperties().get(probePeriodKey));
		}

		return getKubernetesDeployerProperties().getReadinessProbePeriod();
	}
}
