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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link DefaultContainerFactory}.
 *
 * @author Will Kennedy
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = { KubernetesAutoConfiguration.class })
public class DefaultContainerFactoryTest {

	@Test
	public void create() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.memory", "128Mi");
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
				"JAVA_OPTIONS=-Xmx64m,KUBERNETES_NAMESPACE=test-space");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		Container container = defaultContainerFactory.create("app-test",
				appDeploymentRequest, null, null);
		assertNotNull(container);
		assertEquals(2, container.getEnv().size());
		EnvVar envVar1 = container.getEnv().get(0);
		EnvVar envVar2 = container.getEnv().get(1);
		assertEquals("JAVA_OPTIONS", envVar1.getName());
		assertEquals("-Xmx64m", envVar1.getValue());
		assertEquals("KUBERNETES_NAMESPACE", envVar2.getName());
		assertEquals("test-space", envVar2.getValue());
	}

	private Resource getResource() {
		return new DockerResource(
				"springcloud/spring-cloud-deployer-spi-test-app:latest");
	}
}
