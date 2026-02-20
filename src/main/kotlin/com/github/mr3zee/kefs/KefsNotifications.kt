package com.github.mr3zee.kefs

import com.intellij.openapi.components.Service

/**
 * Project service that stores transient notification state for the editor notifications
 * shown when a Kotlin external plugin is detected to throw exceptions.
 */
@Service(Service.Level.PROJECT)
internal class KefsNotifications {
    @Volatile
    private var activePlugins: Set<JarId> = emptySet()

    fun activate(ids: List<JarId>) {
        synchronized(this) {
            activePlugins = activePlugins + ids
        }
    }

    fun deactivate(pluginName: String) {
        synchronized(this) {
            activePlugins = activePlugins.filterNot { it.pluginName == pluginName }.toSet()
        }
    }

    fun deactivate(jarId: JarId) {
        synchronized(this) {
            activePlugins = activePlugins - jarId
        }
    }

    fun clear() {
        synchronized(this) { activePlugins = emptySet() }
    }

    fun currentPlugins(): Set<JarId> = activePlugins
}
