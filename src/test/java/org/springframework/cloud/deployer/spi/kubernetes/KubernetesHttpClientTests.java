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

package org.springframework.cloud.deployer.spi.kubernetes;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link KubernetesHttpClient}.
 *
 * @author Ilayaperumal Gopinathan
 */
@RunWith(SpringRunner.class)
public class KubernetesHttpClientTests {

	@Test
	public void apiVersionTest() {
		assertTrue(KubernetesHttpClient.getApiVersion("v1.9.0").equals("v1beta1"));
		assertTrue(KubernetesHttpClient.getApiVersion("v1.9.0_gke01").equals("v1beta1"));
		assertTrue(KubernetesHttpClient.getApiVersion("v1.10.0-beta1").equals("v1"));
		assertTrue(KubernetesHttpClient.getApiVersion("v1.10.0beta2").equals("v1"));
	}
}
