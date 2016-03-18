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

package org.springframework.cloud.deployer.resource.docker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;

//TODO: move to shared location for CF and Mesos use

/**
 * A {@link Resource} implementation for resolving a Docker image.
 *
 * @author Thomas Risberg
 */
public class DockerResource extends AbstractResource {

	private String scheme = "docker";

	private String registry;

	public DockerResource(String registry) {
		this.registry = registry;
	}

	@Override
	public String getDescription() {
		return this.toString();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return null;
	}

	@Override
	public URI getURI() throws IOException {
		String id = this.scheme + ":" + registry;
		try {
			return ResourceUtils.toURI(id);
		} catch (URISyntaxException e) {
			throw new IllegalStateException("Unable to create URI for " + id, e);
		}
	}

	public String getScheme() {
		return scheme;
	}

	public String getRegistry() {
		return registry;
	}

	@Override
	public String toString() {
		return "DockerResource '" + registry +"'";
	}
}
