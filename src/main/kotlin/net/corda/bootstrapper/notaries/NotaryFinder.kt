package net.corda.bootstrapper.notaries

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import net.corda.bootstrapper.serialization.SerializationEngine
import net.corda.core.internal.readObject
import net.corda.nodeapi.internal.SignedNodeInfo
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import java.io.File
import java.util.stream.Collectors.toList

class NotaryFinder(val dirToSearch: File, val cacheDir: File) {
    init {
        SerializationEngine.init()
    }

    private fun findNotaries(): List<Pair<Config, File>> {
        return FileUtils.listFiles(
                dirToSearch,
                RegexFileFilter("node.conf"),
                DirectoryFileFilter.DIRECTORY
        ).map {
            try {
                ConfigFactory.parseFile(it) to it
            } catch (t: Throwable) {
                null
            }
        }.filterNotNull()
                .filter { it.first.hasPath("notary") }
    }

    private fun getNotaryInfosInDir(): List<Pair<File, File>> {
        val notaryConfigFiles = findNotaries().map { it.first.getConfig("notary").getBoolean("validating") to it.second }
        println("We've found: ${notaryConfigFiles.size} notaries")
        return notaryConfigFiles.parallelStream().map { (isValidating, configFile) ->
            val workingDirectory = configFile.parentFile.toPath()
            println("Entering: ${workingDirectory.toAbsolutePath()} to generate node-info")
            val started = ProcessBuilder()
                    .command(listOf("java", "-jar", "corda.jar", "--just-generate-node-info"))
                    .directory(workingDirectory.toFile())
                    .start()
            started.waitFor()
            val notaryNodeInfo = FileUtils.listFiles(
                    workingDirectory.toFile(),
                    RegexFileFilter("nodeInfo-.*"),
                    object : DirectoryFileFilter(){
                        override fun accept(file: File): Boolean {
                            return !file.isDirectory
                        }
                    }
            ).single()
            val nodeInfo = notaryNodeInfo.toPath().readObject<SignedNodeInfo>().verified()
            println("OK - notary has legal identity: ${nodeInfo.legalIdentities.first()}")
            configFile to notaryNodeInfo
        }.collect(toList())
    }


    private lateinit var notaries: List<Pair<File, File>>

    fun copyNotariesIntoCacheFolderAndGatherNodeInfos(): List<Pair<File, File>> {
        if (!::notaries.isInitialized) {
            val notaryFolders = getNotaryInfosInDir().parallelStream().map { (notaryConfigFile, notaryNodeInfoFile) ->
                val nodeCacheDir = File(cacheDir, notaryConfigFile.parentFile.name)
                println("copying: ${notaryConfigFile.parentFile} to $nodeCacheDir")
                notaryConfigFile.parentFile.copyRecursively(nodeCacheDir, overwrite = true)

                this.javaClass.classLoader.getResourceAsStream("notary-Dockerfile").use { nodeDockerFileInStream ->
                    val nodeDockerFile = File(nodeCacheDir, "Dockerfile")
                    println("Adding some secret sauce to: " + nodeDockerFile)
                    nodeDockerFile.outputStream().use { nodeDockerFileOutStream ->
                        nodeDockerFileInStream.copyTo(nodeDockerFileOutStream)
                    }
                }

                this.javaClass.classLoader.getResourceAsStream("run-corda-notary.sh").use { nodeRunScriptInStream ->
                    val nodeRunScriptFile = File(nodeCacheDir, "run-corda.sh")
                    println("Adding some secret sauce to: " + nodeRunScriptFile)
                    nodeRunScriptFile.outputStream().use { nodeDockerFileOutStream ->
                        nodeRunScriptInStream.copyTo(nodeDockerFileOutStream)
                    }
                }

                val configInCacheDir = File(nodeCacheDir, "node.conf")
                println("Applying precanned config " + configInCacheDir)
                val rpcSettings = this.javaClass
                        .classLoader
                        .getResourceAsStream("rpc-settings.conf")
                        .reader().use {
                    ConfigFactory.parseReader(it)
                }.getValue("rpcSettings")

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
                return@map File(nodeCacheDir, notaryConfigFile.name) to File(nodeCacheDir, notaryNodeInfoFile.name)
            }.collect(toList())
            this.notaries = notaryFolders
        }
        return notaries
    }

    fun foundNotaries(): List<Pair<File, File>> {
        if (!::notaries.isInitialized) {
            copyNotariesIntoCacheFolderAndGatherNodeInfos()
        }
        return notaries
    }
}