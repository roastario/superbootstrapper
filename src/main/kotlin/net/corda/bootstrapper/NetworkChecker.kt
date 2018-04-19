package net.corda.bootstrapper

import net.corda.bootstrapper.nodes.NodeInstantiator
import net.corda.bootstrapper.notaries.NotaryInstantiator
import net.corda.core.internal.openHttpConnection
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture

class NetworkChecker(val context: Context,
                     val notaryInstantiator: NotaryInstantiator,
                     val nodeInstantiator: NodeInstantiator) {


    fun isNetworkHealthy(): Boolean {

        val networkMapAvailable = isNetworkMapAvailable()

        if (!networkMapAvailable) {
            println("Network map instance unavailable network unlikely to be healthy - aborting")
            System.exit(1)
        }

        println("Network map is healthy - checking notaries")
        println("Notaries healthy - checking nodes")
        val allMissingNodes = getAllMissingNodes()
        CompletableFuture.allOf(*allMissingNodes.map { (nodeName, missingInstances) ->
            missingInstances.map { missingInstance ->
                Runnable {
                    println("attempting to fix missing instance: $missingInstance by starting a new container")
                    nodeInstantiator.instantiateNodeInstance(missingInstance)
                }
            }
        }.flatten().map { CompletableFuture.runAsync(it) }.toTypedArray())
                .join()

        return false;
    }

    fun isNetworkMapAvailable(): Boolean {
        return try {
            URL(context.networkMapAddress + "/ping").openHttpConnection().responseCode == 200
        } catch (e: IOException) {
            false
        }

    }

    fun getAllMissingNodes(): Map<String, List<NodeInstanceInfo>> {
        return context.nodes.map { (nodeName, nodeInstances) ->
            val nodeStatuses = nodeInstances.map { nodeInstance ->
                nodeInstance to nodeInstantiator.isNodeRunning(nodeInstance.name)
            }
            nodeName to nodeStatuses.filter { !it.second }.map { it.first }
        }.toMap()
    }

}