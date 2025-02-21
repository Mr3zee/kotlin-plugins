package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import kotlin.io.path.name

@Suppress("UnstableApiUsage")
class KotlinPluginsProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
        logger.debug("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorVersionedOrNull() ?: return null
        logger.debug("Found plugin descriptor: $descriptor")
        return project.service<KotlinPluginsStorageService>().getPluginPath(descriptor).also {
            logger.debug("Returning plugin jar: $it")
        }
    }

    private fun Path.toKotlinPluginDescriptorVersionedOrNull(): KotlinPluginDescriptorVersioned? {
        val descriptor = toKotlinPluginDescriptorOrNull() ?: return null
        val coreVersion = SEMVER_REGEX.findAll(name).lastOrNull()?.value
        val version = coreVersion?.let { "$coreVersion${name.substringAfterLast(coreVersion).substringBeforeLast('.')}" }

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
