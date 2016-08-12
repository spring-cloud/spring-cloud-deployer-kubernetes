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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.deployed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.failed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.unknown;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

import java.util.HashMap;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.core.io.Resource;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Integration tests for {@link KubernetesAppDeployer}.
 *
 * @author Thomas Risberg
 */
@SpringApplicationConfiguration(classes = {KubernetesAutoConfiguration.class})
public class KubernetesAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Autowired
	KubernetesClient kubernetesClient;

	@Autowired
	ContainerFactory containerFactory;

	@Override
	protected AppDeployer appDeployer() {
		return appDeployer;
	}

	@Test
	public void testFailedDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "FailedDeploymentWithLoadBalancer");
		KubernetesDeployerProperties lbProperties = new KubernetesDeployerProperties();
		lbProperties.setCreateLoadBalancer(true);
		lbProperties.setLivenessProbePeriod(10);
		lbProperties.setMaxTerminatedErrorRestarts(1);
		lbProperties.setMaxCrashLoopBackOffRestarts(1);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(lbProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = integrationTestProcessor();
		Map<String, String> props = new HashMap<>();
		// setting to small memory value will cause app to fail to be deployed
		props.put("spring.cloud.deployer.kubernetes.memory", "8Mi");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<AppStatus>hasProperty("state", is(failed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<AppStatus>hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testGoodDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "GoodDeploymentWithLoadBalancer");
		KubernetesDeployerProperties lbProperties = new KubernetesDeployerProperties();
		lbProperties.setCreateLoadBalancer(true);
		lbProperties.setMinutesToWaitForLoadBalancer(1);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(lbProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = integrationTestProcessor();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<AppStatus>hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.<AppStatus>hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Override
	public void testUnknownDeployment() {
		log.info("Testing {}...", "UnknownDeployment");
		super.testUnknownDeployment();
	}

	@Override
	public void testSimpleDeployment() {
		log.info("Testing {}...", "SimpleDeployment");
		super.testSimpleDeployment();
	}

	@Override
	public void testRedeploy() {
		log.info("Testing {}...", "Redeploy");
		super.testRedeploy();
	}

	@Override
	public void testDeployingStateCalculationAndCancel() {
		log.info("Testing {}...", "DeployingStateCalculationAndCancel");
		super.testDeployingStateCalculationAndCancel();
	}

	@Override
	public void testFailedDeployment() {
		log.info("Testing {}...", "FailedDeployment");
		super.testFailedDeployment();
	}

	@Override
	public void testParameterPassing() {
		log.info("Testing {}...", "ParameterPassing");
		super.testParameterPassing();
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
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}
}
