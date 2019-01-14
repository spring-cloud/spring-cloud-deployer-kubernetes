/*
 * Copyright 2016-2019 the original author or authors.
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

import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Test;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import io.fabric8.kubernetes.api.model.Quantity;

/**
 * Unit test for {@link AbstractKubernetesDeployer}.
 *
 * @author Moritz Schulze
 * @author Chris Schaefer
 */
public class RunAbstractKubernetesDeployerTests {

	private AbstractKubernetesDeployer kubernetesDeployer;
	private AppDeploymentRequest deploymentRequest;
	private Map<String, String> deploymentProperties;
	private KubernetesDeployerProperties serverProperties;

	@Before
	public void setUp() throws Exception {
		deploymentProperties = new HashMap<>();
		deploymentRequest = new AppDeploymentRequest(new AppDefinition("foo", Collections.emptyMap()), new FileSystemResource(""), deploymentProperties);
		serverProperties = new KubernetesDeployerProperties();
		kubernetesDeployer = new KubernetesAppDeployer(serverProperties, null);
	}

	@Test
	public void deduceImagePullPolicy_fallsBackToIfNotPresentIfOverrideNotParseable() throws Exception {
		deploymentProperties.put("spring.cloud.deployer.kubernetes.imagePullPolicy", "not-a-real-value");
		ImagePullPolicy pullPolicy = kubernetesDeployer.deduceImagePullPolicy(deploymentRequest);
		assertThat(pullPolicy, is(ImagePullPolicy.IfNotPresent));
	}

	@Test
	public void limitCpu_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverProperties.getLimits().setCpu("400m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(deploymentRequest);
		MatcherAssert.assertThat(limits.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void limitMemory_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverProperties.getLimits().setMemory("540Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(deploymentRequest);
		MatcherAssert.assertThat(limits.get("memory"), is(new Quantity("540Mi")));
	}

	@Test
	public void limitCpu_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverProperties.getLimits().setCpu("100m");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.limits.cpu", "400m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(deploymentRequest);
		MatcherAssert.assertThat(limits.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void limitMemory_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverProperties.getLimits().setMemory("640Mi");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.limits.memory", "256Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(deploymentRequest);
		MatcherAssert.assertThat(limits.get("memory"), is(new Quantity("256Mi")));
	}

	@Test
	public void requestCpu_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverProperties.getRequests().setCpu("400m");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(deploymentRequest);
		MatcherAssert.assertThat(requests.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void requestMemory_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverProperties.getRequests().setMemory("120Mi");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(deploymentRequest);
		MatcherAssert.assertThat(requests.get("memory"), is(new Quantity("120Mi")));
	}

	@Test
	public void requestCpu_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverProperties.getRequests().setCpu("1000m");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.requests.cpu", "461m");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(deploymentRequest);
		MatcherAssert.assertThat(requests.get("cpu"), is(new Quantity("461m")));
	}

	@Test
	public void requestMemory_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverProperties.getRequests().setMemory("640Mi");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.requests.memory", "256Mi");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(deploymentRequest);
		MatcherAssert.assertThat(requests.get("memory"), is(new Quantity("256Mi")));
	}
}
