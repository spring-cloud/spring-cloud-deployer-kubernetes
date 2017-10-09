/*
 * Copyright 2015-2017 the original author or authors.
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

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

import io.fabric8.kubernetes.api.model.Container;

/**
 * Defines how a Kubernetes {@link Container} is created.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author David Turanski
 */
public interface ContainerFactory {

	/**
	 * @deprecated use create(String appId, AppDeploymentRequest request, Integer externalPort,boolean hostNetwork).
	 */
	@Deprecated
	Container create(String appId, AppDeploymentRequest request, Integer externalPort, Integer instanceIndex,
	                 boolean hostNetwork);

	/**
	 *
	 * @param appId the application Id
	 * @param request the {@link AppDeploymentRequest}
	 * @param externalPort the external port
	 * @param hostNetwork true if the application should use the host network
	 * @return a {@link Container}
	 */
	Container create(String appId, AppDeploymentRequest request, Integer externalPort, boolean hostNetwork);

}
