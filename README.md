# spring-cloud-deployer-kubernetes

## Integration tests

### Minikube

[Minikube](https://github.com/kubernetes/minikube) is a tool that makes it easy to run Kubernetes locally. Minikube runs a single-node Kubernetes cluster inside a VM on your laptop for users looking to try out Kubernetes or develop with it day-to-day.

Follow the instructions for installing Minikube [here](https://github.com/kubernetes/minikube#installation).

#### Running the tests

To run all integration tests against the running Minikube cluster, run

```
$ ./mvnw test
```

### Google Container Engine

*TODO*
