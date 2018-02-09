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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import org.junit.Test;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link KubernetesAppDeployer}
 *
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
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

		assertThat(podSpec.getVolumes()).containsOnly(
			new VolumeBuilder().withName("testhostpath").withNewHostPath("/test/override/hostPath").build(),
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
	public void createStatufulSet() throws Exception {

		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);
		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		String appId = deployer.createDeploymentId(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(appId, appDeploymentRequest);

		ObjectMapper objectMapper = new ObjectMapper();

		String statefulSetJson = deployer.createStatefulSet(appId, appDeploymentRequest, idMap, 8080);

		Map<String, Object> statefulSetMap = objectMapper.readValue(statefulSetJson, HashMap.class);

		Map<String, Object> specMap = (Map<String, Object>) statefulSetMap.get("spec");
		assertThat(specMap.get("podManagementPolicy")).isEqualTo("Parallel");

		StatefulSet statefulSet = objectMapper.readValue(statefulSetJson, StatefulSet.class);

		assertThat(statefulSet.getSpec().getReplicas()).isEqualTo(3);
		assertThat(statefulSet.getSpec().getServiceName()).isEqualTo(appId);
		assertThat(statefulSet.getMetadata().getName()).isEqualTo(appId);

		assertThat(statefulSet.getSpec().getSelector().getMatchLabels())
			.containsAllEntriesOf(deployer.createIdMap(appId, appDeploymentRequest));
		assertThat(statefulSet.getSpec().getSelector().getMatchLabels())
			.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		assertThat(statefulSet.getSpec().getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		assertThat(statefulSet.getSpec().getTemplate().getMetadata().getLabels())
			.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSet.getSpec().getTemplate().getSpec().getContainers().get(0);

		assertThat(container.getName()).isEqualTo(appId);
		assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		assertThat(container.getImage()).isEqualTo(getResource().getURI().getSchemeSpecificPart().toString());

		PersistentVolumeClaim pvc = statefulSet.getSpec().getVolumeClaimTemplates().get(0);
		assertThat(pvc.getMetadata().getName()).isEqualTo(appId);

		assertThat(pvc.getSpec().getAccessModes()).containsOnly("ReadWriteOnce");
		assertThat(pvc.getSpec().getStorageClassName()).isNull();
		assertThat(pvc.getSpec().getResources().getLimits().get("storage").getAmount()).isEqualTo("10Mi");
		assertThat(pvc.getSpec().getResources().getRequests().get("storage").getAmount()).isEqualTo("10Mi");
	}

	@Test
	public void createStatufulSetWithOverridingRequest() throws Exception {

		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storageClassName", "test");
		props.put("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storage", "1g");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);
		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		String appId = deployer.createDeploymentId(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(appId, appDeploymentRequest);

		ObjectMapper objectMapper = new ObjectMapper();

		String statefulSetJson = deployer.createStatefulSet(appId, appDeploymentRequest, idMap, 8080);

		Map<String, Object> statefulSetMap = objectMapper.readValue(statefulSetJson, HashMap.class);

		Map<String, Object> specMap = (Map<String, Object>) statefulSetMap.get("spec");
		assertThat(specMap.get("podManagementPolicy")).isEqualTo("Parallel");

		StatefulSet statefulSet = objectMapper.readValue(statefulSetJson, StatefulSet.class);

		assertThat(statefulSet.getSpec().getReplicas()).isEqualTo(3);
		assertThat(statefulSet.getSpec().getServiceName()).isEqualTo(appId);
		assertThat(statefulSet.getMetadata().getName()).isEqualTo(appId);

		assertThat(statefulSet.getSpec().getSelector().getMatchLabels())
				.containsAllEntriesOf(deployer.createIdMap(appId, appDeploymentRequest));
		assertThat(statefulSet.getSpec().getSelector().getMatchLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		assertThat(statefulSet.getSpec().getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		assertThat(statefulSet.getSpec().getTemplate().getMetadata().getLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSet.getSpec().getTemplate().getSpec().getContainers().get(0);

		assertThat(container.getName()).isEqualTo(appId);
		assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		assertThat(container.getImage()).isEqualTo(getResource().getURI().getSchemeSpecificPart().toString());

		PersistentVolumeClaim pvc = statefulSet.getSpec().getVolumeClaimTemplates().get(0);
		assertThat(pvc.getMetadata().getName()).isEqualTo(appId);

		assertThat(pvc.getSpec().getAccessModes()).containsOnly("ReadWriteOnce");
		assertThat(pvc.getSpec().getStorageClassName()).isEqualTo("test");
		assertThat(pvc.getSpec().getResources().getLimits().get("storage").getAmount()).isEqualTo("1024Mi");
		assertThat(pvc.getSpec().getResources().getRequests().get("storage").getAmount()).isEqualTo("1024Mi");
	}

	private Resource getResource() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private KubernetesDeployerProperties bindDeployerProperties() throws Exception {
		YamlConfigurationFactory<KubernetesDeployerProperties> yamlConfigurationFactory = new YamlConfigurationFactory<>(
			KubernetesDeployerProperties.class);
		yamlConfigurationFactory.setResource(new ClassPathResource("dataflow-server.yml"));
		yamlConfigurationFactory.afterPropertiesSet();
		return yamlConfigurationFactory.getObject();
	}
}
