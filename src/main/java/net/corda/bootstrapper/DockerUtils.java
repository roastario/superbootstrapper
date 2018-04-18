package net.corda.bootstrapper;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;

public class DockerUtils {

    public static DockerClient createDockerClient(String registryServerUrl, String username, String password) throws Exception {
        return DockerClientBuilder.getInstance(createDockerClientConfig(registryServerUrl, username, password))
                .build();
    }

    public static DockerClient createLocalDockerClient() {
        return DockerClientBuilder.getInstance().build();
    }

    private static DockerClientConfig createDockerClientConfig(String registryServerUrl, String username, String password) {
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerTlsVerify(false)
                .withRegistryUrl(registryServerUrl)
                .withRegistryUsername(username)
                .withRegistryPassword(password)
                .build();
    }


}
