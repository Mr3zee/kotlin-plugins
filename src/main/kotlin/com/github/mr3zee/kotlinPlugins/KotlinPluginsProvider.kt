package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.to

@Suppress("UnstableApiUsage")
internal class KotlinPluginsProvider : KotlinBundledFirCompilerPluginProvider {
    private val logger by lazy { thisLogger() }

    override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
        if (IGNORE_LIST.any { userSuppliedPluginJar.toString().contains(it) }) {
            return null
        }

        logger.debug("Request for plugin jar: $userSuppliedPluginJar")
        val descriptor = userSuppliedPluginJar.toKotlinPluginDescriptorVersionedOrNull(project) ?: return null
        logger.debug("Found plugin descriptor: ${descriptor.descriptor.name}, ${descriptor.requestedVersion}")
        return project.service<KotlinPluginsStorage>().getPluginPath(descriptor).also {
            logger.debug("Returning plugin jar (${descriptor.descriptor.name}, ${descriptor.requestedVersion}): $it")
        }
    }

    private fun Path.toKotlinPluginDescriptorVersionedOrNull(project: Project): RequestedKotlinPluginDescriptor? {
        val plugins = project.service<KotlinPluginsSettings>().safeState().plugins

        return plugins.filter { it.enabled }.firstNotNullOfOrNull { descriptor ->
            locateKotlinPluginDescriptorVersionedOrNull(this, descriptor)
        }
    }

    internal fun locateKotlinPluginDescriptorVersionedOrNull(
        path: Path,
        descriptor: KotlinPluginDescriptor,
    ): RequestedKotlinPluginDescriptor? {
        return if (descriptor.replacement != null) {
            customMatching(path, descriptor, descriptor.replacement)
        } else {
            val coreVersion = SEMVER_REGEX.findAll(path.name).lastOrNull()?.value
                ?: run {
                    return null
                }

            defaultMatching(path, descriptor, coreVersion)
        }
    }

    private fun customMatching(
        path: Path,
        descriptor: KotlinPluginDescriptor,
        replacement: KotlinPluginDescriptor.Replacement,
    ): RequestedKotlinPluginDescriptor? {
        val sanitizedInput = path.takeIf { it.extension == "jar" }?.name?.removeSuffix(".jar") ?: return null

        descriptor.ids.forEach { id ->
            val regex = replacement.getDetectPattern(id)
            val matchResult = regex.find(sanitizedInput) ?: return@forEach
            val libVersion =
                matchResult.groups[KotlinPluginDescriptor.Replacement.LIB_VERSION_GROUP]?.value ?: return@forEach

            return RequestedKotlinPluginDescriptor(descriptor, libVersion.requested(), id)
        }

        return null
    }

    private fun defaultMatching(
        path: Path,
        descriptor: KotlinPluginDescriptor,
        coreVersion: String,
    ): RequestedKotlinPluginDescriptor? {
        val urls = descriptor.ids.map {
            it to listOf(
                "${it.getPluginGroupPath()}/${it.artifactId}/",
                "${it.groupId}/${it.artifactId}/",
            )
        }

        val stringPath = path.toString()
        return urls.firstOrNull { (_, urlVariations) ->
            urlVariations.any { url ->
                stringPath.contains("/$url") || stringPath.startsWith(url)
            }
        }?.let { (id, _) ->
            // coreVersion (0.1.0) -> version (0.1.0-dev-123)
            val requestedVersion = "$coreVersion${path.name.substringAfterLast(coreVersion).substringBeforeLast('.')}"
                .requested()

            RequestedKotlinPluginDescriptor(descriptor, requestedVersion, id)
        }
    }
}

private val SEMVER_REGEX = "(\\d+\\.\\d+\\.\\d+)".toRegex()

private val IGNORE_LIST = listOf(
    "org.jetbrains.kotlin/kotlin-scripting-jvm/",
    "org.jetbrains.kotlin/kotlin-scripting-common/",
    "org.jetbrains.kotlin/kotlin-stdlib/",
    "org.jetbrains.kotlin/kotlin-script-runtime/",
    "org.jetbrains/annotations/",
)
