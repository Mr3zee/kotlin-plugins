package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "KotlinPluginsSettings",
    storages = [Storage("kotlin-plugins-settings.xml")]
)
class KotlinPluginsSettingsService : SimplePersistentStateComponent<KotlinPluginsSettingsState>(KotlinPluginsSettingsState())

class KotlinPluginsSettingsState : BaseState() {
    val plugins by treeSet<KotlinPluginDescriptor>()

    init {
        if (plugins.add(KotlinPluginDescriptor.KotlinxRpc.CLI) or
            plugins.add(KotlinPluginDescriptor.KotlinxRpc.K2) or
            plugins.add(KotlinPluginDescriptor.KotlinxRpc.BACKEND) or
            plugins.add(KotlinPluginDescriptor.KotlinxRpc.COMMON)
        ) {
            incrementModificationCount()
        }
    }
}

data class KotlinPluginDescriptor(
    val repoUrl: String,
    val groupId: String,
    val artifactId: String,
) : Comparable<KotlinPluginDescriptor>, BaseState() {
    override fun compareTo(other: KotlinPluginDescriptor): Int {
        return id.compareTo(other.id)
    }

    override fun equals(other: Any?): Boolean {
        return other is KotlinPluginDescriptor && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return id
    }

    val id get() = "$groupId:$artifactId"

    object KotlinxRpc {
        private fun kotlinxRpc(suffix: String) = KotlinPluginDescriptor(
            repoUrl = "https://maven.pkg.jetbrains.space/public/p/krpc/for-ide",
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-rpc-compiler-plugin$suffix",
        )

        val CLI = kotlinxRpc("-cli")
        val K2 = kotlinxRpc("-k2")
        val BACKEND = kotlinxRpc("-backend")
        val COMMON = kotlinxRpc("-common")
    }
}
