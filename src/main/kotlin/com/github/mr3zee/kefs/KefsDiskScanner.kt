package com.github.mr3zee.kefs

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

internal class ValidatedJarResult(
    val jar: Jar,
    val resolvedVersion: ResolvedVersion,
    val origin: KotlinArtifactsRepository,
)

internal object KefsDiskScanner {
    private val logger by lazy { thisLogger() }

    /**
     * Scans [basePath] for JARs matching the artifact pattern and version matching strategy.
     * Returns (resolvedVersion, kotlinIdeVersion, matchedJarPath) or null if not found.
     */
    fun findMatchingJar(
        basePath: Path,
        artifact: MavenId,
        kotlinIdeVersion: String,
        replacement: KotlinPluginDescriptor.Replacement?,
        matchFilter: MatchFilter,
    ): Triple<ResolvedVersion, String, Path>? {
        if (!Files.exists(basePath)) {
            return null
        }

        val artifactsPattern = if (replacement != null) {
            val artifactString = replacement.getArtifactString(artifact)
            val versionString = replacement.getVersionString(kotlinIdeVersion, "*")
            "$artifactString-$versionString*.jar"
        } else {
            "${artifact.artifactId}-$kotlinIdeVersion-*.jar"
        }

        val candidates = runCatching {
            basePath.listDirectoryEntries(artifactsPattern).toList()
        }.getOrElse {
            logger.debug("Failed to list directory entries in $basePath with pattern $artifactsPattern: ${it.message}")
            return null
        }

        val versionToPath = candidates
            .associateBy {
                it.name
                    .substringAfter("${artifact.artifactId}-$kotlinIdeVersion-")
                    .substringBefore(".jar")
            }

        val matched = getMatching(listOf(versionToPath.keys.toList()), "", matchFilter)

        return matched?.let { resolvedVersion ->
            val path = versionToPath.getValue(resolvedVersion.value)
            Triple(resolvedVersion, kotlinIdeVersion, path)
        }
    }

    /**
     * Validates a cached JAR file by checking its checksum, verifying the original file
     * (if linked), and loading metadata to confirm origin repository.
     *
     * Returns a [ValidatedJarResult] or null if validation fails.
     *
     * @param resolvedKotlinVersion the Kotlin version the JAR was actually built for
     *        (may differ from [kotlinIdeVersion] in future fallback scenarios)
     */
    fun validateCachedJar(
        jarPath: Path,
        kotlinIdeVersion: String,
        resolvedKotlinVersion: String,
        resolvedVersion: ResolvedVersion,
        repositories: List<KotlinArtifactsRepository>,
    ): ValidatedJarResult? {
        val checksum = runCatchingExceptCancellation { md5(jarPath).asChecksum() }.getOrNull()
            ?: return null

        val original = jarPath.resolveOriginalJarFile()

        if (original != null && checksum != md5(original).asChecksum()) {
            runCatchingExceptCancellation { Files.deleteIfExists(jarPath) }
            logger.debug("Checksums don't match with the original jar for $jarPath")
            return null
        }

        val kotlinVersionMismatch = if (resolvedKotlinVersion != kotlinIdeVersion) {
            KotlinVersionMismatch(ideVersion = kotlinIdeVersion, jarVersion = resolvedKotlinVersion)
        } else {
            null
        }

        val jar = Jar(
            path = jarPath,
            checksum = checksum,
            isLocal = original != null,
            kotlinVersionMismatch = kotlinVersionMismatch,
        )

        val metadataPath = jarPath.resolveSibling("${jarPath.fileName}.$METADATA_EXTENSION")
        val metadata = loadMetadata(metadataPath)

        if (metadata == null || repositories.none { it.name == metadata.originRepositoryName }) {
            logger.debug(
                "Invalid original repository for $jarPath: ${metadata?.originRepositoryName}. " +
                        "Expected one of: $repositories"
            )
            runCatchingExceptCancellation { metadataPath.deleteIfExists() }
            return null
        }

        val origin = repositories.single { it.name == metadata.originRepositoryName }
        return ValidatedJarResult(jar, resolvedVersion, origin)
    }

    private fun loadMetadata(metadataPath: Path): JarDiskMetadata? {
        return try {
            if (metadataPath.exists()) {
                Json.decodeFromString<JarDiskMetadata>(metadataPath.readText())
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to read metadata from $metadataPath: ${e.message}")
            null
        }
    }
}
