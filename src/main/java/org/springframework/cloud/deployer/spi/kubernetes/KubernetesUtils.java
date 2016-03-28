package org.springframework.cloud.deployer.spi.kubernetes;

public final class KubernetesUtils {

	// Kubernetes does not allow . in the name
	public static String createKubernetesName(String id) {
		return id.replace('.', '-');
	}
}
