package net.corda.bootstrapper.containers.instance.azure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerinstance.ContainerGroupRestartPolicy
import com.microsoft.azure.management.containerregistry.Registry
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import net.corda.bootstrapper.containers.registry.azure.create.parseCredentials

class AzureInstantiator(private val azure: Azure,
                        private val resourceGroupName: String,
                        private val region: Region,
                        private val registry: Registry,
                        private val networkName: String
) {
    fun instantiateContainer(imageId: String,
                             portsToOpen: List<Int>,
                             instanceName: String,
                             env: Map<String, String>? = null): String {

        println("Starting instantiation of container: $instanceName using $imageId")
        val registryAddress = registry.loginServerUrl()
        val (username, password) = registry.parseCredentials();
        val containerGroup = azure.containerGroups().define(instanceName + "-" + networkName)
                .withRegion(region)
                .withExistingResourceGroup(resourceGroupName)
                .withLinux()
                .withPrivateImageRegistry(registryAddress, username, password)
                .withoutVolume()
                .defineContainerInstance(instanceName)
                .withImage(imageId)
                .withExternalTcpPorts(*portsToOpen.toIntArray())
                .withEnvironmentVariables(env ?: emptyMap())
                .attach().withRestartPolicy(ContainerGroupRestartPolicy.ON_FAILURE)
                .withDnsPrefix("$instanceName-$networkName")
                .create()

        val fqdn = containerGroup.fqdn()
        println("GREAT SUCCESS! $instanceName is running at $fqdn with port(s) $portsToOpen exposed")
        return fqdn;
    }

    fun getExpectedFQDN(instanceName: String): String {
        return "$instanceName-$networkName-${region.name()}-azurecontainer.io"
    }

}