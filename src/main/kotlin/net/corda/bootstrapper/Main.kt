package net.corda.bootstrapper

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.create.RegistryLocator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.networkmap.NetworkMapBuilder
import net.corda.bootstrapper.nodes.NodeBuilder
import net.corda.bootstrapper.nodes.NodeFinder
import net.corda.bootstrapper.notaries.NotaryFinder
import net.corda.bootstrapper.notaries.NotaryInstantiator
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.randomOrNull
import java.io.File
import java.util.stream.IntStream

fun main(args: Array<String>) {

    listOf<String>().randomOrNull()

    val scratchDir = File("/home/stefano/superbs-scratch/")
    val cacheDir = File("/home/stefano/superbs-scratch/.bootstrapper")
    if (cacheDir.exists()) cacheDir.deleteRecursively()

    val networkName = "this-is-stefanos-demo-network"

    val azure = Azure.configure()
            .withLogLevel(LogLevel.BASIC)
            .authenticate(AzureCliCredentials.create())
            .withDefaultSubscription()

    val resourceGroupName = "rgacr3c3061546"
    val containerRegistryName = "cordabootrapper17999"
    val region = Region.EUROPE_WEST

    val registryLocator = RegistryLocator(azure, resourceGroupName, containerRegistryName, region)
    val registry = registryLocator.getRegistry()
    val containerPusher = ContainerPusher(azure, registry)
    val azureInstantiator = AzureInstantiator(azure, resourceGroupName, region, registry, networkName)

    val nodeFinder = NodeFinder(scratchDir, cacheDir)
    val notaryFinder = NotaryFinder(scratchDir, cacheDir);

    val networkMapURL = NetworkMapBuilder(networkName, containerPusher, azureInstantiator)
            .withBaseDir(scratchDir)
            .withNotaries(notaryFinder)
            .buildUploadAndInstantiate()

    NotaryInstantiator(networkName, containerPusher, azureInstantiator)
            .withNetworkMapAddress(networkMapURL)
            .withNotaries(notaryFinder)
            .buildUploadAndInstantiate()

    NodeInstantiator(networkName, containerPusher, azureInstantiator)
            .withNodes(nodeFinder)
            .withNetworkMapAddress(networkMapURL)
            .withNodeCounts(mapOf("B3i" to 3))
            .buildUploadAndInstantiate()
}

class NodeInstantiator(val networkName: String,
                       val containerPusher: ContainerPusher,
                       val azureInstantiator: AzureInstantiator) {

    var nodeCount: Map<String, Int> = emptyMap()

    private lateinit var nodeFinder: NodeFinder

    fun withNodes(nodeFinder: NodeFinder): NodeInstantiator {
        this.nodeFinder = nodeFinder
        return this;
    }

    private lateinit var networkMapAddress: String

    fun withNetworkMapAddress(networkMapURL: String): NodeInstantiator {
        this.networkMapAddress = networkMapURL;
        return this;
    }

    fun buildUploadAndInstantiate() {
        val copiedNodeConfigFiles = nodeFinder.copyNodesToCacheFolder()
        val builtDockerNodes = NodeBuilder().buildNodes(copiedNodeConfigFiles)
        val nodesByName = builtDockerNodes.associateBy { (_, nodeDir, _) -> nodeDir.name.toLowerCase() }

        nodesByName.forEach { (nodeName, nodeInfo) ->
            val (nodeImageId, _, nodeNetworkConfig) = nodeInfo
            val nodeImageName = containerPusher.pushContainerToImageRepository(nodeImageId,
                    "node-$nodeName", networkName)
            val numberOfThisType = nodeCount.getOrDefault(nodeName, 1)

            IntStream.range(0, numberOfThisType).parallel().forEach { i ->
                val nodeInstanceName = nodeName + i
                val expectedFqdn = azureInstantiator.getExpectedFQDN(nodeInstanceName)
                azureInstantiator.instantiateContainer(
                        nodeImageName,
                        listOf(10020, nodeNetworkConfig.rpcHostAndPort.port),
                        nodeInstanceName,
                        mapOf("NETWORK_MAP" to networkMapAddress,
                                "OUR_NAME" to expectedFqdn,
                                "OUR_PORT" to 10020.toString(),
                                "X500" to buildX500(nodeNetworkConfig.x500, i))
                )
            }
        }

    }

    fun withNodeCounts(nodeCount: Map<String, Int>): NodeInstantiator {
        this.nodeCount = nodeCount.map { (nodeName, count) -> nodeName.toLowerCase() to count }.toMap()
        return this;
    }

    private fun buildX500(exitingX500: CordaX500Name, i: Int): String {
        return exitingX500.copy(commonName = (exitingX500.commonName ?: "") + i).toString()
    }

}


