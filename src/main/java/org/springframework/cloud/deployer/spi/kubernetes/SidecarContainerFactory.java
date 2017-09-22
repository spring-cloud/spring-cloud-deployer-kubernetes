/*
 * Copyright 2017 the original author or authors.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import static org.springframework.cloud.deployer.spi.kubernetes.KubernetesDeployerProperties.Sidecar;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import org.springframework.util.Assert;

import java.util.List;

/**
 * @author David Turanski
 **/
public class SidecarContainerFactory extends AbstractContainerFactory {

	public Container create(String name, Sidecar sidecar) {

		String image = resolveImageName(sidecar.getImage());
		logger.info(String.format("Creating sidecar container %s from image %s", name, image));

		List<EnvVar> envVars = buildEnVars(sidecar.getEnvironmentVariables());

		ContainerBuilder containerBuilder = new ContainerBuilder();

		if (sidecar.getPorts() != null && sidecar.getPorts().length > 0) {
			for (int port : sidecar.getPorts()) {
				Assert.isTrue(port > 0, "'port must be greater than 0");
				containerBuilder.addNewPort().withContainerPort(port).withHostPort(port).endPort();
			}
		}

		createLivenessProbeOnFirstOrDesignatedPort(containerBuilder, sidecar);

		containerBuilder.withName(name).withImage(image).withEnv(envVars).withCommand(sidecar.getCommand())
			.withArgs(sidecar.getArgs()).withVolumeMounts(sidecar.getVolumeMounts());

		return containerBuilder.build();
	}

	private void createLivenessProbeOnFirstOrDesignatedPort(ContainerBuilder containerBuilder, Sidecar sidecar) {

		Integer probePort = sidecar.getLivenessProbe().getPort();
		if (probePort == null) {
			if (sidecar.getPorts().length > 0) {
				probePort = sidecar.getPorts()[0];
				logger.info(String.format("Configuring sidecar liveness probe on first exposed port %d ", probePort));
			}
		}
		else {
			logger.info(String.format("Configuring sidecar liveness probe on port %d ", probePort));
		}
		if (probePort != null) {
			containerBuilder.withLivenessProbe(new KubernetesProbeBuilder(probePort, sidecar.getLivenessProbe()).build());
		}
		else {
			logger.warn("No liveness probe configured for sidecar");
		}
	}
}


