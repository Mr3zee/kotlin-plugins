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
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.exists
import kotlin.io.path.fileSize

data class JarResult(
    val path: Path,
    val artifactVersion: String,
    val requestedVersion: String?,
    val downloaded: Boolean,
)

internal object KotlinPluginsJarDownloader {
    private val logger by lazy { KotlinPluginsJarDownloader.thisLogger() }

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

    suspend fun downloadArtifactIfNotExists(
        project: Project,
        repoUrl: String,
        groupId: String,
        artifactId: String,
        kotlinIdeVersion: String,
        dest: Path,
        optionalPreferredLibVersions: () -> Set<String>,
    ): List<JarResult> {
        val logTag = "[$groupId:$artifactId:${logId.andIncrement}]"

        val groupUrl = groupId.replace(".", "/")
        val artifactUrl = "$repoUrl/$groupUrl/$artifactId"
        logger.debug("$logTag Checking the latest version for $kotlinIdeVersion from $artifactUrl")

        val versions = downloadManifestAndGetVersions(logTag, artifactUrl) ?: return emptyList()

        val requestedLibVersions = optionalPreferredLibVersions()
        if (requestedLibVersions.isNotEmpty()) {
            return requestedLibVersions.mapNotNull {
                downloadArtifactIfNotExists(
                    logTag = logTag,
                    versions = versions,
                    project = project,
                    artifactUrl = artifactUrl,
                    artifactId = artifactId,
                    kotlinIdeVersion = kotlinIdeVersion,
                    dest = dest,
                    requestedLibVersion = it,
                )
            }
        } else {
            return downloadArtifactIfNotExists(
                logTag = logTag,
                versions = versions,
                project = project,
                artifactUrl = artifactUrl,
                artifactId = artifactId,
                kotlinIdeVersion = kotlinIdeVersion,
                dest = dest,
                requestedLibVersion = null,
            )?.let { listOf(it) } ?: emptyList()
        }
    }

    suspend fun downloadArtifactIfNotExists(
        logTag: String,
        versions: List<String>,
        project: Project,
        artifactUrl: String,
        artifactId: String,
        kotlinIdeVersion: String,
        dest: Path,
        requestedLibVersion: String?,
    ): JarResult? {
        val exactVersion: String? = if (requestedLibVersion != null) {
            logger.debug("$logTag Requested exact version: $requestedLibVersion")
            val full = "$kotlinIdeVersion-$requestedLibVersion"
            if (versions.contains(full)) full else null
        } else {
            null
        }

        if (exactVersion != null) {
            logger.debug("$logTag Found exact version")
        } else {
            logger.debug("$logTag No exact version ${if (requestedLibVersion == null) "requested" else "exists"}")
        }

        val artifactVersion = exactVersion
            ?: getLatestVersion(versions, kotlinIdeVersion)
            ?: run {
                logger.debug("$logTag No compiler plugin artifact exists")

                return null
            }

        logger.debug("$logTag Version to download: $artifactVersion")

        val downloadingFilename = "$artifactId-$artifactVersion.jar.$DOWNLOADING_EXTENSION"
        val forIdeFilename = "$artifactId-$artifactVersion-$FOR_IDE_CLASSIFIER.jar"
        val plainFilename = "$artifactId-$artifactVersion.jar"

        val forIdeVersion = downloadJarIfNotExists(
            project = project,
            dest = dest,
            filename = downloadingFilename,
            logTag = logTag,
            artifactUrl = artifactUrl,
            artifactName = forIdeFilename,
            version = artifactVersion,
            requestedLibVersion = requestedLibVersion,
        )

        if (forIdeVersion != null) {
            return forIdeVersion.moved()
        }

        logger.debug("$logTag No for-ide version exists, trying the default version")

        return downloadJarIfNotExists(
            project = project,
            dest = dest,
            filename = downloadingFilename,
            logTag = logTag,
            artifactUrl = artifactUrl,
            artifactName = plainFilename,
            version = artifactVersion,
            requestedLibVersion = requestedLibVersion,
        ).moved()
    }

    private fun JarResult?.moved(): JarResult? {
        return if (this != null && downloaded) {
            val finalFilename = path.removeDownloadingExtension()
            Files.move(path, finalFilename, StandardCopyOption.ATOMIC_MOVE)

            JarResult(finalFilename, artifactVersion, requestedVersion, downloaded = true)
        } else {
            this
        }
    }

    private fun Path.removeDownloadingExtension(): Path {
        return resolveSibling(fileName.toString().removeSuffix(".$DOWNLOADING_EXTENSION"))
    }

    private suspend fun downloadJarIfNotExists(
        project: Project,
        dest: Path,
        filename: String,
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        version: String,
        requestedLibVersion: String?,
    ): JarResult? {
        if (!dest.exists()) {
            dest.toFile().mkdirs()
        }

        val file: Path = dest.resolve(filename)

        val finalFilename = file.removeDownloadingExtension()
        if (Files.exists(finalFilename)) {
            logger.debug("$logTag A file already exists: ${finalFilename.absolutePathString()}")
            return JarResult(finalFilename, version, requestedLibVersion, downloaded = false)
        }

        Files.createFile(file)

        return downloadJarIfNotExistsUnderLock(
            project = project,
            file = file,
            logTag = logTag,
            artifactUrl = artifactUrl,
            artifactName = artifactName,
            version = version,
            requestedVersion = requestedLibVersion,
        ).also {
            if (it == null) {
                logger.debug("$logTag Deleted the file: ${file.absolutePathString()}")
                file.delete()
            }
        }
    }

    private suspend fun downloadJarIfNotExistsUnderLock(
        project: Project,
        file: Path,
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        version: String,
        requestedVersion: String?,
    ): JarResult? {
        val response = client.prepareGet("$artifactUrl/$version/$artifactName").execute { httpResponse ->
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
            Files.delete(file)
            if (response != null) {
                logger.debug("$logTag Failed to download file $artifactName: ${response.status} - ${response.bodyAsText()}")
            }

            return null
        }

        logger.debug("$logTag File downloaded successfully")

        return JarResult(file, version, requestedVersion, downloaded = true)
    }

    internal suspend fun downloadManifestAndGetVersions(logTag: String, artifactUrl: String): List<String>? {
        val response = try {
            client.get("$artifactUrl/maven-metadata.xml")
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

}

private const val FOR_IDE_CLASSIFIER = "for-ide"
private const val DOWNLOADING_EXTENSION = "downloading"

internal fun getLatestVersion(versions: List<String>, prefix: String): String? =
    versions.filter { it.startsWith(prefix) }.maxByOrNull { string ->
        MavenComparableVersion(string.removePrefix(prefix))
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
