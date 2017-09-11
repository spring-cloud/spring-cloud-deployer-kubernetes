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
import static org.springframework.cloud.deployer.spi.kubernetes.KubernetesDeployerProperties.SideCar;

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
public class SideCarContainerFactoryTests {
	private SideCarContainerFactory sideCarContainerFactory = new SideCarContainerFactory();

	@Test
	public void simpleSideCar() {
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		Container container = sideCarContainerFactory.create("foo", sideCar);
		assertThat(container.getName()).isEqualTo("foo");
		assertThat(container.getImage()).isEqualTo("sidecars/sidecar:latest");
	}

	@Test(expected = IllegalArgumentException.class)
	public void immageMissing() {
		SideCar sideCar = new SideCar();
		sideCarContainerFactory.create("foo", sideCar);
	}

	@Test
	public void withVolumeMounts() {
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		List<VolumeMount> volumeMounts = new LinkedList<>();
		VolumeMount v0 = new VolumeMount();
		volumeMounts.add(new VolumeMount("/mountpath0", "v0", false, null));
		sideCar.setVolumeMounts(volumeMounts);
		Container container = sideCarContainerFactory.create("foo", sideCar);
		assertThat(container.getVolumeMounts().size()).isEqualTo(1);
	}

	@Test
	public void withEnv() {
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sideCar.setEnvironmentVariables(new String[] { "FOO=bar", "PORT=9999" });
		Container container = sideCarContainerFactory.create("foo", sideCar);
		List<EnvVar> envVars = container.getEnv();
		assertThat(envVars.size()).isEqualTo(2);
		assertThat(envVars.get(0).getName()).isEqualTo("FOO");
		assertThat(envVars.get(0).getValue()).isEqualTo("bar");
		assertThat(envVars.get(1).getName()).isEqualTo("PORT");
		assertThat(envVars.get(1).getValue()).isEqualTo("9999");
	}

	@Test
	public void withPorts() {
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sideCar.setPorts(new Integer[]{9998, 9999});
		Container container = sideCarContainerFactory.create("foo", sideCar);
		assertThat(container.getPorts()).containsOnly(new ContainerPort(9998,null,9998,null, null),
			new ContainerPort(9999,null,9999,null, null));
	}

	@Test
	public void withCommand(){
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sideCar.setCommand(new String[]{"/bin/bash","-c","ls -lah & ping localhost"});
		Container container = sideCarContainerFactory.create("foo", sideCar);
		assertThat(container.getCommand()).containsOnly("/bin/bash","-c","ls -lah & ping localhost");
	}

	@Test
	public void withArgs(){
		SideCar sideCar = new SideCar();
		sideCar.setImage(new DockerResource("sidecars/sidecar:latest"));
		sideCar.setArgs(new String[]{"-c","ls -lah & ping localhost"});
		Container container = sideCarContainerFactory.create("foo", sideCar);
		assertThat(container.getArgs()).containsOnly("-c","ls -lah & ping localhost");
	}
}
