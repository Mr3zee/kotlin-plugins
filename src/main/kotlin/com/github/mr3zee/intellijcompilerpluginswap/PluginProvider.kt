package com.github.mr3zee.intellijcompilerpluginswap

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class PluginProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(userSuppliedPluginJar: Path): Path? {
        logger.info("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toPluginDescriptor() ?: return null
        logger.info("Found plugin descriptor: $descriptor")
        return service<PluginStorageService>().getPluginPath(descriptor)
    }
}

internal fun Path.toPluginDescriptor(): PluginDescriptor? {
    val stringPath = toString()
    val plugins = service<PluginSettingsService>().state.plugins
    return plugins.firstOrNull {
        val url = "${it.groupId}/${it.artifactId}/"
        stringPath.contains("/$url") || stringPath.startsWith(url)
    }
}
