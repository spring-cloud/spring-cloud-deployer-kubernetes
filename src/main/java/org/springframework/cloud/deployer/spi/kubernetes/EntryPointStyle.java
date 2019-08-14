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

package org.springframework.cloud.deployer.spi.kubernetes;

import java.util.EnumSet;

import org.springframework.cloud.deployer.scheduler.spi.kubernetes.support.RelaxedNames;

/**
 * Defines container entry point styles that are available. The selected entry point style
 * will determine how application properties are made available to the container.
 *
 * @author Chris Schaefer
 */
public enum EntryPointStyle {
	/**
	 * Application properties will be provided to the container as command line arguments.
	 */
	exec,

	/**
	 * Application properties will be provided to the container as environment variables.
	 */
	shell,

	/**
	 * Application properties will be provided to the container as JSON in the
	 * SPRING_APPLICATION_JSON environment variable. Command line arguments will be passed
	 * as-is.
	 */
	boot;

	/**
	 * Converts the string of the provided entry point style to the appropriate enum value
	 * using {@link RelaxedNames}. Defaults to {@link EntryPointStyle#exec} if no matching
	 * entry point style is found.
	 *
	 * @param entryPointStyle the entry point style to use
	 * @return the converted {@link EntryPointStyle}
	 */
	public static EntryPointStyle relaxedValueOf(String entryPointStyle) {
		for (EntryPointStyle candidate : EnumSet.allOf(EntryPointStyle.class)) {
			for (String relaxedName : new RelaxedNames(candidate.name())) {
				if (relaxedName.equals(entryPointStyle)) {
					return candidate;
				}
			}

			if (candidate.name().equalsIgnoreCase(entryPointStyle)) {
				return candidate;
			}
		}

		return exec;
	}
}
