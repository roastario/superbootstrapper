package net.corda.bootstrapper.nodes

import net.corda.bootstrapper.Context
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher

class NodePusher(private val containerPusher: ContainerPusher,
                 private val context: Context) {


    fun pushNode(builtNode: BuiltNode): PushedNode{
        val nodeImageName = containerPusher.pushContainerToImageRepository(builtNode.imageId,
                "node-${builtNode.name}", context.networkName)

        return PushedNode(builtNode, nodeImageName)
    }
}