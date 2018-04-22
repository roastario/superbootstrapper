package net.corda.bootstrapper.nodes

class NodeInstanceRequest(val pushedNode: PushedNode, val nodeInstanceName: String, val actualX500: String, val expectedFqName: String) :
        PushedNode(pushedNode.builtNode, pushedNode.remoteImageName)