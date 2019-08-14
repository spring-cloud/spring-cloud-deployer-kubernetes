/*
 * Copyright 2018 the original author or authors.
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

package org.springframework.cloud.deployer.scheduler.spi.kubernetes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import org.junit.Test;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.scheduler.spi.core.ScheduleRequest;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.springframework.cloud.deployer.scheduler.spi.core.SchedulerPropertyKeys.CRON_EXPRESSION;

/**
 * Tests for {@link ContainerCreator}.
 *
 * @author Chris Schaefer
 */
public class ContainerCreatorTests {
	@Test
	public void testExecEntryPointStyle() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.exec);

		AppDefinition appDefinition = new AppDefinition(randomName(), getApplicationProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				getCommandLineArguments(), randomName(), testApplication());

		Container container = new ContainerCreator(kubernetesSchedulerProperties, scheduleRequest).build();

		assertNotNull("Command line arguments should not be null", container.getArgs());
		assertEquals("Invalid number of command line arguments", 4, container.getArgs().size());

		assertNotNull("Environment variables should not be null", container.getEnv());
		assertTrue("Found environment variables when there should be none", container.getEnv().isEmpty());
	}

	@Test
	public void testShellEntryPointStyle() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.shell);

		AppDefinition appDefinition = new AppDefinition(randomName(), getApplicationProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				getCommandLineArguments(), randomName(), testApplication());

		Container container = new ContainerCreator(kubernetesSchedulerProperties, scheduleRequest).build();

		assertNotNull("Command line arguments should not be null", container.getArgs());
		assertEquals("Invalid number of command line arguments", 0, container.getArgs().size());

		assertNotNull("Environment variables should not be null", container.getEnv());
		assertEquals("Invalid number of environment variables", 2, container.getEnv().size());
	}

	@Test
	public void testBootEntryPointStyle() throws IOException {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.boot);

		AppDefinition appDefinition = new AppDefinition(randomName(), getApplicationProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				getCommandLineArguments(), randomName(), testApplication());

		Container container = new ContainerCreator(kubernetesSchedulerProperties, scheduleRequest).build();

		assertNotNull("Command line arguments should not be null", container.getArgs());
		assertEquals("Invalid number of command line arguments", 2, container.getArgs().size());

		assertNotNull("Environment variables should not be null", container.getEnv());
		assertEquals("Invalid number of environment variables", 1, container.getEnv().size());

		String springApplicationJson = container.getEnv().get(0).getValue();

		Map<String, String> springApplicationJsonValues = new ObjectMapper().readValue(springApplicationJson,
				new TypeReference<HashMap<String, String>>() {
				});

		assertNotNull("SPRING_APPLICATION_JSON should not be null", springApplicationJsonValues);
		assertEquals("Invalid number of SPRING_APPLICATION_JSON entries", 2, springApplicationJsonValues.size());
	}

	@Test(expected = IllegalStateException.class)
	public void testBootEntryPointStyleInvalidSAJ() throws Exception {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.boot);

		AppDefinition appDefinition = new AppDefinition(randomName(), Collections.singletonMap(null, null));
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				null, randomName(), testApplication());

		new ContainerCreator(kubernetesSchedulerProperties, scheduleRequest).build();

		fail();
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidImageName() throws URISyntaxException {
		AppDefinition appDefinition = new AppDefinition(randomName(), null);
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getDefaultSchedulerProperties(), null,
				null, randomName(), new ClassPathResource("invalid"));

		new ContainerCreator(scheduleRequest).build();

		fail();
	}

	@Test
	public void testPropertyCommandLineArgumentConversion() {
		AppDefinition appDefinition = new AppDefinition(randomName(), getApplicationProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				null, randomName(), testApplication());

		ContainerCreator containerCreator = new ContainerCreator(scheduleRequest);
		List<String> commandLineArguments = containerCreator.createCommandLineArguments();

		assertNotNull("Command line arguments should not be null", commandLineArguments);
		assertEquals("Invalid number of command line arguments", 2, commandLineArguments.size());
		assertEquals("Unexpected command line argument", "--prop.1.key=prop.1.value", commandLineArguments.get(0));
		assertEquals("Unexpected command line argument", "--prop.2.key=prop.2.value", commandLineArguments.get(1));
	}

	@Test
	public void testPropertyEnvironmentVariableConversion() {
		AppDefinition appDefinition = new AppDefinition(randomName(), getApplicationProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null,
				null, randomName(), testApplication());

		ContainerCreator containerCreator = new ContainerCreator(scheduleRequest);
		List<EnvVar> environmentVariables = containerCreator.createEnvironmentVariables();

		assertNotNull("Environment variables should not be null", environmentVariables);
		assertEquals("Invalid number of command line arguments", 2, environmentVariables.size());
		assertEquals("Unexpected environment variable key", "PROP_1_KEY", environmentVariables.get(0).getName());
		assertEquals("Unexpected environment variable value", "prop.1.value", environmentVariables.get(0).getValue());
		assertEquals("Unexpected environment variable key", "PROP_2_KEY", environmentVariables.get(1).getName());
		assertEquals("Unexpected environment variable value", "prop.2.value", environmentVariables.get(1).getValue());
	}

	private Map<String, String> getDefaultSchedulerProperties() {
		Map<String, String> result = new HashMap<>();
		result.put(CRON_EXPRESSION, "8 16 ? * *");

		return result;
	}

	private List<String> getCommandLineArguments() {
		List<String> commandLineArguments = new ArrayList<>();
		commandLineArguments.add("arg1");
		commandLineArguments.add("arg2");

		return commandLineArguments;
	}

	private Map<String, String> getApplicationProperties() {
		Map<String, String> applicationProperties = new HashMap<>();
		applicationProperties.put("prop.1.key", "prop.1.value");
		applicationProperties.put("prop.2.key", "prop.2.value");

		return applicationProperties;
	}

	private Resource testApplication() {
		return new DockerResource("springcloud/spring-cloud-scheduler-spi-test-app:latest");
	}

	private String randomName() {
		return UUID.randomUUID().toString().substring(0, 18);
	}
}
