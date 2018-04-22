package net.corda.bootstrapper

import net.corda.bootstrapper.nodes.NodeInstantiator

class NodeAdder(val context: Context,
                val nodeInstantiator: NodeInstantiator) {

    fun addNode(context: Context, nodeGroupName: String) {
        val newNodeInstance = synchronized(this){
            val nodeGroup = context.nodes[nodeGroupName]!!
            val nodeInfo = nodeGroup.iterator().next()
            val currentNodeSize = nodeGroup.size
            val newInstanceX500 = nodeInfo.groupX500.copy(commonName = nodeInfo.groupX500.commonName + (currentNodeSize)).toString()
            val newInstanceName = nodeGroupName + (currentNodeSize)
            val nextNodeInfo = nodeInfo.copy(
                    instanceX500 = newInstanceX500,
                    instanceName = newInstanceName,
                    fqdn = nodeInstantiator.expectedFqdn(newInstanceName)
            )
            nodeGroup.add(nextNodeInfo)
            nextNodeInfo
        }
        nodeInstantiator.instantiateNodeInstance(newNodeInstance)
    }
}