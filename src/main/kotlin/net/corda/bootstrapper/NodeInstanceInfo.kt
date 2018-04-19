package net.corda.bootstrapper

import net.corda.bootstrapper.nodes.NodeNetworkConfig

data class NodeInstanceInfo(val name: String,
                            val nodeImageId: String,
                            val nodeNetworkConfig: NodeNetworkConfig,
                            val nodeX500: String)