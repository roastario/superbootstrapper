package net.corda.bootstrapper.notaries

import com.github.dockerjava.core.command.BuildImageResultCallback
import net.corda.bootstrapper.Constants
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.DockerUtils
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import java.io.File

class NotaryInstantiator(private val pusher: ContainerPusher,
                         private val instaniator: AzureInstantiator,
                         private val context: Context) {

    private lateinit var finder: NotaryFinder

    fun withNotaries(notaryFinder: NotaryFinder): NotaryInstantiator {
        this.finder = notaryFinder
        return this;
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

            val remoteNotaryImageName = pusher.pushContainerToImageRepository(nodeImageId, notaryName, context.networkName)
            context.notaryRemoteImageIds[notaryName] = remoteNotaryImageName

            instaniator.instantiateContainer(
                    remoteNotaryImageName,
                    listOf(Constants.NODE_P2P_PORT),
                    notaryName,
                    mapOf("NETWORK_MAP" to context.networkMapAddress!!,
                            "OUR_NAME" to expectedFQDN,
                            "OUR_PORT" to Constants.NODE_P2P_PORT.toString())
            )
        }
    }

}