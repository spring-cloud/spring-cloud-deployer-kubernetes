package org.springframework.cloud.deployer.spi.kubernetes;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

/**
 * A task launcher that targets Kubernetes.
 *
 * @author Thomas Risberg
 */
public class KubernetesTaskLauncher implements TaskLauncher {

	//TODO: WIP

	@Override
	public String launch(AppDeploymentRequest request) {
		return null;
	}

	@Override
	public void cancel(String id) {

	}

	@Override
	public TaskStatus status(String id) {
		return null;
	}
}
