/*
 * Copyright 2018-2019 the original author or authors.
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

package org.springframework.cloud.deployer.spi.scheduler.kubernetes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusCause;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.batch.CronJob;
import io.fabric8.kubernetes.api.model.batch.CronJobList;
import io.fabric8.kubernetes.api.model.batch.CronJobSpec;
import io.fabric8.kubernetes.api.model.batch.JobSpec;
import io.fabric8.kubernetes.api.model.batch.JobTemplateSpec;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.KubernetesTestSupport;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.kubernetes.EntryPointStyle;
import org.springframework.cloud.deployer.spi.kubernetes.ImagePullPolicy;
import org.springframework.cloud.deployer.spi.scheduler.CreateScheduleException;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleInfo;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.cloud.deployer.spi.scheduler.Scheduler;
import org.springframework.cloud.deployer.spi.scheduler.SchedulerPropertyKeys;
import org.springframework.cloud.deployer.spi.scheduler.test.AbstractSchedulerIntegrationTests;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;
import static org.springframework.cloud.deployer.spi.scheduler.SchedulerPropertyKeys.CRON_EXPRESSION;

/**
 * Tests for Kubernetes {@link Scheduler} implementation.
 *
 * @author Chris Schaefer
 * @author Ilayaperumal Gopinathan
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = NONE)
@ContextConfiguration(classes = { KubernetesSchedulerTests.Config.class })
public class KubernetesSchedulerTests extends AbstractSchedulerIntegrationTests {
	@ClassRule
	public static KubernetesTestSupport kubernetesTestSupport = new KubernetesTestSupport();

	@Autowired
	private Scheduler scheduler;

	@Autowired
	private KubernetesClient kubernetesClient;

	@Override
	protected Scheduler provideScheduler() {
		return this.scheduler;
	}

	@Override
	protected List<String> getCommandLineArgs() {
		List<String> commandLineArguments = new ArrayList<>();
		commandLineArguments.add("arg1");
		commandLineArguments.add("arg2");

		return commandLineArguments;
	}

	@Override
	protected Map<String, String> getSchedulerProperties() {
		return Collections.singletonMap(SchedulerPropertyKeys.CRON_EXPRESSION, "57 13 ? * *");
	}

	@Override
	protected Map<String, String> getDeploymentProperties() {
		return null;
	}

	@Override
	protected Map<String, String> getAppProperties() {
		Map<String, String> applicationProperties = new HashMap<>();
		applicationProperties.put("prop.1.key", "prop.1.value");
		applicationProperties.put("prop.2.key", "prop.2.value");

		return applicationProperties;
	}

	@Override
	// schedule name must match "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$" and size must be between 0
	// and 63
	protected String randomName() {
		return UUID.randomUUID().toString().substring(0, 18);
	}

	@Override
	// schedule name must match "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$" and size must be between 0
	// and 63
	protected String scheduleName() {
		return "schedulename-";
	}

	protected Resource testApplication() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-scheduler-test-app:latest");
	}

	@Test(expected = CreateScheduleException.class)
	public void testMissingSchedule() {
		AppDefinition appDefinition = new AppDefinition(randomName(), null);
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null, null, null, testApplication());

		scheduler.schedule(scheduleRequest);

		fail();
	}

	@Test(expected = CreateScheduleException.class)
	public void testInvalidNameSchedule() {
		AppDefinition appDefinition = new AppDefinition("AAAAAA", null);
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, null, null, null, "AAAAA", testApplication());

		scheduler.schedule(scheduleRequest);

		fail();
	}

	@Test
	public void listScheduleWithExternalCronJobs() {
		CronJobList cronJobList = new CronJobList();
		CronJobSpec cronJobSpec = new CronJobSpec();
		JobTemplateSpec jobTemplateSpec = new JobTemplateSpec();
		JobSpec jobSpec = new JobSpec();
		PodTemplateSpec podTemplateSpec = new PodTemplateSpec();
		PodSpec podSpec = new PodSpec();
		Container container = new Container();
		container.setName("test");
		container.setImage("busybox");
		podSpec.setContainers(Arrays.asList(container));
		podSpec.setRestartPolicy("OnFailure");
		podTemplateSpec.setSpec(podSpec);
		jobSpec.setTemplate(podTemplateSpec);
		jobTemplateSpec.setSpec(jobSpec);
		cronJobSpec.setJobTemplate(jobTemplateSpec);
		cronJobSpec.setSchedule("0/10 * * * *");

		CronJob cronJob1 = new CronJob();
		ObjectMeta objectMeta1 = new ObjectMeta();
		Map<String, String> labels = new HashMap<>();
		labels.put("spring-cronjob-id", "test");
		objectMeta1.setLabels(labels);
		objectMeta1.setName("job1");
		cronJob1.setMetadata(objectMeta1);
		cronJob1.setSpec(cronJobSpec);
		ObjectMeta objectMeta2 = new ObjectMeta();
		objectMeta2.setName("job2");
		CronJob cronJob2 = new CronJob();
		cronJob2.setSpec(cronJobSpec);
		cronJob2.setMetadata(objectMeta2);
		ObjectMeta objectMeta3 = new ObjectMeta();
		objectMeta3.setName("job3");
		CronJob cronJob3 = new CronJob();
		cronJob3.setSpec(cronJobSpec);
		cronJob3.setMetadata(objectMeta3);
		cronJobList.setItems(Arrays.asList(cronJob1, cronJob2, cronJob3));
		this.kubernetesClient.batch().cronjobs().create(cronJob1);
		this.kubernetesClient.batch().cronjobs().create(cronJob2);
		this.kubernetesClient.batch().cronjobs().create(cronJob3);
		List<ScheduleInfo> scheduleInfos = this.scheduler.list();
		assertThat(scheduleInfos.size() == 1);
		assertThat(scheduleInfos.get(0).getScheduleName().equals("job1"));
	}

	@Test(expected = CreateScheduleException.class)
	public void testInvalidCronSyntax() {
		Map<String, String> schedulerProperties = Collections.singletonMap(CRON_EXPRESSION, "1 2 3 4");

		AppDefinition appDefinition = new AppDefinition(randomName(), null);
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties, null, null,
				randomName(), testApplication());

		scheduler.schedule(scheduleRequest);

		fail();
	}

	@Test
	public void testNameTooLong() {
		Map<String, String> schedulerProperties = Collections.singletonMap(CRON_EXPRESSION, "0/10 * * * *");

		AppDefinition appDefinition = new AppDefinition(randomName(), null);
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties, null, null,
				"tencharlng-scdf-itcouldbesaidthatthisislongtoowaytoo-oops", testApplication());

		try {
			scheduler.schedule(scheduleRequest);
		}
		catch (CreateScheduleException createScheduleException) {
			assertThat(createScheduleException.getMessage()).contains("must be no more than");
			return;
		}

		fail();
	}

	@Test
	public void testWithExecEntryPoint() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.exec);

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(), null,
				getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertNotNull("Command line arguments should not be null", container.getArgs());
		assertEquals("Invalid number of command line arguments", 4, container.getArgs().size());

		assertNotNull("Environment variables should not be null", container.getEnv());
		assertTrue("Found environment variables when there should be none", container.getEnv().isEmpty());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testWithShellEntryPoint() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.shell);

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(), null,
				getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertNotNull("Command line arguments should not be null", container.getArgs());
		assertEquals("Invalid number of command line arguments", 0, container.getArgs().size());

		assertNotNull("Environment variables should not be null", container.getEnv());
		assertEquals("Invalid number of environment variables", 2, container.getEnv().size());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testWithBootEntryPoint() throws IOException {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEntryPointStyle(EntryPointStyle.boot);

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(), null,
				getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

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

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testGetExceptionMessageForExistingField() {
		StatusCause statusCause = new StatusCause("spec.schedule", null, null);
		StatusDetails statusDetails = new StatusDetails();
		statusDetails.setCauses(Collections.singletonList(statusCause));

		Status status = new Status();
		status.setCode(0);
		status.setMessage("invalid cron expression");
		status.setDetails(statusDetails);

		KubernetesClientException kubernetesClientException = new KubernetesClientException(status);
		String message = ((KubernetesScheduler) scheduler).getExceptionMessageForField(kubernetesClientException,
				"spec.schedule");

		assertNotNull("Field message should not be null", message);
		assertEquals("Invalid message for field", "invalid cron expression", message);
	}

	@Test
	public void testGetExceptionMessageForNonExistentField() {
		StatusCause statusCause = new StatusCause("spec.schedule", null, null);
		StatusDetails statusDetails = new StatusDetails();
		statusDetails.setCauses(Collections.singletonList(statusCause));

		Status status = new Status();
		status.setCode(0);
		status.setMessage("invalid cron expression");
		status.setDetails(statusDetails);

		KubernetesClientException kubernetesClientException = new KubernetesClientException(status);
		String message = ((KubernetesScheduler) scheduler).getExceptionMessageForField(kubernetesClientException,
				"spec.restartpolicy");

		assertNull("Field message should be null", message);
	}

	@Test
	public void testEntryPointStyleOverride() throws Exception {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".entryPointStyle", "boot");

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties,
				null, getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertTrue("Environment variables should not be empty", !container.getEnv().isEmpty());
		assertEquals("Unexpected number of environment variables", 1, container.getEnv().size());

		String springApplicationJson = container.getEnv().get(0).getValue();

		Map<String, String> springApplicationJsonValues = new ObjectMapper().readValue(springApplicationJson,
				new TypeReference<HashMap<String, String>>() {
				});

		assertNotNull("SPRING_APPLICATION_JSON should not be null", springApplicationJsonValues);
		assertEquals("Invalid number of SPRING_APPLICATION_JSON entries", 2, springApplicationJsonValues.size());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testEntryPointStyleDefault() throws Exception {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(),
				getDeploymentProperties(), getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertTrue("Environment variables should be empty", container.getEnv().isEmpty());
		assertTrue("Command line arguments should not be empty", !container.getArgs().isEmpty());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testImagePullPolicyOverride() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".imagePullPolicy", "Always");

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties,
				null, getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertEquals("Unexpected image pull policy", "Always", container.getImagePullPolicy());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testImagePullPolicyDefault() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(),
				getDeploymentProperties(), getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertEquals("Unexpected default image pull policy", ImagePullPolicy.IfNotPresent,
				ImagePullPolicy.relaxedValueOf(container.getImagePullPolicy()));

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testImagePullSecret() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		String secretName = "mysecret";
		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".imagePullSecret", secretName);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties,
				null, getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		List<LocalObjectReference> secrets = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec()
				.getImagePullSecrets();
		assertEquals("Unexpected image pull secret", secretName, secrets.get(0).getName());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testImagePullSecretDefault() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(),
				getDeploymentProperties(), getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		List<LocalObjectReference> secrets = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec()
				.getImagePullSecrets();
		assertTrue("There should be no secrets", secrets.isEmpty());

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testCustomEnvironmentVariables() {
		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".environmentVariables", "MYVAR1=MYVAL1,MYVAR2=MYVAL2");

		EnvVar[] expectedVars = new EnvVar[] { new EnvVar("MYVAR1", "MYVAL1", null),
				new EnvVar("MYVAR2", "MYVAL2", null) };

		testEnvironmentVariables(new KubernetesSchedulerProperties(), schedulerProperties, expectedVars);
	}

	@Test
	public void testGlobalEnvironmentVariables() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEnvironmentVariables(new String[] { "MYVAR1=MYVAL1,MYVAR2=MYVAL2" });

		EnvVar[] expectedVars = new EnvVar[] { new EnvVar("MYVAR1", "MYVAL1", null),
				new EnvVar("MYVAR2", "MYVAL2", null) };

		testEnvironmentVariables(kubernetesSchedulerProperties, getSchedulerProperties(), expectedVars);
	}

	@Test
	public void testCustomEnvironmentVariablesWithNestedComma() {
		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".environmentVariables", "MYVAR='VAL1,VAL2',MYVAR2=MYVAL2");

		EnvVar[] expectedVars = new EnvVar[] { new EnvVar("MYVAR", "VAL1,VAL2", null),
				new EnvVar("MYVAR2", "MYVAL2", null) };

		testEnvironmentVariables(new KubernetesSchedulerProperties(), schedulerProperties, expectedVars);
	}

	@Test
	public void testGlobalAndCustomEnvironmentVariables() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEnvironmentVariables(new String[] { "MYVAR1=MYVAL1,MYVAR2=MYVAL2" });

		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".environmentVariables", "MYVAR3=MYVAL3,MYVAR4=MYVAL4");

		EnvVar[] expectedVars = new EnvVar[] { new EnvVar("MYVAR1", "MYVAL1", null),
				new EnvVar("MYVAR2", "MYVAL2", null), new EnvVar("MYVAR3", "MYVAL3", null),
				new EnvVar("MYVAR4", "MYVAL4", null) };

		testEnvironmentVariables(kubernetesSchedulerProperties, schedulerProperties, expectedVars);
	}

	@Test
	public void testCustomEnvironmentVariablesOverrideGlobal() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();
		kubernetesSchedulerProperties.setEnvironmentVariables(new String[] { "MYVAR1=MYVAL1,MYVAR2=MYVAL2" });

		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".environmentVariables", "MYVAR2=OVERRIDE");

		EnvVar[] expectedVars = new EnvVar[] { new EnvVar("MYVAR1", "MYVAL1", null),
				new EnvVar("MYVAR2", "OVERRIDE", null) };

		testEnvironmentVariables(kubernetesSchedulerProperties, schedulerProperties, expectedVars);
	}

	private void testEnvironmentVariables(KubernetesSchedulerProperties kubernetesSchedulerProperties,
			Map<String, String> schedulerProperties, EnvVar[] expectedVars) {
		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties,
				null, getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		Container container = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec().getContainers().get(0);

		assertTrue("Environment variables should not be empty", !container.getEnv().isEmpty());

		assertThat(container.getEnv()).contains(expectedVars);

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testTaskServiceAccountNameOverride() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		String taskServiceAccountName = "mysa";
		String prefix = KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

		Map<String, String> schedulerProperties = new HashMap<>(getSchedulerProperties());
		schedulerProperties.put(prefix + ".taskServiceAccountName", taskServiceAccountName);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, schedulerProperties,
				null, getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		String serviceAccountName = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec()
				.getServiceAccountName();
		assertEquals("Unexpected service account name", taskServiceAccountName, serviceAccountName);

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@Test
	public void testTaskServiceAccountNameDefault() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		AppDefinition appDefinition = new AppDefinition(randomName(), getAppProperties());
		ScheduleRequest scheduleRequest = new ScheduleRequest(appDefinition, getSchedulerProperties(),
				getDeploymentProperties(), getCommandLineArgs(), randomName(), testApplication());

		CronJob cronJob = kubernetesScheduler.createCronJob(scheduleRequest);
		CronJobSpec cronJobSpec = cronJob.getSpec();

		String serviceAccountName = cronJobSpec.getJobTemplate().getSpec().getTemplate().getSpec()
				.getServiceAccountName();
		assertEquals("Unexpected service account name", KubernetesSchedulerProperties.DEFAULT_TASK_SERVICE_ACCOUNT_NAME,
				serviceAccountName);

		kubernetesScheduler.unschedule(cronJob.getMetadata().getName());
	}

	@AfterClass
	public static void cleanup() {
		KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		KubernetesClient kubernetesClient = new DefaultKubernetesClient()
				.inNamespace(kubernetesSchedulerProperties.getNamespace());

		KubernetesScheduler kubernetesScheduler = new KubernetesScheduler(kubernetesClient,
				kubernetesSchedulerProperties);

		List<ScheduleInfo> scheduleInfos = kubernetesScheduler.list();

		for (ScheduleInfo scheduleInfo : scheduleInfos) {
			kubernetesScheduler.unschedule(scheduleInfo.getScheduleName());
		}
		// Cleanup the schedules that aren't part of the list() - created from listScheduleWithExternalCronJobs test
		kubernetesScheduler.unschedule("job2");
		kubernetesScheduler.unschedule("job3");
	}

	@Configuration
	@EnableAutoConfiguration
	@EnableConfigurationProperties
	public static class Config {
		private KubernetesSchedulerProperties kubernetesSchedulerProperties = new KubernetesSchedulerProperties();

		@Bean
		public Scheduler scheduler(KubernetesClient kubernetesClient) {
			return new KubernetesScheduler(kubernetesClient, kubernetesSchedulerProperties);
		}

		@Bean
		public KubernetesClient kubernetesClient() {
			return new DefaultKubernetesClient()
					.inNamespace(kubernetesSchedulerProperties.getNamespace());
		}
	}
}
