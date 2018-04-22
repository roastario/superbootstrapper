package net.corda.bootstrapper.notaries

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
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

    private lateinit var notaries: List<FoundNotary>


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
                    DirectoryFileFilter.DIRECTORY
            ).single()
            val nodeInfo = notaryNodeInfo.toPath().readObject<SignedNodeInfo>().verified()
            println("OK - notary has legal identity: ${nodeInfo.legalIdentities.first()}")
            configFile to notaryNodeInfo
        }.collect(toList())
    }


    private fun copyNotariesIntoCacheFolderAndGatherNodeInfos(): List<FoundNotary> {
        if (!::notaries.isInitialized) {
            val foundNotaries = getNotaryInfosInDir().parallelStream().map { (notaryConfigFile, notaryNodeInfoFile) ->
                val nodeCacheDir = prepareNotaryCacheFolder(notaryConfigFile)
                copyBootstrapperFilesIntoCacheDir(nodeCacheDir)
                val configInCacheDir = File(nodeCacheDir, "node.conf")
                println("Applying precanned config " + configInCacheDir)
                val rpcSettings = getDefaultRpcSettings()
                mergeConfigs(configInCacheDir, rpcSettings)
                val credentials = getCredentials(configInCacheDir)
                return@map FoundNotary(File(nodeCacheDir, notaryConfigFile.name), File(nodeCacheDir, notaryNodeInfoFile.name), credentials)
            }.collect(toList())
            this.notaries = foundNotaries!!
        }
        return notaries
    }

    private fun prepareNotaryCacheFolder(notaryConfigFile: File): File {
        val notaryCacheDir = File(cacheDir, notaryConfigFile.parentFile.name)
        notaryCacheDir.deleteRecursively()
        println("copying: ${notaryConfigFile.parentFile} to $notaryCacheDir")
        notaryConfigFile.parentFile.copyRecursively(notaryCacheDir, overwrite = true)
        return notaryCacheDir
    }

    private fun getCredentials(configInCacheDir: File): ConfigValue {
        val config = ConfigFactory.parseFile(configInCacheDir)
        return config.getValue("rpcUsers")
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

    private fun getDefaultRpcSettings(): ConfigValue {
        return javaClass
                .classLoader
                .getResourceAsStream("rpc-settings.conf")
                .reader().use {
            ConfigFactory.parseReader(it)
        }.getValue("rpcSettings")
    }

    private fun copyBootstrapperFilesIntoCacheDir(nodeCacheDir: File) {
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

        this.javaClass.classLoader.getResourceAsStream("node_info_watcher.sh").use { nodeRunScriptInStream ->
            val nodeInfoWatcherFile = File(nodeCacheDir, "node_info_watcher.sh")
            println("Copying nodeinfo watcher" + nodeInfoWatcherFile)
            nodeInfoWatcherFile.outputStream().use { nodeDockerFileOutStream ->
                nodeRunScriptInStream.copyTo(nodeDockerFileOutStream)
            }
        }
    }

    fun foundNotaries(): List<FoundNotary> {
        if (!::notaries.isInitialized) {
            copyNotariesIntoCacheFolderAndGatherNodeInfos()
        }
        return notaries
    }

}

data class FoundNotary(val configFile: File, val nodeInfoFile: File, val credentials: ConfigValue)
