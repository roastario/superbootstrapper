package net.corda.bootstrapper.notaries

import com.github.dockerjava.core.command.BuildImageResultCallback
import net.corda.bootstrapper.DockerUtils
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import java.io.File

class NotaryInstantiator(private val networkName: String,
                         private val pusher: ContainerPusher,
                         private val instaniator: AzureInstantiator) {

    private lateinit var finder: NotaryFinder
    private lateinit var networkMapAddress: String

    fun withNotaries(notaryFinder: NotaryFinder): NotaryInstantiator {
        this.finder = notaryFinder
        return this;
    }

    fun withNetworkMapAddress(networkMapAddress: String): NotaryInstantiator {
        this.networkMapAddress = networkMapAddress
        return this
    }


    fun buildUploadAndInstantiate() {
        val foundNotaries = finder.foundNotaries()
        foundNotaries.forEach { (notaryConfigFile, nodeConfigFile) ->
            val notaryFolder = notaryConfigFile.parentFile
            val notaryName = notaryFolder.name.toLowerCase()
            val expectedFQDN = instaniator.getExpectedFQDN(notaryName)
            val localDockerClient = DockerUtils.createLocalDockerClient()

            println("building notary in folder: $notaryFolder")

            val nodeImageId = localDockerClient.buildImageCmd()
                    .withDockerfile(File(notaryFolder, "Dockerfile"))
                    .withBaseDirectory(notaryFolder)
                    .exec(BuildImageResultCallback()).awaitImageId()


            val remoteNotaryImageName = pusher.pushContainerToImageRepository(nodeImageId, notaryName, networkName)
            instaniator.instantiateContainer(
                    remoteNotaryImageName,
                    listOf(10020),
                    notaryName,
                    mapOf("NETWORK_MAP" to networkMapAddress,
                            "OUR_NAME" to expectedFQDN,
                            "OUR_PORT" to 10020.toString())
            )
        }
    }

}