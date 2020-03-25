/*
 * Copyright 2016-2020 the original author or authors.
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
import java.util.UUID;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.Job;
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
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

/**
 * Integration tests for {@link KubernetesTaskLauncher} using jobs instead of bare pods.
 *
 * @author Leonardo Diniz
 * @author Chris Schaefer
 * @author Ilayaperumal Gopinathan
 */
@SpringBootTest(classes = {KubernetesAutoConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.deployer.kubernetes.create-job=true",
		"spring.cloud.deployer.kubernetes.namespace=default"})
public class KubernetesTaskLauncherWithJobIntegrationTests extends AbstractTaskLauncherIntegrationTests {

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
	@Override
	@Ignore("Currently reported as failed instead of cancelled")
	public void testSimpleCancel() throws InterruptedException {
		super.testSimpleCancel();
	}

	@Test
	public void testJobAnnotations() {
		log.info("Testing {}...", "JobAnnotations");

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setCreateJob(true);

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(kubernetesDeployerProperties,
				new KubernetesTaskLauncherProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.jobAnnotations",
						"key1:val1,key2:val2,key3:val31:val32"));

		log.info("Launching {}...", request.getDefinition().getName());

		String launchId = kubernetesTaskLauncher.launch(request);
		Timeout timeout = deploymentTimeout();

		assertThat(launchId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.launching))), timeout.maxAttempts,
				timeout.pause));

		String taskName = request.getDefinition().getName();

		log.info("Checking Job spec annotations of {}...", taskName);

		List<Job> jobs = kubernetesClient.batch().jobs().withLabel("task-name", taskName).list().getItems();

		assertThat(jobs.size(), is(1));

		Map<String, String> jobAnnotations = jobs.get(0).getMetadata().getAnnotations();
		assertFalse(jobAnnotations.isEmpty());
		assertTrue(jobAnnotations.size() == 3);
		assertTrue(jobAnnotations.containsKey("key1"));
		assertTrue(jobAnnotations.get("key1").equals("val1"));
		assertTrue(jobAnnotations.containsKey("key2"));
		assertTrue(jobAnnotations.get("key2").equals("val2"));
		assertTrue(jobAnnotations.containsKey("key3"));
		assertTrue(jobAnnotations.get("key3").equals("val31:val32"));

		log.info("Checking Pod spec annotations of {}...", taskName);

		List<Pod> pods = kubernetesClient.pods().withLabel("task-name", taskName).list().getItems();

		assertThat(pods.size(), is(1));

		Map<String, String> podAnnotations = pods.get(0).getMetadata().getAnnotations();
		assertFalse(podAnnotations.isEmpty());
		assertTrue(podAnnotations.size() == 3);
		assertTrue(podAnnotations.containsKey("key1"));
		assertTrue(podAnnotations.get("key1").equals("val1"));
		assertTrue(podAnnotations.containsKey("key2"));
		assertTrue(podAnnotations.get("key2").equals("val2"));
		assertTrue(podAnnotations.containsKey("key3"));
		assertTrue(podAnnotations.get("key3").equals("val31:val32"));

		log.info("Destroying {}...", taskName);

		timeout = undeploymentTimeout();
		kubernetesTaskLauncher.destroy(taskName);

		assertThat(taskName, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.unknown))), timeout.maxAttempts,
				timeout.pause));
	}


	@Test
	public void testJobSpecProperties() {
		log.info("Testing {}...", "JobSpecProperties");

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setCreateJob(true);

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(kubernetesDeployerProperties,
				new KubernetesTaskLauncherProperties(), this.kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		Map<String, String> appRequest = new HashMap<>();
		appRequest.put("spring.cloud.deployer.kubernetes.restartPolicy", "OnFailure");
		appRequest.put("spring.cloud.deployer.kubernetes.backoffLimit", "5");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, appRequest);

		log.info("Launching {}...", request.getDefinition().getName());

		String launchId = kubernetesTaskLauncher.launch(request);
		Timeout timeout = deploymentTimeout();

		assertThat(launchId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.launching))), timeout.maxAttempts,
				timeout.pause));

		String taskName = request.getDefinition().getName();

		log.info("Checking job spec of {}...", taskName);

		List<Job> jobs = kubernetesClient.batch().jobs().withLabel("task-name", taskName).list().getItems();

		assertThat(jobs.size(), is(1));

		Job job = jobs.get(0);
		assertThat(job.getSpec().getBackoffLimit(), is(5));

		log.info("Checking pod spec of {}...", taskName);

		List<Pod> pods = kubernetesClient.pods().withLabel("task-name", taskName).list().getItems();

		assertThat(pods.size(), is(1));

		log.info("Destroying {}...", taskName);

		timeout = undeploymentTimeout();
		kubernetesTaskLauncher.destroy(taskName);

		assertThat(taskName, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(LaunchState.unknown))), timeout.maxAttempts,
				timeout.pause));
	}

	@Test
	public void testJobSpecWithInvalidRestartPolicy() {
		log.info("Testing {}...", "JobSpecWithInvalidRestartPolicy");

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setCreateJob(true);

		KubernetesTaskLauncher kubernetesTaskLauncher = new KubernetesTaskLauncher(kubernetesDeployerProperties,
				new KubernetesTaskLauncherProperties(), this.kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		Map<String, String> appRequest = new HashMap<>();
		appRequest.put("spring.cloud.deployer.kubernetes.restartPolicy", "Always");
		appRequest.put("spring.cloud.deployer.kubernetes.backoffLimit", "5");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, appRequest);

		log.info("Launching {}...", request.getDefinition().getName());

		try {
			kubernetesTaskLauncher.launch(request);
			fail("Expected exception as the RestartPolicy Always is not expected to be set for JobSpec.");
		}
		catch (Exception e) {
			assertEquals("Incorrect exception message on RestartPolicy for JobSpec.", e.getMessage(),
					"RestartPolicy should not be 'Always' when the JobSpec is used.");
		}
	}
}
