/*
 * Copyright 2015-2016 the original author or authors.
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

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring Bean configuration for the {@link KubernetesAppDeployer}.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author David Turanski
 */
@Configuration
@EnableConfigurationProperties(KubernetesDeployerProperties.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class KubernetesAutoConfiguration {
	
	@Autowired
	private KubernetesDeployerProperties properties;

	@Bean
	public AppDeployer appDeployer(KubernetesClient kubernetesClient,
	                               MainContainerFactory mainContainerFactory,
		                           SidecarContainerFactory sidecarContainerFactory) {
		return new KubernetesAppDeployer(properties, kubernetesClient, mainContainerFactory, sidecarContainerFactory);
	}

	@Bean
	public TaskLauncher taskDeployer(KubernetesClient kubernetesClient,
	                                 MainContainerFactory mainContainerFactory,
		                             SidecarContainerFactory sidecarContainerFactory){
		return new KubernetesTaskLauncher(properties, kubernetesClient, mainContainerFactory, sidecarContainerFactory);
	}

	@Bean
	public KubernetesClient kubernetesClient() {
		return new DefaultKubernetesClient().inNamespace(properties.getNamespace());
	}

	@Bean
	public MainContainerFactory containerFactory() {
		return new MainContainerFactory(properties);
	}

	@Bean
	public SidecarContainerFactory sidecarContainerFactory() {
		return new SidecarContainerFactory();
	}

}
