package net.corda.bootstrapper.nodes

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import java.io.File
import java.util.stream.Collectors

class NodeFinder(private val scratchDir: File, private val cacheDir: File) {


    private lateinit var nodes: List<FoundNode>


    private fun findNodes(): List<Pair<Config, File>> {
        return FileUtils.listFiles(
                scratchDir,
                RegexFileFilter("node.conf"),
                DirectoryFileFilter.DIRECTORY
        ).toSet().map {
            try {
                ConfigFactory.parseFile(it) to it
            } catch (t: Throwable) {
                null
            }
        }.filterNotNull()
                .filter { !it.first.hasPath("notary") }.map { (nodeConfig, nodeConfigFile) ->
            println("We've found a node with name: ${nodeConfigFile.parentFile.name}")
            nodeConfig to nodeConfigFile
        }

    }


    private fun copyNodesToCacheFolder(): List<FoundNode> {
        if (!::nodes.isInitialized) {
            nodes = findNodes().parallelStream().map { (nodeConfig, nodeConfigFile) ->
                val nodeCacheDir = File(cacheDir, nodeConfigFile.parentFile.name)
                nodeCacheDir.deleteRecursively()
                println("copying: ${nodeConfigFile.parentFile} to $nodeCacheDir")
                nodeConfigFile.parentFile.copyRecursively(nodeCacheDir, overwrite = true)
                copyBootstrapperFiles(nodeCacheDir)
                val configInCacheDir = File(nodeCacheDir, "node.conf")
                println("Applying precanned config " + configInCacheDir)
                val rpcSettings = getDefaultRpcSettings()
                mergeConfigs(configInCacheDir, rpcSettings)
                val credentials = getCredentials(configInCacheDir)
                return@map FoundNode(configInCacheDir, credentials)
            }.collect(Collectors.toList())
        }
        return nodes
    }

    fun foundNodes(): List<FoundNode> {
        return copyNodesToCacheFolder()
    }

    private fun copyBootstrapperFiles(nodeCacheDir: File) {
        this.javaClass.classLoader.getResourceAsStream("node-Dockerfile").use { nodeDockerFileInStream ->
            val nodeDockerFile = File(nodeCacheDir, "Dockerfile")
            println("Adding some secret sauce to: " + nodeDockerFile)
            nodeDockerFile.outputStream().use { nodeDockerFileOutStream ->
                nodeDockerFileInStream.copyTo(nodeDockerFileOutStream)
            }
        }

        this.javaClass.classLoader.getResourceAsStream("run-corda-node.sh").use { nodeRunScriptInStream ->
            val nodeRunScriptFile = File(nodeCacheDir, "run-corda.sh")
            println("Adding some secret sauce to: " + nodeRunScriptFile)
            nodeRunScriptFile.outputStream().use { nodeDockerFileOutStream ->
                nodeRunScriptInStream.copyTo(nodeDockerFileOutStream)
            }
        }

        this.javaClass.classLoader.getResourceAsStream("node_info_watcher.sh").use { nodeRunScriptInStream ->
            val nodeInfoWatcherFile = File(nodeCacheDir, "node_info_watcher.sh")
            println("Copying nodeinfo watcher" + nodeInfoWatcherFile)
            nodeInfoWatcherFile.outputStream().use { nodeDockerFileOutStream ->
                nodeRunScriptInStream.copyTo(nodeDockerFileOutStream)
            }
        }
    }

    private fun getDefaultRpcSettings(): ConfigValue {
        return javaClass
                .classLoader
                .getResourceAsStream("rpc-settings.conf")
                .reader().use {
            ConfigFactory.parseReader(it)
        }.getValue("rpcSettings")
    }

    private fun mergeConfigs(configInCacheDir: File, rpcSettings: ConfigValue) {
        val trimmedConfig = ConfigFactory.parseFile(configInCacheDir)
                .withoutPath("compatibilityZoneURL")
                .withoutPath("p2pAddress")
                .withValue("rpcSettings", rpcSettings)

        configInCacheDir.outputStream().use {
            trimmedConfig.root().render(ConfigRenderOptions
                    .defaults()
                    .setOriginComments(false)
                    .setComments(false)
                    .setFormatted(true)
                    .setJson(false)).byteInputStream().copyTo(it)
        }
    }

    private fun getCredentials(configInCacheDir: File): ConfigValue {
        val config = ConfigFactory.parseFile(configInCacheDir)
        return config.getValue("rpcUsers")
    }


}

