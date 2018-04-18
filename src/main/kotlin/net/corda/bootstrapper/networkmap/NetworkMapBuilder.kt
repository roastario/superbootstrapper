package net.corda.bootstrapper.networkmap

import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.notaries.NotaryFinder
import net.corda.core.internal.createDirectories
import java.io.File
import java.net.URL
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


class NetworkMapBuilder(val networkName: String,
                        val pusher: ContainerPusher,
                        val instaniator: AzureInstantiator) {

    private val branchName = "off_piste"
    private val networkMapRemoteImageName = "this-is-the-network-map"
    private val networkMapPort = 8080

    private lateinit var notaryFinder: NotaryFinder;
    private lateinit var baseDir: File


    private fun getNetworkMap(baseDir: File): File {
        val cacheDir = File(baseDir, ".bootstrapper")
        val website = URL("https://github.com/roastario/spring-boot-network-map/archive/$branchName.zip")
        val zippedStream = ZipInputStream(website.openStream())
        zippedStream.use { autoCloseZipStream ->
            var zipEntry: ZipEntry? = autoCloseZipStream.nextEntry;
            while (zipEntry != null) {
                val path = Paths.get(cacheDir.toPath().toString(), zipEntry.name)
                if (!zipEntry.isDirectory) {
                    path.toFile().outputStream().use { autoCloseFos ->
                        autoCloseZipStream.copyTo(autoCloseFos)
                    }
                    if (zipEntry.name.endsWith("gradlew")) {
                        path.toFile().setExecutable(true)
                    }
                } else {
                    path.createDirectories()
                }
                zipEntry = autoCloseZipStream.nextEntry
            }
        }
        return File(cacheDir, "spring-boot-network-map-$branchName")
    }


    private fun buildNetworkMap(downloadedTo: File): String {
        println("################ STARTING BUILD OF SECRET SAUCE ################")

        val startedBuild = ProcessBuilder().directory(downloadedTo).command(listOf("./gradlew", "clean", "build", "docker")).inheritIO().start()
        val result = startedBuild.waitFor()

        println(if (result == 0)
            "################ FINISHED BUILD OF SECRET SAUCE ################"
        else "################ OOPS SECRET SAUCE HAS FAILED ################")

        return "net.corda/spring-boot-network-map-${branchName}"
    }


    private fun copyNotariesIntoNetworkMap(nmExtractedFolder: File, files: List<Pair<File, File>>) {
        val nodesDirectory = File(nmExtractedFolder, "nodes")
        if (nodesDirectory.exists()) {
            nodesDirectory.mkdirs()
        }
        files.forEach {
            val confFile = it.first
            val nodeInfo = it.second
            val notaryName = confFile.parentFile.name
            val notaryDirectory = File(nodesDirectory, notaryName)
            val confFileInNotaryDirectory = File(notaryDirectory, confFile.name)
            val infoFileInNotaryDirectory = File(notaryDirectory, nodeInfo.name)
            confFile.copyTo(confFileInNotaryDirectory)
            nodeInfo.copyTo(infoFileInNotaryDirectory)
        }

        val specialDocker = this.javaClass.classLoader.getResourceAsStream("nm-Dockerfile")
        val oldDocker = File(nmExtractedFolder, "Dockerfile")

        specialDocker.use { specialDockerStream ->
            oldDocker.outputStream().use { oldDockerStream ->
                specialDockerStream.copyTo(oldDockerStream)
            }
        }

    }


    fun withNotaries(copiedNotariesNodeInfos: NotaryFinder): NetworkMapBuilder {
        this.notaryFinder = copiedNotariesNodeInfos
        return this;
    }


    fun withBaseDir(baseDir: File): NetworkMapBuilder {
        this.baseDir = baseDir;
        return this
    }


    fun buildUploadAndInstantiate(): String {
        val downloadedNetworkMap = getNetworkMap(baseDir)
        val copiedNotariesNodeInfos = notaryFinder.copyNotariesIntoCacheFolderAndGatherNodeInfos()
        copyNotariesIntoNetworkMap(downloadedNetworkMap, copiedNotariesNodeInfos)
        val localImageName = buildNetworkMap(downloadedNetworkMap);
        val remoteNetworkMapImageName = pusher.pushContainerToImageRepository(localImageName, networkMapRemoteImageName, networkName)
        val networkMapFQDN = instaniator.instantiateContainer(remoteNetworkMapImageName, listOf(networkMapPort), "this-is-the-network-map")
        return "http://$networkMapFQDN:$networkMapPort"
    }
}