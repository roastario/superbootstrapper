package net.corda.bootstrapper.notaries

import com.github.dockerjava.core.command.BuildImageResultCallback
import net.corda.bootstrapper.Constants
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.DockerUtils
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.networkmap.AzureSmbVolume
import java.io.File

class NotaryInstantiator(private val pusher: ContainerPusher,
                         private val instaniator: AzureInstantiator,
                         private val azureSmbVolume: AzureSmbVolume,
                         private val context: Context) {


    fun buildUploadAndInstantiate(finder: NotaryFinder) {
        val foundNotaries = finder.foundNotaries()
        foundNotaries.forEach { (notaryConfigFile, _) ->
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
                    mapOf("OUR_NAME" to expectedFQDN,
                            "OUR_PORT" to Constants.NODE_P2P_PORT.toString()),
                    azureSmbVolume)
        }
    }

}