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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.deployer.spi.kubernetes.KubernetesDeployerProperties.Sidecar;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.junit.Test;
import org.springframework.cloud.deployer.resource.docker.DockerResource;

import java.util.LinkedList;
import java.util.List;

/**
 * @author David Turanski
 **/
public class SidecarContainerFactoryTests {
	private SidecarContainerFactory sidecarContainerFactory = new SidecarContainerFactory();

	@Test
	public void simpleSideCar() {
		Sidecar sidecar = new Sidecar();
		sidecar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sidecar.setPorts(new Integer[]{8888});
		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getName()).isEqualTo("foo");
		assertThat(container.getImage()).isEqualTo("sidecars/sidecar:latest");
	}

	@Test(expected = IllegalArgumentException.class)
	public void immageMissing() {
		Sidecar sidecar = new Sidecar();
		sidecar.setPorts(new Integer[]{8888});
		sidecarContainerFactory.create("foo", sidecar);
	}

	@Test(expected = IllegalArgumentException.class)
	public void portMissing() {
		Sidecar sidecar = new Sidecar();
		sidecarContainerFactory.create("foo", sidecar);
	}

	@Test
	public void withVolumeMounts() {
		Sidecar sidecar = validSidecar();
		List<VolumeMount> volumeMounts = new LinkedList<>();
		VolumeMount v0 = new VolumeMount();
		volumeMounts.add(new VolumeMount("/mountpath0", "v0", false, null));
		sidecar.setVolumeMounts(volumeMounts);
		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getVolumeMounts().size()).isEqualTo(1);
	}

	@Test
	public void withEnv() {
		Sidecar sidecar = validSidecar();
		sidecar.setEnvironmentVariables(new String[] { "FOO=bar", "NUM=9999" });
		Container container = sidecarContainerFactory.create("foo", sidecar);
		List<EnvVar> envVars = container.getEnv();
		assertThat(envVars.size()).isEqualTo(2);
		assertThat(envVars.get(0).getName()).isEqualTo("FOO");
		assertThat(envVars.get(0).getValue()).isEqualTo("bar");
		assertThat(envVars.get(1).getName()).isEqualTo("NUM");
		assertThat(envVars.get(1).getValue()).isEqualTo("9999");
	}

	@Test
	public void withPorts() {
		Sidecar sidecar = validSidecar();
		sidecar.setPorts(new Integer[] { 9998, 9999 });
		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getPorts()).containsOnly(new ContainerPort(9998, null, 9998, null, null),
			new ContainerPort(9999, null, 9999, null, null));
	}

	@Test
	public void withCommand() {
		Sidecar sidecar = validSidecar();
		sidecar.setCommand(new String[] { "/bin/bash", "-c", "ls -lah & ping localhost" });
		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getCommand()).containsOnly("/bin/bash", "-c", "ls -lah & ping localhost");
	}

	@Test
	public void withArgs() {
		Sidecar sidecar = validSidecar();
		sidecar.setArgs(new String[] { "-c", "ls -lah & ping localhost" });
		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getArgs()).containsOnly("-c", "ls -lah & ping localhost");
	}

	@Test
	public void withTcpProbeOnFirstExposedPortByDefault() {
		Sidecar sidecar = validSidecar();
		sidecar.setPorts(new Integer[] { 1111, 2222 });
		KubernetesDeployerProperties.Probe liveness = new KubernetesDeployerProperties.Probe();

		Container container = sidecarContainerFactory.create("foo", sidecar);
		assertThat(container.getLivenessProbe().getTcpSocket().getPort().getIntVal()).isEqualTo(1111);
	}

	private Sidecar validSidecar(){
		Sidecar sidecar = new Sidecar();
		sidecar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sidecar.setPorts(new Integer[]{8888});
		return sidecar;
	}
}
