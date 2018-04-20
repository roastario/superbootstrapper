package net.corda.bootstrapper.nodes

import net.corda.bootstrapper.Constants
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.NodeInstanceInfo
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.core.identity.CordaX500Name
import java.util.stream.IntStream

class NodeInstantiator(val containerPusher: ContainerPusher,
                       val azureInstantiator: AzureInstantiator,
                       val context: Context) {


    fun buildUploadAndInstantiate(nodeFinder: NodeFinder, nodeCount: Map<String, Int>) {
        val copiedNodeConfigFiles = nodeFinder.copyNodesToCacheFolder()
        val builtDockerNodes = NodeBuilder().buildNodes(copiedNodeConfigFiles)
        val nodesByName = builtDockerNodes.associateBy { (_, nodeDir, _) -> nodeDir.name.toLowerCase() }

        nodesByName.entries.parallelStream().forEach { (nodeName, nodeInfo) ->
            val (nodeImageId, _, nodeNetworkConfig) = nodeInfo
            val nodeImageName = containerPusher.pushContainerToImageRepository(nodeImageId,
                    "node-$nodeName", context.networkName)

            context.nodeRemoteImageIds[nodeName] = nodeImageName

            IntStream.range(0, nodeCount.getOrDefault(nodeName, 1)).parallel().forEach { i ->
                val nodeInstanceName = nodeName + i
                val expectedFqdn = azureInstantiator.getExpectedFQDN(nodeInstanceName)
                val nodeX500 = buildX500(nodeNetworkConfig.x500, i)
                context.registerNode(nodeName, nodeInstanceName, nodeImageName, nodeNetworkConfig, nodeX500)
                azureInstantiator.instantiateContainer(
                        nodeImageName,
                        listOf(Constants.NODE_P2P_PORT, nodeNetworkConfig.rpcHostAndPort.port, Constants.NODE_SSHD_PORT),
                        nodeInstanceName,
                        mapOf("NETWORK_MAP" to context.networkMapAddress!!,
                                "OUR_NAME" to expectedFqdn,
                                "OUR_PORT" to Constants.NODE_P2P_PORT.toString(),
                                "X500" to nodeX500)
                )
            }
        }

    }

    private fun buildX500(baseX500: CordaX500Name, i: Int): String {
        if (i == 0) {
            return baseX500.toString()
        }
        return baseX500.copy(commonName = (baseX500.commonName ?: "") + i).toString()
    }

    fun isNodeRunning(nodeInstance: String): Boolean {
        return azureInstantiator.isContainerRunning(nodeInstance)
    }

    fun instantiateNodeInstance(nodeInstance: NodeInstanceInfo) {
        val nodeNetworkConfig = nodeInstance.nodeNetworkConfig
        val imageId = nodeInstance.nodeImageId
        azureInstantiator.instantiateContainer(
                imageId,
                listOf(Constants.NODE_P2P_PORT, nodeNetworkConfig.rpcHostAndPort.port, Constants.NODE_SSHD_PORT),
                nodeInstance.name,
                mapOf("NETWORK_MAP" to context.networkMapAddress!!,
                        "OUR_NAME" to azureInstantiator.getExpectedFQDN(nodeInstance.name),
                        "OUR_PORT" to 10020.toString(),
                        "X500" to nodeInstance.nodeX500)
        )

    }

}