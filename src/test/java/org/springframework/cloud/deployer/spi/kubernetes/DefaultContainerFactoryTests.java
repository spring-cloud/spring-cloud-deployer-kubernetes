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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Unit tests for {@link DefaultContainerFactory}.
 *
 * @author Will Kennedy
 * @author Donovan Muller
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = { KubernetesAutoConfiguration.class })
public class DefaultContainerFactoryTests {

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

	@Test
	public void createWithContainerCommand() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.containerCommand",
				"echo arg1 'arg2'");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		Container container = defaultContainerFactory.create("app-test",
				appDeploymentRequest, null, null);
		assertNotNull(container);
		assertThat(container.getCommand()).containsExactly("echo", "arg1", "arg2");
	}

	@Test
	public void createWithPorts() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.containerPorts",
				"8081, 8082, 65535");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		Container container = defaultContainerFactory.create("app-test",
				appDeploymentRequest, null, null);
		assertNotNull(container);
		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);
		assertTrue("There should be three ports set", containerPorts.size() == 3);
		assertTrue(8081 == containerPorts.get(0).getContainerPort());
		assertTrue(8082 == containerPorts.get(1).getContainerPort());
		assertTrue(65535 == containerPorts.get(2).getContainerPort());
	}

	@Test(expected = NumberFormatException.class)
	public void createWithInvalidPort() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.containerPorts",
				"8081, 8082, invalid, 9212");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		//Attempting to create with an invalid integer set for a port should cause an exception to bubble up.
		defaultContainerFactory.create("app-test", appDeploymentRequest, null, null);
	}

	@Test
	public void createWithEntryPointStyle() throws JsonProcessingException {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String, String> appProps = new HashMap<>();
		appProps.put("foo.bar.baz", "test");
		AppDefinition definition = new AppDefinition("app-test", appProps);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();

		props.put("spring.cloud.deployer.kubernetes.entryPointStyle", "shell");
		AppDeploymentRequest appDeploymentRequestShell = new AppDeploymentRequest(definition,
				resource, props);
		Container containerShell = defaultContainerFactory.create("app-test",
				appDeploymentRequestShell, null, null);
		assertNotNull(containerShell);
		assertTrue(containerShell.getEnv().get(0).getName().equals("FOO_BAR_BAZ"));
		assertTrue(containerShell.getArgs().size() == 0);

		props.put("spring.cloud.deployer.kubernetes.entryPointStyle", "exec");
		AppDeploymentRequest appDeploymentRequestExec = new AppDeploymentRequest(definition,
				resource, props);
		Container containerExec = defaultContainerFactory.create("app-test",
				appDeploymentRequestExec, null, null);
		assertNotNull(containerExec);
		assertTrue(containerExec.getEnv().size() == 0);
		assertTrue(containerExec.getArgs().get(0).equals("--foo.bar.baz=test"));

		props.put("spring.cloud.deployer.kubernetes.entryPointStyle", "boot");
		AppDeploymentRequest appDeploymentRequestBoot = new AppDeploymentRequest(definition,
				resource, props);
		Container containerBoot = defaultContainerFactory.create("app-test",
				appDeploymentRequestBoot, null, null);
		assertNotNull(containerBoot);
		assertTrue(containerBoot.getEnv().get(0).getName().equals("SPRING_APPLICATION_JSON"));
		assertTrue(containerBoot.getEnv().get(0).getValue().equals(new ObjectMapper().writeValueAsString(appProps)));
		assertTrue(containerBoot.getArgs().size() == 0);
	}

	@Test
	public void createWithVolumeMounts() {
		// test volume mounts defined as deployer properties
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts", "test:/test");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		Container container = defaultContainerFactory.create("app-test",
				appDeploymentRequest, null, null);

		assertThat(container.getVolumeMounts()).first()
				.isEqualTo(new VolumeMount("/test", "test", false, null));

		// test volume mounts defined as app deployment property, overriding the deployer property
		kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setVolumeMounts(
				Collections.singletonList(new VolumeMount("/test", "test", false, null)));
		defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		props.clear();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts", "test:/test/overridden:true");
		container = defaultContainerFactory.create("app-test",
				appDeploymentRequest, null, null);

		assertThat(container.getVolumeMounts()).first()
				.isEqualTo(new VolumeMount("/test/overridden", "test", true, null));
	}

	private Resource getResource() {
		return new DockerResource(
				"springcloud/spring-cloud-deployer-spi-test-app:latest");
	}
}
