package net.corda.bootstrapper.containers.registry.azure.create

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerregistry.AccessKeyType
import com.microsoft.azure.management.containerregistry.Registry
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.azure.management.resources.fluentcore.utils.SdkContext

class RegistryLocator(private val azure: Azure,
                      private val resourceGroupName: String = SdkContext.randomResourceName("rgACR", 15),
                      private val containerRegistryName: String = SdkContext.randomResourceName("cordaBootrapper", 20),
                      private val containerRegistryRegion: Region = Region.EUROPE_WEST) {


    fun getRegistry(): Registry {

        val azureRegistry = azure.containerRegistries()
                .define(containerRegistryName)
                .withRegion(containerRegistryRegion)
                .withNewResourceGroup(resourceGroupName)
                .withBasicSku()
                .withRegistryNameAsAdminUser()
                .create()

        return azureRegistry
    }


}

fun Registry.parseCredentials(): Pair<String, String> {
    val credentials = this.credentials
    return credentials.username() to
            (credentials.accessKeys()[AccessKeyType.PRIMARY]
                    ?: throw IllegalStateException("no registry password found"))
}


