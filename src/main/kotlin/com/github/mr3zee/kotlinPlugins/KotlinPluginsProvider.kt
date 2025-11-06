package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import kotlin.io.path.name

@Suppress("UnstableApiUsage")
internal class KotlinPluginsProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
        logger.debug("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorVersionedOrNull(project) ?: return null
        logger.debug("Found plugin descriptor: ${descriptor.descriptor.name}")
        return project.service<KotlinPluginsStorage>().getPluginPath(descriptor).also {
            logger.debug("Returning plugin jar: $it")
        }
    }

    private fun Path.toKotlinPluginDescriptorVersionedOrNull(project: Project): RequestedKotlinPluginDescriptor? {
        val stringPath = toString()
        val plugins = project.service<KotlinPluginsSettings>().safeState().plugins

        return plugins.filter { it.enabled }.firstNotNullOfOrNull { descriptor ->
            val urls = descriptor.ids.map {
                it to listOf(
                    "${it.getPluginGroupPath()}/${it.artifactId}/",
                    "${it.groupId}/${it.artifactId}/",
                )
            }

            urls.firstOrNull { (_, urlVariations) ->
                urlVariations.any { url ->
                    stringPath.contains("/$url") || stringPath.startsWith(url)
                }
            }?.let { (id, _) ->
                val coreVersion = SEMVER_REGEX.findAll(name).lastOrNull()?.value
                    ?: run {
                        logger.error("Couldn't find core version in plugin jar name: $name")
                        return null
                    }

                // coreVersion (0.1.0) -> version (0.1.0-dev-123)
                val requestedVersion = "$coreVersion${name.substringAfterLast(coreVersion).substringBeforeLast('.')}"
                    .requested()

                RequestedKotlinPluginDescriptor(descriptor, requestedVersion, id)
            }
        }
    }

}

private val SEMVER_REGEX = "(\\d+\\.\\d+\\.\\d+)".toRegex()
