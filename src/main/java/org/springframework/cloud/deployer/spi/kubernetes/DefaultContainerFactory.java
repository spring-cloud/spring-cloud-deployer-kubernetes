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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

/**
 * Create a Kubernetes {@link Container} that will be started as part of a
 * Kubernetes Pod by launching the specified Docker image.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
public class DefaultContainerFactory implements ContainerFactory {

	private static Logger logger = LoggerFactory.getLogger(DefaultContainerFactory.class);

	private static final String HEALTH_ENDPOINT = "/health";

	private final KubernetesAppDeployerProperties properties;

	public DefaultContainerFactory(KubernetesAppDeployerProperties properties) {
		this.properties = properties;
	}

	@Override
	public Container create(String appId, AppDeploymentRequest request, int port) {
		ContainerBuilder container = new ContainerBuilder();
		String image = null;
		//TODO: what's the proper format for a Docker URI?
		try {
			image = request.getResource().getURI().getSchemeSpecificPart();
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + request.getResource(), e);
		}
		logger.info("Using Docker image: " + image);

		//TODO: make this configurable!
		List<EnvVar> envVars = new ArrayList<>();
		envVars.add(new EnvVar("SPRING_RABBITMQ_HOST", "${RABBITMQ_SERVICE_HOST}", null));
		envVars.add(new EnvVar("SPRING_RABBITMQ_PORT", "${RABBITMQ_SERVICE_PORT}", null));
		envVars.add(new EnvVar("SPRING_CLOUD_STREAM_KAFKA_BINDER_BROKERS", "${KAFKA_SERVICE_HOST}:${KAFKA_SERVICE_PORT}", null));
		envVars.add(new EnvVar("SPRING_CLOUD_STREAM_KAFKA_BINDER_ZK_NODES", "${ZK_SERVICE_HOST}:${ZK_SERVICE_PORT}", null));

		container.withName(appId)
				.withImage(image)
				.withEnv(envVars)
				.withArgs(createCommandArgs(request))
				.addNewPort()
					.withContainerPort(port)
				.endPort()
				.withReadinessProbe(
						createProbe(port, properties.getReadinessProbeTimeout(),
								properties.getReadinessProbeDelay()))
				.withLivenessProbe(
						createProbe(port, properties.getLivenessProbeTimeout(),
								properties.getLivenessProbeDelay()));
		return container.build();
	}

	/**
	 * Create a readiness probe for the /health endpoint exposed by each module.
	 */
	protected Probe createProbe(Integer externalPort, int timeout, int initialDelay) {
		return new ProbeBuilder()
			.withHttpGet(
				new HTTPGetActionBuilder()
					.withPath(HEALTH_ENDPOINT)
					.withNewPort(externalPort)
					.build()
			)
			.withTimeoutSeconds(timeout)
			.withInitialDelaySeconds(initialDelay)
			.build();
	}

	/**
	 * Create command arguments
	 */
	protected List<String> createCommandArgs(AppDeploymentRequest request) {
		Map<String, String> args = request.getDefinition().getProperties();
		List<String> cmdArgs = new LinkedList<String>();
		for (Map.Entry<String, String> entry : args.entrySet()) {
			cmdArgs.add(String.format("--%s=%s", bashEscape(entry.getKey()),
					bashEscape(entry.getValue())));
		}
		logger.debug("Using command args: " + cmdArgs);
		return cmdArgs;
	}

	/**
	 * Escape arguments
	 */
	protected String bashEscape(String original) {
		// Adapted from http://ruby-doc.org/stdlib-1.9.3/libdoc/shellwords/rdoc/Shellwords.html#method-c-shellescape
		return original.replaceAll("([^A-Za-z0-9_\\-.,:\\/@\\n])", "\\\\$1").replaceAll("\n", "'\\\\n'");
	}
}
