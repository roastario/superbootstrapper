package net.corda.bootstrapper.containers.instance.azure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerinstance.ContainerGroupRestartPolicy
import com.microsoft.azure.management.containerregistry.Registry
import net.corda.bootstrapper.Context
import net.corda.bootstrapper.containers.registry.azure.create.parseCredentials
import net.corda.bootstrapper.networkmap.AzureNetworkStore

class AzureInstantiator(private val azure: Azure,
                        private val registry: Registry,
                        private val context: Context
) {
    fun instantiateContainer(imageId: String,
                             portsToOpen: List<Int>,
                             instanceName: String,
                             env: Map<String, String>? = null,
                             azureNetworkStore: AzureNetworkStore): String {

        println("Starting instantiation of container: $instanceName using $imageId")
        val registryAddress = registry.loginServerUrl()
        val (username, password) = registry.parseCredentials();
        val mountName = "node-setup"
        val containerGroup = azure.containerGroups().define(buildIdent(instanceName))
                .withRegion(context.region)
                .withNewResourceGroup(context.resourceGroupName)
                .withLinux()
                .withPrivateImageRegistry(registryAddress, username, password)
                .defineVolume(mountName)
                .withExistingReadWriteAzureFileShare(azureNetworkStore.shareName)
                .withStorageAccountName(azureNetworkStore.storageAccountName)
                .withStorageAccountKey(azureNetworkStore.storageAccountKey)
                .attach()
                .defineContainerInstance(instanceName)
                .withImage(imageId)
                .withExternalTcpPorts(*portsToOpen.toIntArray())
                .withVolumeMountSetting(mountName, "/opt/corda/additional-node-infos")
                .withEnvironmentVariables(env ?: emptyMap())
                .attach().withRestartPolicy(ContainerGroupRestartPolicy.ON_FAILURE)
                .withDnsPrefix(buildIdent(instanceName))
                .create()

        val fqdn = containerGroup.fqdn()
        println("GREAT SUCCESS! $instanceName is running at $fqdn with port(s) $portsToOpen exposed")
        return fqdn;
    }

    private fun buildIdent(instanceName: String) = "$instanceName-${context.networkName}"

    fun getExpectedFQDN(instanceName: String): String {
        return "${buildIdent(instanceName)}.${context.region.name()}.azurecontainer.io"
    }

    fun isContainerRunning(instanceName: String): Boolean {
        val containerGroup = azure.containerGroups().getByResourceGroup(context.resourceGroupName, buildIdent(instanceName))
                ?: return false
        return containerGroup.containers().filter { (containerName, _) ->
            containerName == instanceName
        }.map { (_, second) -> second }
                .filter { it.instanceView().currentState().state() == "Running" }.isNotEmpty()
    }

}