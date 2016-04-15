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

import org.junit.ClassRule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.core.io.Resource;

/**
 * Integration tests for {@link KubernetesAppDeployer}.
 *
 * @author Thomas Risberg
 */
@SpringApplicationConfiguration(classes = {KubernetesAutoConfiguration.class})
public class KubernetesAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport marathonAvailable = new KubernetesTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Override
	protected AppDeployer appDeployer() {
		return appDeployer;
	}

	@Override
	protected String randomName() {
		// Kubernetest app names must start with a letter and can only be 24 characters long
		return "app-" + super.randomName().substring(0, 18);
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(36, 10000);
	}

	@Override
	protected Resource integrationTestProcessor() {
		//TODO: create a project that builds docker image for testing
		return new DockerResource("trisberg/deployer-test-app:latest");
	}
}
