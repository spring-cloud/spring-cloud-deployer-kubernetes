package org.springframework.cloud.deployer.spi.kubernetes;

import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AbstractKubernetesDeployerTest {

	private AbstractKubernetesDeployer kubernetesDeployer;
	private AppDeploymentRequest deploymentRequest;
	private Map<String, String> deploymentProperties;
	private KubernetesDeployerProperties serverProperties;
	private KubernetesDeployerProperties.Resources serverLimits;
	private KubernetesDeployerProperties.Resources serverRequests;

	@Before
	public void setUp() throws Exception {
		kubernetesDeployer = new AbstractKubernetesDeployer();
		deploymentProperties = new HashMap<String, String>();
		deploymentRequest = new AppDeploymentRequest(new AppDefinition("foo", Collections.emptyMap()), new FileSystemResource(""), deploymentProperties);
		serverProperties = new KubernetesDeployerProperties();
		serverLimits = new KubernetesDeployerProperties.Resources();
		serverProperties.setLimits(serverLimits);
		serverRequests = new KubernetesDeployerProperties.Resources();
		serverProperties.setRequests(serverRequests);
	}

	@Test
	public void limitCpu_noDeploymentProperty_deprecatedServerProperty_usesServerProperty() throws Exception {
		serverProperties.setCpu("500m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("cpu"), is(new Quantity("500m")));
	}

	@Test
	public void limitCpu_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverLimits.setCpu("400m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void limitMemory_noDeploymentProperty_deprecatedServerProperty_usesServerProperty() throws Exception {
		serverProperties.setMemory("640Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("memory"), is(new Quantity("640Mi")));
	}

	@Test
	public void limitMemory_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverLimits.setMemory("540Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("memory"), is(new Quantity("540Mi")));
	}

	@Test
	public void limitCpu_deploymentProperty_deprecatedKey_usesDeploymentProperty() throws Exception {
		serverLimits.setCpu("100m");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.cpu", "300m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("cpu"), is(new Quantity("300m")));
	}

	@Test
	public void limitCpu_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverLimits.setCpu("100m");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.limits.cpu", "400m");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void limitMemory_deploymentProperty_deprecatedKey_usesDeploymentProperty() throws Exception {
		serverLimits.setMemory("640Mi");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.memory", "1024Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("memory"), is(new Quantity("1024Mi")));
	}

	@Test
	public void limitMemory_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverLimits.setMemory("640Mi");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.limits.memory", "256Mi");
		Map<String, Quantity> limits = kubernetesDeployer.deduceResourceLimits(serverProperties, deploymentRequest);
		assertThat(limits.get("memory"), is(new Quantity("256Mi")));
	}

	@Test
	public void requestCpu_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverRequests.setCpu("400m");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(serverProperties, deploymentRequest);
		assertThat(requests.get("cpu"), is(new Quantity("400m")));
	}

	@Test
	public void requestMemory_noDeploymentProperty_serverProperty_usesServerProperty() throws Exception {
		serverRequests.setMemory("120Mi");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(serverProperties, deploymentRequest);
		assertThat(requests.get("memory"), is(new Quantity("120Mi")));
	}

	@Test
	public void requestCpu_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverRequests.setCpu("1000m");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.requests.cpu", "461m");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(serverProperties, deploymentRequest);
		assertThat(requests.get("cpu"), is(new Quantity("461m")));
	}

	@Test
	public void requestMemory_deploymentProperty_usesDeploymentProperty() throws Exception {
		serverRequests.setMemory("640Mi");
		deploymentProperties.put("spring.cloud.deployer.kubernetes.requests.memory", "256Mi");
		Map<String, Quantity> requests = kubernetesDeployer.deduceResourceRequests(serverProperties, deploymentRequest);
		assertThat(requests.get("memory"), is(new Quantity("256Mi")));
	}

}