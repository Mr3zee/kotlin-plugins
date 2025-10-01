package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.util.io.delete
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import org.jetbrains.kotlin.config.MavenComparableVersion
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText

data class JarResult(
    val path: Path,
    val libVersion: String,
    val artifactVersion: String,
    val isNew: Boolean,
)

internal object KotlinPluginJarLocator {
    private val logger by lazy { thisLogger() }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    private val logId = AtomicLong(0)

    internal class ArtifactManifest(
        val artifactId: String,
        val locator: Locator,
        val matchFilter: MatchFilter,
        val dest: Path,
        val kotlinIdeVersion: String,
    ) {
        sealed interface Locator {
            data class ByUrl(
                val url: String,
            ) : Locator

            data class ByPath(
                val path: Path,
            ) : Locator
        }
    }

    suspend fun locateArtifact(
        project: Project,
        versioned: KotlinPluginDescriptorVersioned,
        kotlinIdeVersion: String,
        dest: Path,
    ): JarResult? {
        val descriptor = versioned.descriptor
        val logTag = "[${descriptor.id}:${logId.andIncrement}]"

        logger.debug("$logTag Locating artifact for $kotlinIdeVersion")

        var first: JarResult? = null
        descriptor.repositories.forEach {
            val locator = when (it.type) {
                KotlinArtifactsRepository.Type.URL -> {
                    val groupUrl = descriptor.groupId.replace(".", "/")
                    val artifactUrl = "${it.value}/$groupUrl/${descriptor.artifactId}"

                    ArtifactManifest.Locator.ByUrl(artifactUrl)
                }

                KotlinArtifactsRepository.Type.PATH -> {
                    val list = descriptor.groupId.split(".")
                    val groupPath = Path(list.first(), *list.drop(1).toTypedArray())
                    val artifactPath = Path(it.value).resolve(groupPath).resolve(descriptor.artifactId)

                    ArtifactManifest.Locator.ByPath(artifactPath)
                }
            }

            val manifest = ArtifactManifest(
                artifactId = descriptor.artifactId,
                locator = locator,
                matchFilter = versioned.asMatchFilter(),
                dest = dest,
                kotlinIdeVersion = kotlinIdeVersion,
            )

            logger.debug("$logTag Accessing manifest from ${manifest.locator}")

            val versions = locateManifestAndGetVersions(logTag, locator, kotlinIdeVersion)
                ?: return null

            logger.debug("$logTag Found ${versions.size} versions for $kotlinIdeVersion: $versions")

            val jar = locateArtifactByManifest(
                logTag = logTag,
                project = project,
                versions = versions,
                manifest = manifest,
            )

            if (jar != null && jar.libVersion == versioned.version) {
                return jar
            } else if (first == null) {
                first = jar
            }
        }

        return first
    }

    internal suspend fun locateArtifactByManifest(
        logTag: String,
        project: Project,
        versions: List<String>,
        manifest: ArtifactManifest,
    ): JarResult? {
        logger.debug("$logTag Requested version: ${manifest.matchFilter.version}")

        val libVersion = getMatching(versions, "${manifest.kotlinIdeVersion}-", manifest.matchFilter)
            ?: run {
                logger.debug(
                    "$logTag No compiler plugin artifact exists matching " +
                            "the requested version and criteria (${manifest.matchFilter})"
                )

                return null
            }

        val artifactVersion = "${manifest.kotlinIdeVersion}-$libVersion"

        logger.debug("$logTag Version to locate: $artifactVersion")

        val filename = "${manifest.artifactId}-$artifactVersion.jar.$DOWNLOADING_EXTENSION"
        val forIdeFilename = "${manifest.artifactId}-$artifactVersion-$FOR_IDE_CLASSIFIER.jar"
        val plainFilename = "${manifest.artifactId}-$artifactVersion.jar"

        val forIdeVersion = locateArtifactByFullQualifier(
            project = project,
            manifest = manifest,
            filename = filename,
            logTag = logTag,
            artifactName = forIdeFilename,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
        )

        if (forIdeVersion != null) {
            return forIdeVersion.moved()
        }

        logger.debug("$logTag No for-ide version exists, trying the default version")

        return locateArtifactByFullQualifier(
            project = project,
            manifest = manifest,
            filename = filename,
            logTag = logTag,
            artifactName = plainFilename,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
        ).moved()
    }

    private fun JarResult?.moved(): JarResult? {
        return if (this != null && isNew) {
            val finalFilename = path.removeDownloadingExtension()
            Files.move(path, finalFilename, StandardCopyOption.ATOMIC_MOVE)

            JarResult(finalFilename, libVersion, artifactVersion, isNew = true)
        } else {
            this
        }
    }

    private fun Path.removeDownloadingExtension(): Path {
        return resolveSibling(fileName.toString().removeSuffix(".$DOWNLOADING_EXTENSION"))
    }

    private suspend fun locateArtifactByFullQualifier(
        project: Project,
        manifest: ArtifactManifest,
        filename: String,
        logTag: String,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
    ): JarResult? {
        if (!manifest.dest.exists()) {
            manifest.dest.toFile().mkdirs()
        }

        val file: Path = manifest.dest.resolve(filename)

        val finalFilename = file.removeDownloadingExtension()
        if (Files.exists(finalFilename)) {
            logger.debug("$logTag A file already exists: ${finalFilename.absolutePathString()}")
            return JarResult(finalFilename, libVersion, artifactVersion, isNew = false)
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        Files.createFile(file)

        return locateNewArtifact(
            project = project,
            file = file,
            logTag = logTag,
            locator = manifest.locator,
            artifactName = artifactName,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
        ).also {
            if (it == null) {
                file.delete()
            }
        }
    }

    private suspend fun locateNewArtifact(
        project: Project,
        file: Path,
        logTag: String,
        locator: ArtifactManifest.Locator,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
    ): JarResult? {
        return when (locator) {
            is ArtifactManifest.Locator.ByUrl -> locateNewArtifact(
                project = project,
                file = file,
                logTag = logTag,
                artifactUrl = locator.url,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
            )

            is ArtifactManifest.Locator.ByPath -> locateNewArtifact(
                file = file,
                logTag = logTag,
                artifactPath = locator.path,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
            )
        }
    }

    private suspend fun locateNewArtifact(
        project: Project,
        file: Path,
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
    ): JarResult? {
        val response = client.prepareGet("$artifactUrl/$artifactVersion/$artifactName").execute { httpResponse ->
            if (!httpResponse.status.isSuccess()) {
                return@execute httpResponse
            }

            val requestUrl = httpResponse.request.url.toString()
            val contentLength = httpResponse.contentLength()?.toDouble() ?: 0.0
            logger.debug("$logTag Request URL: $requestUrl, size: $contentLength")

            if (contentLength == 0.0) {
                return@execute httpResponse
            }

            try {
                withBackgroundProgress(project = project, "Downloading Kotlin Plugin") {
                    @Suppress("UnstableApiUsage")
                    reportRawProgress { reporter ->
                        val fileSize = file.fileSize().toDouble()
                        val fraction = fileSize / contentLength
                        reporter.fraction(fraction)
                        reporter.details("Downloading $artifactName, ${Files.size(file)} / $contentLength")

                        val channel: ByteReadChannel = httpResponse.body()
                        while (!channel.isClosedForRead) {
                            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                            while (!packet.exhausted()) {
                                val bytes: ByteArray = packet.readByteArray()
                                file.appendBytes(bytes)
                                val size = Files.size(file)
                                reporter.fraction(size.toDouble() / contentLength)
                                reporter.details("Downloading $artifactName, $size / $contentLength")
                                logger.trace("$logTag Received $size / $contentLength")
                            }
                        }

                        httpResponse
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                logger.error("$logTag Exception while downloading file $artifactName", e)
                null
            }
        }

        if (response?.status?.isSuccess() != true) {
            @Suppress("BlockingMethodInNonBlockingContext")
            Files.delete(file)
            if (response != null) {
                logger.debug("$logTag Failed to download file $artifactName: ${response.status} - ${response.bodyAsText()}")
            }

            return null
        }

        logger.debug("$logTag File downloaded successfully")

        return JarResult(file, libVersion, artifactVersion, isNew = true)
    }

    private fun locateNewArtifact(
        file: Path,
        logTag: String,
        artifactPath: Path,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
    ): JarResult? {
        val jarPath = artifactPath.resolve(artifactVersion).resolve(artifactName)

        if (!jarPath.exists()) {
            logger.debug("$logTag File does not exist: ${jarPath.absolutePathString()}")
            return null
        }

        Files.copy(jarPath, file, StandardCopyOption.REPLACE_EXISTING)

        return JarResult(file, libVersion, artifactVersion, isNew = true)
    }

    internal suspend fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator,
        kotlinIdeVersion: String,
    ): List<String>? {
        return when (artifactUrl) {
            is ArtifactManifest.Locator.ByUrl -> locateManifestAndGetVersions(logTag, artifactUrl)
            is ArtifactManifest.Locator.ByPath -> locateManifestAndGetVersions(logTag, artifactUrl)
        }?.filter { it.startsWith(kotlinIdeVersion) }
    }

    internal suspend fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator.ByUrl,
    ): List<String>? {
        val response = try {
            client.get("${artifactUrl.url}/maven-metadata.xml")
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            logger.error("$logTag Exception downloading the manifest", e)
            return null
        }

        if (response.status.value != 200) {
            logger.debug("$logTag Failed to download the manifest: ${response.status.value} ${response.bodyAsText()}")

            return null
        }

        val manifest = response.bodyAsText()

        return parseManifestXmlToVersions(manifest)
    }

    internal fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator.ByPath,
    ): List<String>? {
        val manifestPath = artifactUrl.path.resolve("maven-metadata.xml")

        if (!manifestPath.exists()) {
            logger.debug("$logTag Manifest file does not exist: ${manifestPath.absolutePathString()}")
            return null
        }

        return parseManifestXmlToVersions(manifestPath.readText())
    }
}

private const val FOR_IDE_CLASSIFIER = "for-ide"
private const val DOWNLOADING_EXTENSION = "downloading"

class MatchFilter(
    val version: String,
    val matching: KotlinPluginDescriptor.VersionMatching,
)

fun KotlinPluginDescriptorVersioned.asMatchFilter(): MatchFilter {
    return MatchFilter(version, descriptor.versionMatching)
}

internal fun getMatching(
    versions: List<String>,
    prefix: String,
    filter: MatchFilter,
): String? {
    val transformed = versions.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    if (transformed.isEmpty()) {
        return null
    }

    // exact version wins over any other
    if (transformed.firstOrNull { it == filter.version } != null) {
        return filter.version
    }

    return when (filter.matching) {
        KotlinPluginDescriptor.VersionMatching.LATEST -> {
            transformed.maxByOrNull { string ->
                MavenComparableVersion(string)
            }
        }

        KotlinPluginDescriptor.VersionMatching.SAME_MAJOR -> {
            val major = filter.version.substringBefore(".")

            transformed
                .filter { it.substringBefore(".") == major }
                .maxByOrNull { string ->
                    MavenComparableVersion(string)
                }
        }

        KotlinPluginDescriptor.VersionMatching.EXACT -> {
            transformed.firstOrNull { it == filter.version }
        }
    }
}

internal fun parseManifestXmlToVersions(manifest: String): List<String> {
    return try {
        Jsoup.parse(manifest, "", Parser.xmlParser())
            .getElementsByTag("metadata")[0]
            .getElementsByTag("versioning")[0]
            .getElementsByTag("versions")[0]
            .getElementsByTag("version")
            .map {
                it.text()
            }
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    }
}
