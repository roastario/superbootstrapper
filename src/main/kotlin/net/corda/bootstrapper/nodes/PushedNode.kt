package net.corda.bootstrapper.nodes

open class PushedNode(val builtNode: BuiltNode, val remoteImageName: String) : BuiltNode(
        builtNode.foundNode,
        builtNode.imageId,
        builtNode.x500,
        builtNode.rpcHostAndPort) {
}