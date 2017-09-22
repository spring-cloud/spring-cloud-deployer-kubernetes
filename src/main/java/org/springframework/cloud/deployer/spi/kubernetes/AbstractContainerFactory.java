/*
 * Copyright 2017 the original author or authors.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.TCPSocketActionBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author David Turanski
 **/
public abstract class AbstractContainerFactory {
	protected final KubernetesDeployerProperties properties;
	protected final Log logger = LogFactory.getLog(this.getClass());

	/**
	 * @param properties deployer properties
	 */
	protected AbstractContainerFactory(KubernetesDeployerProperties properties) {
		this.properties = properties;
	}

	protected AbstractContainerFactory() {
		this(new KubernetesDeployerProperties());
	}

	protected String resolveImageName(Resource image) {
		String imageName;
		try {
			Assert.notNull(image, "No image provided for sidecar container");
			imageName = image.getURI().getSchemeSpecificPart();
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + image, e);
		}
		return imageName;
	}

	protected List<EnvVar> buildEnVars(String[] env) {
		List<EnvVar> envVars = new ArrayList<>();
		for (String envVar : env) {
			String[] strings = envVar.split("=", 2);
			Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
			envVars.add(new EnvVar(strings[0], strings[1], null));
		}
		return envVars;
	}

	/**
	 * Create a readiness/liveness probe
	 */
	protected static class KubernetesProbeBuilder {

		private final Integer port;
		private final KubernetesDeployerProperties.Probe probe;


		public KubernetesProbeBuilder(Integer port, KubernetesDeployerProperties.Probe.HandlerType type) {
			this.probe = new KubernetesDeployerProperties.Probe();
			Assert.notNull(port,"'port' is required for a probe.");
			Assert.isTrue(port > 0,"'port' must be > 0.");
			this.probe.setType(type);
			this.port = port;
		}

		public KubernetesProbeBuilder(Integer port, KubernetesDeployerProperties.Probe probe) {
			this.probe = new KubernetesDeployerProperties.Probe();
			Assert.notNull(port,"'port' is required for a probe.");
			Assert.isTrue(port > 0,"'port' must be > 0.");
			this.port = port;

			this.probe.setType(probe.getType());
			this.probe.setPeriod(probe.getPeriod());
			this.probe.setTimeout(probe.getTimeout());
			this.probe.setPath(probe.getPath());
			this.probe.setDelay(probe.getDelay());
		}

		protected KubernetesProbeBuilder withPath(String path) {
			this.probe.setPath(path);
			return this;
		}

		protected KubernetesProbeBuilder withTimout(int timeout) {
			this.probe.setTimeout(timeout);
			return this;
		}

		protected KubernetesProbeBuilder withDelay(int delay) {
			this.probe.setDelay(delay);
			return this;
		}

		protected KubernetesProbeBuilder withPeriod(int period) {
			this.probe.setPeriod(period);
			return this;
		}


		protected Probe build() {
			Probe probe;
			switch (this.probe.getType()) {
			case http:
				Assert.hasText(this.probe.getPath(),"'path' is required for this probe.");
				probe = buildHttProbe();
				break;
			case socket:
				probe = buildTcpProbe();
				break;

			default:
				throw new IllegalArgumentException(
					String.format("Probe type %s is not supported", this.probe.getType().name()));
			}
			return probe;
		}


		private Probe buildHttProbe() {
			return new ProbeBuilder()
				.withHttpGet(new HTTPGetActionBuilder().withPath(this.probe.getPath())
					.withNewPort(this.port)
					.build())
				.withTimeoutSeconds(this.probe.getTimeout())
				.withInitialDelaySeconds(this.probe.getDelay())
				.withPeriodSeconds(this.probe.getPeriod())
				.build();
		}

		private Probe buildTcpProbe() {
			return new ProbeBuilder()
				.withTcpSocket(new TCPSocketActionBuilder()
					.withNewPort(this.port)
					.build())
				.withTimeoutSeconds(this.probe.getTimeout())
				.withInitialDelaySeconds(this.probe.getDelay())
				.withPeriodSeconds(this.probe.getPeriod())
				.build();
		}
	}
}
