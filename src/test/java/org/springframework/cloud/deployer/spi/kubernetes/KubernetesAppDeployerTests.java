/*
 * Copyright 2015-2018 the original author or authors.
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

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link KubernetesAppDeployer}
 *
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @author Chris Schaefer
 */
public class KubernetesAppDeployerTests {

	private KubernetesAppDeployer deployer;

	@Test
	public void deployWithVolumesOnly() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(),
			new HashMap<>());

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getVolumes()).isEmpty();
	}

	@Test
	public void deployWithTolerations() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(),
				new HashMap<>());

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getTolerations()).isNotEmpty();
	}

	@Test
	public void deployWithVolumesAndVolumeMounts() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts", "[" + "{name: 'testpvc', mountPath: '/test/pvc'}, "
			+ "{name: 'testnfs', mountPath: '/test/nfs', readOnly: 'true'}" + "]");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

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
		podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		HostPathVolumeSource hostPathVolumeSource = new HostPathVolumeSourceBuilder()
				.withPath("/test/override/hostPath").build();

		assertThat(podSpec.getVolumes()).containsOnly(
			new VolumeBuilder().withName("testhostpath").withHostPath(hostPathVolumeSource).build(),
			new VolumeBuilder().withName("testpvc").withNewPersistentVolumeClaim("testClaim", true).build(),
			new VolumeBuilder().withName("testnfs").withNewNfs("/test/override/nfs", null, "192.168.1.1:111").build());
	}

	@Test
	public void deployWithNodeSelector() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deployment.nodeSelector", "disktype:ssd, os: linux");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getNodeSelector()).containsOnly(entry("disktype", "ssd"), entry("os", "linux"));
	}

	@Test
	public void deployWithEnvironmentWithCommaDelimitedValue() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
			"foo='bar,baz',car=caz,boo='zoo,gnu',doo=dar");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getContainers().get(0).getEnv())
			.contains(
				new EnvVar("foo", "bar,baz", null),
				new EnvVar("car", "caz", null),
				new EnvVar("boo", "zoo,gnu", null),
				new EnvVar("doo", "dar", null));
	}

	@Test
	public void deployWithImagePullSecretDeploymentProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.imagePullSecret", "regcred");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

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
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

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
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

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
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

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
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("overridesan"));
	}

	@Test
	public void deployWithGlobalTolerations() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.tolerations", "[{key: 'test', value: 'true', operator: 'Equal'}, "
				+ "{key: 'test2', value: 'false', operator: 'Equal'}]");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 2);
		assertThat(podSpec.getTolerations().contains(new Toleration(null,"test","Equal",null,"true")));
	}

	@Test
	public void deployWithTolerationPropertyOverride() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.tolerations", "[{key: 'test', value: 'true', operator: 'Equal'}, "
				+ "{key: 'test2', value: 'false', operator: 'Equal'}]");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties.Toleration toleration = new KubernetesDeployerProperties.Toleration();
		toleration.setKey("test");
		toleration.setValue("false");
		toleration.setOperator("Equal");
		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.getTolerations().add(toleration);

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getTolerations());
		assertThat(podSpec.getTolerations().size() == 2);
		assertThat(podSpec.getTolerations().contains(new Toleration(null,"test","Equal",null,"false")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidDeploymentLabelDelimiter() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1|value1");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		kubernetesAppDeployer.getDeploymentLabels(appDeploymentRequest);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidMultipleDeploymentLabelDelimiter() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1:value1 label2:value2");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		kubernetesAppDeployer.getDeploymentLabels(appDeploymentRequest);
	}

	@Test
	public void testDeploymentLabels() {
		Map<String, String> props = Collections.singletonMap("spring.cloud.deployer.kubernetes.deploymentLabels",
				"label1:value1,label2:value2");

		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesAppDeployer kubernetesAppDeployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		Map<String, String> deploymentLabels = kubernetesAppDeployer.getDeploymentLabels(appDeploymentRequest);

		assertTrue("Deployment labels should not be empty", !deploymentLabels.isEmpty());
		assertEquals("Invalid number of labels", 2, deploymentLabels.size());
		assertTrue("Expected label 'label1' not found", deploymentLabels.containsKey("label1"));
		assertEquals("Invalid value for 'label1'", "value1", deploymentLabels.get("label1"));
		assertTrue("Expected label 'label2' not found", deploymentLabels.containsKey("label2"));
		assertEquals("Invalid value for 'label2'", "value2", deploymentLabels.get("label2"));
	}

	private Resource getResource() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private KubernetesDeployerProperties bindDeployerProperties() throws Exception {
		YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
		properties.setResources(new ClassPathResource("dataflow-server.yml"), new ClassPathResource("dataflow-server-tolerations.yml"));
		Properties yaml = properties.getObject();
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
		return new Binder(source).bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
	}
}
