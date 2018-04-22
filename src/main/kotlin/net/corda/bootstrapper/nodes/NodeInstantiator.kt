package net.corda.bootstrapper.nodes

import net.corda.bootstrapper.Constants
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.networkmap.AzureNetworkStore
import net.corda.core.identity.CordaX500Name

class NodeInstantiator(val containerPusher: ContainerPusher,
                       val azureInstantiator: AzureInstantiator,
                       val azureNetworkStore: AzureNetworkStore,
                       val context: Context) {


    fun createInstanceRequests(pushedNode: PushedNode, nodeCount: Map<String, Int>): List<NodeInstanceRequest> {
        return (0..(nodeCount[pushedNode.name] ?: 1)).map { i ->
            val nodeInstanceName = pushedNode.name + i
            val expectedName = azureInstantiator.getExpectedFQDN(nodeInstanceName)
            NodeInstanceRequest(pushedNode, nodeInstanceName, buildX500(pushedNode.x500, i), expectedName)
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

    fun instantiateNodeInstance(request: NodeInstanceRequest) {
        azureInstantiator.instantiateContainer(
                request.remoteImageName,
                listOf(Constants.NODE_P2P_PORT, request.rpcHostAndPort.port, Constants.NODE_SSHD_PORT),
                request.nodeInstanceName,
                mapOf("OUR_NAME" to request.expectedFqName,
                        "OUR_PORT" to Constants.NODE_P2P_PORT.toString(),
                        "X500" to request.actualX500),
                azureNetworkStore
        )
    }

    fun instantiateNodeInstance(remoteImageName: String,
                                rpcPort: Int,
                                nodeInstanceName: String,
                                expectedFqName: String,
                                actualX500: String) {

        azureInstantiator.instantiateContainer(
                remoteImageName,
                listOf(Constants.NODE_P2P_PORT, rpcPort, Constants.NODE_SSHD_PORT),
                nodeInstanceName,
                mapOf("OUR_NAME" to expectedFqName,
                        "OUR_PORT" to Constants.NODE_P2P_PORT.toString(),
                        "X500" to actualX500),
                azureNetworkStore
        )
    }

    fun expectedFqdn(newInstanceName: String): String {
        return azureInstantiator.getExpectedFQDN(newInstanceName)
    }

    fun instantiateNodeInstance(request: Context.PersistableNodeInstance) {
        instantiateNodeInstance(request.remoteImageName, request.rpcPort, request.instanceName, request.fqdn, request.instanceX500)
    }

}