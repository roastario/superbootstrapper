package net.corda.bootstrapper

import com.microsoft.azure.management.resources.fluentcore.arm.Region
import net.corda.bootstrapper.nodes.NodeNetworkConfig
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
    var networkMapLocalImageId: String? = null
    @Volatile
    var notaryRemoteImageIds: ConcurrentMap<String, String> = ConcurrentHashMap()
    @Volatile
    var nodeRemoteImageIds: ConcurrentMap<String, String> = ConcurrentHashMap()
    @Volatile
    var networkMapAddress: String? = null
    @Volatile
    var nodes: MutableMap<String, ConcurrentHashSet<NodeInstanceInfo>> = ConcurrentHashMap()
    @Volatile
    var networkInitiated: Boolean = false

    fun registerNode(name: String, instanceId: String, nodeImageId: String,
                     nodeNetworkConfig: NodeNetworkConfig, nodeX500: String) {
        nodes.computeIfAbsent(name, { _ -> ConcurrentHashSet() }).add(NodeInstanceInfo(instanceId, nodeImageId, nodeNetworkConfig, nodeX500))
    }


}