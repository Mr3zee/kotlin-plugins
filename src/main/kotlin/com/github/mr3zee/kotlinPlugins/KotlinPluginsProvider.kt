package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class KotlinPluginsProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(userSuppliedPluginJar: Path): Path? {
        logger.info("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorOrNull() ?: return null
        logger.info("Found plugin descriptor: $descriptor")
        return service<KotlinPluginsStorageService>().getPluginPath(descriptor).also {
            logger.info("Returning plugin jar: $it")
        }
    }
}

internal fun Path.toKotlinPluginDescriptorOrNull(): KotlinPluginDescriptor? {
    val stringPath = toString()
    val plugins = service<KotlinPluginsSettingsService>().state.plugins
    return plugins.firstOrNull {
        val url = "${it.groupId}/${it.artifactId}/"
        stringPath.contains("/$url") || stringPath.startsWith(url)
    }
}
