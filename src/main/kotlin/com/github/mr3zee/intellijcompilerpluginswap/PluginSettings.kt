package com.github.mr3zee.intellijcompilerpluginswap

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "CompilerPluginsSwapSettings",
    storages = [Storage("kotlin-fir-compiler-plugins-swap.xml")]
)
class PluginSettingsService : SimplePersistentStateComponent<PluginSettings>(PluginSettings())

class PluginSettings : BaseState() {
    val plugins by treeSet<PluginDescriptor>()

    init {
        if (plugins.add(PluginDescriptor.KotlinxRpc.CLI) or
            plugins.add(PluginDescriptor.KotlinxRpc.K2) or
            plugins.add(PluginDescriptor.KotlinxRpc.BACKEND) or
            plugins.add(PluginDescriptor.KotlinxRpc.COMMON)
        ) {
            incrementModificationCount()
        }
    }
}

data class PluginDescriptor(
    val repoUrl: String,
    val groupId: String,
    val artifactId: String,
) : Comparable<PluginDescriptor>, BaseState() {
    override fun compareTo(other: PluginDescriptor): Int {
        return id.compareTo(other.id)
    }

    override fun toString(): String {
        return id
    }

    val id get() = "$groupId:$artifactId"

    object KotlinxRpc {
        private fun kotlinxRpc(suffix: String) = PluginDescriptor(
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
