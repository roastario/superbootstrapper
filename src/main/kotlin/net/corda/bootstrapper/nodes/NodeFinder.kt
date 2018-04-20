package net.corda.bootstrapper.nodes

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import java.io.File
import java.util.stream.Collectors

class NodeFinder(val scratchDir: File, val cacheDir: File) {


    private lateinit var foundNodes: List<Pair<Config, File>>

    fun findNodes(): List<Pair<Config, File>> {
        if (!::foundNodes.isInitialized) {
            this.foundNodes = FileUtils.listFiles(
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
        return foundNodes
    }


    fun copyNodesToCacheFolder(): List<File> {
        return findNodes().parallelStream().map { (nodeConfig, nodeConfigFile) ->
            val nodeCacheDir = File(cacheDir, nodeConfigFile.parentFile.name)
            println("copying: ${nodeConfigFile.parentFile} to $nodeCacheDir")
            nodeConfigFile.parentFile.copyRecursively(nodeCacheDir, overwrite = true)
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

            val newConfig = this.javaClass.classLoader.getResourceAsStream("node.conf").reader().use {
                ConfigFactory.parseReader(it)
            }.withValue("myLegalName", nodeConfig.getValue("myLegalName"))

            val configInCacheDir = File(nodeCacheDir, "node.conf")
            println("Applying precanned config " + configInCacheDir)
            configInCacheDir.outputStream().use { nodeDockerFileOutStream ->
                newConfig.root().render(ConfigRenderOptions
                        .defaults()
                        .setOriginComments(false)
                        .setComments(false)
                        .setFormatted(true)
                        .setJson(false)).byteInputStream().copyTo(nodeDockerFileOutStream)
            }

            return@map File(nodeCacheDir, nodeConfigFile.name)
        }.collect(Collectors.toList())
    }


}