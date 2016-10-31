/*
 * Copyright 2016 the original author or authors.
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

import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * Unit test for {@link AbstractKubernetesDeployer}.
 *
 * @author Moritz Schulze
 */
public class KubernetesDeployerTest {

    private AbstractKubernetesDeployer kubernetesDeployer;
    private AppDeploymentRequest deploymentRequest;
    private Map<String, String> deploymentProperties;
    private KubernetesDeployerProperties serverProperties;

    @Before
    public void setUp() throws Exception {
        kubernetesDeployer = new AbstractKubernetesDeployer();
        deploymentProperties = new HashMap<String, String>();
        deploymentRequest = new AppDeploymentRequest(new AppDefinition("foo", Collections.emptyMap()), new FileSystemResource(""), deploymentProperties);
        serverProperties = new KubernetesDeployerProperties();
    }

    @Test
    public void deduceImagePullPolicy_fallsBackToAlwaysIfOverrideNotParseable() throws Exception {
        deploymentProperties.put("spring.cloud.deployer.kubernetes.imagePullPolicy", "not-a-real-value");
        ImagePullPolicy pullPolicy = kubernetesDeployer.deduceImagePullPolicy(serverProperties, deploymentRequest);
        assertThat(pullPolicy, is(ImagePullPolicy.Always));
    }
}