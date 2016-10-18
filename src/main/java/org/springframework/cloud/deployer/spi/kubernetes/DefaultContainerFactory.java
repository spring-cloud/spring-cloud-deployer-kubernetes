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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
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

	private static Log logger = LogFactory.getLog(DefaultContainerFactory.class);

	private final KubernetesDeployerProperties properties;

	public DefaultContainerFactory(KubernetesDeployerProperties properties) {
		this.properties = properties;
	}

	@Override
	public Container create(String appId, AppDeploymentRequest request, Integer port, Integer instanceIndex) {
		String image = null;
		try {
			image = request.getResource().getURI().getSchemeSpecificPart();
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + request.getResource(), e);
		}
		logger.info("Using Docker image: " + image);

		Map<String, String> envVarsMap = new HashMap<>();
		for (String envVar : properties.getEnvironmentVariables()) {
			String[] strings = envVar.split("=", 2);
			Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
			envVarsMap.put(strings[0], strings[1]);
		}
		//Create EnvVar entries for additional variables set at the app level
		//For instance, this may be used to set JAVA_OPTS independently for each app if the base container
		//image supports it.
		envVarsMap.putAll(getAppEnvironmentVariables(request));

		String appInstanceId = instanceIndex == null ? appId : appId + "-" + instanceIndex;

		List<EnvVar> envVars = new ArrayList<>();
		for (Map.Entry<String, String> e : envVarsMap.entrySet()) {
			envVars.add(new EnvVar(e.getKey(), e.getValue(), null));
		}
		if (instanceIndex != null) {
			envVars.add(new EnvVar(AppDeployer.INSTANCE_INDEX_PROPERTY_KEY, instanceIndex.toString(), null));
		}

		ContainerBuilder container = new ContainerBuilder();
		container.withName(appInstanceId)
				.withImage(image)
				.withEnv(envVars)
				.withArgs(createCommandArgs(request));
		if (port != null) {
			container.addNewPort()
					.withContainerPort(port)
					.endPort()
					.withReadinessProbe(
							createProbe(port, properties.getReadinessProbePath(), properties.getReadinessProbeTimeout(),
									properties.getReadinessProbeDelay(), properties.getReadinessProbePeriod()))
					.withLivenessProbe(
							createProbe(port, properties.getLivenessProbePath(), properties.getLivenessProbeTimeout(),
									properties.getLivenessProbeDelay(), properties.getLivenessProbePeriod()));
		}

		//Add additional specified ports.  Further work is needed to add probe customization for each port.
		List<Integer> additionalPorts = getContainerPorts(request);
		if(!additionalPorts.isEmpty()) {
			for (Integer containerPort : additionalPorts) {
				container.addNewPort()
						.withContainerPort(containerPort)
						.endPort();
			}
		}

		//Override the containers default entry point with one specified during the app deployment
		List<String> containerCommand = getContainerCommand(request);
		if(!containerCommand.isEmpty()) {
			container.withCommand(containerCommand);
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
	protected List<String> createCommandArgs(AppDeploymentRequest request) {
		List<String> cmdArgs = new LinkedList<String>();
		// add properties from deployment request
		Map<String, String> args = request.getDefinition().getProperties();
		for (Map.Entry<String, String> entry : args.entrySet()) {
			cmdArgs.add(String.format("--%s=%s", entry.getKey(), entry.getValue()));
		}
		// add provided command line args
		cmdArgs.addAll(request.getCommandlineArguments());
		logger.debug("Using command args: " + cmdArgs);
		return cmdArgs;
	}

    /**
     * The list represents a single command with many arguments.
     *
     * @param request AppDeploymentRequest - used to gather application overridden
     * container command
     * @return a list of strings that represents the command and any arguments for that command
     */
    private List<String> getContainerCommand(AppDeploymentRequest request) {
        List<String> containerCommandList = new ArrayList<>();
        String containerCommand = request.getDeploymentProperties()
                .get("spring.cloud.deployer.kubernetes.containerCommand");
        if (containerCommand != null) {
			logger.trace("Building container command from AppDeploymentRequest: "
					+ containerCommand);
            String[] containerCommandSplit = containerCommand.split(",");
            for (String commandPart : containerCommandSplit) {
                containerCommandList.add(commandPart.trim());
            }
        }
        return containerCommandList;
    }

    /**
     * @param request AppDeploymentRequest - used to gather additional container ports
     * @return a list of Integers to add to the container
     */
    private List<Integer> getContainerPorts(AppDeploymentRequest request) {
        List<Integer> containerPortList = new ArrayList<>();
        String containerPorts = request.getDeploymentProperties()
                .get("spring.cloud.deployer.kubernetes.containerPorts");
        if (containerPorts != null) {
            String[] containerPortSplit = containerPorts.split(",");
            for (String containerPort : containerPortSplit) {
                logger.trace("Adding container ports from AppDeploymentRequest: "
                        + containerPort);
				Integer port = Integer.parseInt(containerPort.trim());
				containerPortList.add(port);
            }
        }
        return containerPortList;
    }

    /**
	 *
	 * @param request AppDeploymentRequest - used to gather application specific
	 * environment variables
	 * @return a List of EnvVar objects for app specific environment settings
	 */
	private Map<String, String> getAppEnvironmentVariables(AppDeploymentRequest request) {
		Map<String, String> appEnvVarMap = new HashMap<>();
		String appEnvVar = request.getDeploymentProperties()
				.get("spring.cloud.deployer.kubernetes.environmentVariables");
		if (appEnvVar != null) {
			String[] appEnvVars = appEnvVar.split(",");
			for (String envVar : appEnvVars) {
				logger.trace("Adding environment variable from AppDeploymentRequest: "
						+ envVar);
				String[] strings = envVar.split("=", 2);
				Assert.isTrue(strings.length == 2,
						"Invalid environment variable declared: " + envVar);
				appEnvVarMap.put(strings[0], strings[1]);
			}
		}
		return appEnvVarMap;
	}

}
