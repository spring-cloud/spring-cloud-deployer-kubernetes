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

import java.util.UUID;

import org.junit.ClassRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.test.AbstractTaskLauncherIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@link KubernetesTaskLauncher} using jobs instead of bare pods.
 *
 * @author Leonardo Diniz
 */
@SpringBootTest(classes = {KubernetesAutoConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.deployer.kubernetes.create-job=true"})
public class KubernetesTaskLauncherWithJobIntegrationTests extends AbstractTaskLauncherIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	private TaskLauncher taskLauncher;

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
}
