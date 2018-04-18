package net.corda.bootstrapper.containers.registry.azure.push

import com.github.dockerjava.core.command.PushImageResultCallback
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerregistry.Registry
import net.corda.bootstrapper.DockerUtils
import net.corda.bootstrapper.containers.registry.azure.create.parseCredentials


class ContainerPusher(private val azure: Azure, private val azureRegistry: Registry) {


    fun pushContainerToImageRepository(localImageName: String,
                                       remoteImageName: String,
                                       networkName: String): String {


        val (registryUser, registryPassword) = azureRegistry.parseCredentials()
        val dockerClient = DockerUtils.createDockerClient(
                azureRegistry.loginServerUrl(),
                registryUser,
                registryPassword)


        val privateRepoUrl = "${azureRegistry.loginServerUrl()}/$remoteImageName".toLowerCase()
        dockerClient.tagImageCmd(localImageName, privateRepoUrl, networkName).exec()

        println("starting PUSH image: $localImageName to registryURL: $privateRepoUrl:$networkName")
        val pushImageCmd = dockerClient.pushImageCmd("$privateRepoUrl:$networkName")
                .withAuthConfig(dockerClient.authConfig())
                .exec(PushImageResultCallback())
                .awaitSuccess()

        println("completed PUSH image: $localImageName to registryURL: $privateRepoUrl:$networkName")
        return "$privateRepoUrl:$networkName"

    }


}

fun getBaseNameOfImage(imageName: String): String {
    val index = imageName.lastIndexOf("/")
    return imageName.substring(index + 1, imageName.length)
}