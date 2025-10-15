package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service

/**
 * Project service that stores transient notification state for the editor notifications
 * shown when a Kotlin external plugin is detected to throw exceptions.
 */
@Service(Service.Level.PROJECT)
internal class KotlinPluginsNotifications {
    @Volatile
    private var activePlugins: Set<VersionedPluginKey> = emptySet()

    fun activate(pluginName: String, versions: List<String>) {
        synchronized(this) {
            activePlugins = activePlugins + versions.map { VersionedPluginKey(pluginName, it) }
        }
    }

    fun deactivate(pluginName: String, version: String) {
        synchronized(this) {
            activePlugins = activePlugins - VersionedPluginKey(pluginName, version)
        }
    }

    fun clear() {
        synchronized(this) { activePlugins = emptySet() }
    }

    fun currentPlugins(): Set<VersionedPluginKey> = activePlugins
}
