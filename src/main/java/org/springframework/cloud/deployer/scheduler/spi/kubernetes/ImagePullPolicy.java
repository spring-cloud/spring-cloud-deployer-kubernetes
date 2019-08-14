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

package org.springframework.cloud.deployer.scheduler.spi.kubernetes;

import java.util.EnumSet;

import org.springframework.cloud.deployer.scheduler.spi.kubernetes.support.RelaxedNames;

/**
 * Defines container image pull policies that are available. The selection of pull policy
 * will determine when an image is pulled.
 *
 * @author Chris Schaefer
 */
public enum ImagePullPolicy {
	/**
	 * Always pull the image regardless if it's present in the registry already.
	 */
	Always,

	/**
	 * Only pull the image if it's not present in the registry.
	 */
	IfNotPresent,

	/**
	 * Never pull the image, only use what is present in the registry.
	 */
	Never;

	/**
	 * Converts the string of the provided image pull policy to the appropriate enum value
	 * using {@link RelaxedNames}. Defaults to {@link ImagePullPolicy#IfNotPresent} if no
	 * matching image pull policy is found.
	 *
	 * @param imagePullPolicy the image pull policy to use
	 * @return the converted {@link ImagePullPolicy}
	 */
	public static ImagePullPolicy relaxedValueOf(String imagePullPolicy) {
		for (ImagePullPolicy candidate : EnumSet.allOf(ImagePullPolicy.class)) {
			for (String relaxedName : new RelaxedNames(candidate.name())) {
				if (relaxedName.equals(imagePullPolicy)) {
					return candidate;
				}
			}

			if (candidate.name().equalsIgnoreCase(imagePullPolicy)) {
				return candidate;
			}
		}

		return IfNotPresent;
	}
}
