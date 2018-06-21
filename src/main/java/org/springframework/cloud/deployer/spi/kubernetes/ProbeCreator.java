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

import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.HTTPHeader;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Create a readiness/liveness probe overriding any value that is provided as a deployment property.
 */
class ProbeCreator {
	private static final String KUBERNETES_DEPLOYER_PREFIX = "spring.cloud.deployer.kubernetes";
	protected static final String AUTHORIZATION_HEADER_NAME = "Authorization";
	protected static final String PROBE_CREDENTIALS_SECRET_KEY_NAME = "credentials";

	private Integer externalPort;
	private String endpoint;
	private int timeout;
	private int initialDelay;
	private int period;
	private String prefix;
	private Map<String, String> deployProperties;
	private Secret probeCredentialsSecret;

	ProbeCreator(Integer externalPort, String endpoint, int timeout, int initialDelay, int period, String prefix,
				 Map<String, String> deployProperties) {

		this.externalPort = externalPort;
		this.endpoint = endpoint;
		this.timeout = timeout;
		this.initialDelay = initialDelay;
		this.period = period;
		this.prefix = prefix;
		this.deployProperties = deployProperties;
	}

	Probe create() {
		setPropertyOverrides();

		HTTPGetActionBuilder httpGetActionBuilder = new HTTPGetActionBuilder().withPath(endpoint)
				.withNewPort(externalPort);

		List<HTTPHeader> httpHeaders = getHttpHeaders();

		if (!httpHeaders.isEmpty()) {
			httpGetActionBuilder.withHttpHeaders(httpHeaders);
		}

		return new ProbeBuilder()
				.withHttpGet(httpGetActionBuilder.build())
				.withTimeoutSeconds(timeout).withInitialDelaySeconds(initialDelay).withPeriodSeconds(period).build();
	}

	ProbeCreator withProbeCredentialsSecret(Secret probeCredentialsSecret) {
		this.probeCredentialsSecret = probeCredentialsSecret;
		return this;
	}

	private void setPropertyOverrides() {
		String probePropertyPrefix = KUBERNETES_DEPLOYER_PREFIX + "." + prefix;

		String probePathKey = probePropertyPrefix + "ProbePath";
		if (deployProperties.containsKey(probePathKey)) {
			this.endpoint = deployProperties.get(probePathKey);
		}

		String probeTimeoutKey = probePropertyPrefix + "ProbeTimeout";
		if (deployProperties.containsKey(probeTimeoutKey)) {
			this.timeout = Integer.valueOf(deployProperties.get(probeTimeoutKey));
		}

		String probeDelayKey = probePropertyPrefix + "ProbeDelay";
		if (deployProperties.containsKey(probeDelayKey)) {
			this.initialDelay = Integer.valueOf(deployProperties.get(probeDelayKey));
		}

		String probePeriodKey = probePropertyPrefix + "ProbePeriod";
		if (deployProperties.containsKey(probePeriodKey)) {
			this.period = Integer.valueOf(deployProperties.get(probePeriodKey));
		}
	}

	private List<HTTPHeader> getHttpHeaders() {
		List<HTTPHeader> httpHeaders = new ArrayList<>();

		HTTPHeader authenticationHeader = getAuthorizationHeader();

		if (authenticationHeader != null) {
			httpHeaders.add(authenticationHeader);
		}

		return httpHeaders;
	}

	private HTTPHeader getAuthorizationHeader() {
		HTTPHeader httpHeader = null;

		if (probeCredentialsSecret != null) {
			Assert.isTrue(probeCredentialsSecret.getData().containsKey(PROBE_CREDENTIALS_SECRET_KEY_NAME),
					"Secret does not contain a key by the name of " + PROBE_CREDENTIALS_SECRET_KEY_NAME);

			httpHeader = new HTTPHeader();
			httpHeader.setName(AUTHORIZATION_HEADER_NAME);

			// only Basic auth is supported currently
			httpHeader.setValue(ProbeAuthenticationType.Basic.name() + " " +
					probeCredentialsSecret.getData().get(PROBE_CREDENTIALS_SECRET_KEY_NAME));
		}

		return httpHeader;
	}
}
