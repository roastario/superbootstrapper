package net.corda.bootstrapper

import com.microsoft.azure.CloudException
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import net.corda.bootstrapper.containers.instance.azure.AzureInstantiator
import net.corda.bootstrapper.containers.registry.azure.create.RegistryLocator
import net.corda.bootstrapper.containers.registry.azure.push.ContainerPusher
import net.corda.bootstrapper.networkmap.AzureSmbVolume
import java.util.concurrent.CompletableFuture

data class AzureContext(val containerPusher: ContainerPusher, val instantiator: AzureInstantiator, val volume: AzureSmbVolume) {

    companion object {
        private val azure: Azure = kotlin.run {
            Azure.configure()
                    .withLogLevel(LogLevel.BASIC)
                    .authenticate(AzureCliCredentials.create())
                    .withDefaultSubscription()
        }

        fun fromContext(context: Context): AzureContext {
            val resourceGroup = try {
                azure.resourceGroups().getByName(context.resourceGroupName)
                        ?: azure.resourceGroups().define(context.resourceGroupName).withRegion(context.region).create()
            } catch (e: CloudException) {
                azure.resourceGroups().define(context.resourceGroupName).withRegion(context.region).create()
            }

            val registryLocatorFuture = CompletableFuture.supplyAsync {
                RegistryLocator(azure, context)
            }
            val containerPusherFuture = registryLocatorFuture.thenApplyAsync {
                ContainerPusher(azure, it.registry)
            }

            val azureInstantiatorFuture = registryLocatorFuture.thenApplyAsync {
                AzureInstantiator(azure, it.registry, context)
            }

            val azureNetworkStore = CompletableFuture.supplyAsync { AzureSmbVolume(azure, context) }

            return AzureContext(containerPusherFuture.get(), azureInstantiatorFuture.get(), azureNetworkStore.get())
        }
    }


}
