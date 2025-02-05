package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import kotlin.io.path.name

@Suppress("UnstableApiUsage")
class KotlinPluginsProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(userSuppliedPluginJar: Path): Path? {
        logger.info("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorVersionedOrNull() ?: return null
        logger.info("Found plugin descriptor: $descriptor")
        // TODO pass project here
        return service<KotlinPluginsStorageService>().getPluginPath(null, descriptor).also {
            logger.info("Returning plugin jar: $it")
        }
    }

    private fun Path.toKotlinPluginDescriptorVersionedOrNull(): KotlinPluginDescriptorVersioned? {
        val descriptor = toKotlinPluginDescriptorOrNull() ?: return null
        val coreVersion = SEMVER_REGEX.findAll(name).lastOrNull()?.value
        val version = coreVersion?.let { "$coreVersion${name.substringAfterLast(coreVersion)}" }

        return KotlinPluginDescriptorVersioned(descriptor, version)
    }

    private fun Path.toKotlinPluginDescriptorOrNull(): KotlinPluginDescriptor? {
        val stringPath = toString()
        val plugins = service<KotlinPluginsSettingsService>().state.plugins
        return plugins.firstOrNull {
            val url = "${it.groupId}/${it.artifactId}/"
            stringPath.contains("/$url") || stringPath.startsWith(url)
        }
    }
}

private val SEMVER_REGEX = "(\\d+\\.\\d+\\.\\d+)".toRegex()
