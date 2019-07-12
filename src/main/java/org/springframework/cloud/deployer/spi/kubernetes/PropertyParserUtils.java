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

package org.springframework.cloud.deployer.spi.kubernetes;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

/**
 * Utility methods for formatting and parsing properties
 *
 * @author Chris Schaefer
 */
class PropertyParserUtils {
	/**
	 * Extracts annotations from the provided value
	 *
	 * @param annotation The deployment request annotations
	 * @return {@link Map} of annotations
	 */
	static Map<String, String> getAnnotations(String annotation) {
		Map<String, String> annotations = new HashMap<>();

		if (StringUtils.hasText(annotation)) {
			String[] annotationPairs = annotation.split(",");
			for (String annotationPair : annotationPairs) {
				String[] splitAnnotation = annotationPair.split(":", 2);
				Assert.isTrue(splitAnnotation.length == 2, format("Invalid annotation value: %s", annotationPair));
				annotations.put(splitAnnotation[0].trim(), splitAnnotation[1].trim());
			}
		}

		return annotations;
	}

	/**
	 * Binds the YAML formatted value of a deployment property to a {@link KubernetesDeployerProperties} instance.
	 *
	 * @param request the {@link AppDeploymentRequest} to obtain the property from
	 * @param propertyKey the property key to obtain the value to bind for
	 * @param yamlLabel the label representing the field to bind to
	 * @return a {@link KubernetesDeployerProperties} with the bound property data
	 */
	static KubernetesDeployerProperties bindProperties(AppDeploymentRequest request, String propertyKey, String yamlLabel) {
		String deploymentProperty = request.getDeploymentProperties().getOrDefault(propertyKey, "");

		KubernetesDeployerProperties deployerProperties = new KubernetesDeployerProperties();

		if (!StringUtils.isEmpty(deploymentProperty)) {
			try {
				YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
				String tmpYaml = "{ " + yamlLabel + ": " + deploymentProperty + " }";
				properties.setResources(new ByteArrayResource(tmpYaml.getBytes()));
				Properties yaml = properties.getObject();
				MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
				deployerProperties = new Binder(source)
						.bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
			} catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid binding property '%s'", deploymentProperty), e);
			}
		}

		return deployerProperties;
	}
}
