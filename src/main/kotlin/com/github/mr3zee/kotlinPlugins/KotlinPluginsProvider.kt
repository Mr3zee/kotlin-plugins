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
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorVersionedOrNull(project) ?: return null
        logger.debug("Found plugin descriptor: $descriptor")
        return project.service<KotlinPluginsStorageService>().getPluginPath(descriptor).also {
            logger.debug("Returning plugin jar: $it")
        }
    }

    private fun Path.toKotlinPluginDescriptorVersionedOrNull(project: Project): KotlinPluginDescriptorVersioned? {
        val descriptor = toKotlinPluginDescriptorOrNull(project) ?: return null
        val coreVersion = SEMVER_REGEX.findAll(name).lastOrNull()?.value
            ?: run {
                logger.error("Couldn't find core version in plugin jar name: $name")
                return null
            }

        // coreVersion (0.1.0) -> version (0.1.0-dev-123)
        val version = "$coreVersion${name.substringAfterLast(coreVersion).substringBeforeLast('.')}"

        return KotlinPluginDescriptorVersioned(descriptor, version)
    }

    private fun Path.toKotlinPluginDescriptorOrNull(project: Project): KotlinPluginDescriptor? {
        val stringPath = toString()
        val plugins = project.service<KotlinPluginsSettingsService>().safeState().plugins

        return plugins.filter { it.enabled }.firstOrNull {
            val url = "${it.groupId}/${it.artifactId}/"
            stringPath.contains("/$url") || stringPath.startsWith(url)
        }
    }
}

private val SEMVER_REGEX = "(\\d+\\.\\d+\\.\\d+)".toRegex()
