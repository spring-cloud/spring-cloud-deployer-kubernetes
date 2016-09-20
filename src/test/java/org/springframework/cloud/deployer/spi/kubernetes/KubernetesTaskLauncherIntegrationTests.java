/*
 * Copyright 2016 the original author or authors.
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.fabric8.kubernetes.client.KubernetesClient;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.Is;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.springframework.cloud.deployer.spi.task.LaunchState.complete;
import static org.springframework.cloud.deployer.spi.task.LaunchState.failed;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

/**
 * Integration tests for {@link KubernetesTaskLauncher}.
 *
 * @author Thomas Risberg
 */
@SpringApplicationConfiguration(classes = {KubernetesAutoConfiguration.class})
@RunWith(SpringJUnit4ClassRunner.class)
public class KubernetesTaskLauncherIntegrationTests {

	private static final Log logger = LogFactory.getLog(KubernetesTaskLauncherIntegrationTests.class);

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	TaskLauncher taskLauncher;

	@Autowired
	KubernetesClient kubernetesClient;

	@Autowired
	ContainerFactory containerFactory;

	@Test
	public void testSimpleLaunch() {
		logger.info("Testing SimpleLaunch...");
		Map<String, String> properties = new HashMap<>();
		properties.put("killDelay", "1000");
		properties.put("exitCode", "0");
		AppDefinition definition = new AppDefinition(this.randomName(), properties);
		Resource resource = integrationTestTask();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);
		logger.info(String.format("Launching %s...", request.getDefinition().getName()));
		String deploymentId = taskLauncher.launch(request);
		logger.info(String.format("Launched %s ", deploymentId));

		Timeout timeout = launchTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", is(complete))), timeout.maxAttempts, timeout.pause));

		((KubernetesTaskLauncher)taskLauncher).cleanup(deploymentId);
	}

	@Test
	public void testReLaunch() {
		logger.info("Testing ReLaunch...");
		Map<String, String> properties = new HashMap<>();
		properties.put("killDelay", "1000");
		properties.put("exitCode", "0");
		AppDefinition definition = new AppDefinition(this.randomName(), properties);
		Resource resource = integrationTestTask();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);
		logger.info(String.format("Launching %s...", request.getDefinition().getName()));
		String deploymentId1 = taskLauncher.launch(request);
		logger.info(String.format("Launched %s ", deploymentId1));
		String deploymentId2 = taskLauncher.launch(request);
		logger.info(String.format("Re-launched %s ", deploymentId2));
		MatcherAssert.assertThat(deploymentId1, not(Is.is(deploymentId2)));

		Timeout timeout = launchTimeout();
		Assert.assertThat(deploymentId1, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(complete))), timeout.maxAttempts, timeout.pause));
		Assert.assertThat(deploymentId2, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", Matchers.is(complete))), timeout.maxAttempts, timeout.pause));

		((KubernetesTaskLauncher)taskLauncher).cleanup(deploymentId1, deploymentId2);
	}

	@Test
	public void testFailedLaunch() {
		logger.info("Testing FailedLaunch...");
		Map<String, String> properties = new HashMap<>();
		properties.put("killDelay", "1000");
		properties.put("exitCode", "1");
		AppDefinition definition = new AppDefinition(this.randomName(), properties);
		Resource resource = integrationTestTask();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);
		logger.info(String.format("Launching %s...", request.getDefinition().getName()));
		String deploymentId = taskLauncher.launch(request);
		logger.info(String.format("Launched %s", deploymentId));

		Timeout timeout = launchTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", is(failed))), timeout.maxAttempts, timeout.pause));

		((KubernetesTaskLauncher)taskLauncher).cleanup(deploymentId);
	}

	/**
	 * Tests that command line args can be passed in.
	 */
	@Test
	public void testCommandLineArgs() {
		logger.info("Testing CommandLineArgs...");
		Map<String, String> properties = new HashMap<>();
		properties.put("killDelay", "1000");
		AppDefinition definition = new AppDefinition(this.randomName(), properties);
		Resource resource = integrationTestTask();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap(),
				Collections.singletonList("--exitCode=0"));
		logger.info(String.format("Launching %s...", request.getDefinition().getName()));
		String deploymentId = taskLauncher.launch(request);
		logger.info(String.format("Launched %s ", deploymentId));

		Timeout timeout = launchTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<TaskStatus>hasProperty("state", is(complete))), timeout.maxAttempts, timeout.pause));

		((KubernetesTaskLauncher)taskLauncher).cleanup(deploymentId);
	}

	protected String randomName() {
		return "task-" + UUID.randomUUID().toString().substring(0, 18);
	}

	protected Resource integrationTestTask() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	protected Timeout launchTimeout() {
		return new Timeout(20, 5000);
	}

	protected Matcher<String> hasStatusThat(final Matcher<TaskStatus> statusMatcher) {
		return new BaseMatcher() {
			private TaskStatus status;

			public boolean matches(Object item) {
				this.status = KubernetesTaskLauncherIntegrationTests.this.taskLauncher.status((String)item);
				return statusMatcher.matches(this.status);
			}

			public void describeMismatch(Object item, Description mismatchDescription) {
				mismatchDescription.appendText("status of ").appendValue(item).appendText(" ");
				statusMatcher.describeMismatch(this.status, mismatchDescription);
			}

			public void describeTo(Description description) {
				statusMatcher.describeTo(description);
			}
		};
	}

	protected static class Timeout {
		public final int maxAttempts;
		public final int pause;

		public Timeout(int maxAttempts, int pause) {
			this.maxAttempts = maxAttempts;
			this.pause = pause;
		}
	}
}
