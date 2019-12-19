/*
 * Copyright 2015-2020 the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.PodAffinity;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PreferredSchedulingTerm;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.WeightedPodAffinityTerm;
import org.junit.Test;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link KubernetesAppDeployer}
 *
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @author Chris Schaefer
 * @author Enrique Medina Montenegro
 */
public class KubernetesAppDeployerTests {

	private KubernetesAppDeployer deployer;

	private DeploymentPropertiesResolver deploymentPropertiesResolver = new DeploymentPropertiesResolver("spring.cloud.deployer.",  new KubernetesDeployerProperties());


	@Test
	public void deployWithVolumesOnly() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(),
			new HashMap<>());

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getVolumes()).isEmpty();
	}

	@Test
	public void deployWithVolumesAndVolumeMounts() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts", "[" + "{name: 'testpvc', mountPath: '/test/pvc'}, "
			+ "{name: 'testnfs', mountPath: '/test/nfs', readOnly: 'true'}" + "]");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getVolumes()).containsOnly(
			// volume 'testhostpath' defined in dataflow-server.yml should not be added
			// as there is no corresponding volume mount
			new VolumeBuilder().withName("testpvc").withNewPersistentVolumeClaim("testClaim", true).build(),
			new VolumeBuilder().withName("testnfs").withNewNfs("/test/nfs", null, "10.0.0.1:111").build());

		props.clear();
		props.put("spring.cloud.deployer.kubernetes.volumes",
			"[" + "{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},"
				+ "{name: 'testnfs', nfs: { server: '192.168.1.1:111', path: '/test/override/nfs' }} " + "]");
		props.put("spring.cloud.deployer.kubernetes.volumeMounts",
			"[" + "{name: 'testhostpath', mountPath: '/test/hostPath'}, "
				+ "{name: 'testpvc', mountPath: '/test/pvc'}, "
				+ "{name: 'testnfs', mountPath: '/test/nfs', readOnly: 'true'}" + "]");
		appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		podSpec = deployer.createPodSpec(appDeploymentRequest);

		HostPathVolumeSource hostPathVolumeSource = new HostPathVolumeSourceBuilder()
				.withPath("/test/override/hostPath").build();

		assertThat(podSpec.getVolumes()).containsOnly(
			new VolumeBuilder().withName("testhostpath").withHostPath(hostPathVolumeSource).build(),
			new VolumeBuilder().withName("testpvc").withNewPersistentVolumeClaim("testClaim", true).build(),
			new VolumeBuilder().withName("testnfs").withNewNfs("/test/override/nfs", null, "192.168.1.1:111").build());
	}

	@Test
	public void deployWithNodeSelectorGlobalProperty() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setNodeSelector("disktype:ssd, os:qnx");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getNodeSelector()).containsOnly(entry("disktype", "ssd"), entry("os", "qnx"));
	}

	@Test
	public void deployWithNodeSelectorDeploymentProperty() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesDeployerProperties.KUBERNETES_DEPLOYMENT_NODE_SELECTOR, "disktype:ssd, os: linux");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getNodeSelector()).containsOnly(entry("disktype", "ssd"), entry("os", "linux"));
	}

	@Test
	public void deployWithNodeSelectorDeploymentPropertyGlobalOverride() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesDeployerProperties.KUBERNETES_DEPLOYMENT_NODE_SELECTOR, "disktype:ssd, os: openbsd");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setNodeSelector("disktype:ssd, os:qnx");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getNodeSelector()).containsOnly(entry("disktype", "ssd"), entry("os", "openbsd"));
	}

	@Test
	public void deployWithEnvironmentWithCommaDelimitedValue() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
				"JAVA_TOOL_OPTIONS='thing1,thing2',foo='bar,baz',car=caz,boo='zoo,gnu',doo=dar,OPTS='thing1'");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getContainers().get(0).getEnv())
			.contains(
				new EnvVar("foo", "bar,baz", null),
				new EnvVar("car", "caz", null),
				new EnvVar("boo", "zoo,gnu", null),
				new EnvVar("doo", "dar", null),
				new EnvVar("JAVA_TOOL_OPTIONS", "thing1,thing2", null),
				new EnvVar("OPTS", "thing1", null));
	}

	@Test
	public void deployWithEnvironmentWithSingleCommaDelimitedValue() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
				"JAVA_TOOL_OPTIONS='thing1,thing2'");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getContainers().get(0).getEnv())
				.contains(new EnvVar("JAVA_TOOL_OPTIONS", "thing1,thing2", null));
	}

	@Test
	public void deployWithEnvironmentWithMultipleCommaDelimitedValue() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
				"JAVA_TOOL_OPTIONS='thing1,thing2',OPTS='thing3, thing4'");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getContainers().get(0).getEnv())
				.contains(
					new EnvVar("JAVA_TOOL_OPTIONS", "thing1,thing2", null),
					new EnvVar("OPTS", "thing3, thing4", null));
	}

	@Test
	public void deployWithImagePullSecretDeploymentProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.imagePullSecret", "regcred");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getImagePullSecrets().size()).isEqualTo(1);
		assertThat(podSpec.getImagePullSecrets().get(0).getName()).isEqualTo("regcred");
	}

	@Test
	public void deployWithImagePullSecretDeployerProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setImagePullSecret("regcred");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getImagePullSecrets().size()).isEqualTo(1);
		assertThat(podSpec.getImagePullSecrets().get(0).getName()).isEqualTo("regcred");
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeploymentProperties() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deploymentServiceAccountName", "myserviceaccount");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("myserviceaccount"));
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeployerProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setDeploymentServiceAccountName("myserviceaccount");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("myserviceaccount"));
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeploymentPropertyOverride() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deploymentServiceAccountName", "overridesan");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setDeploymentServiceAccountName("defaultsan");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("overridesan"));
	}

	@Test
	public void deployWithTolerations() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(),
				new HashMap<>());

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertThat(podSpec.getTolerations()).isNotEmpty();
	}

	@Test
	public void deployWithGlobalTolerations() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.tolerations",
				"[{key: 'test', value: 'true', operator: 'Equal', effect: 'NoSchedule', tolerationSeconds: 5}, "
						+ "{key: 'test2', value: 'false', operator: 'Equal', effect: 'NoSchedule', tolerationSeconds: 5}]");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 2);
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test", "Equal", 5L, "true")));
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test2", "Equal", 5L, "false")));
	}

	@Test
	public void deployWithTolerationPropertyOverride() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.tolerations",
				"[{key: 'test', value: 'true', operator: 'Equal', effect: 'NoSchedule', tolerationSeconds: 5}, "
						+ "{key: 'test2', value: 'false', operator: 'Equal', effect: 'NoSchedule', tolerationSeconds: 5}]");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties.Toleration toleration = new KubernetesDeployerProperties.Toleration();
		toleration.setEffect("NoSchedule");
		toleration.setKey("test");
		toleration.setOperator("Equal");
		toleration.setTolerationSeconds(5L);
		toleration.setValue("false");

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.getTolerations().add(toleration);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 2);
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test", "Equal", 5L, "true")));
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test2", "Equal", 5L, "false")));
	}

	@Test
	public void deployWithDuplicateTolerationKeyPropertyOverride() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.tolerations",
				"[{key: 'test', value: 'true', operator: 'Equal', effect: 'NoSchedule', tolerationSeconds: 5}]");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties.Toleration toleration = new KubernetesDeployerProperties.Toleration();
		toleration.setEffect("NoSchedule");
		toleration.setKey("test");
		toleration.setOperator("Equal");
		toleration.setTolerationSeconds(5L);
		toleration.setValue("false");

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.getTolerations().add(toleration);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 1);
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test2", "Equal", 5L, "false")));
	}

	@Test
	public void deployWithDuplicateGlobalToleration() {
		AppDefinition definition = new AppDefinition("app-test", null);

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		KubernetesDeployerProperties.Toleration toleration1 = new KubernetesDeployerProperties.Toleration();
		toleration1.setEffect("NoSchedule");
		toleration1.setKey("test");
		toleration1.setOperator("Equal");
		toleration1.setTolerationSeconds(5L);
		toleration1.setValue("false");

		kubernetesDeployerProperties.getTolerations().add(toleration1);

		KubernetesDeployerProperties.Toleration toleration2 = new KubernetesDeployerProperties.Toleration();
		toleration2.setEffect("NoSchedule");
		toleration2.setKey("test");
		toleration2.setOperator("Equal");
		toleration2.setTolerationSeconds(5L);
		toleration2.setValue("true");

		kubernetesDeployerProperties.getTolerations().add(toleration2);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 1);
		assertThat(podSpec.getTolerations().contains(new Toleration("NoSchedule", "test2", "Equal", 5L, "true")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidDeploymentLabelDelimiter() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1|value1");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);
		this.deploymentPropertiesResolver.getDeploymentLabels(appDeploymentRequest.getDeploymentProperties());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidMultipleDeploymentLabelDelimiter() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1:value1 label2:value2");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		this.deploymentPropertiesResolver.getDeploymentLabels(appDeploymentRequest.getDeploymentProperties());
	}

	@Test
	public void testDeploymentLabels() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1:value1,label2:value2");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		Map<String, String> deploymentLabels = this.deploymentPropertiesResolver.getDeploymentLabels(appDeploymentRequest.getDeploymentProperties());

		assertTrue("Deployment labels should not be empty", !deploymentLabels.isEmpty());
		assertEquals("Invalid number of labels", 2, deploymentLabels.size());
		assertTrue("Expected label 'label1' not found", deploymentLabels.containsKey("label1"));
		assertEquals("Invalid value for 'label1'", "value1", deploymentLabels.get("label1"));
		assertTrue("Expected label 'label2' not found", deploymentLabels.containsKey("label2"));
		assertEquals("Invalid value for 'label2'", "value2", deploymentLabels.get("label2"));
	}

	@Test
	public void testSecretKeyRef() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretKeyRefs",
				"[{envVarName: 'SECRET_PASSWORD', secretName: 'mySecret', dataKey: 'password'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 2, envVars.size());

		EnvVar secretKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "SECRET_PASSWORD", secretKeyRefEnvVar.getName());
		SecretKeySelector secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "password", secretKeySelector.getKey());
	}

	@Test
	public void testSecretKeyRefMultiple() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretKeyRefs",
				"[{envVarName: 'SECRET_PASSWORD', secretName: 'mySecret', dataKey: 'password'}," +
						"{envVarName: 'SECRET_USERNAME', secretName: 'mySecret2', dataKey: 'username'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 3, envVars.size());

		EnvVar secretKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "SECRET_PASSWORD", secretKeyRefEnvVar.getName());
		SecretKeySelector secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "password", secretKeySelector.getKey());

		secretKeyRefEnvVar = envVars.get(1);
		assertEquals("Unexpected env var name", "SECRET_USERNAME", secretKeyRefEnvVar.getName());
		secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret2", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "username", secretKeySelector.getKey());
	}

	@Test
	public void testSecretKeyRefGlobal() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		KubernetesDeployerProperties.SecretKeyRef secretKeyRef = new KubernetesDeployerProperties.SecretKeyRef();
		secretKeyRef.setEnvVarName("SECRET_PASSWORD_GLOBAL");
		secretKeyRef.setSecretName("mySecretGlobal");
		secretKeyRef.setDataKey("passwordGlobal");
		kubernetesDeployerProperties.setSecretKeyRefs(Collections.singletonList(secretKeyRef));

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 2, envVars.size());

		EnvVar secretKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "SECRET_PASSWORD_GLOBAL", secretKeyRefEnvVar.getName());
		SecretKeySelector secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecretGlobal", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "passwordGlobal", secretKeySelector.getKey());
	}

	@Test
	public void testSecretKeyRefPropertyOverride() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.secretKeyRefs",
				"[{envVarName: 'SECRET_PASSWORD_GLOBAL', secretName: 'mySecret', dataKey: 'password'}," +
						"{envVarName: 'SECRET_USERNAME', secretName: 'mySecret2', dataKey: 'username'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		List<KubernetesDeployerProperties.SecretKeyRef> globalSecretKeyRefs = new ArrayList<>();
		KubernetesDeployerProperties.SecretKeyRef globalSecretKeyRef1 = new KubernetesDeployerProperties.SecretKeyRef();
		globalSecretKeyRef1.setEnvVarName("SECRET_PASSWORD_GLOBAL");
		globalSecretKeyRef1.setSecretName("mySecretGlobal");
		globalSecretKeyRef1.setDataKey("passwordGlobal");

		KubernetesDeployerProperties.SecretKeyRef globalSecretKeyRef2 = new KubernetesDeployerProperties.SecretKeyRef();
		globalSecretKeyRef2.setEnvVarName("SECRET_USERNAME_GLOBAL");
		globalSecretKeyRef2.setSecretName("mySecretGlobal");
		globalSecretKeyRef2.setDataKey("usernameGlobal");

		globalSecretKeyRefs.add(globalSecretKeyRef1);
		globalSecretKeyRefs.add(globalSecretKeyRef2);

		kubernetesDeployerProperties.setSecretKeyRefs(globalSecretKeyRefs);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 4, envVars.size());

		// deploy prop overrides global
		EnvVar secretKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "SECRET_PASSWORD_GLOBAL", secretKeyRefEnvVar.getName());
		SecretKeySelector secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "password", secretKeySelector.getKey());

		// unique deploy prop
		secretKeyRefEnvVar = envVars.get(1);
		assertEquals("Unexpected env var name", "SECRET_USERNAME", secretKeyRefEnvVar.getName());
		secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret2", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "username", secretKeySelector.getKey());

		// unique, non-overridden global prop
		secretKeyRefEnvVar = envVars.get(2);
		assertEquals("Unexpected env var name", "SECRET_USERNAME_GLOBAL", secretKeyRefEnvVar.getName());
		secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecretGlobal", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "usernameGlobal", secretKeySelector.getKey());
	}

	@Test
	public void testSecretKeyRefGlobalFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 3, envVars.size());

		EnvVar secretKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "SECRET_PASSWORD", secretKeyRefEnvVar.getName());
		SecretKeySelector secretKeySelector = secretKeyRefEnvVar.getValueFrom().getSecretKeyRef();
		assertEquals("Unexpected secret name", "mySecret", secretKeySelector.getName());
		assertEquals("Unexpected secret data key", "myPassword", secretKeySelector.getKey());
	}

	@Test
	public void testConfigMapKeyRef() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.configMapKeyRefs",
				"[{envVarName: 'MY_ENV', configMapName: 'myConfigMap', dataKey: 'envName'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 2, envVars.size());

		EnvVar configMapKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "MY_ENV", configMapKeyRefEnvVar.getName());
		ConfigMapKeySelector configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "envName", configMapKeySelector.getKey());
	}

	@Test
	public void testConfigMapKeyRefMultiple() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.configMapKeyRefs",
				"[{envVarName: 'MY_ENV', configMapName: 'myConfigMap', dataKey: 'envName'}," +
						"{envVarName: 'ENV_VALUES', configMapName: 'myOtherConfigMap', dataKey: 'diskType'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 3, envVars.size());

		EnvVar configMapKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "MY_ENV", configMapKeyRefEnvVar.getName());
		ConfigMapKeySelector configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "envName", configMapKeySelector.getKey());

		configMapKeyRefEnvVar = envVars.get(1);
		assertEquals("Unexpected env var name", "ENV_VALUES", configMapKeyRefEnvVar.getName());
		configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myOtherConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "diskType", configMapKeySelector.getKey());
	}

	@Test
	public void testConfigMapKeyRefGlobal() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		KubernetesDeployerProperties.ConfigMapKeyRef configMapKeyRef = new KubernetesDeployerProperties.ConfigMapKeyRef();
		configMapKeyRef.setEnvVarName("MY_ENV_GLOBAL");
		configMapKeyRef.setConfigMapName("myConfigMapGlobal");
		configMapKeyRef.setDataKey("envGlobal");
		kubernetesDeployerProperties.setConfigMapKeyRefs(Collections.singletonList(configMapKeyRef));

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 2, envVars.size());

		EnvVar configMapKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "MY_ENV_GLOBAL", configMapKeyRefEnvVar.getName());
		ConfigMapKeySelector configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myConfigMapGlobal", configMapKeySelector.getName());
		assertEquals("Unexpected config data key", "envGlobal", configMapKeySelector.getKey());
	}

	@Test
	public void testConfigMapKeyRefPropertyOverride() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.configMapKeyRefs",
				"[{envVarName: 'MY_ENV', configMapName: 'myConfigMap', dataKey: 'envName'}," +
						"{envVarName: 'ENV_VALUES', configMapName: 'myOtherConfigMap', dataKey: 'diskType'}]");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		List<KubernetesDeployerProperties.ConfigMapKeyRef> globalConfigMapKeyRefs = new ArrayList<>();
		KubernetesDeployerProperties.ConfigMapKeyRef globalConfigMapKeyRef1 = new KubernetesDeployerProperties.ConfigMapKeyRef();
		globalConfigMapKeyRef1.setEnvVarName("MY_ENV");
		globalConfigMapKeyRef1.setConfigMapName("myEnvGlobal");
		globalConfigMapKeyRef1.setDataKey("envGlobal");

		KubernetesDeployerProperties.ConfigMapKeyRef globalConfigMapKeyRef2 = new KubernetesDeployerProperties.ConfigMapKeyRef();
		globalConfigMapKeyRef2.setEnvVarName("MY_VALS_GLOBAL");
		globalConfigMapKeyRef2.setConfigMapName("myValsGlobal");
		globalConfigMapKeyRef2.setDataKey("valsGlobal");

		globalConfigMapKeyRefs.add(globalConfigMapKeyRef1);
		globalConfigMapKeyRefs.add(globalConfigMapKeyRef2);

		kubernetesDeployerProperties.setConfigMapKeyRefs(globalConfigMapKeyRefs);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 4, envVars.size());

		// deploy prop overrides global
		EnvVar configMapKeyRefEnvVar = envVars.get(0);
		assertEquals("Unexpected env var name", "MY_ENV", configMapKeyRefEnvVar.getName());
		ConfigMapKeySelector configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "envName", configMapKeySelector.getKey());

		// unique deploy prop
		configMapKeyRefEnvVar = envVars.get(1);
		assertEquals("Unexpected env var name", "ENV_VALUES", configMapKeyRefEnvVar.getName());
		configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myOtherConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "diskType", configMapKeySelector.getKey());

		// unique, non-overridden global prop
		configMapKeyRefEnvVar = envVars.get(2);
		assertEquals("Unexpected env var name", "MY_VALS_GLOBAL", configMapKeyRefEnvVar.getName());
		configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myValsGlobal", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "valsGlobal", configMapKeySelector.getKey());
	}

	@Test
	public void testConfigMapKeyRefGlobalFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		List<EnvVar> envVars = podSpec.getContainers().get(0).getEnv();

		assertEquals("Invalid number of env vars", 3, envVars.size());

		EnvVar configMapKeyRefEnvVar = envVars.get(1);
		assertEquals("Unexpected env var name", "MY_ENV", configMapKeyRefEnvVar.getName());
		ConfigMapKeySelector configMapKeySelector = configMapKeyRefEnvVar.getValueFrom().getConfigMapKeyRef();
		assertEquals("Unexpected config map name", "myConfigMap", configMapKeySelector.getName());
		assertEquals("Unexpected config map data key", "envName", configMapKeySelector.getKey());
	}

	@Test
	public void testPodSecurityContextProperty() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.podSecurityContext", "{runAsUser: 65534, fsGroup: 65534}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertEquals("Unexpected run as user", Long.valueOf("65534"), podSecurityContext.getRunAsUser());
		assertEquals("Unexpected fs group", Long.valueOf("65534"), podSecurityContext.getFsGroup());
	}

	@Test
	public void testNodeAffinityProperty() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.nodeAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { nodeSelectorTerms:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'kubernetes.io/e2e-az-name', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'e2e-az1', 'e2e-az2']}]}]}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      preference:" +
						"      { matchExpressions:" +
						"        [ { key: 'another-node-label-key'," +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'another-node-label-value' ]}]}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		NodeAffinity nodeAffinity = podSpec.getAffinity().getNodeAffinity();
		assertNotNull("Node affinity should not be null", nodeAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", nodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, nodeAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAffinityProperty() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.podAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { labelSelector:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'app', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'store']}]}], " +
						"     topologyKey: 'kubernetes.io/hostname'}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      podAffinityTerm:" +
						"      { labelSelector:" +
						"        { matchExpressions:" +
						"          [ { key: 'security'," +
						"              operator: 'In'," +
						"              values:" +
						"              [ 'S2' ]}]}, " +
						"        topologyKey: 'failure-domain.beta.kubernetes.io/zone'}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAffinity podAffinity = podSpec.getAffinity().getPodAffinity();
		assertNotNull("Pod affinity should not be null", podAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAntiAffinityProperty() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.podAntiAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { labelSelector:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'app', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'store']}]}], " +
						"     topologyKey: 'kubernetes.io/hostname'}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      podAffinityTerm:" +
						"      { labelSelector:" +
						"        { matchExpressions:" +
						"          [ { key: 'security'," +
						"              operator: 'In'," +
						"              values:" +
						"              [ 'S2' ]}]}, " +
						"        topologyKey: 'failure-domain.beta.kubernetes.io/zone'}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAntiAffinity podAntiAffinity = podSpec.getAffinity().getPodAntiAffinity();
		assertNotNull("Pod anti-affinity should not be null", podAntiAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAntiAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAntiAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodSecurityContextGlobalProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		KubernetesDeployerProperties.PodSecurityContext securityContext = new KubernetesDeployerProperties.PodSecurityContext();
		securityContext.setFsGroup(65534L);
		securityContext.setRunAsUser(65534L);

		kubernetesDeployerProperties.setPodSecurityContext(securityContext);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertEquals("Unexpected run as user", Long.valueOf("65534"), podSecurityContext.getRunAsUser());
		assertEquals("Unexpected fs group", Long.valueOf("65534"), podSecurityContext.getFsGroup());
	}

	@Test
	public void testNodeAffinityGlobalProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		NodeSelectorTerm nodeSelectorTerm = new NodeSelectorTerm();
		nodeSelectorTerm.setMatchExpressions(Arrays.asList(new NodeSelectorRequirementBuilder()
				.withKey("kubernetes.io/e2e-az-name")
				.withOperator("In")
				.withValues("e2e-az1", "e2e-az2")
				.build()));
		NodeSelectorTerm nodeSelectorTerm2 = new NodeSelectorTerm();
		nodeSelectorTerm2.setMatchExpressions(Arrays.asList(new NodeSelectorRequirementBuilder()
				.withKey("another-node-label-key")
				.withOperator("In")
				.withValues("another-node-label-value2")
				.build()));
		PreferredSchedulingTerm preferredSchedulingTerm = new PreferredSchedulingTerm(nodeSelectorTerm2, 1);
		NodeAffinity nodeAffinity = new AffinityBuilder()
				.withNewNodeAffinity()
				.withNewRequiredDuringSchedulingIgnoredDuringExecution()
				.withNodeSelectorTerms(nodeSelectorTerm)
				.endRequiredDuringSchedulingIgnoredDuringExecution()
				.withPreferredDuringSchedulingIgnoredDuringExecution(preferredSchedulingTerm)
				.endNodeAffinity()
				.buildNodeAffinity();

		kubernetesDeployerProperties.setNodeAffinity(nodeAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		NodeAffinity nodeAffinityTest = podSpec.getAffinity().getNodeAffinity();
		assertNotNull("Node affinity should not be null", nodeAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", nodeAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, nodeAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAffinityGlobalProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		LabelSelector labelSelector = new LabelSelector();
		labelSelector.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("security")
				.withOperator("In")
				.withValues("S1")
				.build()));
		PodAffinityTerm podAffinityTerm = new PodAffinityTerm(labelSelector, null, "failure-domain.beta.kubernetes.io/zone");
		LabelSelector labelSelector2 = new LabelSelector();
		labelSelector2.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("security")
				.withOperator("In")
				.withValues("s2")
				.build()));
		PodAffinityTerm podAffinityTerm2 = new PodAffinityTerm(labelSelector2, null, "failure-domain.beta.kubernetes.io/zone");
		WeightedPodAffinityTerm weightedPodAffinityTerm = new WeightedPodAffinityTerm(podAffinityTerm2, 100);
		PodAffinity podAffinity = new AffinityBuilder()
				.withNewPodAffinity()
				.withRequiredDuringSchedulingIgnoredDuringExecution(podAffinityTerm)
				.withPreferredDuringSchedulingIgnoredDuringExecution(weightedPodAffinityTerm)
				.endPodAffinity()
				.buildPodAffinity();

		kubernetesDeployerProperties.setPodAffinity(podAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAffinity podAffinityTest = podSpec.getAffinity().getPodAffinity();
		assertNotNull("Pod affinity should not be null", podAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAntiAffinityGlobalProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		LabelSelector labelSelector = new LabelSelector();
		labelSelector.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("app")
				.withOperator("In")
				.withValues("store")
				.build()));
		PodAffinityTerm podAffinityTerm = new PodAffinityTerm(labelSelector, null, "kubernetes.io/hostname");
		LabelSelector labelSelector2 = new LabelSelector();
		labelSelector2.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("security")
				.withOperator("In")
				.withValues("s2")
				.build()));
		PodAffinityTerm podAffinityTerm2 = new PodAffinityTerm(labelSelector2, null, "failure-domain.beta.kubernetes.io/zone");
		WeightedPodAffinityTerm weightedPodAffinityTerm = new WeightedPodAffinityTerm(podAffinityTerm2, 100);
		PodAntiAffinity podAntiAffinity = new AffinityBuilder()
				.withNewPodAntiAffinity()
				.withRequiredDuringSchedulingIgnoredDuringExecution(podAffinityTerm)
				.withPreferredDuringSchedulingIgnoredDuringExecution(weightedPodAffinityTerm)
				.endPodAntiAffinity()
				.buildPodAntiAffinity();

		kubernetesDeployerProperties.setPodAntiAffinity(podAntiAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAntiAffinity podAntiAffinityTest = podSpec.getAffinity().getPodAntiAffinity();
		assertNotNull("Pod anti-affinity should not be null", podAntiAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAntiAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAntiAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodSecurityContextFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertEquals("Unexpected run as user", Long.valueOf("65534"), podSecurityContext.getRunAsUser());
		assertEquals("Unexpected fs group", Long.valueOf("65534"), podSecurityContext.getFsGroup());
	}

	@Test
	public void testNodeAffinityFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		NodeAffinity nodeAffinity = podSpec.getAffinity().getNodeAffinity();
		assertNotNull("Node affinity should not be null", nodeAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", nodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, nodeAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAffinityFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAffinity podAffinity = podSpec.getAffinity().getPodAffinity();
		assertNotNull("Pod affinity should not be null", podAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAntiAffinityFromYaml() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAntiAffinity podAntiAffinity = podSpec.getAffinity().getPodAntiAffinity();
		assertNotNull("Pod anti-affinity should not be null", podAntiAffinity);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAntiAffinity.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAntiAffinity.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodSecurityContextUIDOnly() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.podSecurityContext", "{runAsUser: 65534}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertEquals("Unexpected run as user", Long.valueOf("65534"), podSecurityContext.getRunAsUser());
		assertNull("Unexpected fs group", podSecurityContext.getFsGroup());
	}

	@Test
	public void testPodSecurityContextFsGroupOnly() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.podSecurityContext", "{fsGroup: 65534}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertNull("Unexpected run as user", podSecurityContext.getRunAsUser());
		assertEquals("Unexpected fs group", Long.valueOf("65534"), podSecurityContext.getFsGroup());
	}

	@Test
	public void testPodSecurityContextPropertyOverrideGlobal() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.podSecurityContext", "{runAsUser: 65534, fsGroup: 65534}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		KubernetesDeployerProperties.PodSecurityContext securityContext = new KubernetesDeployerProperties.PodSecurityContext();
		securityContext.setFsGroup(1000L);
		securityContext.setRunAsUser(1000L);

		kubernetesDeployerProperties.setPodSecurityContext(securityContext);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodSecurityContext podSecurityContext = podSpec.getSecurityContext();

		assertNotNull("Pod security context should not be null", podSecurityContext);

		assertEquals("Unexpected run as user", Long.valueOf("65534"), podSecurityContext.getRunAsUser());
		assertEquals("Unexpected fs group", Long.valueOf("65534"), podSecurityContext.getFsGroup());
	}

	@Test
	public void testNodeAffinityPropertyOverrideGlobal() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.nodeAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { nodeSelectorTerms:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'kubernetes.io/e2e-az-name', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'e2e-az1', 'e2e-az2']}]}]}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      preference:" +
						"      { matchExpressions:" +
						"        [ { key: 'another-node-label-key'," +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'another-node-label-value' ]}]}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		NodeSelectorTerm nodeSelectorTerm = new NodeSelectorTerm();
		nodeSelectorTerm.setMatchExpressions(Arrays.asList(new NodeSelectorRequirementBuilder()
				.withKey("kubernetes.io/e2e-az-name")
				.withOperator("In")
				.withValues("e2e-az1", "e2e-az2")
				.build()));
		NodeAffinity nodeAffinity = new AffinityBuilder()
				.withNewNodeAffinity()
				.withNewRequiredDuringSchedulingIgnoredDuringExecution()
				.withNodeSelectorTerms(nodeSelectorTerm)
				.endRequiredDuringSchedulingIgnoredDuringExecution()
				.endNodeAffinity()
				.buildNodeAffinity();

		kubernetesDeployerProperties.setNodeAffinity(nodeAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		NodeAffinity nodeAffinityTest = podSpec.getAffinity().getNodeAffinity();
		assertNotNull("Node affinity should not be null", nodeAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", nodeAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, nodeAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAffinityPropertyOverrideGlobal() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.podAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { labelSelector:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'security', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'S1']}]}], " +
						"     topologyKey: 'failure-domain.beta.kubernetes.io/zone'}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      podAffinityTerm:" +
						"      { labelSelector:" +
						"        { matchExpressions:" +
						"          [ { key: 'security'," +
						"              operator: 'In'," +
						"              values:" +
						"              [ 'S2' ]}]}, " +
						"        topologyKey: 'failure-domain.beta.kubernetes.io/zone'}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		LabelSelector labelSelector = new LabelSelector();
		labelSelector.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("tolerance")
				.withOperator("In")
				.withValues("Reliable")
				.build()));
		PodAffinityTerm podAffinityTerm = new PodAffinityTerm(labelSelector, null, "failure-domain.beta.kubernetes.io/zone");
		PodAffinity podAffinity = new AffinityBuilder()
				.withNewPodAffinity()
				.withRequiredDuringSchedulingIgnoredDuringExecution(podAffinityTerm)
				.endPodAffinity()
				.buildPodAffinity();

		kubernetesDeployerProperties.setPodAffinity(podAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAffinity podAffinityTest = podSpec.getAffinity().getPodAffinity();
		assertNotNull("Pod affinity should not be null", podAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	@Test
	public void testPodAntiAffinityPropertyOverrideGlobal() {
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.affinity.podAntiAffinity",
				"{ requiredDuringSchedulingIgnoredDuringExecution:" +
						"  { labelSelector:" +
						"    [ { matchExpressions:" +
						"        [ { key: 'app', " +
						"            operator: 'In'," +
						"            values:" +
						"            [ 'store']}]}], " +
						"     topologyKey: 'kubernetes.io/hostnam'}, " +
						"  preferredDuringSchedulingIgnoredDuringExecution:" +
						"  [ { weight: 1," +
						"      podAffinityTerm:" +
						"      { labelSelector:" +
						"        { matchExpressions:" +
						"          [ { key: 'security'," +
						"              operator: 'In'," +
						"              values:" +
						"              [ 'S2' ]}]}, " +
						"        topologyKey: 'failure-domain.beta.kubernetes.io/zone'}}]}");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();

		LabelSelector labelSelector = new LabelSelector();
		labelSelector.setMatchExpressions(Arrays.asList(new LabelSelectorRequirementBuilder()
				.withKey("version")
				.withOperator("Equals")
				.withValues("v1")
				.build()));
		PodAffinityTerm podAffinityTerm = new PodAffinityTerm(labelSelector, null, "kubernetes.io/hostnam");
		PodAntiAffinity podAntiAffinity = new AffinityBuilder()
				.withNewPodAntiAffinity()
				.withRequiredDuringSchedulingIgnoredDuringExecution(podAffinityTerm)
				.endPodAntiAffinity()
				.buildPodAntiAffinity();

		kubernetesDeployerProperties.setPodAntiAffinity(podAntiAffinity);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec(appDeploymentRequest);

		PodAntiAffinity podAntiAffinityTest = podSpec.getAffinity().getPodAntiAffinity();
		assertNotNull("Pod anti-affinity should not be null", podAntiAffinityTest);
		assertNotNull("RequiredDuringSchedulingIgnoredDuringExecution should not be null", podAntiAffinityTest.getRequiredDuringSchedulingIgnoredDuringExecution());
		assertEquals("PreferredDuringSchedulingIgnoredDuringExecution should have one element", 1, podAntiAffinityTest.getPreferredDuringSchedulingIgnoredDuringExecution().size());
	}

	private Resource getResource() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private KubernetesDeployerProperties bindDeployerProperties() throws Exception {
		YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
		properties.setResources(new ClassPathResource("dataflow-server.yml"),
				new ClassPathResource("dataflow-server-tolerations.yml"),
				new ClassPathResource("dataflow-server-secretKeyRef.yml"),
				new ClassPathResource("dataflow-server-configMapKeyRef.yml"),
				new ClassPathResource("dataflow-server-podsecuritycontext.yml"),
				new ClassPathResource("dataflow-server-nodeAffinity.yml"),
				new ClassPathResource("dataflow-server-podAffinity.yml"),
				new ClassPathResource("dataflow-server-podAntiAffinity.yml"));
		Properties yaml = properties.getObject();
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
		return new Binder(source).bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
	}
}
