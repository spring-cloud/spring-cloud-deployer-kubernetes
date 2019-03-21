/*
 * Copyright 2015-2016 the original author or authors.
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

import org.springframework.boot.bind.RelaxedNames;

import java.util.EnumSet;

/**
 * ImagePullPolicy for containers inside a Kubernetes Pod, cf. http://kubernetes.io/docs/user-guide/images/
 *
 * @author Moritz Schulze
 */
public enum ImagePullPolicy {

    Always,
    IfNotPresent,
    Never;

    /**
     * Tries to convert {@code name} to an {@link ImagePullPolicy} by ignoring case, dashes, underscores
     * and so on like Spring Boot does it in {@link org.springframework.boot.bind.RelaxedConversionService}.
     *
     * @param name The name to convert to an {@link ImagePullPolicy}.
     * @return The {@link ImagePullPolicy} for {@code name} or {@code null} if the conversion was not possible.
     */
    public static ImagePullPolicy relaxedValueOf(String name) {
        for (ImagePullPolicy candidate : EnumSet.allOf(ImagePullPolicy.class)) {
            for (String relaxedName : new RelaxedNames(candidate.name())) {
                if (relaxedName.equals(name)) {
                    return candidate;
                }
            }
            if (candidate.name().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

}
