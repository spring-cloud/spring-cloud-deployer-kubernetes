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

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * ImagePullPolicy for containers inside a Kubernetes Pod, cf. https://kubernetes.io/docs/user-guide/images/
 *
 * @author Moritz Schulze
 */
public enum ImagePullPolicy {

    Always,
    IfNotPresent,
    Never;

    /**
     * Tries to convert {@code name} to an {@link ImagePullPolicy} by ignoring case, dashes, underscores
     * and so on in a relaxed fashion.
     *
     * @param name The name to convert to an {@link ImagePullPolicy}.
     * @return The {@link ImagePullPolicy} for {@code name} or {@code null} if the conversion was not possible.
     */
    public static ImagePullPolicy relaxedValueOf(String name) {
		// 'value' is just a dummy key as you can't bind a single value to an enum
		Map<String, String> props = new HashMap<>();
		props.put("value", name);
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(props);
		Binder binder = new Binder(source);
		try {
			return binder.bind("value", Bindable.of(ImagePullPolicy.class)).get();
		} catch (Exception e) {
			// error means we couldn't bind, caller seem to handle null
		}
        return null;
    }

}
