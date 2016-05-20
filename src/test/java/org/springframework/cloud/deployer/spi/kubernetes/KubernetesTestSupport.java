package org.springframework.cloud.deployer.spi.kubernetes;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.test.junit.AbstractExternalResourceTestSupport;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * JUnit {@link org.junit.Rule} that detects the fact that a Kubernetes installation is available.
 *
 * @author Thomas Risberg
 */
public class KubernetesTestSupport extends AbstractExternalResourceTestSupport<KubernetesClient> {

	private ConfigurableApplicationContext context;


	protected KubernetesTestSupport() {
		super("KUBERNETES");
	}

	@Override
	protected void cleanupResource() throws Exception {
		context.close();
	}

	@Override
	protected void obtainResource() throws Exception {
		context = SpringApplication.run(Config.class);
		resource = context.getBean(KubernetesClient.class);
		resource.namespaces().list();
	}

	@Configuration
	@EnableAutoConfiguration
	@EnableConfigurationProperties(KubernetesDeployerProperties.class)
	public static class Config {

		@Autowired
		private KubernetesDeployerProperties properties;

		@Bean
		public KubernetesClient kubernetesClient() {
			return new DefaultKubernetesClient().inNamespace(properties.getNamespace());
		}
	}
}
