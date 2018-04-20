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
import java.util.concurrent.CompletableFuture

fun main(args: Array<String>) {

    val parsedArgs = ArgParser(args).parseInto(::BootstrapArgParser)

    if (!parsedArgs.validate()) {
        println("Invalid parameters passed")
    }

    val baseDir = File(parsedArgs.workingDir)
    val cacheDir = File(baseDir, ".bootstrapper")

    val networkName = parsedArgs.name

    val azure = Azure.configure()
            .withLogLevel(LogLevel.BASIC)
            .authenticate(AzureCliCredentials.create())
            .withDefaultSubscription()

    val objectMapper = getContextMapper()

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

    if (parsedArgs.new) {
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        val nodeFinder = NodeFinder(baseDir, cacheDir)
        val notaryFinder = NotaryFinder(baseDir, cacheDir)

        val nodeCount = parseNodeCounts(nodeFinder, parsedArgs.nodes)
        println("Constructing new network with name: $networkName with nodes: $nodeCount")
        context = Context(networkName, Region.EUROPE_WEST)


        NetworkMapBuilder(containerPusher, azureInstantiator, context)
                .buildUploadAndInstantiate(baseDir, notaryFinder)

        val notaryLoadingFuture = CompletableFuture.runAsync({
            NotaryInstantiator(containerPusher, azureInstantiator, context)
                    .buildUploadAndInstantiate(notaryFinder)
        })

        val nodeLoadingFuture = CompletableFuture.runAsync({
            NodeInstantiator(containerPusher, azureInstantiator, context)
                    .buildUploadAndInstantiate(nodeFinder, nodeCount)
        })

        CompletableFuture.allOf(nodeLoadingFuture, notaryLoadingFuture).join()
        context.networkInitiated = true
        persistContext(contextFile, objectMapper, context)
    }

    parsedArgs.nodesToAdd.forEach {
        addNode(context, it.toLowerCase())
    }

    NetworkChecker(context, NotaryInstantiator(containerPusher, azureInstantiator, context),
            NodeInstantiator(containerPusher, azureInstantiator, context)).isNetworkHealthy()

    persistContext(contextFile, objectMapper, context)
}

private fun getContextMapper(): ObjectMapper {
    val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
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
    return objectMapper
}

private fun parseNodeCounts(nodeFinder: NodeFinder,
                            nodes: List<String>): Map<String, Int> {
    val argNodes = nodes.map {
        val split = it.split(":")
        val nodeName = split[0].toLowerCase()
        val nodeCount = split[1].toInt()
        nodeName to nodeCount
    }.toMap(HashMap())

    val dirNodes = nodeFinder.findNodes().map { (_, nodeConfigFile) ->
        nodeConfigFile.parentFile.name.toLowerCase() to 1
    }.toMap()

    dirNodes.forEach { (name, count) ->
        if (!argNodes.containsKey(name)) {
            argNodes[name] = count
        }
    }

    return argNodes
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

    val nodes by parser.adding("--node", "-N",
            help = "<nodeName>:<nodeCount> eg NodeOne:2 will result in 2 node instances from folder NodeOne").default { emptyList<String>() }

    val nodesToAdd by parser.adding("--add-node", "-a",
            help = "node folder to add to network").default { emptyList<String>() }

    val workingDir by parser.storing("--nodes-dir", "-d",
            help = "nodes directory").default(".")

    fun validate(): Boolean {
        return (this.new && nodesToAdd.isEmpty() || !this.new && nodesToAdd.isNotEmpty())
    }

}

