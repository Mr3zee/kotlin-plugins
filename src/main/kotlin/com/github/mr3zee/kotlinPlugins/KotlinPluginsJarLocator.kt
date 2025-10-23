package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.diagnostic.thisLogger
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
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BundleResult(
    val locatorResults: Map<MavenId, LocatorResult>,
) {
    fun allFound() = locatorResults.all { it.value is LocatorResult.Cached }
}

sealed interface LocatorResult {
    val libVersion: String
    val state: ArtifactState

    class Cached(
        val jar: Jar,
        val filter: MatchFilter,
        override val libVersion: String,
        val original: Path? = null,
    ) : LocatorResult {
        override val state: ArtifactState.Cached =
            ArtifactState.Cached(jar, filter.version, libVersion, filter.matching)
    }

    class FailedToFetch(
        override val state: ArtifactState.FailedToFetch,
        override val libVersion: String,
    ) : LocatorResult

    class NotFound(
        override val state: ArtifactState.NotFound,
        override val libVersion: String,
    ) : LocatorResult
}

class Jar(
    val path: Path,
    val checksum: String,
    val isLocal: Boolean,
    val kotlinVersionMismatch: KotlinVersionMismatch?,
)

internal object KotlinPluginsJarLocator {
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

    suspend fun locateArtifacts(
        versioned: VersionedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        dest: Path,
        known: Map<String, Jar>,
    ): BundleResult {
        val logTag = "[${versioned.descriptor.name}:${logId.andIncrement}]"

        logger.debug("$logTag Locating artifact for $kotlinIdeVersion")

        return BundleResult(
            locatorResults = versioned.descriptor.ids.associateWith { mavenId ->
                locateArtifact(
                    logTag = logTag,
                    versioned = RequestedKotlinPluginDescriptor(versioned.descriptor, versioned.version, mavenId),
                    kotlinIdeVersion = kotlinIdeVersion,
                    dest = dest,
                    known = known[mavenId.id],
                ).also {
                    val resultString = when (it) {
                        is LocatorResult.Cached -> "Cached"
                        is LocatorResult.NotFound -> "Not Found: ${it.state.message}"
                        is LocatorResult.FailedToFetch -> "Failed To Fetch: ${it.state.message}"
                    }

                    logger.debug("$logTag '${mavenId.id}' is $resultString")
                }
            }
        )
    }

    private suspend fun locateArtifact(
        logTag: String,
        versioned: RequestedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        dest: Path,
        known: Jar?,
    ): LocatorResult {
        val descriptor = versioned.descriptor
        val artifact = versioned.artifact

        var firstSearchedFound = false
        var firstSearched: LocatorResult = LocatorResult.NotFound(
            state = ArtifactState.NotFound("No repositories added found for ${artifact.id}"),
            libVersion = versioned.version,
        )

        val notFound = mutableListOf<LocatorResult.NotFound>()
        val failedToFetch = mutableListOf<LocatorResult.FailedToFetch>()

        descriptor.repositories.sortedByPriority().forEach {
            val locator = when (it.type) {
                KotlinArtifactsRepository.Type.URL -> {
                    val groupUrl = artifact.groupId.replace(".", "/")
                    val artifactUrl = "${it.value}/$groupUrl/${artifact.artifactId}"

                    ArtifactManifest.Locator.ByUrl(artifactUrl)
                }

                KotlinArtifactsRepository.Type.PATH -> {
                    val artifactPath = Path(it.value)
                        .resolve(artifact.getPluginGroupPath())
                        .resolve(artifact.artifactId)

                    ArtifactManifest.Locator.ByPath(artifactPath)
                }
            }

            val manifest = ArtifactManifest(
                artifactId = artifact.artifactId,
                locator = locator,
                matchFilter = versioned.asMatchFilter(),
                dest = dest,
                kotlinIdeVersion = kotlinIdeVersion,
            )

            logger.debug("$logTag Accessing manifest from ${manifest.locator}")

            val manifestResult = locateManifestAndGetVersions(locator)

            when (manifestResult) {
                is ManifestResult.Success -> {}

                is ManifestResult.NotFound -> {
                    notFound.add(
                        LocatorResult.NotFound(
                            state = manifestResult.state,
                            libVersion = versioned.version,
                        )
                    )

                    return@forEach
                }

                is ManifestResult.FailedToFetch -> {
                    failedToFetch.add(
                        LocatorResult.FailedToFetch(
                            state = manifestResult.state,
                            libVersion = versioned.version,
                        )
                    )

                    return@forEach
                }
            }

            logger.trace("$logTag Found ${manifestResult.versions.size} versions for ${artifact.artifactId}:$kotlinIdeVersion: $manifestResult")

            val locatorResult = locateArtifactByManifest(
                logTag = logTag,
                versions = manifestResult.versions,
                manifest = manifest,
                known = known,
            )

            if (locatorResult is LocatorResult.Cached && locatorResult.libVersion == versioned.version) {
                return locatorResult
            } else {
                when {
                    !firstSearchedFound && locatorResult is LocatorResult.Cached -> {
                        firstSearchedFound = true
                        firstSearched = locatorResult
                    }

                    firstSearched is LocatorResult.Cached -> {}

                    locatorResult is LocatorResult.NotFound -> {
                        notFound.add(locatorResult)
                    }

                    locatorResult is LocatorResult.FailedToFetch -> {
                        failedToFetch.add(locatorResult)
                    }
                }
            }
        }

        if (!firstSearchedFound) {
            if (failedToFetch.isNotEmpty() && notFound.isEmpty()) {
                if (failedToFetch.size == 1) {
                    return failedToFetch.first()
                }

                return LocatorResult.FailedToFetch(
                    state = ArtifactState.FailedToFetch(
                        """
                        |Failed to fetch artifacts from multiple repositories:
                        ${
                            failedToFetch.withIndex()
                                .joinToString("\n") { "|    ${it.index + 1}. ${it.value.state.message}" }
                        }
                        """.trimMargin(),
                    ),
                    libVersion = versioned.version,
                )
            }

            if (failedToFetch.isEmpty() && notFound.isNotEmpty()) {
                if (notFound.size == 1) {
                    return notFound.first()
                }

                return LocatorResult.NotFound(
                    state = ArtifactState.NotFound(
                        """
                        |No artifacts found matching the requested version and criteria:
                        ${
                            notFound.withIndex()
                                .joinToString("\n") { "|${it.index + 1}. ${it.value.state.message}" }
                        }
                        """.trimMargin(),
                    ),
                    libVersion = versioned.version,
                )
            }

            if (failedToFetch.isEmpty()) {
                return firstSearched
            }

            return LocatorResult.FailedToFetch(
                state = ArtifactState.FailedToFetch(
                    """
                    |Multiple failures occurred while fetching artifacts:
                    |Failed to fetch:
                    ${
                        failedToFetch.withIndex()
                            .joinToString("\n") { "|${it.index + 1}. ${it.value.state.message}" }
                    }
                    |Not found:
                    ${
                        notFound.withIndex()
                            .joinToString("\n") { "|${it.index + 1}. ${it.value.state.message}" }
                    }
                    """.trimMargin(),
                ),
                libVersion = versioned.version,
            )
        }

        return firstSearched
    }

    internal suspend fun locateArtifactByManifest(
        logTag: String,
        versions: List<String>,
        manifest: ArtifactManifest,
        known: Jar?,
    ): LocatorResult {
        logger.debug("$logTag Requested version: ${manifest.matchFilter.version}")

        val libVersion = getMatching(versions, "${manifest.kotlinIdeVersion}-", manifest.matchFilter)
            ?: return LocatorResult.NotFound(
                state = ArtifactState.NotFound(
                    """
                        |No compiler plugin artifact exists matching the requested version and criteria: 
                        |  - Version: ${manifest.matchFilter.version}, prefixed with ${manifest.kotlinIdeVersion}
                        |  - Criteria: ${manifest.matchFilter.matching}
                        |Available versions: ${if (versions.isEmpty()) "none" else versions.joinToString()} 
                    """.trimMargin()
                ),

                libVersion = manifest.matchFilter.version,
            )

        val artifactVersion = "${manifest.kotlinIdeVersion}-$libVersion"

        logger.debug("$logTag Version to locate: $artifactVersion")

        val filename = "${manifest.artifactId}-$artifactVersion.jar.$DOWNLOADING_EXTENSION"
        val plainFilename = "${manifest.artifactId}-$artifactVersion.jar"

        val checksumResult = getChecksum(
            logTag = logTag,
            manifest = manifest,
            artifactName = plainFilename,
            artifactVersion = artifactVersion,
        )

        val checksum = when (checksumResult) {
            is ChecksumResult.Success -> checksumResult.checksum

            is ChecksumResult.NotFound -> return LocatorResult.NotFound(
                state = ArtifactState.NotFound("No checksum file found for $plainFilename"),
                libVersion = libVersion,
            )

            is ChecksumResult.FailedToFetch -> return LocatorResult.FailedToFetch(
                state = ArtifactState.FailedToFetch(
                    "Failed to fetch checksum file for $plainFilename: ${checksumResult.message}"
                ),
                libVersion = libVersion,
            )
        }

        val cached = manifest.dest.resolve(plainFilename)

        if (Files.exists(cached) && known?.path == cached) {
            if (known.checksum == checksum) {
                logger.debug("$logTag A file already exists, matching checksums: ${cached.absolutePathString()}")

                return LocatorResult.Cached(
                    jar = known,
                    filter = manifest.matchFilter,
                    libVersion = libVersion,
                )
            }

            logger.debug("$logTag A file already exists, but checksums do not match: ${cached.absolutePathString()}")
        }

        return locateArtifactByFullQualifier(
            manifest = manifest,
            filename = filename,
            logTag = logTag,
            artifactName = plainFilename,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
            checksum = checksum,
        ).moved()
    }

    private fun LocatorResult.moved(): LocatorResult {
        if (this !is LocatorResult.Cached) {
            return this
        }

        return if (jar.path.extension == DOWNLOADING_EXTENSION) {
            val finalFilename = jar.path.removeDownloadingExtension()
            Files.move(jar.path, finalFilename, StandardCopyOption.ATOMIC_MOVE)

            LocatorResult.Cached(
                jar = Jar(
                    path = finalFilename,
                    checksum = jar.checksum,
                    isLocal = jar.isLocal,
                    kotlinVersionMismatch = jar.kotlinVersionMismatch,
                ),
                filter = filter,
                libVersion = libVersion,
                original = original,
            )
        } else {
            this
        }
    }

    private fun Path.removeDownloadingExtension(): Path {
        return resolveSibling(fileName.toString().removeSuffix(".$DOWNLOADING_EXTENSION"))
    }

    private suspend fun locateArtifactByFullQualifier(
        manifest: ArtifactManifest,
        filename: String,
        logTag: String,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        checksum: String,
    ): LocatorResult {
        if (!manifest.dest.exists()) {
            manifest.dest.toFile().mkdirs()
        }

        val file: Path = manifest.dest.resolve(filename)

        try {
            file.createFile()
        } catch (e: IOException) {
            return LocatorResult.FailedToFetch(
                state = ArtifactState.FailedToFetch(
                    "Failed to create file $filename: ${e::class}: ${e.message}"
                ),
                libVersion = libVersion,
            )
        }

        var result: LocatorResult? = null

        return try {
            locateNewArtifact(
                file = file,
                logTag = logTag,
                manifest = manifest,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
                checksum = checksum,
            ).also {
                result = it
            }
        } finally {
            if (result !is LocatorResult.Cached) {
                file.delete()
            }
        }
    }

    private suspend fun locateNewArtifact(
        file: Path,
        logTag: String,
        manifest: ArtifactManifest,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        checksum: String,
    ): LocatorResult {
        return when (manifest.locator) {
            is ArtifactManifest.Locator.ByUrl -> locateNewArtifactFromRemoteRepository(
                file = file,
                logTag = logTag,
                artifactUrl = manifest.locator.url,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
                filter = manifest.matchFilter,
                checksum = checksum,
            )

            is ArtifactManifest.Locator.ByPath -> locateNewArtifactLocally(
                file = file,
                artifactPath = manifest.locator.path,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
                filter = manifest.matchFilter,
                checksum = checksum,
            )
        }
    }

    private suspend fun locateNewArtifactFromRemoteRepository(
        file: Path,
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        filter: MatchFilter,
        checksum: String,
    ): LocatorResult {
        val url = "$artifactUrl/$artifactVersion/$artifactName"

        try {
            val link = file.removeDownloadingExtension().jarLinkName()
            link.deleteIfExists()
        } catch (e: Exception) {
            logger.debug("Failed to delete old link for $artifactName: ${e::class}: ${e.message}")
        }

        val downloadResult = downloadFile(
            destination = file,
            logTag = logTag,
            url = url,
        )

        return when (downloadResult) {
            is DownloadResult.Success -> LocatorResult.Cached(
                jar = Jar(file, checksum, isLocal = false, kotlinVersionMismatch = null),
                filter = filter,
                libVersion = libVersion,
            )

            is DownloadResult.NotFound -> LocatorResult.NotFound(
                state = ArtifactState.NotFound("File not found $url"),
                libVersion = libVersion,
            )

            is DownloadResult.FailedToFetch -> LocatorResult.FailedToFetch(
                state = ArtifactState.FailedToFetch(downloadResult.message),
                libVersion = libVersion,
            )
        }
    }

    private fun locateNewArtifactLocally(
        file: Path,
        artifactPath: Path,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        filter: MatchFilter,
        checksum: String,
    ): LocatorResult {
        val jarPath = artifactPath.resolve(artifactVersion).resolve(artifactName)

        if (!jarPath.exists()) {
            return LocatorResult.NotFound(
                state = ArtifactState.NotFound("File does not exist: ${jarPath.absolutePathString()}"),
                libVersion = libVersion,
            )
        }

        try {
            Files.copy(jarPath, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            return LocatorResult.FailedToFetch(
                state = ArtifactState.FailedToFetch(
                    "Failed to copy file $artifactName: ${e::class}: ${e.message}"
                ),
                libVersion = libVersion,
            )
        }

        val link = file.removeDownloadingExtension().jarLinkName()

        try {
            link.deleteIfExists()

            Files.createSymbolicLink(link, jarPath)
        } catch (e: Exception) {
            logger.debug("Failed to create symbolic link for $artifactName: ${e::class}: ${e.message}")

            try {
                link.deleteIfExists()
                link.createFile()
                link.writeText(jarPath.absolutePathString())
            } catch (e: Exception) {
                logger.debug("Failed to create fallback link for $artifactName: ${e::class}: ${e.message}")
            }
        }

        return LocatorResult.Cached(
            jar = Jar(path = file, checksum = checksum, isLocal = true, kotlinVersionMismatch = null),
            filter = filter,
            libVersion = libVersion,
            original = jarPath
        )
    }

    private suspend fun getChecksum(
        logTag: String,
        manifest: ArtifactManifest,
        artifactName: String,
        artifactVersion: String,
    ): ChecksumResult {
        return when (manifest.locator) {
            is ArtifactManifest.Locator.ByUrl -> getRemoteChecksum(
                logTag = logTag,
                artifactUrl = manifest.locator.url,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
            )

            is ArtifactManifest.Locator.ByPath -> getLocalChecksum(
                artifactPath = manifest.locator.path,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
            )
        }
    }

    sealed interface ChecksumResult {
        class Success(val checksum: String) : ChecksumResult
        object NotFound : ChecksumResult
        class FailedToFetch(val message: String) : ChecksumResult
    }

    private suspend fun getRemoteChecksum(
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        artifactVersion: String,
    ): ChecksumResult {
        val url = "$artifactUrl/$artifactVersion/$artifactName.md5"
        val temp = Files.createTempFile("checksum", ".md5")
        try {
            val downloadResult = downloadFile(
                destination = temp,
                logTag = logTag,
                url = url,
            )

            return when (downloadResult) {
                is DownloadResult.Success -> {
                    val checksum = try {
                        temp.readText()
                    } catch (e: IOException) {
                        return ChecksumResult.FailedToFetch("Failed to read checksum file: ${e.message}")
                    }

                    ChecksumResult.Success(checksum)
                }

                is DownloadResult.NotFound -> ChecksumResult.NotFound
                is DownloadResult.FailedToFetch -> ChecksumResult.FailedToFetch(downloadResult.message)
            }
        } finally {
            temp.deleteIfExists()
        }
    }

    private fun getLocalChecksum(
        artifactPath: Path,
        artifactName: String,
        artifactVersion: String,
    ): ChecksumResult {
        val md5Path = artifactPath.resolve(artifactVersion).resolve("${artifactName}.md5")

        if (!md5Path.exists()) {
            val originalFile = artifactPath.resolve(artifactVersion).resolve(artifactName)

            if (!originalFile.exists()) {
                return ChecksumResult.NotFound
            }

            return try {
                ChecksumResult.Success(md5(originalFile).asChecksum())
            } catch (e: Exception) {
                ChecksumResult.FailedToFetch(
                    "Failed to calculate the checksum for the $originalFile: ${e::class}: ${e.message}"
                )
            }
        }

        return try {
            ChecksumResult.Success(md5Path.readText())
        } catch (e: Exception) {
            ChecksumResult.FailedToFetch(
                "Failed to read md5 file $md5Path: ${e::class}: ${e.message}"
            )
        }
    }

    private sealed interface DownloadResult {
        object NotFound : DownloadResult
        object Success : DownloadResult
        class FailedToFetch(val message: String) : DownloadResult
    }

    private suspend fun downloadFile(
        destination: Path,
        logTag: String,
        url: String,
    ): DownloadResult {
        val result = client.prepareGet(url).execute { httpResponse ->
            val contentLength = httpResponse.contentLength()?.toDouble() ?: 0.0

            logger.debug("$logTag Request URL: $url, status: ${httpResponse.status}, size: $contentLength")

            if (httpResponse.status == HttpStatusCode.NotFound) {
                return@execute DownloadResult.NotFound
            }

            if (!httpResponse.status.isSuccess()) {
                return@execute DownloadResult.FailedToFetch(
                    "Non 200 status code (${httpResponse.status.value}) when downloading " +
                            "from ${url}: ${httpResponse.bodyAsText()}"
                )
            }

            if (contentLength == 0.0) {
                return@execute DownloadResult.FailedToFetch(
                    "Empty response body when downloading from $url"
                )
            }

            try {
                val channel: ByteReadChannel = httpResponse.body()
                while (!channel.isClosedForRead) {
                    val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                    while (!packet.exhausted()) {
                        val bytes: ByteArray = packet.readByteArray()
                        destination.appendBytes(bytes)
                    }
                }

                DownloadResult.Success
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                DownloadResult.FailedToFetch(
                    "Exception while downloading file from $url: ${e::class}: ${e.message}"
                )
            }
        }

        if (result !is DownloadResult.Success) {
            runCatching { destination.deleteIfExists() }

            return result
        }

        logger.debug("$logTag File downloaded successfully")

        return result
    }

    sealed interface ManifestResult {
        class Success(val versions: List<String>) : ManifestResult {
            override fun toString(): String {
                return versions.toString()
            }
        }

        class FailedToFetch(val state: ArtifactState.FailedToFetch) : ManifestResult
        class NotFound(val state: ArtifactState.NotFound) : ManifestResult
    }

    internal suspend fun locateManifestAndGetVersions(
        artifactUrl: ArtifactManifest.Locator,
    ): ManifestResult {
        return when (artifactUrl) {
            is ArtifactManifest.Locator.ByUrl -> locateManifestAndGetVersionsFromRemoteRepository(artifactUrl)
            is ArtifactManifest.Locator.ByPath -> locateManifestAndGetVersionsLocally(artifactUrl)
        }
    }

    internal suspend fun locateManifestAndGetVersionsFromRemoteRepository(
        artifactUrl: ArtifactManifest.Locator.ByUrl,
    ): ManifestResult {
        val urlString = "${artifactUrl.url}/maven-metadata.xml"
        val response = try {
            client.get(urlString)
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            return ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch("Exception downloading the manifest, ${e::class}: ${e.message}")
            )
        }

        if (response.status.value != 200) {
            if (response.status == HttpStatusCode.NotFound) {
                return ManifestResult.NotFound(
                    ArtifactState.NotFound("Manifest not found at $urlString")
                )
            }

            return ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch(
                    "Non 200 status code (${response.status.value}) when downloading the manifest from $urlString. " +
                            "Response: ${response.bodyAsText()}"
                )
            )
        }

        val manifest = response.bodyAsText()

        return ManifestResult.Success(parseManifestXmlToVersions(manifest))
    }

    internal fun locateManifestAndGetVersionsLocally(
        artifactUrl: ArtifactManifest.Locator.ByPath,
    ): ManifestResult {
        val manifestPath = artifactUrl.path.resolve("maven-metadata.xml")

        if (!manifestPath.exists()) {
            return ManifestResult.NotFound(
                ArtifactState.NotFound(
                    "Manifest file does not exist: ${manifestPath.absolutePathString()}"
                )
            )
        }

        return try {
            ManifestResult.Success(parseManifestXmlToVersions(manifestPath.readText()))
        } catch (_: Exception) {
            ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch(
                    "Failed to parse manifest XML: ${manifestPath.absolutePathString()}"
                )
            )
        }
    }
}

internal fun md5(originalFile: Path): ByteArray =
    MessageDigest.getInstance("MD5")
        .digest(originalFile.readBytes())

internal fun ByteArray.asChecksum(): String = String.format("%032x", BigInteger(1, this))

private const val DOWNLOADING_EXTENSION = "downloading"

data class MatchFilter(
    val version: String,
    val matching: KotlinPluginDescriptor.VersionMatching,
)

fun RequestedKotlinPluginDescriptor.asMatchFilter(): MatchFilter {
    return MatchFilter(version, descriptor.versionMatching)
}

internal fun getMatching(
    versions: List<String>,
    prefix: String,
    filter: MatchFilter,
): String? {
    val transformed = versions.filter { it.startsWith(prefix) }.map {
        MavenComparableVersion(it.removePrefix(prefix))
    }

    if (transformed.isEmpty()) {
        return null
    }

    // exact version wins over any other
    if (transformed.firstOrNull { it.toString() == filter.version } != null) {
        return filter.version
    }

    return when (filter.matching) {
        KotlinPluginDescriptor.VersionMatching.LATEST -> {
            transformed.maxOrNull()
        }

        KotlinPluginDescriptor.VersionMatching.SAME_MAJOR -> {
            val major = filter.version.substringBefore(".")

            transformed
                .filter { it.toString().substringBefore(".") == major }
                .maxOrNull()
        }

        KotlinPluginDescriptor.VersionMatching.EXACT -> {
            null // already checked for exact match above
        }
    }.atLeast(filter.version)
}

private fun List<KotlinArtifactsRepository>.sortedByPriority(): List<KotlinArtifactsRepository> {
    return sortedByDescending {
        when (it.type) {
            KotlinArtifactsRepository.Type.PATH -> 1
            KotlinArtifactsRepository.Type.URL -> 0
        }
    }
}

private fun MavenComparableVersion?.atLeast(version: String): String? {
    if (this == null) {
        return null
    }

    return if (this >= MavenComparableVersion(version)) this.toString() else null
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

private fun Path.jarLinkName(): Path {
    return resolveSibling("$fileName.link")
}

internal fun Path.resolveOriginalJarFile(): Path? {
    val link = jarLinkName()
    return runCatching {
        if (link.exists()) {
            val maybeOriginal = if (Files.isSymbolicLink(link)) {
                link.toRealPath()
            } else {
                Path(link.readText())
            }

            if (maybeOriginal.exists() && maybeOriginal.fileName == fileName) {
                maybeOriginal
            } else {
                null
            }
        } else {
            null
        }
    }.getOrNull()
}
