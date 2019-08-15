/*
 * Copyright 2018-2019 the original author or authors.
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

package org.springframework.cloud.deployer.scheduler.spi.kubernetes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.cloud.deployer.scheduler.spi.core.ScheduleRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Provides centralized lookup methods for properties that can resolved from multiple
 * sources along with any custom logic to be applied.
 *
 * @author Chris Schaefer
 */
class KubernetesSchedulerPropertyResolver {
	private static final NestedCommaDelimitedVariableParser nestedCommaDelimitedVariableParser
			= new NestedCommaDelimitedVariableParser();

	public static String getImagePullPolicy(ScheduleRequest scheduleRequest,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		String imagePullPolicy = scheduleRequest.getSchedulerProperties()
				.get(KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES + ".imagePullPolicy");

		if (StringUtils.hasText(imagePullPolicy)) {
			return ImagePullPolicy.relaxedValueOf(imagePullPolicy).name();
		}

		return kubernetesSchedulerProperties.getImagePullPolicy().name();
	}

	public static EntryPointStyle getEntryPointStyle(ScheduleRequest scheduleRequest,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		String entryPointStyle = scheduleRequest.getSchedulerProperties()
				.get(KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES + ".entryPointStyle");

		if (StringUtils.hasText(entryPointStyle)) {
			return EntryPointStyle.relaxedValueOf(entryPointStyle);
		}

		return kubernetesSchedulerProperties.getEntryPointStyle();
	}

	public static Map<String, String> getTaskEnvironmentVariables(ScheduleRequest request,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		Map<String, String> environmentVariableMap = new HashMap<>();

		for (String environmentVariable : kubernetesSchedulerProperties.getEnvironmentVariables()) {
			environmentVariableMap.putAll(parseEnvironmentVariables(environmentVariable));
		}

		String taskEnvironmentVariables = request.getSchedulerProperties()
				.get(KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES + ".environmentVariables");

		environmentVariableMap.putAll(parseEnvironmentVariables(taskEnvironmentVariables));

		return environmentVariableMap;
	}

	private static Map<String, String> parseEnvironmentVariables(String environmentVariables) {
		Map<String, String> environmentVariableMap = new HashMap<>();

		if (environmentVariables != null) {
			String[] appEnvVars = nestedCommaDelimitedVariableParser.parse(environmentVariables);

			for (String envVar : appEnvVars) {
				String[] strings = envVar.split("=", 2);
				Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
				environmentVariableMap.put(strings[0], strings[1]);
			}
		}

		return environmentVariableMap;
	}

	public static String getImagePullSecret(ScheduleRequest request,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		String imagePullSecret = request.getSchedulerProperties()
				.get(KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES + ".imagePullSecret");

		if (StringUtils.hasText(imagePullSecret)) {
			return imagePullSecret;
		}

		return kubernetesSchedulerProperties.getImagePullSecret();
	}

	public static String getTaskServiceAccountName(ScheduleRequest request,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		String taskServiceAccountName = request.getSchedulerProperties()
				.get(KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES + ".taskServiceAccountName");

		if (StringUtils.hasText(taskServiceAccountName)) {
			return taskServiceAccountName;
		}

		return kubernetesSchedulerProperties.getTaskServiceAccountName();
	}

	static class NestedCommaDelimitedVariableParser {
		static final String REGEX = "(\\w+='.+?'),?";

		static final Pattern pattern = Pattern.compile(REGEX);

		String[] parse(String value) {

			String[] vars = value.replaceAll(pattern.pattern(), "").split(",");

			Matcher m = pattern.matcher(value);
			while (m.find()) {
				vars = Arrays.copyOf(vars, vars.length + 1);
				vars[vars.length - 1] = m.group(1).replaceAll("'", "");
			}
			return vars;
		}
	}
}
