package net.corda.bootstrapper

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.create.RegistryLocator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.networkmap.NetworkMapBuilder
import net.corda.bootstrapper.nodes.NodeFinder
import net.corda.bootstrapper.nodes.NodeInstantiator
import net.corda.bootstrapper.notaries.NotaryFinder
import net.corda.bootstrapper.notaries.NotaryInstantiator
import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

fun main(args: Array<String>) {

    val parsedArgs = ArgParser(args).parseInto(::BootstrapArgParser)

    val baseDir = File("/home/stefano/superbs-scratch/")
    val cacheDir = File(baseDir, ".bootstrapper")

    val networkName = parsedArgs.name

    val azure = Azure.configure()
            .withLogLevel(LogLevel.BASIC)
            .authenticate(AzureCliCredentials.create())
            .withDefaultSubscription()


    val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.registerModule(object : SimpleModule() {}.let {
        it.addSerializer(Region::class.java, object : JsonSerializer<Region>() {
            override fun serialize(value: Region, gen: JsonGenerator, serializers: SerializerProvider?) {
                gen.writeString(value.name())
            }
        })
        it.addDeserializer(Region::class.java, object : JsonDeserializer<Region>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Region {
                return Region.findByLabelOrName(p.valueAsString)
            }
        })
    })

    val contextFile = File(cacheDir, "$networkName.yaml")
    var context = contextFile.let {
        if (it.exists()) {
            it.inputStream().use {
                objectMapper.readValue(it, Context::class.java)
            }
        } else {
            Context(networkName, Region.EUROPE_WEST)
        }
    }

    val registryLocator = RegistryLocator(azure, context)
    val registry = registryLocator.getRegistry()
    val containerPusher = ContainerPusher(azure, registry)
    val azureInstantiator = AzureInstantiator(azure, registry, context)

    if (parsedArgs.new || !context.networkInitiated) {
        val nodeCount = parseNodeCounts(parsedArgs.nodes, baseDir)
        println("Constructing new network with name: $networkName with nodes: $nodeCount")
        context = Context(networkName, Region.EUROPE_WEST)
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        val nodeFinder = NodeFinder(baseDir, cacheDir)
        val notaryFinder = NotaryFinder(baseDir, cacheDir);

        NetworkMapBuilder(containerPusher, azureInstantiator, context)
                .withBaseDir(baseDir)
                .withNotaries(notaryFinder)
                .buildUploadAndInstantiate()

        val notaryLoadingFuture = CompletableFuture.runAsync({
            NotaryInstantiator(containerPusher, azureInstantiator, context)
                    .withNotaries(notaryFinder)
                    .buildUploadAndInstantiate()
        })

        val nodeLoadingFuture = CompletableFuture.runAsync({
            NodeInstantiator(containerPusher, azureInstantiator, context)
                    .withNodes(nodeFinder)
                    .withNodeCounts(nodeCount)
                    .buildUploadAndInstantiate()
        })

        CompletableFuture.allOf(nodeLoadingFuture, notaryLoadingFuture).join()
        context.networkInitiated = true
        persistContext(contextFile, objectMapper, context)
    }

    val b3iNodeName = "b3i"
    addNode(context, b3iNodeName)

    NetworkChecker(context, NotaryInstantiator(containerPusher, azureInstantiator, context),
            NodeInstantiator(containerPusher, azureInstantiator, context)).isNetworkHealthy()

    persistContext(contextFile, objectMapper, context)
}

private fun parseNodeCounts(nodes: List<String>, baseDir: File): Map<String, Int> {

    val map = nodes.map {
        val split = it.split(":")
        val nodeName = split[0]
        val nodeCount = split[1].toInt()
        nodeName to nodeCount
    }.toMap()

    map.forEach {
        if (!File(baseDir, it.key).exists()) {
            println("Requested node: ${it.key} but not present in working directory")
            throw IllegalStateException("Requested node: ${it.key} but not present in working directory")
        }
    }

    return map;

}

private fun persistContext(contextFile: File, objectMapper: ObjectMapper, context: Context?) {
    contextFile.outputStream().use {
        objectMapper.writeValue(it, context)
    }
}

private fun addNode(context: Context, nodeGroupName: String) {
    val nodeGroup = context.nodes[nodeGroupName]!!
    val nodeInfo = nodeGroup.iterator().next()
    val currentNodeSize = nodeGroup.size

    val nextNodeInfo = nodeInfo.copy(
            nodeX500 = nodeInfo.nodeNetworkConfig.x500.copy(commonName = nodeInfo.nodeNetworkConfig.x500.commonName + (currentNodeSize)).toString(),
            name = nodeGroupName + (currentNodeSize)
    )
    nodeGroup.add(nextNodeInfo)
}

class BootstrapArgParser(parser: ArgParser) {

    val name by parser.storing("--network-name",
            help = "network name")

    val new by parser.flagging("-n", "--new",
            help = "enable verbose mode").default(true)

    val nodes by parser.adding("-N",
            help = "<nodeName>:<nodeCount> eg NodeOne:2 will result in 2 node instances from folder NodeOne").default { emptyList<String>() }

}

