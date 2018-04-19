package net.corda.bootstrapper

import com.microsoft.azure.management.resources.fluentcore.arm.Region
import net.corda.bootstrapper.nodes.NodeNetworkConfig

class Context(val networkName: String,
              val region: Region) {

    var resourceGroupName: String = networkName.replace("[^\\p{IsAlphabetic}^\\p{IsDigit}]".toRegex(), "");
    var registryName: String = networkName.replace("[^\\p{IsAlphabetic}^\\p{IsDigit}]".toRegex(), "");

    var networkMapLocalImageId: String? = null
    var notaryRemoteImageIds: MutableMap<String, String> = mutableMapOf()
    var nodeRemoteImageIds: MutableMap<String, String> = mutableMapOf()
    @Volatile
    var networkMapAddress: String? = null

    var nodes: MutableMap<String, MutableSet<NodeInstanceInfo>> = mutableMapOf();


    fun registerNode(name: String, instanceId: String, nodeImageId: String,
                     nodeNetworkConfig: NodeNetworkConfig, nodeX500: String) {
        nodes.computeIfAbsent(name, { _ -> mutableSetOf() }).add(NodeInstanceInfo(instanceId, nodeImageId, nodeNetworkConfig, nodeX500))
    }

    var networkInitiated: Boolean = false

}