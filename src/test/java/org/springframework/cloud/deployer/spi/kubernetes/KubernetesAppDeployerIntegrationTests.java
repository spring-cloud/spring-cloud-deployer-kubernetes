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

import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.deployed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.failed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.unknown;
import static org.springframework.cloud.deployer.spi.kubernetes.AbstractKubernetesDeployer.SPRING_APP_KEY;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import okhttp3.Response;
import org.assertj.core.api.Assertions;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.KubernetesTestSupport;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppScaleRequest;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Integration tests for {@link KubernetesAppDeployer}.
 *
 * @author Thomas Risberg
 * @author Donovan Muller
 * @author David Turanski
 * @author Chris Schaefer
 * @author Christian Tzolov
 */
@SpringBootTest(classes = {KubernetesAutoConfiguration.class}, properties = {
		"logging.level.org.springframework.cloud.deployer.spi=INFO"
})
public class KubernetesAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Autowired
	private KubernetesClient kubernetesClient;

	@Autowired
	private KubernetesDeployerProperties originalProperties;

	@Override
	protected AppDeployer provideAppDeployer() {
		return appDeployer;
	}

	@Before
	public void setup() {
		if (kubernetesClient.getNamespace() == null) {
			kubernetesClient.getConfiguration().setNamespace("default");
		}
	}

	@Test
	public void testScaleStatefulSet() {
		log.info("Testing {}...", "ScaleStatefulSet");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();

		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		Timeout timeout = deploymentTimeout();
		String deploymentId = appDeployer.deploy(request);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));
		assertThat(deploymentId, eventually(appInstanceCount(is(3))));

		// Ensure that a StatefulSet is deployed
		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();
		assertNotNull(statefulSets);
		assertEquals(1, statefulSets.size());
		StatefulSet statefulSet = statefulSets.get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();
		Assertions.assertThat(statefulSetSpec.getPodManagementPolicy()).isEqualTo("Parallel");
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(3);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);
		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		log.info("Scale Down {}...", request.getDefinition().getName());
		appDeployer.scale(new AppScaleRequest(deploymentId, 1));
		assertThat(deploymentId, eventually(appInstanceCount(is(1)), timeout.maxAttempts, timeout.pause));

		statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();
		assertEquals(1, statefulSets.size());
		statefulSetSpec = statefulSets.get(0).getSpec();
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(1);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);
		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		appDeployer.undeploy(deploymentId);
	}

	@Test
	public void testScaleDeployment() {
		log.info("Testing {}...", "ScaleDeployment");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();

		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		log.info("Deploying {}...", request.getDefinition().getName());
		Timeout timeout = deploymentTimeout();
		String deploymentId = appDeployer.deploy(request);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));
		assertThat(deploymentId, eventually(appInstanceCount(is(1))));

		log.info("Scale Up {}...", request.getDefinition().getName());
		appDeployer.scale(new AppScaleRequest(deploymentId, 3));
		assertThat(deploymentId, eventually(appInstanceCount(is(3)), timeout.maxAttempts, timeout.pause));

		log.info("Scale Down {}...", request.getDefinition().getName());
		appDeployer.scale(new AppScaleRequest(deploymentId, 1));
		assertThat(deploymentId, eventually(appInstanceCount(is(1)), timeout.maxAttempts, timeout.pause));

		appDeployer.undeploy(deploymentId);
	}

	@Test(expected = IllegalStateException.class)
	public void testScaleWithNonExistingApps() {
		appDeployer.scale(new AppScaleRequest("Fake App", 10));
	}

	@Test
	public void testFailedDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "FailedDeploymentWithLoadBalancer");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setLivenessHttpProbePeriod(10);
		deployProperties.setMaxTerminatedErrorRestarts(1);
		deployProperties.setMaxCrashLoopBackOffRestarts(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.livenessHttpProbePath", "/invalidpath");
		props.put("spring.cloud.deployer.kubernetes.livenessHttpProbeDelay", "1");
		props.put("spring.cloud.deployer.kubernetes.livenessHttpProbePeriod", "1");

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(failed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testGoodDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "GoodDeploymentWithLoadBalancer");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithLoadBalancerHasUrlAndAnnotation() {
		log.info("Testing {}...", "DeploymentWithLoadBalancerShowsUrl");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.serviceAnnotations", "foo:bar,fab:baz"));

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Checking instance attributes of {}...", request.getDefinition().getName());
		AppStatus status = lbAppDeployer.status(deploymentId);
		for (String inst : status.getInstances().keySet()) {
			assertThat(deploymentId, eventually(hasInstanceAttribute(Matchers.hasKey("url"), lbAppDeployer, inst),
					timeout.maxAttempts, timeout.pause));
		}
		log.info("Checking service annotations of {}...", request.getDefinition().getName());
		Map<String, String> annotations = kubernetesClient.services().withName(request.getDefinition().getName()).get()
				.getMetadata().getAnnotations();
		assertThat(annotations, is(notNullValue()));
		assertThat(annotations.size(), is(2));
		assertTrue(annotations.containsKey("foo"));
		assertThat(annotations.get("foo"), is("bar"));
		assertTrue(annotations.containsKey("fab"));
		assertThat(annotations.get("fab"), is("baz"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithPodAnnotation() {
		log.info("Testing {}...", "DeploymentWithPodAnnotation");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.podAnnotations",
						"iam.amazonaws.com/role:role-arn,foo:bar"));

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Checking pod spec annotations of {}...", request.getDefinition().getName());

		List<Pod> pods = kubernetesClient.pods().withLabel("spring-deployment-id", request.getDefinition()
				.getName()).list().getItems();

		assertThat(pods, is(notNullValue()));
		assertThat(pods.size(), is(1));

		Pod pod = pods.get(0);
		Map<String, String> annotations = pod.getMetadata().getAnnotations();
		log.info("Number of annotations found" + annotations.size());
		for (Map.Entry<String, String> annotationsEntry : annotations.entrySet()) {
			log.info("Annotation key: " + annotationsEntry.getKey());
		}
		assertTrue(annotations.containsKey("iam.amazonaws.com/role"));
		assertEquals("role-arn", annotations.get("iam.amazonaws.com/role"));
		assertTrue(annotations.containsKey("foo"));
		assertEquals("bar", annotations.get("foo"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithMountedHostPathVolume() throws IOException {
		log.info("Testing {}...", "DeploymentWithMountedVolume");
		String containerPath = "/tmp/";
		String subPath = randomName();
		String mountName = "mount";

		HostPathVolumeSource hostPathVolumeSource = new HostPathVolumeSourceBuilder()
				.withPath("/tmp/" + randomName() + '/').build();

		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setVolumes(Collections.singletonList(new VolumeBuilder()
				.withHostPath(hostPathVolumeSource)
				.withName(mountName)
				.build()));
		deployProperties.setVolumeMounts(Collections.singletonList(new VolumeMount(hostPathVolumeSource.getPath(), null,
				mountName, false, null, null)));
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(),
				Collections.singletonMap("logging.file", containerPath + subPath));
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		PodSpec spec = kubernetesClient.pods().withLabels(selector).list().getItems().get(0).getSpec();
		assertThat(spec.getVolumes(), is(notNullValue()));
		Volume volume = spec.getVolumes().stream()
				.filter(v -> mountName.equals(v.getName()))
				.findAny()
				.orElseThrow(() -> new AssertionError("Volume not mounted"));
		assertThat(volume.getHostPath(), is(notNullValue()));
		assertThat(hostPathVolumeSource.getPath(), is(volume.getHostPath().getPath()));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	private void verifyAppEnv(String appId) {
		String ip = "";
		int port = 0;

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();
		boolean success = false;

		Service svc = kubernetesClient.services().withName(appId).get();
		RestTemplate restTemplate = new RestTemplate();

		if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
			int tries = 0;
			int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
			while (tries++ < maxWait && !success) {
				if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null &&
						svc.getStatus().getLoadBalancer().getIngress() != null &&
						!(svc.getStatus().getLoadBalancer().getIngress().isEmpty())) {
					ip = svc.getStatus().getLoadBalancer().getIngress().get(0).getIp();
					if (ip == null) {
						ip = svc.getStatus().getLoadBalancer().getIngress().get(0).getHostname();
					}
					port = svc.getSpec().getPorts().get(0).getPort();
					success = true;
					try {
						String url = String.format("http://%s:%d/actuator/env", ip, port);
						restTemplate.exchange(url,
								HttpMethod.GET, HttpEntity.EMPTY,
								new ParameterizedTypeReference<LinkedHashMap<String, ArrayList<LinkedHashMap>>>() {
									@Override
									public Type getType() {
										return Map.class;
									}
								});
					}
					catch(ResourceAccessException rae) {
						success = false;
						try {
							Thread.sleep(5000L);
						}
						catch (InterruptedException e) {
						}
					}
				}
				else {
					try {
						Thread.sleep(5000L);
					}
					catch (InterruptedException e) {
					}
					svc = kubernetesClient.services().withName(appId).get();
				}
			}
			log.debug(String.format("LoadBalancer Ingress: %s",
					svc.getStatus().getLoadBalancer().getIngress().toString()));
		}

		if (!success) {
			fail("cannot get service information for " + appId);
		}

		String url = String.format("http://%s:%d/actuator/env", ip, port);
		log.debug("getting app environment from " + url);
		restTemplate = new RestTemplate();

		ResponseEntity<LinkedHashMap<String, ArrayList<LinkedHashMap>>> response = restTemplate.exchange(url,
				HttpMethod.GET, HttpEntity.EMPTY,
				new ParameterizedTypeReference<LinkedHashMap<String, ArrayList<LinkedHashMap>>>() {
					@Override
					public Type getType() {
						return Map.class;
					}
				});

		LinkedHashMap<String, ArrayList<LinkedHashMap>> env = response.getBody();
		ArrayList<LinkedHashMap> propertySources = env.get("propertySources");

		String hostName = null;
		String instanceIndex = null;

		for (LinkedHashMap propertySource : propertySources) {
			if (propertySource.get("name").equals("systemEnvironment")) {
				LinkedHashMap s = (LinkedHashMap) propertySource.get("properties");
				hostName = (String) ((LinkedHashMap) s.get("HOSTNAME")).get("value");
			}

			if (propertySource.get("name").equals("applicationConfig: [file:./config/application.properties]")) {
				LinkedHashMap s = (LinkedHashMap) propertySource.get("properties");
				instanceIndex = (String) ((LinkedHashMap) s.get("INSTANCE_INDEX")).get("value");
			}
		}

		assertNotNull("Hostname is null", hostName);
		assertNotNull("Instance index is null", instanceIndex);

		String expectedIndex = hostName.substring(hostName.lastIndexOf("-") + 1);
		assertEquals(instanceIndex, expectedIndex);
	}

	@Test
	public void testDeploymentWithGroupAndIndex() throws IOException {
		log.info("Testing {}...", "DeploymentWithWithGroupAndIndex");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer testAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		Map<String, String> appProperties = new HashMap<>();
		appProperties.put("security.basic.enabled", "false");
		appProperties.put("management.security.enabled", "false");
		AppDefinition definition = new AppDefinition(randomName(), appProperties);
		Resource resource = testApplication();
		Map<String, String> props = new HashMap<>();
		props.put(AppDeployer.GROUP_PROPERTY_KEY, "foo");
		props.put(AppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = testAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		PodSpec spec = kubernetesClient.pods().withLabels(selector).list().getItems().get(0).getSpec();

		Map<String, String> envVars = new HashMap<>();
		for (EnvVar e : spec.getContainers().get(0).getEnv()) {
			envVars.put(e.getName(), e.getValue());
		}
		assertThat(envVars.get("SPRING_CLOUD_APPLICATION_GROUP"), is("foo"));
		verifyAppEnv(deploymentId);

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		testAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentServiceAccountName() {
		log.info("Testing {}...", "DeploymentServiceAccountName");

		ServiceAccount deploymentServiceAccount = new ServiceAccountBuilder().withNewMetadata().withName("appsa")
				.endMetadata().build();

		this.kubernetesClient.serviceAccounts().create(deploymentServiceAccount);

		String serviceAccountName = deploymentServiceAccount.getMetadata().getName();

		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setDeploymentServiceAccountName(serviceAccountName);

		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication());

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.serviceAccounts().delete(deploymentServiceAccount);
	}

	@Test
	public void testCreateStatefulSet() throws Exception {
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(deploymentId, appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();

		assertNotNull(statefulSets);
		assertEquals(1, statefulSets.size());

		StatefulSet statefulSet = statefulSets.get(0);

		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		List<Container> statefulSetInitContainers = statefulSetSpec.getTemplate().getSpec().getInitContainers();
		assertEquals(1, statefulSetInitContainers.size());
		Container statefulSetInitContainer = statefulSetInitContainers.get(0);
		assertEquals(DeploymentPropertiesResolver.STATEFUL_SET_IMAGE_NAME, statefulSetInitContainer.getImage());

		Assertions.assertThat(statefulSetSpec.getPodManagementPolicy()).isEqualTo("Parallel");
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(3);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.containsAllEntriesOf(deployer.createIdMap(deploymentId, appDeploymentRequest));
		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSetSpec.getTemplate().getSpec().getContainers().get(0);

		Assertions.assertThat(container.getName()).isEqualTo(deploymentId);
		Assertions.assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		Assertions.assertThat(container.getImage()).isEqualTo(testApplication().getURI().getSchemeSpecificPart());

		PersistentVolumeClaim pvc = statefulSetSpec.getVolumeClaimTemplates().get(0);
		Assertions.assertThat(pvc.getMetadata().getName()).isEqualTo(deploymentId);

		PersistentVolumeClaimSpec pvcSpec = pvc.getSpec();
		Assertions.assertThat(pvcSpec.getAccessModes()).containsOnly("ReadWriteOnce");
		Assertions.assertThat(pvcSpec.getStorageClassName()).isNull();

		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getAmount()).isEqualTo("10");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getAmount()).isEqualTo("10");

		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getFormat()).isEqualTo("Mi");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getFormat()).isEqualTo("Mi");

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCreateStatefulSetInitContainerImageNamePropOverride() throws Exception {
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");

		String imageName = testApplication().getURI().getSchemeSpecificPart();

		props.put("spring.cloud.deployer.kubernetes.statefulSetInitContainerImageName", imageName);

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();

		assertNotNull(statefulSets);
		assertEquals(1, statefulSets.size());

		StatefulSet statefulSet = statefulSets.get(0);

		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		List<Container> statefulSetInitContainers = statefulSetSpec.getTemplate().getSpec().getInitContainers();
		assertEquals(1, statefulSetInitContainers.size());
		Container statefulSetInitContainer = statefulSetInitContainers.get(0);
		assertEquals(imageName, statefulSetInitContainer.getImage());

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void createStatefulSetInitContainerImageNameGlobalOverride() throws Exception {
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		String imageName = testApplication().getURI().getSchemeSpecificPart();

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setStatefulSetInitContainerImageName(imageName);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();

		assertNotNull(statefulSets);
		assertEquals(1, statefulSets.size());

		StatefulSet statefulSet = statefulSets.get(0);

		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		List<Container> statefulSetInitContainers = statefulSetSpec.getTemplate().getSpec().getInitContainers();
		assertEquals(1, statefulSetInitContainers.size());
		Container statefulSetInitContainer = statefulSetInitContainers.get(0);
		assertEquals(imageName, statefulSetInitContainer.getImage());

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void createStatefulSetWithOverridingRequest() throws Exception {
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");
		props.put("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storage", "1g");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(deploymentId, appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		StatefulSet statefulSet = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems().get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		Assertions.assertThat(statefulSetSpec.getPodManagementPolicy()).isEqualTo("Parallel");
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(3);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);
		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.containsAllEntriesOf(deployer.createIdMap(deploymentId, appDeploymentRequest));
		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSetSpec.getTemplate().getSpec().getContainers().get(0);

		Assertions.assertThat(container.getName()).isEqualTo(deploymentId);
		Assertions.assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		Assertions.assertThat(container.getImage()).isEqualTo(testApplication().getURI().getSchemeSpecificPart());

		PersistentVolumeClaim pvc = statefulSetSpec.getVolumeClaimTemplates().get(0);
		Assertions.assertThat(pvc.getMetadata().getName()).isEqualTo(deploymentId);

		PersistentVolumeClaimSpec pvcSpec = pvc.getSpec();
		Assertions.assertThat(pvcSpec.getAccessModes()).containsOnly("ReadWriteOnce");

		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getAmount()).isEqualTo("1");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getAmount()).isEqualTo("1");

		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getFormat()).isEqualTo("Gi");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getFormat()).isEqualTo("Gi");

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testStatefulSetPodAnnotations() {
		log.info("Testing {}...", "StatefulSetPodAnnotations");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();

		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");
		props.put("spring.cloud.deployer.kubernetes.podAnnotations",
				"iam.amazonaws.com/role:role-arn,foo:bar");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		Timeout timeout = deploymentTimeout();
		String deploymentId = appDeployer.deploy(request);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));
		assertThat(deploymentId, eventually(appInstanceCount(is(3))));

		// Ensure that a StatefulSet is deployed
		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems();
		assertNotNull(statefulSets);
		assertEquals(1, statefulSets.size());
		StatefulSet statefulSet = statefulSets.get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		Map<String, String> annotations = statefulSetSpec.getTemplate().getMetadata().getAnnotations();
		assertTrue(annotations.containsKey("iam.amazonaws.com/role"));
		assertEquals("role-arn", annotations.get("iam.amazonaws.com/role"));
		assertTrue(annotations.containsKey("foo"));
		assertEquals("bar", annotations.get("foo"));
		appDeployer.undeploy(deploymentId);
	}

	@Test
	public void testDeploymentLabels() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1:value1,label2:value2");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());

		String deploymentId = deployer.deploy(appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		List<Deployment> deployments = kubernetesClient.apps().deployments().withLabels(selector).list().getItems();

		Map<String, String> specLabels = deployments.get(0).getSpec().getTemplate().getMetadata().getLabels();

		assertTrue("Label 'label1' not found in deployment spec", specLabels.containsKey("label1"));
		assertEquals("Unexpected value for label1", "value1", specLabels.get("label1"));
		assertTrue("Label 'label2' not found in deployment spec", specLabels.containsKey("label2"));
		assertEquals("Unexpected value for label1", "value2", specLabels.get("label2"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentLabelsStatefulSet() {
		log.info("Testing {}...", "DeploymentLabelsForStatefulSet");
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "2");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");
		props.put("spring.cloud.deployer.kubernetes.deploymentLabels",
				"stateful-label1:stateful-value1,stateful-label2:stateful-value2");
		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());

		String deploymentId = deployer.deploy(appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));
		Map<String, String> idMap = deployer.createIdMap(deploymentId, appDeploymentRequest);
		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		StatefulSet statefulSet = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems().get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(2);
		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);

		//verify stateful set match labels
		Map<String, String> setLabels = statefulSet.getMetadata().getLabels();
		assertTrue("Label 'stateful-label1' not found in StatefulSet metadata", setLabels.containsKey("stateful-label1"));
		assertEquals("Unexpected value in stateful-set metadata label for stateful-label1", "stateful-value1", setLabels.get("stateful-label1"));
		assertTrue("Label 'stateful-label2' not found in StatefulSet metadata", setLabels.containsKey("stateful-label2"));
		assertEquals("Unexpected value in stateful-set metadata label for stateful-label2","stateful-value2", setLabels.get("stateful-label2"));

		//verify pod template labels
		Map<String, String> specLabels = statefulSetSpec.getTemplate().getMetadata().getLabels();
		assertTrue("Label 'stateful-label1' not found in template metadata", specLabels.containsKey("stateful-label1"));
		assertEquals("Unexpected value for statefulSet metadata stateful-label1", "stateful-value1", specLabels.get("stateful-label1"));
		assertTrue("Label 'stateful-label2' not found in statefulSet template", specLabels.containsKey("stateful-label2"));
		assertEquals("Unexpected value for statefulSet metadata stateful-label2", "stateful-value2", specLabels.get("stateful-label2"));

		//verify that labels got replicated to one of the deployments
		List<Pod> pods =  kubernetesClient.pods().withLabels(selector).list().getItems();
		Map<String, String> podLabels = pods.get(0).getMetadata().getLabels();

		assertTrue("Label 'stateful-label1' not found in podLabels", podLabels.containsKey("stateful-label1"));
		assertEquals("Unexpected value for podLabels stateful-label1", "stateful-value1", podLabels.get("stateful-label1"));
		assertTrue("Label 'stateful-label2' not found in podLabels", podLabels.containsKey("stateful-label2"));
		assertEquals("Unexpected value for podLabels stateful-label2", "stateful-value2", podLabels.get("stateful-label2"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCleanupOnDeployFailure() throws InterruptedException {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		// simulate a pod going into an un-schedulable state
		KubernetesDeployerProperties.LimitsResources resources = new KubernetesDeployerProperties.LimitsResources();
		resources.setCpu("9000000");

		kubernetesDeployerProperties.setLimits(resources);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());

		String deploymentId = deployer.deploy(appDeploymentRequest);

		Timeout timeout = deploymentTimeout();

		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		// attempt to undeploy the failed deployment
		log.info("Undeploying {}...", deploymentId);

		try {
			appDeployer.undeploy(deploymentId);
		} catch (Exception e) {
			log.info("Got expected not not deployed exception on undeployment: " + e.getMessage());
		}

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		log.info("Deploying {}... again", deploymentId);

		// ensure a previous failed deployment with the same name was cleaned up and can be deployed again
		deployer.deploy(appDeploymentRequest);

		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		appDeployer.undeploy(deploymentId);

		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testMultipleContainersInPod() {
		log.info("Testing {}...", "MultipleContainersInPod");

		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();

		KubernetesAppDeployer kubernetesAppDeployer = Mockito.spy(new KubernetesAppDeployer(deployProperties,
				kubernetesClient, new DefaultContainerFactory(deployProperties)));

		AppDefinition definition = new AppDefinition(randomName(), Collections.singletonMap("server.port", "9090"));
		Resource resource = testApplication();

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		doAnswer((Answer<PodSpec>) invocationOnMock -> {
			PodSpec podSpec = (PodSpec) invocationOnMock.callRealMethod();

			Container container = new ContainerBuilder().withName("asecondcontainer")
					.withImage(resource.getURI().getSchemeSpecificPart()).build();

			podSpec.getContainers().add(container);

			return podSpec;
		}).when(kubernetesAppDeployer).createPodSpec(Mockito.any(AppDeploymentRequest.class));

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDefaultServicePort() {
		log.info("Testing {}...", "DefaultServicePort");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		List<ServicePort> servicePorts = kubernetesClient.services().withName(request.getDefinition().getName()).get()
				.getSpec().getPorts();

		assertThat(servicePorts, is(notNullValue()));
		assertThat(servicePorts.size(), is(1));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getPort().equals(8080)));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getName().equals("port-8080")));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDefaultServicePortOverride() {
		log.info("Testing {}...", "DefaultServicePortOverride");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), Collections.singletonMap("server.port", "9090"));
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		List<ServicePort> servicePorts = kubernetesClient.services().withName(request.getDefinition().getName()).get()
				.getSpec().getPorts();

		assertThat(servicePorts, is(notNullValue()));
		assertThat(servicePorts.size(), is(1));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getPort().equals(9090)));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getName().equals("port-9090")));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testServiceWithMultiplePorts() {
		log.info("Testing {}...", "ServiceWithMultiplePorts");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.servicePorts", "8080,9090"));

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		List<ServicePort> servicePorts = kubernetesClient.services().withName(request.getDefinition().getName()).get()
				.getSpec().getPorts();

		assertThat(servicePorts, is(notNullValue()));
		assertThat(servicePorts.size(), is(2));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getPort().equals(8080)));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getName().equals("port-8080")));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getPort().equals(9090)));
		assertTrue(servicePorts.stream().anyMatch(o -> o.getName().equals("port-9090")));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCreateInitContainer() {
		log.info("Testing {}...", "CreateInitContainer");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.initContainer",
				"{containerName: 'test', imageName: 'busybox:latest', commands: ['sh', '-c', 'echo hello']}");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication(), props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Deployment deployment = kubernetesClient.apps().deployments().withName(request.getDefinition().getName()).get();
		List<Container> initContainers = deployment.getSpec().getTemplate().getSpec().getInitContainers();

		Optional<Container> initContainer = initContainers.stream().filter(i -> i.getName().equals("test")).findFirst();
		assertTrue("Init container not found", initContainer.isPresent());

		Container testInitContainer = initContainer.get();

		assertEquals("Unexpected init container name", testInitContainer.getName(), "test");
		assertEquals("Unexpected init container image", testInitContainer.getImage(), "busybox:latest");

		List<String> commands = testInitContainer.getCommand();

		assertTrue("Init container commands missing", commands != null && !commands.isEmpty());
		assertEquals("Invalid number of init container commands", 3, commands.size());
		assertEquals("sh", commands.get(0));
		assertEquals("-c", commands.get(1));
		assertEquals("echo hello", commands.get(2));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCreateInitContainerWithVolumeMounts() {
		log.info("Testing {}...", "CreateInitContainerWithVolumeMounts");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		Map<String, String> props = Stream.of(new String[][]{
				{
						"spring.cloud.deployer.kubernetes.volumes",
						"[{name: 'test-volume', emptyDir: {}}]",
				},
				{
						"spring.cloud.deployer.kubernetes.volumeMounts",
						"[{name: 'test-volume', mountPath: '/tmp'}]",
				},
				{
						"spring.cloud.deployer.kubernetes.initContainer",
						"{containerName: 'test', imageName: 'busybox:latest', commands: ['sh', '-c', 'echo hello'], " +
								"volumeMounts: [{name: 'test-volume', mountPath: '/tmp', readOnly: true}]}",
				}
		}).collect(Collectors.toMap(data -> data[0], data -> data[1]));

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication(), props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Deployment deployment = kubernetesClient.apps().deployments().withName(request.getDefinition().getName()).get();
		List<Container> initContainers = deployment.getSpec().getTemplate().getSpec().getInitContainers();

		Optional<Container> initContainer = initContainers.stream().filter(i -> i.getName().equals("test")).findFirst();
		assertTrue("Init container not found", initContainer.isPresent());

		Container testInitContainer = initContainer.get();

		assertEquals("Unexpected init container name", testInitContainer.getName(), "test");
		assertEquals("Unexpected init container image", testInitContainer.getImage(), "busybox:latest");

		List<String> commands = testInitContainer.getCommand();

		assertTrue("Init container commands missing", commands != null && !commands.isEmpty());
		assertEquals("Invalid number of init container commands", 3, commands.size());
		assertEquals("sh", commands.get(0));
		assertEquals("-c", commands.get(1));
		assertEquals("echo hello", commands.get(2));

		List<VolumeMount> volumeMounts = testInitContainer.getVolumeMounts();
		assertTrue("Init container volumeMounts missing", volumeMounts != null && !volumeMounts.isEmpty());
		assertEquals("Unexpected init container volume mounts size", 1, volumeMounts.size());

		VolumeMount vm = volumeMounts.get(0);
		assertEquals("Unexpected init container volume mount name", "test-volume", vm.getName());
		assertEquals("Unexpected init container volume mount path", "/tmp", vm.getMountPath());
		assertTrue("Expected read only volume mount", vm.getReadOnly());

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCreateAdditionalContainers() {
		log.info("Testing {}...", "CreateAdditionalContainers");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		Map<String, String> props = Stream.of(new String[][]{
				{
						"spring.cloud.deployer.kubernetes.volumes",
						"[{name: 'test-volume', emptyDir: {}}]",
				},
				{
						"spring.cloud.deployer.kubernetes.volumeMounts",
						"[{name: 'test-volume', mountPath: '/tmp'}]",
				},
				{
						"spring.cloud.deployer.kubernetes.additional-containers",
						"[{name: 'c1', image: 'busybox:latest', command: ['sh', '-c', 'echo hello1'], volumeMounts: [{name: 'test-volume', mountPath: '/tmp', readOnly: true}]},"
								+ "{name: 'c2', image: 'busybox:1.26.1', command: ['sh', '-c', 'echo hello2']}]"
				}}).collect(Collectors.toMap(data -> data[0], data -> data[1]));

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication(), props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Deployment deployment = kubernetesClient.apps().deployments().withName(request.getDefinition().getName()).get();
		List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();

		assertTrue("Number of containers is incorrect", containers.size() == 3);

		Optional<Container> additionalContainer1 = containers.stream().filter(i -> i.getName().equals("c1")).findFirst();
		assertTrue("Additional container c1 not found", additionalContainer1.isPresent());

		Container testAdditionalContainer1 = additionalContainer1.get();

		assertEquals("Unexpected additional container name", testAdditionalContainer1.getName(), "c1");
		assertEquals("Unexpected additional container image", testAdditionalContainer1.getImage(), "busybox:latest");

		List<String> commands = testAdditionalContainer1.getCommand();

		assertTrue("Additional container commands missing", commands != null && !commands.isEmpty());
		assertEquals("Invalid number of additional container commands", 3, commands.size());
		assertEquals("sh", commands.get(0));
		assertEquals("-c", commands.get(1));
		assertEquals("echo hello1", commands.get(2));

		List<VolumeMount> volumeMounts = testAdditionalContainer1.getVolumeMounts();

		assertTrue("Volume mount size is incorrect", volumeMounts.size() == 1);
		assertEquals("test-volume", volumeMounts.get(0).getName());
		assertEquals("/tmp", volumeMounts.get(0).getMountPath());
		assertEquals(Boolean.TRUE, volumeMounts.get(0).getReadOnly());

		Optional<Container> additionalContainer2 = containers.stream().filter(i -> i.getName().equals("c2")).findFirst();
		assertTrue("Additional container c2 not found", additionalContainer2.isPresent());

		Container testAdditionalContainer2 = additionalContainer2.get();

		assertEquals("Unexpected additional container name", testAdditionalContainer2.getName(), "c2");
		assertEquals("Unexpected additional container image", testAdditionalContainer2.getImage(), "busybox:1.26.1");

		List<String> container2Commands = testAdditionalContainer2.getCommand();

		assertTrue("Additional container commands missing", container2Commands != null && !container2Commands.isEmpty());
		assertEquals("Invalid number of additional container commands", 3, container2Commands.size());
		assertEquals("sh", container2Commands.get(0));
		assertEquals("-c", container2Commands.get(1));
		assertEquals("echo hello2", container2Commands.get(2));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testCreateAdditionalContainersOverride() {
		log.info("Testing {}...", "CreateAdditionalContainersOverride");
		KubernetesDeployerProperties.Container container1 = new KubernetesDeployerProperties.Container();
		container1.setName("c1");
		container1.setImage("busybox:1.31.0");
		container1.setCommand(Arrays.asList("sh", "-c", "echo hello-from-original-properties"));
		KubernetesDeployerProperties.Container container2 = new KubernetesDeployerProperties.Container();
		container2.setName("container2");
		container2.setImage("busybox:1.31.0");
		container2.setCommand(Arrays.asList("sh", "-c", "echo hello-from-original-properties"));
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setAdditionalContainers(Arrays.asList(container1, container2));
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		Map<String, String> props = Stream.of(new String[][]{
				{
						"spring.cloud.deployer.kubernetes.volumes",
						"[{name: 'test-volume', emptyDir: {}}]",
				},
				{
						"spring.cloud.deployer.kubernetes.volumeMounts",
						"[{name: 'test-volume', mountPath: '/tmp'}]",
				},
				{
						"spring.cloud.deployer.kubernetes.additional-containers",
						"[{name: 'c1', image: 'busybox:latest', command: ['sh', '-c', 'echo hello1'], volumeMounts: [{name: 'test-volume', mountPath: '/tmp', readOnly: true}]},"
								+ "{name: 'c2', image: 'busybox:1.26.1', command: ['sh', '-c', 'echo hello2']}]"
				}}).collect(Collectors.toMap(data -> data[0], data -> data[1]));

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication(), props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Deployment deployment = kubernetesClient.apps().deployments().withName(request.getDefinition().getName()).get();
		List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();

		assertTrue("Number of containers is incorrect", containers.size() == 4);

		// c1 from the deployment properties should have overridden the c1 from the original deployer properties
		Optional<Container> additionalContainer1 = containers.stream().filter(i -> i.getName().equals("c1")).findFirst();
		assertTrue("Additional container c1 not found", additionalContainer1.isPresent());

		Container testAdditionalContainer1 = additionalContainer1.get();

		assertEquals("Unexpected additional container name", testAdditionalContainer1.getName(), "c1");
		assertEquals("Unexpected additional container image", testAdditionalContainer1.getImage(), "busybox:latest");

		List<String> commands = testAdditionalContainer1.getCommand();

		assertTrue("Additional container commands missing", commands != null && !commands.isEmpty());
		assertEquals("Invalid number of additional container commands", 3, commands.size());
		assertEquals("sh", commands.get(0));
		assertEquals("-c", commands.get(1));
		assertEquals("echo hello1", commands.get(2));

		List<VolumeMount> volumeMounts = testAdditionalContainer1.getVolumeMounts();

		assertTrue("Volume mount size is incorrect", volumeMounts.size() == 1);
		assertEquals("test-volume", volumeMounts.get(0).getName());
		assertEquals("/tmp", volumeMounts.get(0).getMountPath());
		assertEquals(Boolean.TRUE, volumeMounts.get(0).getReadOnly());

		Optional<Container> additionalContainer2 = containers.stream().filter(i -> i.getName().equals("c2")).findFirst();
		assertTrue("Additional container c2 not found", additionalContainer2.isPresent());

		Container testAdditionalContainer2 = additionalContainer2.get();

		assertEquals("Unexpected additional container name", testAdditionalContainer2.getName(), "c2");
		assertEquals("Unexpected additional container image", testAdditionalContainer2.getImage(), "busybox:1.26.1");

		List<String> container2Commands = testAdditionalContainer2.getCommand();

		assertTrue("Additional container commands missing", container2Commands != null && !container2Commands.isEmpty());
		assertEquals("Invalid number of additional container commands", 3, container2Commands.size());
		assertEquals("sh", container2Commands.get(0));
		assertEquals("-c", container2Commands.get(1));
		assertEquals("echo hello2", container2Commands.get(2));

		// Verifying the additional container passed from the root deployer properties
		Optional<Container> additionalContainer3 = containers.stream().filter(i -> i.getName().equals("container2")).findFirst();
		assertTrue("Additional container c2 not found", additionalContainer3.isPresent());

		Container testAdditionalContainer3 = additionalContainer3.get();

		assertEquals("Unexpected additional container name", testAdditionalContainer3.getName(), "container2");
		assertEquals("Unexpected additional container image", testAdditionalContainer3.getImage(), "busybox:1.31.0");

		List<String> container3Commands = testAdditionalContainer3.getCommand();

		assertTrue("Additional container commands missing", container3Commands != null && !container3Commands.isEmpty());
		assertEquals("Invalid number of additional container commands", 3, container3Commands.size());
		assertEquals("sh", container3Commands.get(0));
		assertEquals("-c", container3Commands.get(1));
		assertEquals("echo hello-from-original-properties", container3Commands.get(2));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testUnknownStatusOnPendingResources() throws InterruptedException {
		log.info("Testing {}...", "UnknownStatusOnPendingResources");
		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();

		Map<String, String> props = new HashMap<>();
		// requests.cpu mirrors limits.cpu when only limits is set avoiding need to set both here
		props.put("spring.cloud.deployer.kubernetes.limits.cpu", "5000");

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);

		while(kubernetesClient.pods().withLabel("spring-deployment-id", deploymentId).list().getItems().isEmpty()) {
			log.info("Waiting for deployed pod");
			Thread.sleep(500);
		}

		Pod pod = kubernetesClient.pods().withLabel("spring-deployment-id", deploymentId).list().getItems().get(0);

		while (pod.getStatus().getConditions().isEmpty()) {
			log.info("Waiting for pod conditions to be set");
			Thread.sleep(500);
		}

		while(!"Unschedulable".equals(pod.getStatus().getConditions().get(0).getReason())) {
			log.info("Waiting for deployed pod to become Unschedulable");
		}

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();

		try {
			kubernetesAppDeployer.undeploy(deploymentId);
		} catch (IllegalStateException e) {
			assertEquals("App '" + deploymentId + "' is not deployed", e.getMessage());
		}

		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testSecretRef() throws InterruptedException {
		log.info("Testing {}...", "SecretRef");

		Secret secret = randomSecret();

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setSecretRefs(Collections.singletonList(secret.getMetadata().getName()));

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties, kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getSecretRef().getName(), secret.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		assertEquals(secret.getData().size(), 2);

		for(Map.Entry<String, String> secretData : secret.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(secretData.getValue()));
			assertTrue(podEnvironment.contains(secretData.getKey() + "=" + decodedValue));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.secrets().delete(secret);
	}

	@Test
	public void testSecretRefFromDeployerProperty() throws InterruptedException {
		log.info("Testing {}...", "SecretRefFromDeployerProperty");

		Secret secret = randomSecret();

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretRefs", secret.getMetadata().getName());

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getSecretRef().getName(), secret.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		assertEquals(secret.getData().size(), 2);

		for(Map.Entry<String, String> secretData : secret.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(secretData.getValue()));
			assertTrue(podEnvironment.contains(secretData.getKey() + "=" + decodedValue));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.secrets().delete(secret);
	}

	@Test
	public void testSecretRefFromDeployerPropertyOverride() throws IOException, InterruptedException {
		log.info("Testing {}...", "SecretRefFromDeployerPropertyOverride");

		Secret propertySecret = randomSecret();
		Secret deployerPropertySecret = randomSecret();

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setSecretRefs(Collections.singletonList(propertySecret.getMetadata().getName()));

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretRefs", deployerPropertySecret.getMetadata().getName());

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getSecretRef().getName(), deployerPropertySecret.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		for(Map.Entry<String, String> deployerPropertySecretData : deployerPropertySecret.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(deployerPropertySecretData.getValue()));
			assertTrue(podEnvironment.contains(deployerPropertySecretData.getKey() + "=" + decodedValue));
		}

		for(Map.Entry<String, String> propertySecretData : propertySecret.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(propertySecretData.getValue()));
			assertFalse(podEnvironment.contains(propertySecretData.getKey() + "=" + decodedValue));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.secrets().delete(propertySecret);
		kubernetesClient.secrets().delete(deployerPropertySecret);
	}

	@Test
	public void testSecretRefFromPropertyMultiple() throws InterruptedException {
		log.info("Testing {}...", "SecretRefFromPropertyMultiple");

		Secret secret1 = randomSecret();
		Secret secret2 = randomSecret();

		List<String> secrets = new ArrayList<>();
		secrets.add(secret1.getMetadata().getName());
		secrets.add(secret2.getMetadata().getName());

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setSecretRefs(secrets);

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, null);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(2));

		String podEnvironment = getPodEnvironment(deploymentId);

		EnvFromSource envFromSource1 = envFromSources.get(0);
		assertEquals(envFromSource1.getSecretRef().getName(), secret1.getMetadata().getName());

		EnvFromSource envFromSource2 = envFromSources.get(1);
		assertEquals(envFromSource2.getSecretRef().getName(), secret2.getMetadata().getName());

		Map<String, String> mergedSecretData = new HashMap<>();
		mergedSecretData.putAll(secret1.getData());
		mergedSecretData.putAll(secret2.getData());

		assertEquals(4, mergedSecretData.size());

		for(Map.Entry<String, String> secretData : secret1.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(secretData.getValue()));
			assertTrue(podEnvironment.contains(secretData.getKey() + "=" + decodedValue));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.secrets().delete(secret1);
		kubernetesClient.secrets().delete(secret2);
	}

	@Test
	public void testSecretRefFromDeploymentPropertyMultiple() throws InterruptedException {
		log.info("Testing {}...", "SecretRefFromDeploymentPropertyMultiple");

		Secret secret1 = randomSecret();
		Secret secret2 = randomSecret();

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretRefs", "[" + secret1.getMetadata().getName() + "," +
				secret2.getMetadata().getName() + "]");

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(2));

		String podEnvironment = getPodEnvironment(deploymentId);

		EnvFromSource envFromSource1 = envFromSources.get(0);
		assertEquals(envFromSource1.getSecretRef().getName(), secret1.getMetadata().getName());

		EnvFromSource envFromSource2 = envFromSources.get(1);
		assertEquals(envFromSource2.getSecretRef().getName(), secret2.getMetadata().getName());

		Map<String, String> mergedSecretData = new HashMap<>();
		mergedSecretData.putAll(secret1.getData());
		mergedSecretData.putAll(secret2.getData());

		assertEquals(4, mergedSecretData.size());

		for(Map.Entry<String, String> secretData : secret1.getData().entrySet()) {
			String decodedValue = new String(Base64.getDecoder().decode(secretData.getValue()));
			assertTrue(podEnvironment.contains(secretData.getKey() + "=" + decodedValue));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.secrets().delete(secret1);
		kubernetesClient.secrets().delete(secret2);
	}

	@Test
	public void testConfigMapRef() throws InterruptedException {
		log.info("Testing {}...", "ConfigMapRef");

		ConfigMap configMap = randomConfigMap();

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setConfigMapRefs(Collections.singletonList(configMap.getMetadata().getName()));

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties, kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getConfigMapRef().getName(), configMap.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		assertEquals(configMap.getData().size(), 2);

		for(Map.Entry<String, String> configMapData : configMap.getData().entrySet()) {
			assertTrue(podEnvironment.contains(configMapData.getKey() + "=" + configMapData.getValue()));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.configMaps().delete(configMap);
	}

	@Test
	public void testConfigMapRefFromDeployerProperty() throws InterruptedException {
		log.info("Testing {}...", "ConfigMapRefFromDeployerProperty");

		ConfigMap configMap = randomConfigMap();

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.config-map-refs", configMap.getMetadata().getName());

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getConfigMapRef().getName(), configMap.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		assertEquals(configMap.getData().size(), 2);

		for(Map.Entry<String, String> configMapData : configMap.getData().entrySet()) {
			assertTrue(podEnvironment.contains(configMapData.getKey() + "=" + configMapData.getValue()));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.configMaps().delete(configMap);
	}

	@Test
	public void testConfigMapRefFromDeployerPropertyOverride() throws IOException, InterruptedException {
		log.info("Testing {}...", "ConfigMapRefFromDeployerPropertyOverride");

		ConfigMap propertyConfigMap = randomConfigMap();
		ConfigMap deployerPropertyConfigMap = randomConfigMap();

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setConfigMapRefs(Collections.singletonList(propertyConfigMap.getMetadata().getName()));

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.configMapRefs", deployerPropertyConfigMap.getMetadata().getName());

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(1));

		EnvFromSource envFromSource = envFromSources.get(0);
		assertEquals(envFromSource.getConfigMapRef().getName(), deployerPropertyConfigMap.getMetadata().getName());

		String podEnvironment = getPodEnvironment(deploymentId);

		for(Map.Entry<String, String> deployerPropertyConfigMapData : deployerPropertyConfigMap.getData().entrySet()) {
			assertTrue(podEnvironment.contains(deployerPropertyConfigMapData.getKey() + "="
					+ deployerPropertyConfigMapData.getValue()));
		}

		for(Map.Entry<String, String> propertyConfigMapData : propertyConfigMap.getData().entrySet()) {
			assertFalse(podEnvironment.contains(propertyConfigMapData.getKey() + "=" + propertyConfigMapData.getValue()));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.configMaps().delete(propertyConfigMap);
		kubernetesClient.configMaps().delete(deployerPropertyConfigMap);
	}

	@Test
	public void testConfigMapRefFromPropertyMultiple() throws InterruptedException {
		log.info("Testing {}...", "ConfigMapRefFromPropertyMultiple");

		ConfigMap configMap1 = randomConfigMap();
		ConfigMap configMap2 = randomConfigMap();

		List<String> configMaps = new ArrayList<>();
		configMaps.add(configMap1.getMetadata().getName());
		configMaps.add(configMap2.getMetadata().getName());

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setConfigMapRefs(configMaps);

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(kubernetesDeployerProperties,
				kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, null);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(2));

		String podEnvironment = getPodEnvironment(deploymentId);

		EnvFromSource envFromSource1 = envFromSources.get(0);
		assertEquals(envFromSource1.getConfigMapRef().getName(), configMap1.getMetadata().getName());

		EnvFromSource envFromSource2 = envFromSources.get(1);
		assertEquals(envFromSource2.getConfigMapRef().getName(), configMap2.getMetadata().getName());

		Map<String, String> mergedConfigMapData = new HashMap<>();
		mergedConfigMapData.putAll(configMap1.getData());
		mergedConfigMapData.putAll(configMap2.getData());

		assertEquals(4, mergedConfigMapData.size());

		for(Map.Entry<String, String> configMapData : configMap1.getData().entrySet()) {
			assertTrue(podEnvironment.contains(configMapData.getKey() + "=" + configMapData.getValue()));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.configMaps().delete(configMap1);
		kubernetesClient.configMaps().delete(configMap2);
	}

	@Test
	public void testConfigMapRefFromDeploymentPropertyMultiple() throws InterruptedException {
		log.info("Testing {}...", "ConfigMapRefFromDeploymentPropertyMultiple");

		ConfigMap configMap1 = randomConfigMap();
		ConfigMap configMap2 = randomConfigMap();

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.configMapRefs", "[" + configMap1.getMetadata().getName() + "," +
				configMap2.getMetadata().getName() + "]");

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(),
				kubernetesClient);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = kubernetesAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Container container = kubernetesClient.apps().deployments().withName(deploymentId).get().getSpec()
				.getTemplate().getSpec().getContainers().get(0);

		List<EnvFromSource> envFromSources = container.getEnvFrom();

		assertThat(envFromSources, is(notNullValue()));
		assertThat(envFromSources.size(), is(2));

		String podEnvironment = getPodEnvironment(deploymentId);

		EnvFromSource envFromSource1 = envFromSources.get(0);
		assertEquals(envFromSource1.getConfigMapRef().getName(), configMap1.getMetadata().getName());

		EnvFromSource envFromSource2 = envFromSources.get(1);
		assertEquals(envFromSource2.getConfigMapRef().getName(), configMap2.getMetadata().getName());

		Map<String, String> mergedConfigMapData = new HashMap<>();
		mergedConfigMapData.putAll(configMap1.getData());
		mergedConfigMapData.putAll(configMap2.getData());

		assertEquals(4, mergedConfigMapData.size());

		for(Map.Entry<String, String> configMapData : configMap1.getData().entrySet()) {
			assertTrue(podEnvironment.contains(configMapData.getKey() + "=" + configMapData.getValue()));
		}

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		kubernetesAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.configMaps().delete(configMap1);
		kubernetesClient.configMaps().delete(configMap2);
	}

	@Override
	protected String randomName() {
		// Kubernetest service names must start with a letter and can only be 24 characters long
		return "app-" + UUID.randomUUID().toString().substring(0, 18);
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(300, 2000);
	}

	@Override
	protected Resource testApplication() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private Matcher<String> hasInstanceAttribute(final Matcher<Map<? extends String, ?>> mapMatcher,
			final KubernetesAppDeployer appDeployer,
			final String inst) {
		return new BaseMatcher<String>() {
			private Map<String, String> instanceAttributes;

			@Override
			public boolean matches(Object item) {
				this.instanceAttributes = appDeployer.status(item.toString()).getInstances().get(inst).getAttributes();
				return mapMatcher.matches(this.instanceAttributes);
			}

			@Override
			public void describeMismatch(Object item, Description mismatchDescription) {
				mismatchDescription.appendText("attributes of instance " + inst + " of ").appendValue(item)
						.appendText(" ");
				mapMatcher.describeMismatch(this.instanceAttributes, mismatchDescription);
			}

			@Override
			public void describeTo(Description description) {
				mapMatcher.describeTo(description);
			}
		};
	}

	private String getPodEnvironment(String deploymentId) throws InterruptedException {
		String podName = kubernetesClient.pods().withLabel("spring-deployment-id", deploymentId).list().getItems()
				.get(0).getMetadata().getName();

		final CountDownLatch countDownLatch = new CountDownLatch(1);
		ByteArrayOutputStream execOutputStream = new ByteArrayOutputStream();

		ExecWatch watch = kubernetesClient.pods().withName(podName).inContainer(deploymentId)
				.writingOutput(execOutputStream)
				.usingListener(new ExecListener() {
					@Override
					public void onOpen(Response response) {
					}

					@Override
					public void onFailure(Throwable throwable, Response response) {
						countDownLatch.countDown();
					}

					@Override
					public void onClose(int code, String reason) {
						countDownLatch.countDown();
					}
				}).exec("printenv");

		countDownLatch.await();

		byte[] bytes = execOutputStream.toByteArray();

		watch.close();

		return new String(bytes);
	}

	// Creates a Secret with a name will be generated prefixed by "secret-" followed by random numbers.
	//
	// Two data keys are present, both prefixed by "d-" followed by random numbers. This allows for cases where
	// multiple Secrets may be read into environment variables avoiding variable name clashes.
	//
	// Data values are Base64 encoded strings of value1 and value2
	private Secret randomSecret() {
		Map<String, String> secretData = new HashMap<>();
		secretData.put("d-" + UUID.randomUUID().toString().substring(0, 5), "dmFsdWUx");
		secretData.put("d-" + UUID.randomUUID().toString().substring(0, 5), "dmFsdWUy");

		Secret secret = new Secret();
		secret.setData(secretData);

		ObjectMeta objectMeta = new ObjectMeta();
		objectMeta.setName("secret-" + UUID.randomUUID().toString().substring(0, 5));

		secret.setMetadata(objectMeta);

		return kubernetesClient.secrets().create(secret);
	}

	// Creates a ConfigMap with a name will be generated prefixed by "cm-" followed by random numbers.
	//
	// Two data keys are present, both prefixed by "d-" followed by random numbers. This allows for cases where
	// multiple ConfigMaps may be read into environment variables avoiding variable name clashes.
	//
	// Data values are strings of value1 and value
	private ConfigMap randomConfigMap() {
		Map<String, String> configMapData = new HashMap<>();
		configMapData.put("d-" + UUID.randomUUID().toString().substring(0, 5), "value1");
		configMapData.put("d-" + UUID.randomUUID().toString().substring(0, 5), "value2");

		ConfigMap configMap = new ConfigMap();
		configMap.setData(configMapData);

		ObjectMeta objectMeta = new ObjectMeta();
		objectMeta.setName("cm-" + UUID.randomUUID().toString().substring(0, 5));

		configMap.setMetadata(objectMeta);

		return kubernetesClient.configMaps().create(configMap);
	}
}
