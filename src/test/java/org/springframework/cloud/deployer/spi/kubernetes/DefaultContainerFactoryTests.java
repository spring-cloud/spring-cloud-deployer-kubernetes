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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPHeader;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.VolumeMount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link DefaultContainerFactory}.
 *
 * @author Will Kennedy
 * @author Donovan Muller
 * @author Chris Schaefer
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
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
		props.put("spring.cloud.deployer.kubernetes.limits.memory", "128Mi");
		props.put("spring.cloud.deployer.kubernetes.environment-variables",
				"JAVA_OPTIONS=-Xmx64m,KUBERNETES_NAMESPACE=test-space");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);
		assertEquals(3, container.getEnv().size());
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

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		Container container = defaultContainerFactory.create(containerConfiguration);
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

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		Container container = defaultContainerFactory.create(containerConfiguration);
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
		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		defaultContainerFactory.create(containerConfiguration);
	}

	@Test
	public void createWithPortAndHostNetwork() {
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

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);
		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);
		assertTrue("There should be three container ports set", containerPorts.size() == 3);
		assertTrue(8081 == containerPorts.get(0).getContainerPort());
		assertTrue(8081 == containerPorts.get(0).getHostPort());
		assertTrue(8082 == containerPorts.get(1).getContainerPort());
		assertTrue(8082 == containerPorts.get(1).getHostPort());
		assertTrue(65535 == containerPorts.get(2).getContainerPort());
		assertTrue(65535 == containerPorts.get(2).getHostPort());
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
		ContainerConfiguration shellContainerConfiguration = new ContainerConfiguration("app-test",
				appDeploymentRequestShell);
		Container containerShell = defaultContainerFactory.create(shellContainerConfiguration);
		assertNotNull(containerShell);
		assertTrue(containerShell.getEnv().get(0).getName().equals("FOO_BAR_BAZ"));
		assertTrue(containerShell.getArgs().size() == 0);

		List<String> cmdLineArgs = new ArrayList<>();
		cmdLineArgs.add("--foo.bar=value1");
		cmdLineArgs.add("--spring.cloud.task.executionid=1");
		cmdLineArgs.add("--spring.cloud.data.flow.platformname=platform1");
		cmdLineArgs.add("--spring.cloud.data.flow.taskappname==a1");
		cmdLineArgs.add("blah=chacha");
		appDeploymentRequestShell = new AppDeploymentRequest(definition,
				resource, props, cmdLineArgs);
		shellContainerConfiguration = new ContainerConfiguration("app-test",
				appDeploymentRequestShell);
		containerShell = defaultContainerFactory.create(shellContainerConfiguration);
		assertNotNull(containerShell);
		assertTrue(containerShell.getEnv().size() == 7);
		assertTrue(containerShell.getArgs().size() == 0);
		String envVarString = containerShell.getEnv().toString();
		assertTrue(envVarString.contains("name=FOO_BAR_BAZ, value=test"));
		assertTrue(envVarString.contains("name=FOO_BAR, value=value1"));
		assertTrue(envVarString.contains("name=SPRING_CLOUD_TASK_EXECUTIONID, value=1"));
		assertTrue(envVarString.contains("name=SPRING_CLOUD_DATA_FLOW_TASKAPPNAME, value==a1"));
		assertTrue(envVarString.contains("name=SPRING_CLOUD_DATA_FLOW_PLATFORMNAME, value=platform1"));
		assertTrue(envVarString.contains("name=BLAH, value=chacha"));

		props.put("spring.cloud.deployer.kubernetes.entryPointStyle", "exec");
		AppDeploymentRequest appDeploymentRequestExec = new AppDeploymentRequest(definition,
				resource, props);
		ContainerConfiguration execContainerConfiguration = new ContainerConfiguration("app-test",
				appDeploymentRequestExec);
		Container containerExec = defaultContainerFactory.create(execContainerConfiguration);
		assertNotNull(containerExec);
		assertTrue(containerExec.getEnv().size() == 1);
		assertTrue(containerExec.getArgs().get(0).equals("--foo.bar.baz=test"));

		props.put("spring.cloud.deployer.kubernetes.entryPointStyle", "boot");
		AppDeploymentRequest appDeploymentRequestBoot = new AppDeploymentRequest(definition,
				resource, props, Arrays.asList("--arg1=val1", "--arg2=val2"));
		ContainerConfiguration bootContainerConfiguration = new ContainerConfiguration("app-test",
				appDeploymentRequestBoot);
		Container containerBoot = defaultContainerFactory.create(bootContainerConfiguration);
		assertNotNull(containerBoot);
		assertTrue(containerBoot.getEnv().get(0).getName().equals("SPRING_APPLICATION_JSON"));
		assertTrue(containerBoot.getEnv().get(0).getValue().equals(new ObjectMapper().writeValueAsString(appProps)));
		assertTrue(containerBoot.getArgs().size() == 2);
		assertTrue(containerBoot.getArgs().get(0).equals("--arg1=val1"));
		assertTrue(containerBoot.getArgs().get(1).equals("--arg2=val2"));
	}

	@Test
	public void createWithVolumeMounts() {
		// test volume mounts defined as deployer properties
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts",
				"["
						+ "{name: 'testhostpath', mountPath: '/test/hostPath'}, "
						+ "{name: 'testpvc', mountPath: '/test/pvc', readOnly: 'true'}, "
						+ "{name: 'testnfs', mountPath: '/test/nfs'}"
					+ "]");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		Container container = defaultContainerFactory.create(containerConfiguration);

		assertThat(container.getVolumeMounts()).containsOnly(
				new VolumeMount("/test/hostPath", null, "testhostpath", null, null, null),
				new VolumeMount("/test/pvc", null, "testpvc", true, null, null),
				new VolumeMount("/test/nfs", null, "testnfs", null, null, null));

		// test volume mounts defined as app deployment property, overriding the deployer property
		kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties
				.setVolumeMounts(Stream.of(
						new VolumeMount("/test/hostPath", null, "testhostpath", false, null, null),
						new VolumeMount("/test/pvc", null, "testpvc", true, null, null),
						new VolumeMount("/test/nfs", null, "testnfs", false, null, null))
				.collect(Collectors.toList()));
		defaultContainerFactory = new DefaultContainerFactory(kubernetesDeployerProperties);

		props.clear();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts",
				"["
						+ "{name: 'testpvc', mountPath: '/test/pvc/overridden'}, "
						+ "{name: 'testnfs', mountPath: '/test/nfs/overridden', readOnly: 'true'}"
					+ "]");

		containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest);
		container = defaultContainerFactory.create(containerConfiguration);

		assertThat(container.getVolumeMounts()).containsOnly(
				new VolumeMount("/test/hostPath", null, "testhostpath", false, null, null),
				new VolumeMount("/test/pvc/overridden", null, "testpvc", null, null, null),
				new VolumeMount("/test/nfs/overridden", null, "testnfs", true, null, null));
	}

	@Test
	public void createCustomHttpLivenessPortFromProperties() {
		int defaultPort = 8080;
		int livenessPort = 8090;

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setLivenessProbePort(livenessPort);
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withExternalPort(defaultPort)
				.withHostNetwork(true);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);

		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);

		assertTrue("Only two container ports should be set", containerPorts.size() == 2);
		assertTrue(8080 == containerPorts.get(0).getContainerPort());
		assertTrue(8080 == containerPorts.get(0).getHostPort());
		assertTrue(8090 == containerPorts.get(1).getContainerPort());
		assertTrue(8090 == containerPorts.get(1).getHostPort());
		assertTrue(8090 == container.getLivenessProbe().getHttpGet().getPort().getIntVal());
	}

	@Test
	public void createCustomHttpLivenessPortFromAppRequest() {
		int defaultPort = 8080;
		int livenessPort = 8090;

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.liveness-probe-port", Integer.toString(livenessPort));
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(defaultPort);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);

		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);

		assertTrue("Only two container ports should be set", containerPorts.size() == 2);
		assertTrue(8080 == containerPorts.get(0).getContainerPort());
		assertTrue(8080 == containerPorts.get(0).getHostPort());
		assertTrue(8090 == containerPorts.get(1).getContainerPort());
		assertTrue(8090 == containerPorts.get(1).getHostPort());
		assertTrue(8090 == container.getLivenessProbe().getHttpGet().getPort().getIntVal());
	}

	@Test
	public void createCustomHttpReadinessPortFromAppRequest() {
		int defaultPort = 8080;
		int readinessPort = 8090;

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.readinessProbePort", Integer.toString(readinessPort));
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(defaultPort);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);

		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);

		assertTrue("Only two container ports should be set", containerPorts.size() == 2);
		assertTrue(8080 == containerPorts.get(0).getContainerPort());
		assertTrue(8080 == containerPorts.get(0).getHostPort());
		assertTrue(8090 == containerPorts.get(1).getContainerPort());
		assertTrue(8090 == containerPorts.get(1).getHostPort());
		assertTrue(8090 == container.getReadinessProbe().getHttpGet().getPort().getIntVal());
	}

	@Test
	public void createCustomHttpReadinessPortFromProperties() {
		int defaultPort = 8080;
		int readinessPort = 8090;

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setReadinessProbePort(readinessPort);
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(defaultPort);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);

		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);

		assertTrue("Only two container ports should be set", containerPorts.size() == 2);
		assertTrue(8080 == containerPorts.get(0).getContainerPort());
		assertTrue(8080 == containerPorts.get(0).getHostPort());
		assertTrue(8090 == containerPorts.get(1).getContainerPort());
		assertTrue(8090 == containerPorts.get(1).getHostPort());
		assertTrue(8090 == container.getReadinessProbe().getHttpGet().getPort().getIntVal());
	}

	@Test
	public void createDefaultHttpProbePorts() {
		int defaultPort = 8080;

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();
		Map<String, String> props = new HashMap<>();
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(defaultPort);
		Container container = defaultContainerFactory.create(containerConfiguration);
		assertNotNull(container);
		List<ContainerPort> containerPorts = container.getPorts();
		assertNotNull(containerPorts);
		assertTrue("Only the default container port should set", containerPorts.size() == 1);
		assertTrue(8080 == containerPorts.get(0).getContainerPort());
		assertTrue(8080 == containerPorts.get(0).getHostPort());
		assertTrue(8080 == container.getLivenessProbe().getHttpGet().getPort().getIntVal());
		assertTrue(8080 == container.getReadinessProbe().getHttpGet().getPort().getIntVal());
	}

	@Test
	public void createHttpProbesWithDefaultEndpoints() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();

		Map<String, String> props = new HashMap<>();
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, props);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertNotNull(container.getReadinessProbe().getHttpGet().getPath());
		assertEquals(HttpProbeCreator.BOOT_2_READINESS_PROBE_PATH, container.getReadinessProbe().getHttpGet().getPath());

		assertNotNull(container.getLivenessProbe().getHttpGet().getPath());
		assertEquals(HttpProbeCreator.BOOT_2_LIVENESS_PROBE_PATH, container.getLivenessProbe().getHttpGet().getPath());
	}

	@Test
	public void createHttpProbesWithBoot1Endpoints() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.boot-major-version", "1");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertNotNull(container.getReadinessProbe().getHttpGet().getPath());
		assertEquals(HttpProbeCreator.BOOT_1_READINESS_PROBE_PATH, container.getReadinessProbe().getHttpGet().getPath());

		assertNotNull(container.getLivenessProbe().getHttpGet().getPath());
		assertEquals(HttpProbeCreator.BOOT_1_LIVENESS_PROBE_PATH, container.getLivenessProbe().getHttpGet().getPath());
	}

	@Test
	public void createHttpProbesWithOverrides() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.livenessProbePath", "/liveness");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessProbePath", "/readiness");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertNotNull(container.getReadinessProbe().getHttpGet().getPath());
		assertEquals("/readiness", container.getReadinessProbe().getHttpGet().getPath());

		assertNotNull(container.getLivenessProbe().getHttpGet().getPath());
		assertEquals("/liveness", container.getLivenessProbe().getHttpGet().getPath());
	}

	@Test
	public void createHttpProbesWithPropertyOverrides() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setReadinessProbePath("/readiness");
		kubernetesDeployerProperties.setLivenessProbePath("/liveness");
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition,
				resource, null);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertNotNull(container.getReadinessProbe().getHttpGet().getPath());
		assertEquals("/readiness", container.getReadinessProbe().getHttpGet().getPath());

		assertNotNull(container.getLivenessProbe().getHttpGet().getPath());
		assertEquals("/liveness", container.getLivenessProbe().getHttpGet().getPath());
	}

	@Test
	public void testHttpProbeCredentialsSecret() {
		Secret secret = randomSecret();
		String secretName = secret.getMetadata().getName();

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeCredentialsSecret", secretName);

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withExternalPort(8080)
				.withProbeCredentialsSecret(secret);

		ContainerFactory containerFactory = new DefaultContainerFactory(new KubernetesDeployerProperties());
		Container container = containerFactory.create(containerConfiguration);

		String credentials = containerConfiguration.getProbeCredentialsSecret().getData()
				.get(HttpProbeCreator.PROBE_CREDENTIALS_SECRET_KEY_NAME);

		HTTPHeader livenessProbeHeader = container.getLivenessProbe().getHttpGet().getHttpHeaders().get(0);
		assertEquals(HttpProbeCreator.AUTHORIZATION_HEADER_NAME, livenessProbeHeader.getName());
		assertEquals(ProbeAuthenticationType.Basic.name() + " " + credentials, livenessProbeHeader.getValue());

		HTTPHeader readinessProbeHeader = container.getReadinessProbe().getHttpGet().getHttpHeaders().get(0);
		assertEquals(HttpProbeCreator.AUTHORIZATION_HEADER_NAME, readinessProbeHeader.getName());
		assertEquals(ProbeAuthenticationType.Basic.name() + " " + credentials, readinessProbeHeader.getValue());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testHttpProbeCredentialsInvalidSecret() {
		Secret secret = randomSecret();
		secret.setData(Collections.singletonMap("unexpectedkey", "dXNlcjpwYXNz"));

		String secretName = secret.getMetadata().getName();

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeCredentialsSecret", secretName);

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withExternalPort(8080)
				.withProbeCredentialsSecret(secret);

		ContainerFactory containerFactory = new DefaultContainerFactory(new KubernetesDeployerProperties());
		containerFactory.create(containerConfiguration);

		fail();
	}

	@Test
	public void testHttpProbeHeadersWithoutAuth() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource());

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withExternalPort(8080);

		ContainerFactory containerFactory = new DefaultContainerFactory(new KubernetesDeployerProperties());
		Container container = containerFactory.create(containerConfiguration);

		assertTrue("Liveness probe should not contain any HTTP headers",
				container.getLivenessProbe().getHttpGet().getHttpHeaders().isEmpty());
		assertTrue("Readiness probe should not contain any HTTP headers",
				container.getReadinessProbe().getHttpGet().getHttpHeaders().isEmpty());
	}

	@Test
	public void createTcpProbe() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePort", "5050");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePort", "9090");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(5050).withContainerPort(5050).build());
		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(9090).withContainerPort(9090).build());

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		Integer livenessTcpProbePort = livenessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Liveness TCP probe port should not be null", livenessTcpProbePort);
		assertEquals("Invalid liveness TCP probe port", 9090, livenessTcpProbePort.intValue());

		Integer readinessTcpProbePort = readinessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Readiness TCP probe port should not be null", readinessTcpProbePort);
		assertEquals("Invalid readiness TCP probe port", 5050, readinessTcpProbePort.intValue());

		assertNotNull("Liveness TCP probe period seconds should not be null", livenessProbe.getPeriodSeconds());
		assertNotNull("Readiness TCP probe period seconds should not be null", readinessProbe.getPeriodSeconds());

		assertNotNull("Liveness TCP probe initial delay seconds should not be null", livenessProbe.getInitialDelaySeconds());
		assertNotNull("Readiness TCP probe initial delay seconds should not be null", readinessProbe.getInitialDelaySeconds());
	}

	@Test
	public void createTcpProbeMissingLivenessPort() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePort", "5050");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		try {
			defaultContainerFactory.create(containerConfiguration);
		} catch (IllegalArgumentException e) {
			assertTrue("Expected livenessTcpProbePort to be set", e.getMessage()
					.contains("The livenessTcpProbePort property must be set"));
		}
	}

	@Test
	public void createTcpProbeMissingReadinessPort() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePort", "5050");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		try {
			defaultContainerFactory.create(containerConfiguration);
		} catch (IllegalArgumentException e) {
			assertTrue("A readinessTcpProbePort property must be set.", e.getMessage()
					.contains("A readinessTcpProbePort property must be set."));
		}
	}

	@Test
	public void createReadinessTcpProbeWithNonDigitPort() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePort", "9090");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePort", "somePort");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		try {
			defaultContainerFactory.create(containerConfiguration);
		} catch (Exception e) {
			assertTrue("ReadinessTcpPortProbe must contain all digits",
					e.getMessage().contains("ReadinessTcpProbePort must contain all digits"));
		}
	}

	@Test
	public void createLivenessTcpProbeWithNonDigitPort() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePort", "somePort");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePort", "5050");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		try {
			defaultContainerFactory.create(containerConfiguration);
		} catch (Exception e) {
			assertTrue("LivenessTcpPortProbe must contain all digits",
					e.getMessage().contains("LivenessTcpProbePort must contain all digits"));
		}
	}

	@Test
	public void createTcpProbeGlobalProperties() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setProbeType(ProbeType.TCP);
		kubernetesDeployerProperties.setReadinessTcpProbePort(5050);
		kubernetesDeployerProperties.setReadinessTcpProbeDelay(1);
		kubernetesDeployerProperties.setReadinessTcpProbePeriod(2);
		kubernetesDeployerProperties.setLivenessTcpProbePort(9090);
		kubernetesDeployerProperties.setLivenessTcpProbeDelay(3);
		kubernetesDeployerProperties.setLivenessTcpProbePeriod(4);

		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, null);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(5050).withContainerPort(5050).build());
		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(9090).withContainerPort(9090).build());

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		Integer livenessTcpProbePort = livenessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Liveness TCP probe port should not be null", livenessTcpProbePort);
		assertEquals("Invalid liveness TCP probe port", 9090, livenessTcpProbePort.intValue());

		Integer readinessTcpProbePort = readinessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Readiness TCP probe port should not be null", readinessTcpProbePort);
		assertEquals("Invalid readiness TCP probe port", 5050, readinessTcpProbePort.intValue());

		Integer livenessProbePeriodSeconds = livenessProbe.getPeriodSeconds();
		assertNotNull("Liveness TCP probe period seconds should not be null", livenessProbePeriodSeconds);
		assertEquals("Invalid livesness TCP probe period seconds", 4, livenessProbePeriodSeconds.intValue());

		Integer readinessProbePeriodSeconds = readinessProbe.getPeriodSeconds();
		assertNotNull("Readiness TCP probe period seconds should not be null", readinessProbePeriodSeconds);
		assertEquals("Invalid readiness TCP probe period seconds", 2, readinessProbePeriodSeconds.intValue());

		Integer livenessProbeInitialDelaySeconds = livenessProbe.getInitialDelaySeconds();
		assertNotNull("Liveness TCP probe initial delay seconds should not be null", livenessProbeInitialDelaySeconds);
		assertEquals("Invalid liveness TCP probe initial delay seconds", 3, livenessProbeInitialDelaySeconds.intValue());

		Integer readinessProbeInitialDelaySeconds = readinessProbe.getInitialDelaySeconds();
		assertNotNull("Readiness TCP probe initial delay seconds should not be null", readinessProbeInitialDelaySeconds);
		assertEquals("Invalid readiness TCP probe initial delay seconds", 1, readinessProbeInitialDelaySeconds.intValue());
	}

	@Test
	public void createTcpProbeGlobalPropertyOverride() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setProbeType(ProbeType.TCP);
		kubernetesDeployerProperties.setReadinessTcpProbePort(5050);
		kubernetesDeployerProperties.setReadinessTcpProbeDelay(1);
		kubernetesDeployerProperties.setReadinessTcpProbePeriod(2);
		kubernetesDeployerProperties.setLivenessTcpProbePort(9090);
		kubernetesDeployerProperties.setLivenessTcpProbeDelay(3);
		kubernetesDeployerProperties.setLivenessTcpProbePeriod(4);

		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.TCP.name());
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePort", "5050");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbePeriod", "11");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessTcpProbeDelay", "12");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePort", "9090");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbePeriod", "13");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessTcpProbeDelay", "14");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(5050).withContainerPort(5050).build());
		assertThat(container.getPorts())
				.contains(new ContainerPortBuilder().withHostPort(9090).withContainerPort(9090).build());

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		Integer livenessTcpProbePort = livenessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Liveness TCP probe port should not be null", livenessTcpProbePort);
		assertEquals("Invalid liveness TCP probe port", 9090, livenessTcpProbePort.intValue());

		Integer readinessTcpProbePort = readinessProbe.getTcpSocket().getPort().getIntVal();
		assertNotNull("Readiness TCP probe port should not be null", readinessTcpProbePort);
		assertEquals("Invalid readiness TCP probe port", 5050, readinessTcpProbePort.intValue());

		Integer livenessProbePeriodSeconds = livenessProbe.getPeriodSeconds();
		assertNotNull("Liveness TCP probe period seconds should not be null", livenessProbePeriodSeconds);
		assertEquals("Invalid livesness TCP probe period seconds", 13, livenessProbePeriodSeconds.intValue());

		Integer readinessProbePeriodSeconds = readinessProbe.getPeriodSeconds();
		assertNotNull("Readiness TCP probe period seconds should not be null", readinessProbePeriodSeconds);
		assertEquals("Invalid readiness TCP probe period seconds", 11, readinessProbePeriodSeconds.intValue());

		Integer livenessProbeInitialDelaySeconds = livenessProbe.getInitialDelaySeconds();
		assertNotNull("Liveness TCP probe initial delay seconds should not be null", livenessProbeInitialDelaySeconds);
		assertEquals("Invalid liveness TCP probe initial delay seconds", 14, livenessProbeInitialDelaySeconds.intValue());

		Integer readinessProbeInitialDelaySeconds = readinessProbe.getInitialDelaySeconds();
		assertNotNull("Readiness TCP probe initial delay seconds should not be null", readinessProbeInitialDelaySeconds);
		assertEquals("Invalid readiness TCP probe initial delay seconds", 12, readinessProbeInitialDelaySeconds.intValue());
	}

	@Test
	public void createCommandProbe() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.COMMAND.name());
		appProperties.put("spring.cloud.deployer.kubernetes.readinessCommandProbeCommand", "ls /");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessCommandProbeCommand", "ls /dev");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		List<String> livenessCommandProbeCommand = livenessProbe.getExec().getCommand();
		assertFalse("Liveness command probe command should not be empty", livenessCommandProbeCommand.isEmpty());
		assertEquals("Invalid liveness command probe command", "ls /dev", String.join(" ", livenessCommandProbeCommand));

		List<String> readinessCommandProbeCommand = readinessProbe.getExec().getCommand();
		assertFalse("Readiness command probe command should not be empty", readinessCommandProbeCommand.isEmpty());
		assertEquals("Invalid readiness command probe command", "ls /", String.join(" ", readinessCommandProbeCommand));

		assertNotNull("Liveness command probe period seconds should not be null", livenessProbe.getPeriodSeconds());
		assertNotNull("Readiness command probe period seconds should not be null", readinessProbe.getPeriodSeconds());

		assertNotNull("Liveness command probe initial delay seconds should not be null", livenessProbe.getInitialDelaySeconds());
		assertNotNull("Readiness command probe initial delay seconds should not be null", readinessProbe.getInitialDelaySeconds());
	}

	@Test
	public void createCommandProbeMissingCommand() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.COMMAND.name());

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		try {
			defaultContainerFactory.create(containerConfiguration);
		} catch (IllegalArgumentException e) {
			assertTrue("The readinessCommandProbeCommand property must be set.", e.getMessage()
					.contains("The readinessCommandProbeCommand property must be set."));
		}
	}

	@Test
	public void createCommandProbeGlobalProperties() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setProbeType(ProbeType.COMMAND);
		kubernetesDeployerProperties.setReadinessCommandProbeCommand("ls /");
		kubernetesDeployerProperties.setReadinessCommandProbeDelay(1);
		kubernetesDeployerProperties.setReadinessCommandProbePeriod(2);
		kubernetesDeployerProperties.setLivenessCommandProbeCommand("ls /dev");
		kubernetesDeployerProperties.setLivenessCommandProbeDelay(3);
		kubernetesDeployerProperties.setLivenessCommandProbePeriod(4);

		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		AppDefinition definition = new AppDefinition("app-test", null);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, null);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		String livenessTcpProbeCommand = String.join(" ", livenessProbe.getExec().getCommand());
		assertNotNull("Liveness command probe command should not be null", livenessTcpProbeCommand);
		assertEquals("Invalid liveness command probe command", "ls /dev", livenessTcpProbeCommand);

		String readinessTcpProbeCommand = String.join(" ", readinessProbe.getExec().getCommand());
		assertNotNull("Readiness command probe command should not be null", readinessTcpProbeCommand);
		assertEquals("Invalid readiness command probe command", "ls /", readinessTcpProbeCommand);

		Integer livenessProbePeriodSeconds = livenessProbe.getPeriodSeconds();
		assertNotNull("Liveness command probe period seconds should not be null", livenessProbePeriodSeconds);
		assertEquals("Invalid livesness command probe period seconds", 4, livenessProbePeriodSeconds.intValue());

		Integer readinessProbePeriodSeconds = readinessProbe.getPeriodSeconds();
		assertNotNull("Readiness command probe period seconds should not be null", readinessProbePeriodSeconds);
		assertEquals("Invalid readiness command probe period seconds", 2, readinessProbePeriodSeconds.intValue());

		Integer livenessProbeInitialDelaySeconds = livenessProbe.getInitialDelaySeconds();
		assertNotNull("Liveness command probe initial delay seconds should not be null", livenessProbeInitialDelaySeconds);
		assertEquals("Invalid liveness command probe initial delay seconds", 3, livenessProbeInitialDelaySeconds.intValue());

		Integer readinessProbeInitialDelaySeconds = readinessProbe.getInitialDelaySeconds();
		assertNotNull("Readiness command probe initial delay seconds should not be null", readinessProbeInitialDelaySeconds);
		assertEquals("Invalid readiness command probe initial delay seconds", 1, readinessProbeInitialDelaySeconds.intValue());
	}

	@Test
	public void createCommandProbeGlobalPropertyOverride() {
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setProbeType(ProbeType.COMMAND);
		kubernetesDeployerProperties.setReadinessCommandProbeCommand("ls /");
		kubernetesDeployerProperties.setReadinessCommandProbeDelay(1);
		kubernetesDeployerProperties.setReadinessCommandProbePeriod(2);
		kubernetesDeployerProperties.setLivenessCommandProbeCommand("ls /dev");
		kubernetesDeployerProperties.setLivenessCommandProbeDelay(3);
		kubernetesDeployerProperties.setLivenessCommandProbePeriod(4);

		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);

		Map<String,String> appProperties = new HashMap<>();
		appProperties.put("spring.cloud.deployer.kubernetes.probeType", ProbeType.COMMAND.name());
		appProperties.put("spring.cloud.deployer.kubernetes.readinessCommandProbeCommand", "ls /");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessCommandProbePeriod", "11");
		appProperties.put("spring.cloud.deployer.kubernetes.readinessCommandProbeDelay", "12");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessCommandProbeCommand", "ls /dev");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessCommandProbePeriod", "13");
		appProperties.put("spring.cloud.deployer.kubernetes.livenessCommandProbeDelay", "14");

		AppDefinition definition = new AppDefinition("app-test", appProperties);
		Resource resource = getResource();

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, resource, appProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration("app-test", appDeploymentRequest)
				.withHostNetwork(true)
				.withExternalPort(8080);

		Container container = defaultContainerFactory.create(containerConfiguration);

		assertNotNull(container);

		Probe livenessProbe = container.getLivenessProbe();
		Probe readinessProbe = container.getReadinessProbe();

		assertNotNull("LivenessProbe should not be null", livenessProbe);
		assertNotNull("ReadinessProbe should not be null", readinessProbe);

		String livenessTcpProbeCommand = String.join(" ", livenessProbe.getExec().getCommand());
		assertNotNull("Liveness command probe command should not be null", livenessTcpProbeCommand);
		assertEquals("Invalid liveness command probe command", "ls /dev", livenessTcpProbeCommand);

		String readinessTcpProbeCommand = String.join(" ", readinessProbe.getExec().getCommand());
		assertNotNull("Readiness command probe command should not be null", readinessTcpProbeCommand);
		assertEquals("Invalid readiness command probe command", "ls /", readinessTcpProbeCommand);

		Integer livenessProbePeriodSeconds = livenessProbe.getPeriodSeconds();
		assertNotNull("Liveness command probe period seconds should not be null", livenessProbePeriodSeconds);
		assertEquals("Invalid livesness command probe period seconds", 13, livenessProbePeriodSeconds.intValue());

		Integer readinessProbePeriodSeconds = readinessProbe.getPeriodSeconds();
		assertNotNull("Readiness command probe period seconds should not be null", readinessProbePeriodSeconds);
		assertEquals("Invalid readiness command probe period seconds", 11, readinessProbePeriodSeconds.intValue());

		Integer livenessProbeInitialDelaySeconds = livenessProbe.getInitialDelaySeconds();
		assertNotNull("Liveness command probe initial delay seconds should not be null", livenessProbeInitialDelaySeconds);
		assertEquals("Invalid liveness command probe initial delay seconds", 14, livenessProbeInitialDelaySeconds.intValue());

		Integer readinessProbeInitialDelaySeconds = readinessProbe.getInitialDelaySeconds();
		assertNotNull("Readiness command probe initial delay seconds should not be null", readinessProbeInitialDelaySeconds);
		assertEquals("Invalid readiness command probe initial delay seconds", 12, readinessProbeInitialDelaySeconds.intValue());
	}

	@Test
	public void testCommandLineArgsOverridesExistingProperties() {
		AppDefinition definition = new AppDefinition("app-test", Collections.singletonMap("foo", "bar"));
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null,
				Collections.singletonList("--foo=newValue"));

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);
		assertThat(defaultContainerFactory.createCommandArgs(appDeploymentRequest)).containsExactly("--foo=newValue");
	}

	@Test
	public void testCommandLineArgsExcludesMalformedProperties() {
		Map<String,String> properties = new HashMap<>();
		properties.put("sun.cpu.isalist","");
		properties.put("foo","bar");
		AppDefinition definition = new AppDefinition("app-test", properties);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource());

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		DefaultContainerFactory defaultContainerFactory = new DefaultContainerFactory(
				kubernetesDeployerProperties);
		assertThat(defaultContainerFactory.createCommandArgs(appDeploymentRequest)).containsExactly("--foo=bar");
	}

	private Resource getResource() {
		return new DockerResource(
				"springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private Secret randomSecret() {
		String secretName = "secret-" + UUID.randomUUID().toString().substring(0, 18);
		String secretValue = "dXNlcjpwYXNz"; // base64 encoded string of: user:pass

		ObjectMeta objectMeta = new ObjectMeta();
		objectMeta.setName(secretName);

		Secret secret = new Secret();
		secret.setData(Collections.singletonMap(HttpProbeCreator.PROBE_CREDENTIALS_SECRET_KEY_NAME, secretValue));
		secret.setMetadata(objectMeta);

		return secret;
	}
}
