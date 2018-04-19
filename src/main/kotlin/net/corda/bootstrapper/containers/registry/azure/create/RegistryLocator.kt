package net.corda.bootstrapper.containers.registry.azure.create

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerregistry.AccessKeyType
import com.microsoft.azure.management.containerregistry.Registry
import net.corda.bootstrapper.Context

class RegistryLocator(private val azure: Azure,
                      private val context: Context) {


    fun getRegistry(): Registry {

        return azure.containerRegistries()
                .define(context.registryName)
                .withRegion(context.region)
                .withNewResourceGroup(context.resourceGroupName)
                .withBasicSku()
                .withRegistryNameAsAdminUser()
                .create()
    }


}

fun Registry.parseCredentials(): Pair<String, String> {
    val credentials = this.credentials
    return credentials.username() to
            (credentials.accessKeys()[AccessKeyType.PRIMARY]
                    ?: throw IllegalStateException("no registry password found"))
}


