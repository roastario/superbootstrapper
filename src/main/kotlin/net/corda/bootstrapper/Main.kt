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
import net.corda.bootstrapper.networkmap.AzureNetworkStore
import net.corda.bootstrapper.nodes.*
import net.corda.bootstrapper.notaries.NotaryFinder
import net.corda.bootstrapper.notaries.NotaryInstantiator
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.streams.toList

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
    val containerPusher = ContainerPusher(azure, registryLocator.getRegistry())
    val azureInstantiator = AzureInstantiator(azure, registryLocator.getRegistry(), context)
    val azureNetworkStore = AzureNetworkStore(azure, context)

    if (parsedArgs.new) {
        context = Context(networkName, Region.EUROPE_WEST)
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        val nodeFinder = NodeFinder(baseDir, cacheDir)
        val notaryFinder = NotaryFinder(baseDir, cacheDir)
        val nodeCount = parseNodeCounts(nodeFinder, parsedArgs.nodes)
        println("Constructing new network with name: $networkName with nodes: $nodeCount")


        val notaryInstantiator = NotaryInstantiator(containerPusher, azureInstantiator, azureNetworkStore, context)
        val nodeInstantiator = NodeInstantiator(containerPusher, azureInstantiator, azureNetworkStore, context)
        val nodeBuilder = NodeBuilder()
        val nodePusher = NodePusher(containerPusher, context)


        val foundNotaries = notaryFinder.foundNotaries()
        azureNetworkStore.storeNotaryInfo(foundNotaries)

        val notaryLoadingFuture = CompletableFuture.runAsync({
            NotaryInstantiator(containerPusher, azureInstantiator, azureNetworkStore, context)
                    .buildUploadAndInstantiate(notaryFinder)
        })

        val nodeInstances = nodeFinder.foundNodes().parallelStream()
                .map { foundNode ->
                    nodeBuilder.buildNode(foundNode)
                }.map { builtNode ->
                    nodePusher.pushNode(builtNode)
                }.map { pushedNode ->
                    nodeInstantiator.createInstanceRequests(pushedNode, nodeCount)
                }.toList().flatten().parallelStream()
                .map {
                    nodeInstantiator.instantiateNodeInstance(it)
                    context.registerNode(it)
                    it
                }.toList()



        notaryLoadingFuture.join()
        context.networkInitiated = true
        persistContext(contextFile, objectMapper, context)
    } else {
//        val nodeAdder = NodeAdder(context, NodeInstantiator(containerPusher, azureInstantiator, azureNetworkStore, context))
//        parsedArgs.nodesToAdd.parallelStream().forEach {
//            nodeAdder.addNode(context, it.toLowerCase())
//        }
//        persistContext(contextFile, objectMapper, context)

        val next = context.nodes.entries.iterator().next().value.iterator().next()
        containerPusher.pushContainerToImageRepository(next.localImageId, "TEST", "testnet")
    }

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

//TODO - rework so does not cause copy
private fun parseNodeCounts(nodeFinder: NodeFinder,
                            nodes: List<String>): Map<String, Int> {
    val argNodes = nodes.map {
        val split = it.split(":")
        val nodeName = split[0].toLowerCase()
        val nodeCount = split[1].toInt()
        nodeName to nodeCount
    }.toMap(HashMap())
    val dirNodes = nodeFinder.foundNodes().map { it.name to 1 }.toMap()
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

