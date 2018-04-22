package net.corda.bootstrapper.nodes

import com.github.dockerjava.core.command.BuildImageResultCallback
import com.typesafe.config.ConfigFactory
import net.corda.bootstrapper.DockerUtils
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import java.io.File

class NodeBuilder {

    fun buildNode(foundNode: FoundNode): BuiltNode {
        val localDockerClient = DockerUtils.createLocalDockerClient()
        val nodeConfigFile = foundNode.configFile
        val nodeDir = nodeConfigFile.parentFile
        if (!nodeConfigFile.exists()) {
            throw IllegalStateException("There is no nodeConfig for dir: " + nodeConfigFile)
        }
        val nodeConfig = ConfigFactory.parseFile(nodeConfigFile)
        val rpcConfig = nodeConfig.getObject("rpcSettings")

        val rpcHostAndPort = NetworkHostAndPort.parse(rpcConfig["address"]!!.unwrapped().toString())
        val x500 = CordaX500Name.parse(nodeConfig.getString("myLegalName"))

        println("starting to build docker image for: " + nodeDir)
        val nodeImageId = localDockerClient.buildImageCmd()
                .withDockerfile(File(nodeDir, "Dockerfile"))
                .withBaseDirectory(nodeDir)
                .exec(BuildImageResultCallback()).awaitImageId()
        println("finished building docker image for: $nodeDir with id: $nodeImageId")

        return BuiltNode(foundNode, nodeImageId, x500, rpcHostAndPort)
    }

}

