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
package org.springframework.cloud.deployer.spi.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;

/**
 * @author Ilayaperumal Gopinathan
 * @author Chris Schaefer
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {KubernetesConfigurationPropertiesTests.TestConfig.class}, properties = {
		"spring.cloud.deployer.kubernetes.fabric8.trustCerts=true",
		"spring.cloud.deployer.kubernetes.fabric8.masterUrl=http://localhost:8090",
		"spring.cloud.deployer.kubernetes.fabric8.namespace=testing"
})
public class KubernetesConfigurationPropertiesTests {

	@Autowired
	private KubernetesClient kubernetesClient;

	@Test
	public void testFabric8Properties() {
		assertEquals("http://localhost:8090", kubernetesClient.getMasterUrl().toString());
		assertEquals("testing", kubernetesClient.getNamespace());
		assertEquals("http://localhost:8090", kubernetesClient.getConfiguration().getMasterUrl());
		assertEquals(Boolean.TRUE, kubernetesClient.getConfiguration().isTrustCerts());
	}

	@Configuration
	@EnableConfigurationProperties(KubernetesDeployerProperties.class)
	@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
	public static class TestConfig {

		@Autowired
		private KubernetesDeployerProperties properties;

		@Bean
		public KubernetesClient kubernetesClient() {
			return KubernetesClientFactory.getKubernetesClient(this.properties);
		}
	}
}
