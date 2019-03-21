/*
 * Copyright 2016 the original author or authors.
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
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.cloud.deployer.spi.test.AbstractTaskLauncherIntegrationTests;
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
public class KubernetesTaskLauncherIntegrationTests extends AbstractTaskLauncherIntegrationTests {

	private static final Log logger = LogFactory.getLog(KubernetesTaskLauncherIntegrationTests.class);

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	TaskLauncher taskLauncher;

	@Autowired
	KubernetesClient kubernetesClient;

	@Autowired
	ContainerFactory containerFactory;

	@Override
	protected TaskLauncher taskLauncher() {
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
}
