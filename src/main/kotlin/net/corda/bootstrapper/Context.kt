package net.corda.bootstrapper

import com.microsoft.azure.management.resources.fluentcore.arm.Region
import net.corda.bootstrapper.nodes.NodeInstanceRequest
import net.corda.core.identity.CordaX500Name
import org.apache.activemq.artemis.utils.collections.ConcurrentHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class Context(val networkName: String,
              val region: Region) {

    @Volatile
    var resourceGroupName: String = networkName.replace("[^\\p{IsAlphabetic}^\\p{IsDigit}]".toRegex(), "")
    @Volatile
    var registryName: String = networkName.replace("[^\\p{IsAlphabetic}^\\p{IsDigit}]".toRegex(), "")
    @Volatile
    var storageAccountName: String = networkName.replace("[^\\p{IsAlphabetic}^\\p{IsDigit}]".toRegex(), "").toLowerCase()

    @Volatile
    var notaryRemoteImageIds: ConcurrentMap<String, String> = ConcurrentHashMap()
    @Volatile
    var nodeRemoteImageIds: ConcurrentMap<String, String> = ConcurrentHashMap()

    @Volatile
    var nodes: MutableMap<String, ConcurrentHashSet<PersistableNodeInstance>> = ConcurrentHashMap()
    @Volatile
    var networkInitiated: Boolean = false

    private fun registerNode(name: String, nodeInstanceRequest: NodeInstanceRequest) {
        nodes.computeIfAbsent(name, { _ -> ConcurrentHashSet() }).add(nodeInstanceRequest.toPersistable())
    }

    fun registerNode(request: NodeInstanceRequest) {
        registerNode(request.name, request)
    }


    data class PersistableNodeInstance(
            val groupName: String,
            val groupX500: CordaX500Name,
            val instanceName: String,
            val instanceX500: String,
            val localImageId: String,
            val remoteImageName: String,
            val rpcPort: Int,
            val fqdn: String,
            val rpcUser: String,
            val rpcPassword: String)


    companion object {
        fun fromInstanceRequest(nodeInstanceRequest: NodeInstanceRequest): PersistableNodeInstance {
            return PersistableNodeInstance(
                    nodeInstanceRequest.name,
                    nodeInstanceRequest.x500,
                    nodeInstanceRequest.nodeInstanceName,
                    nodeInstanceRequest.actualX500,
                    nodeInstanceRequest.imageId,
                    nodeInstanceRequest.remoteImageName,
                    nodeInstanceRequest.rpcHostAndPort.port,
                    nodeInstanceRequest.expectedFqName,
                    "",
                    ""
            )

        }
    }

    fun NodeInstanceRequest.toPersistable(): PersistableNodeInstance {
        return fromInstanceRequest(this)
    }
}

