package net.corda.bootstrapper.nodes

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort

open class BuiltNode(val foundNode: FoundNode, val imageId: String, val x500: CordaX500Name, val rpcHostAndPort: NetworkHostAndPort) :
        FoundNode(foundNode.configFile, foundNode.credentials) {


}