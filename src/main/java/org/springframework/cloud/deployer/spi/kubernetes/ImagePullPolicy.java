package org.springframework.cloud.deployer.spi.kubernetes;

/**
 * ImagePullPolicy for containers inside a Kubernetes Pod, cf. http://kubernetes.io/docs/user-guide/images/
 */
public enum ImagePullPolicy {

    Always,
    IfNotPresent,
    Never

}
