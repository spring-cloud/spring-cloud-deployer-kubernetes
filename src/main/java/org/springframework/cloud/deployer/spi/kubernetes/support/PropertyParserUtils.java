/*
 * Copyright 2018-2019 the original author or authors.
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

package org.springframework.cloud.deployer.spi.kubernetes.support;

import static java.lang.String.format;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Utility methods for formatting and parsing properties
 *
 * @author Chris Schaefer
 * @author Ilayaperumal Gopinathan
 */
public class PropertyParserUtils {
	/**
	 * Extracts annotations from the provided value
	 *
	 * @param stringPairs The deployment request annotations
	 * @return {@link Map} of annotations
	 */
	public static Map<String, String> getStringPairsToMap(String stringPairs) {
		Map<String, String> mapValue = new HashMap<>();

		if (StringUtils.hasText(stringPairs)) {
			String[] pairs = stringPairs.split(",");
			for (String pair : pairs) {
				String[] splitString = pair.split(":", 2);
				Assert.isTrue(splitString.length == 2, format("Invalid annotation value: %s", pair));
				mapValue.put(splitString[0].trim(), splitString[1].trim());
			}
		}

		return mapValue;
	}

	public static String getDeploymentPropertyValue(Map<String, String> deploymentProperties, String propertyName) {
		return getDeploymentPropertyValue(deploymentProperties, propertyName, null);
	}

	public static String getDeploymentPropertyValue(Map<String, String> deploymentProperties, String propertyName,
			String defaultValue) {
		RelaxedNames relaxedNames = new RelaxedNames(propertyName);
		for (Iterator<String> itr = relaxedNames.iterator(); itr.hasNext();) {
			String relaxedName = itr.next();
			if (deploymentProperties.containsKey(relaxedName)) {
				return deploymentProperties.get(relaxedName);
			}
		}
		return defaultValue;
	}

	public static Map<String, String> getEnvironmentVariables(AppDeploymentRequest request){
		Map<String, String> envVarsMap = new HashMap<>();

		for (String key : request.getDefinition().getProperties().keySet()) {
			String envVar = key.replace('.', '_').toUpperCase();
			envVarsMap.put(envVar, request.getDefinition().getProperties().get(key));
		}
		// Push all the command line arguments as environment properties
		// The task app name(in case of Composed Task), platform_name and executionId are expected to be updated.
		// This will also override any of the existing app properties that match the provided cmdline args.
		for (String cmdLineArg: request.getCommandlineArguments()) {
			String cmdLineArgKey;

			if (cmdLineArg.startsWith("--")) {
				cmdLineArgKey = cmdLineArg.substring(2, cmdLineArg.indexOf("="));
			} else {
				cmdLineArgKey = cmdLineArg.substring(0, cmdLineArg.indexOf("="));
			}

			String cmdLineArgValue = cmdLineArg.substring(cmdLineArg.indexOf("=") + 1);
			envVarsMap.put(cmdLineArgKey.replace('.', '_').toUpperCase(), cmdLineArgValue);
		}
		return envVarsMap;
	}
}
