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
import org.springframework.util.Assert;

/**
 * Create a Kubernetes {@link Container} that will be started as part of a
 * Kubernetes Pod by launching the specified Docker image.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
public class DefaultContainerFactory implements ContainerFactory {

	private static Logger logger = LoggerFactory.getLogger(DefaultContainerFactory.class);

	private static final String LIVENESS_ENDPOINT = "/health";

	private static final String READINESS_ENDPOINT = "/info";

	private final KubernetesDeployerProperties properties;

	public DefaultContainerFactory(KubernetesDeployerProperties properties) {
		this.properties = properties;
	}

	@Override
	public Container create(String appId, AppDeploymentRequest request, Integer port, int index) {
		ContainerBuilder container = new ContainerBuilder();
		String image = null;
		//TODO: what's the proper format for a Docker URI?
		try {
			image = request.getResource().getURI().getSchemeSpecificPart();
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + request.getResource(), e);
		}
		logger.info("Using Docker image: " + image);

		List<EnvVar> envVars = new ArrayList<>();
		for (String envVar : properties.getEnvironmentVariables()) {
			String[] strings = envVar.split("=", 2);
			Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
			envVars.add(new EnvVar(strings[0], strings[1], null));
		}
		envVars.add(new EnvVar("INSTANCE_INDEX", String.valueOf(index), null));

		int containerPort = port + index;
		container.withName(appId + "-" + index)
				.withImage(image)
				.withEnv(envVars)
				.withArgs(createCommandArgs(request, containerPort));
		if (port != null) {
			container.addNewPort()
					.withContainerPort(containerPort)
				.endPort()
				.withReadinessProbe(
						createProbe(port, READINESS_ENDPOINT, properties.getReadinessProbeTimeout(),
								properties.getReadinessProbeDelay(), properties.getReadinessProbePeriod()))
				.withLivenessProbe(
						createProbe(port, LIVENESS_ENDPOINT, properties.getLivenessProbeTimeout(),
								properties.getLivenessProbeDelay(), properties.getLivenessProbePeriod()));
		}
		return container.build();
	}

	/**
	 * Create a readiness probe for the /health endpoint exposed by each module.
	 */
	protected Probe createProbe(Integer externalPort, String endpoint, int timeout, int initialDelay, int period) {
		return new ProbeBuilder()
			.withHttpGet(
				new HTTPGetActionBuilder()
					.withPath(endpoint)
					.withNewPort(externalPort)
					.build()
			)
			.withTimeoutSeconds(timeout)
			.withInitialDelaySeconds(initialDelay)
			.withPeriodSeconds(period)
			.build();
	}

	/**
	 * Create command arguments
	 */
	protected List<String> createCommandArgs(AppDeploymentRequest request, int port) {
		List<String> cmdArgs = new LinkedList<String>();
		// add properties from deployment request
		Map<String, String> args = request.getDefinition().getProperties();
		for (Map.Entry<String, String> entry : args.entrySet()) {
			if (!"server.port".equals(entry)) {
				cmdArgs.add(String.format("--%s=%s", entry.getKey(), entry.getValue()));
			}
		}
		cmdArgs.add(String.format("--%s=%s", "server.port", port));
		// add provided command line args
		cmdArgs.addAll(request.getCommandlineArguments());
		logger.debug("Using command args: " + cmdArgs);
		return cmdArgs;
	}

}
