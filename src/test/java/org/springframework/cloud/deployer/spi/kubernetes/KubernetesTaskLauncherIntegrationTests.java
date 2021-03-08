/*
 * Copyright 2016-2021 the original author or authors.
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.hamcrest.Matchers;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.KubernetesTestSupport;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.cloud.deployer.spi.test.AbstractTaskLauncherIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.core.io.Resource;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

/**
 * Integration tests for {@link KubernetesTaskLauncher}.
 *
 * @author Thomas Risberg
 * @author Chris Schaefer
 */
@SpringBootTest(classes = {KubernetesAutoConfiguration.class}, properties = {
		"spring.cloud.deployer.kubernetes.namespace=default"
})
public class KubernetesTaskLauncherIntegrationTests extends AbstractTaskLauncherIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	private TaskLauncher taskLauncher;

	@Autowired
	private KubernetesClient kubernetesClient;

	@Override
	protected TaskLauncher provideTaskLauncher() {
		return taskLauncher;
	}

	@Test
	@Override
	@Ignore("Currently reported as failed instead of cancelled")
	public void testSimpleCancel() throws InterruptedException {
		super.testSimpleCancel();
	}

	@Override
	protected String randomName() {
		return "task-" + UUID.randomUUID().toString().substring(0, 18);
	}

	@Override
	protected Resource testApplication() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(20, 5000);
	}

	@Test
	public void testJobPodAnnotation() {
		log.info("Testing {}...", "JobPodAnnotation");

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(new KubernetesDeployerProperties(),
				new KubernetesTaskLauncherProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.jobAnnotations", "key1:val1,key2:val2,key3:val31:val32"));

		log.info("Launching {}...", request.getDefinition().getName());

		String launchId = kubernetesTaskLauncher.launch(request);
		Timeout timeout = deploymentTimeout();

		assertThat(launchId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.running))), timeout.maxAttempts,
				timeout.pause));

		String taskName = request.getDefinition().getName();

		log.info("Checking job pod spec annotations of {}...", taskName);

		List<Pod> pods = kubernetesClient.pods().withLabel("task-name", taskName).list().getItems();

		assertThat(pods.size(), is(1));

		Pod pod = pods.get(0);

		assertTrue(pod.getSpec().getContainers().get(0).getPorts().isEmpty());

		Map<String, String> annotations = pod.getMetadata().getAnnotations();

		assertTrue(annotations.containsKey("key1"));
		assertTrue(annotations.get("key1").equals("val1"));
		assertTrue(annotations.containsKey("key2"));
		assertTrue(annotations.get("key2").equals("val2"));
		assertTrue(annotations.containsKey("key3"));
		assertTrue(annotations.get("key3").equals("val31:val32"));

		log.info("Destroying {}...", taskName);

		timeout = undeploymentTimeout();
		kubernetesTaskLauncher.destroy(taskName);

		assertThat(taskName, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.unknown))), timeout.maxAttempts,
				timeout.pause));
	}

	@Test
	public void testDeploymentLabels() {
		log.info("Testing {}...", "deploymentLabels");

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(new KubernetesDeployerProperties(),
				new KubernetesTaskLauncherProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels", "label1:value1,label2:value2"));

		log.info("Launching {}...", request.getDefinition().getName());

		String launchId = kubernetesTaskLauncher.launch(request);
		Timeout timeout = deploymentTimeout();

		assertThat(launchId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.running))), timeout.maxAttempts,
				timeout.pause));

		String taskName = request.getDefinition().getName();

		log.info("Checking job pod spec labels of {}...", taskName);

		List<Pod> pods = kubernetesClient.pods().withLabel("task-name", taskName).list().getItems();

		assertThat(pods.size(), is(1));

		Pod pod = pods.get(0);

		assertTrue(pod.getSpec().getContainers().get(0).getPorts().isEmpty());

		Map<String, String> labels = pod.getMetadata().getLabels();

		assertTrue(labels.containsKey("label1"));
		assertTrue(labels.get("label1").equals("value1"));
		assertTrue(labels.containsKey("label2"));
		assertTrue(labels.get("label2").equals("value2"));

		log.info("Destroying {}...", taskName);

		timeout = undeploymentTimeout();
		kubernetesTaskLauncher.destroy(taskName);

		assertThat(taskName, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.unknown))), timeout.maxAttempts,
				timeout.pause));
	}

	@Test
	public void testTaskSidecarContainer() {
		log.info("Testing {}...", "TaskSidecarContainer");

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(new KubernetesDeployerProperties(),
				new KubernetesTaskLauncherProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.sidecarContainer",
				"{containerName: 'test', imageName: 'busybox:latest', commands: ['sh', '-c', 'echo hello']}");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Launching {}...", request.getDefinition().getName());

		String launchId = kubernetesTaskLauncher.launch(request);
		Timeout timeout = deploymentTimeout();

		assertThat(launchId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.running))), timeout.maxAttempts,
				timeout.pause));

		String taskName = request.getDefinition().getName();

		log.info("Checking job pod spec annotations of {}...", taskName);

		List<Pod> pods = kubernetesClient.pods().withLabel("task-name", taskName).list().getItems();

		assertThat(pods.size(), is(1));

		Pod pod = pods.get(0);

		assertTrue(pod.getSpec().getContainers().size() == 2);

		Optional<Container> sidecarContainer = pod.getSpec().getContainers().stream().filter(i -> i.getName().equals("test")).findFirst();
		assertTrue("Sidecar container not found", sidecarContainer.isPresent());

		Container testSidecarContainer = sidecarContainer.get();

		assertEquals("Unexpected sidecar container name", testSidecarContainer.getName(), "test");
		assertEquals("Unexpected sidecar container image", testSidecarContainer.getImage(), "busybox:latest");

		List<String> commands = testSidecarContainer.getCommand();

		assertTrue("Sidecar container commands missing", commands != null && !commands.isEmpty());
		assertEquals("Invalid number of sidecar container commands", 3, commands.size());
		assertEquals("sh", commands.get(0));
		assertEquals("-c", commands.get(1));
		assertEquals("echo hello", commands.get(2));

		log.info("Destroying {}...", taskName);

		timeout = undeploymentTimeout();
		kubernetesTaskLauncher.destroy(taskName);

		assertThat(taskName, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.unknown))), timeout.maxAttempts,
				timeout.pause));
	}
}
