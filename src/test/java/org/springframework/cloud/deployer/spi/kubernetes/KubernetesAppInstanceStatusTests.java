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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateRunning;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Service;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.deployer.spi.app.DeploymentState;

import java.util.ArrayList;
import java.util.List;

/**
 * @author David Turanski
 **/
public class KubernetesAppInstanceStatusTests {

	private Service service = mock(Service.class);
	private Pod pod = mock(Pod.class);
	private PodStatus podStatus = mock(PodStatus.class);

	@Before
	public void setup() {
		ObjectMeta metadata = new ObjectMeta();
		metadata.setName("test-pod");
		when(pod.getStatus()).thenReturn(podStatus);
		when(pod.getMetadata()).thenReturn(metadata);

	}

	@Test
	public void singleContainerStatusDeployed() {

		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();
		containerStatus.setReady(true);
		containerStatus.setName("main-app");
		containerStatus.setState(new ContainerStateBuilder().withNewRunning().endRunning().build());

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			new KubernetesDeployerProperties());

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.deployed);
	}

	@Test
	public void singleContainerStatusDeploying() {

		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();

		containerStatus.setReady(false);
		containerStatus.setRestartCount(0);
		containerStatus.setName("main-app");
		containerStatus.setState(new ContainerStateBuilder().withNewRunning().endRunning().build());

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			new KubernetesDeployerProperties());

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.deploying);
	}

	@Test
	public void singleContainerStatusUnDeployed() {

		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();

		containerStatus.setReady(false);
		containerStatus.setRestartCount(0);
		containerStatus.setName("main-app");
		containerStatus.setLastState(new ContainerStateBuilder().withNewTerminated().endTerminated().build());
		containerStatus.setState(new ContainerStateBuilder().withNewTerminated().endTerminated().build());


		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			new KubernetesDeployerProperties());

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.undeployed);
	}

	@Test
	public void singleContainerStatusFailedOnMaxTerminatorErrorRestarts() {

		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();

		containerStatus.setReady(false);
		containerStatus.setRestartCount(4);

		containerStatus.setName("main-app");

		ContainerState containerState = new ContainerStateBuilder()
			.withTerminated(new ContainerStateTerminatedBuilder().withExitCode(143).build()).build();

		containerState.setRunning(new ContainerStateRunning());
		containerStatus.setLastState(containerState);

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();
		properties.setMaxTerminatedErrorRestarts(3);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.failed);
	}

	@Test
	public void singleContainerStatusFailedOnMaxRestartsDueToSameError() {
		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();

		containerStatus.setReady(false);
		containerStatus.setRestartCount(4);

		containerStatus.setName("main-app");

		containerStatus.setLastState(new ContainerStateBuilder().withNewRunning().and()
			.withTerminated(new ContainerStateTerminatedBuilder().withExitCode(123).withReason("Error").build())
			.build());

		containerStatus.setState(
			new ContainerStateBuilder().withTerminated(containerStatus.getLastState().getTerminated())
				.withWaiting(new ContainerStateWaitingBuilder().withReason("Error").build()).build());

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();
		properties.setMaxTerminatedErrorRestarts(3);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.failed);
	}

	@Test
	public void singleContainerStatusFailedOnMaxCrashLoopBackOffRestarts() {

		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus containerStatus = new ContainerStatus();

		containerStatus.setReady(false);
		containerStatus.setRestartCount(4);

		containerStatus.setName("main-app");

		containerStatus.setLastState(
			new ContainerStateBuilder().withTerminated(new ContainerStateTerminatedBuilder().build()).build());

		containerStatus.setState(new ContainerStateBuilder()
			.withWaiting(new ContainerStateWaitingBuilder().withReason("CrashLoopBackOff").build()).build());

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(containerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();
		properties.setMaxTerminatedErrorRestarts(10);
		properties.setMaxCrashLoopBackOffRestarts(3);

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.failed);
	}

	@Test
	public void partialStatusOnSidecarContainerDown() {
		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus mainContainerStatus = new ContainerStatusBuilder()
			.withName("main-app")
			.withReady(true)
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();
		ContainerStatus sidecarContainerStatus = new ContainerStatusBuilder()
			.withName("sidecar")
			.build();

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(mainContainerStatus);
		containerStatuses.add(sidecarContainerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.partial);
	}

	@Test
	public void multiContainerStatusDeployed() {
		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus mainContainerStatus = new ContainerStatusBuilder()
			.withName("main-app")
			.withReady(true)
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		ContainerStatus sidecar0ContainerStatus = new ContainerStatusBuilder()
			.withName("sidecar0")
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		ContainerStatus sidecar1ContainerStatus = new ContainerStatusBuilder()
			.withName("sidecar1")
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(mainContainerStatus);
		containerStatuses.add(sidecar0ContainerStatus);
		containerStatuses.add(sidecar1ContainerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.deployed);
	}

	@Test
	public void multiContainerStatusDeploying() {
		when(podStatus.getPhase()).thenReturn("Running");

		ContainerStatus mainContainerStatus = new ContainerStatusBuilder()
			.withName("main-app")
			.withRestartCount(0)
			.withReady(false)
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		ContainerStatus sidecar0ContainerStatus = new ContainerStatusBuilder()
			.withName("sidecar0")
			.withRestartCount(0)
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		ContainerStatus sidecar1ContainerStatus = new ContainerStatusBuilder()
			.withName("sidecar1")
			.withRestartCount(0)
			.withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
			.build();

		List<ContainerStatus> containerStatuses = new ArrayList<>();
		containerStatuses.add(mainContainerStatus);
		containerStatuses.add(sidecar0ContainerStatus);
		containerStatuses.add(sidecar1ContainerStatus);

		when(podStatus.getContainerStatuses()).thenReturn(containerStatuses);

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

		KubernetesAppInstanceStatus kubernetesAppInstanceStatus = new KubernetesAppInstanceStatus(pod, service,
			properties);

		assertThat(kubernetesAppInstanceStatus.getState()).isEqualTo(DeploymentState.deploying);
	}

}
