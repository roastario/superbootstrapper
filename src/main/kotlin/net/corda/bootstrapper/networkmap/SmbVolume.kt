package net.corda.bootstrapper.networkmap

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.storage.StorageAccount
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.file.CloudFile
import com.typesafe.config.ConfigFactory
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.notaries.FoundNotary
import net.corda.bootstrapper.serialization.SerializationEngine
import net.corda.core.crypto.Crypto
import net.corda.core.internal.signWithCert
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NotaryInfo
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import java.io.File
import java.security.cert.X509Certificate
import java.time.Instant
import javax.security.auth.x500.X500Principal


class AzureNetworkStore(private val azure: Azure, private val context: Context) {

    private val storageAccount = getStorageAccount()

    private val accKeys = storageAccount.keys[0]


    private val cloudFileShare = CloudStorageAccount.parse(
            "DefaultEndpointsProtocol=https;" +
                    "AccountName=${storageAccount.name()};" +
                    "AccountKey=${accKeys.value()};" +
                    "EndpointSuffix=core.windows.net"
    )
            .createCloudFileClient()
            .getShareReference("nodeinfos")

    val networkParamsFolder = cloudFileShare.rootDirectoryReference.getDirectoryReference("network-params")
    val nodeInfoFolder = cloudFileShare.rootDirectoryReference.getDirectoryReference("node-infos")

    val shareName: String = cloudFileShare.name
    val storageAccountName: String
        get() = context.storageAccountName
    val storageAccountKey: String
        get() = accKeys.value()

    private val networkMapCa = createDevNetworkMapCa(DEV_ROOT_CA)
    private val networkMapCert: X509Certificate = networkMapCa.certificate
    private val keyPair = networkMapCa.keyPair

    init {
        SerializationEngine.init()
        cloudFileShare.createIfNotExists()
        networkParamsFolder.createIfNotExists()
        nodeInfoFolder.createIfNotExists()
    }

    private fun getStorageAccount(): StorageAccount {

        return azure.storageAccounts().getByResourceGroup(context.resourceGroupName, context.storageAccountName)
                ?: azure.storageAccounts().define(context.storageAccountName)
                        .withRegion(context.region)
                        .withNewResourceGroup(context.resourceGroupName).withAccessFromAllNetworks()
                        .create()
    }

    fun storeNotaryInfo(notaries: List<FoundNotary>) {
        val networkParamsFile = networkParamsFolder.getFileReference("network-parameters")
        networkParamsFile.deleteIfExists()
        println("Storing network-params at location: " + networkParamsFile.uri)
        val networkParameters = convertNodeIntoToNetworkParams(notaries.map { it.configFile to it.nodeInfoFile })
        networkParamsFile.uploadFromByteArray(networkParameters.signWithCert(keyPair.private, networkMapCert).serialize().bytes)
    }

    private fun convertNodeIntoToNetworkParams(notaryFiles: List<Pair<File, File>>): NetworkParameters {
        val notaryInfos = notaryFiles.map { (configFile, nodeInfoFile) ->
            val validating = ConfigFactory.parseFile(configFile).getConfig("notary").getBoolean("validating")
            nodeInfoFile.readBytes().deserialize<SignedNodeInfo>().verified().let { NotaryInfo(it.legalIdentities.first(), validating) }
        }

        return notaryInfos.let {
            NetworkParameters(
                    minimumPlatformVersion = 1,
                    notaries = it,
                    maxMessageSize = 10485760,
                    maxTransactionSize = Int.MAX_VALUE,
                    modifiedTime = Instant.now(),
                    epoch = 10,
                    whitelistedContractImplementations = emptyMap())
        }
    }


}

fun CloudFile.uploadFromByteArray(array: ByteArray) {
    this.uploadFromByteArray(array, 0, array.size)
}

private fun createDevNetworkMapCa(rootCa: CertificateAndKeyPair): CertificateAndKeyPair {
    val keyPair = Crypto.generateKeyPair()
    val cert = X509Utilities.createCertificate(
            CertificateType.NETWORK_MAP,
            rootCa.certificate,
            rootCa.keyPair,
            X500Principal("CN=Network Map,O=R3 Ltd,L=London,C=GB"),
            keyPair.public)
    return CertificateAndKeyPair(cert, keyPair)
}



