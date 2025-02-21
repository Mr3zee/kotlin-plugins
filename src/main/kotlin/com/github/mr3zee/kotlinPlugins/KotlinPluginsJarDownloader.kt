package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.jetbrains.kotlin.config.MavenComparableVersion
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.exists
import kotlin.io.path.fileSize

data class JarResult(
    val path: Path,
    val artifactVersion: String,
    val downloaded: Boolean,
)

internal object KotlinPluginsJarDownloader {
    val lockedFiles = ConcurrentHashMap<String, Unit>()

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
        logger.debug("$logTag Checking the latest version for $kotlinIdeVersion from $repoUrl")

        val groupUrl = groupId.replace(".", "/")
        val artifactUrl = "$repoUrl/$groupUrl/$artifactId"

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
            logger.debug("$logTag No exact version ${if(requestedLibVersion == null) "requested" else "exists"}")
        }

        val artifactVersion = exactVersion
            ?: getLatestVersion(versions, kotlinIdeVersion)
            ?: run {
                logger.debug("$logTag No compiler plugin artifact exists")

                return null
            }

        val forIdeFilename = "$artifactId-$artifactVersion-$FOR_IDE_CLASSIFIER.jar"

        val forIdeVersion = downloadJarIfNotExists(
            project = project,
            dest = dest,
            filename = forIdeFilename,
            logTag = logTag,
            artifactUrl = artifactUrl,
            version = artifactVersion,
        )

        if (forIdeVersion != null) {
            return forIdeVersion
        }

        logger.debug("$logTag No for-ide version exists, trying the default version")

        val defaultFilename = "$artifactId-$artifactVersion.jar"
        return downloadJarIfNotExists(
            project = project,
            dest = dest,
            filename = defaultFilename,
            logTag = logTag,
            artifactUrl = artifactUrl,
            version = artifactVersion,
        )
    }

    private suspend fun downloadJarIfNotExists(
        project: Project,
        dest: Path,
        filename: String,
        logTag: String,
        artifactUrl: String,
        version: String,
    ): JarResult? {
        if (!dest.exists()) {
            dest.toFile().mkdirs()
        }

        val file: Path = dest.resolve(filename)

        if (Files.exists(file)) {
            logger.debug("$logTag A file already exists: ${file.absolutePathString()}")
            return JarResult(file, version, downloaded = false)
        } else {
            Files.createFile(file)
        }

        return try {
            lockedFiles[file.absolutePathString()] = Unit

            downloadJarIfNotExistsUnderLock(
                project = project,
                file = file,
                filename = filename,
                logTag = logTag,
                artifactUrl = artifactUrl,
                version = version,
            )
        } finally {
            lockedFiles.remove(file.absolutePathString())
        }
    }

    private suspend fun downloadJarIfNotExistsUnderLock(
        project: Project,
        file: Path,
        filename: String,
        logTag: String,
        artifactUrl: String,
        version: String,
    ): JarResult? {
        val response = client.prepareGet("$artifactUrl/$version/$filename").execute { httpResponse ->
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
                        logger.debug("$logTag Reported progress: $fraction, file size: $fileSize")
                        reporter.fraction(fraction)
                        reporter.details("Downloading $filename, ${Files.size(file)} / $contentLength")

                        val channel: ByteReadChannel = httpResponse.body()
                        while (!channel.isClosedForRead) {
                            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                            while (!packet.exhausted()) {
                                val bytes: ByteArray = packet.readByteArray()
                                file.appendBytes(bytes)
                                val size = Files.size(file)
                                reporter.fraction(size.toDouble() / contentLength)
                                reporter.details("Downloading $filename, $size / $contentLength")
                                logger.trace("$logTag Received $size / $contentLength")
                            }
                        }

                        httpResponse
                    }
                }
            } catch (e: CancellationException) {
                logger.error("$logTag Cancellation while downloading file $filename", e)
                throw e
            } catch (e: Exception) {
                logger.error("$logTag Exception while downloading file $filename", e)
                null
            }
        }

        if (response?.status?.isSuccess() != true) {
            Files.delete(file)
            if (response != null) {
                logger.debug("$logTag Failed to download file $filename: ${response.status} - ${response.bodyAsText()}")
            }

            return null
        }

        logger.debug("$logTag File downloaded successfully")

        return JarResult(file, version, downloaded = true)
    }

    internal suspend fun downloadManifestAndGetVersions(logTag: String, artifactUrl: String): List<String>? {
        val response = try {
            client.get("$artifactUrl/maven-metadata.xml")
        } catch (e: CancellationException) {
            logger.error("$logTag Cancellation downloading the manifest", e)
            throw e
        } catch (e: Exception) {
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

const val FOR_IDE_CLASSIFIER = "for-ide"

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
