package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service

/**
 * Project service that stores transient notification state for the editor notifications
 * shown when a Kotlin external plugin is detected to throw exceptions.
 */
@Service(Service.Level.PROJECT)
class KotlinPluginsNotifications {
    @Volatile
    private var activePlugins: Set<String> = emptySet()

    fun activate(pluginName: String) {
        synchronized(this) {
            activePlugins = activePlugins + pluginName
        }
    }

    fun clear() {
        synchronized(this) { activePlugins = emptySet() }
    }

    fun currentPlugins(): Set<String> = activePlugins
}
