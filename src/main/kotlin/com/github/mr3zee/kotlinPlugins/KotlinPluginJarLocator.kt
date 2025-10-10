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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.exists
import kotlin.io.path.readText

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
    ) : LocatorResult {
        override val state: ArtifactState.Cached = ArtifactState.Cached(jar.path, filter.version, libVersion, filter.matching)
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

    suspend fun locateArtifacts(
        versioned: VersionedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        dest: Path,
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
                )
            }
        )
    }

    suspend fun locateArtifact(
        logTag: String,
        versioned: RequestedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        dest: Path,
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

        descriptor.repositories.forEach {
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

            val manifestResult = locateManifestAndGetVersions(logTag, locator)

            if (manifestResult is ManifestResult.FailedToFetch) {
                return LocatorResult.FailedToFetch(manifestResult.state, versioned.version)
            }

            manifestResult as ManifestResult.Found

            logger.trace("$logTag Found ${manifestResult.versions.size} versions for ${artifact.artifactId}:$kotlinIdeVersion: $manifestResult")

            val locatorResult = locateArtifactByManifest(
                logTag = logTag,
                versions = manifestResult.versions,
                manifest = manifest,
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
                        Failed to fetch artifacts from multiple repositories:
                        ${failedToFetch.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.state.message}" }}
                        """.trimIndent(),
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
                        No artifacts found matching the requested version and criteria:
                        ${notFound.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.state.message}" }}
                        """.trimIndent(),
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
                    Multiple failures occurred while fetching artifacts:
                    
                    Failed to fetch:
                    ${failedToFetch.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.state.message}" }}

                    Not found:
                    ${notFound.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.state.message}" }}
                    """.trimIndent(),
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
    ): LocatorResult {
        logger.debug("$logTag Requested version: ${manifest.matchFilter.version}")

        val libVersion = getMatching(versions, "${manifest.kotlinIdeVersion}-", manifest.matchFilter)
            ?: run {
                logger.debug(
                    "$logTag No compiler plugin artifact exists matching " +
                            "the requested version and criteria (${manifest.matchFilter})"
                )

                return LocatorResult.NotFound(
                    state = ArtifactState.NotFound(
                        """
                            No compiler plugin artifact exists matching the requested version and criteria: 
                              - Version: ${manifest.matchFilter.version}, prefixed with ${manifest.kotlinIdeVersion}
                              - Criteria: ${manifest.matchFilter.matching}
                              
                            Available versions: ${if (versions.isEmpty()) "none" else ""} 
                            ${versions.joinToString("\n") { "  - $it" }}
                        """.trimIndent()
                    ),

                    libVersion = manifest.matchFilter.version,
                )
            }

        val artifactVersion = "${manifest.kotlinIdeVersion}-$libVersion"

        logger.debug("$logTag Version to locate: $artifactVersion")

        val filename = "${manifest.artifactId}-$artifactVersion.jar.$DOWNLOADING_EXTENSION"
        val forIdeFilename = "${manifest.artifactId}-$artifactVersion-$FOR_IDE_CLASSIFIER.jar"
        val plainFilename = "${manifest.artifactId}-$artifactVersion.jar"

        val forIdeVersion = locateArtifactByFullQualifier(
            manifest = manifest,
            filename = filename,
            logTag = logTag,
            artifactName = forIdeFilename,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
        )

        if (forIdeVersion is LocatorResult.Cached) {
            return forIdeVersion.moved()
        }

        logger.debug("$logTag No for-ide version exists, trying the default version")

        return locateArtifactByFullQualifier(
            manifest = manifest,
            filename = filename,
            logTag = logTag,
            artifactName = plainFilename,
            artifactVersion = artifactVersion,
            libVersion = libVersion,
        ).moved()
    }

    private fun LocatorResult.moved(): LocatorResult {
        if (this !is LocatorResult.Cached) {
            return this
        }

        return if (jar.isNew) {
            val finalFilename = jar.path.removeDownloadingExtension()
            Files.move(jar.path, finalFilename, StandardCopyOption.ATOMIC_MOVE)

            LocatorResult.Cached(Jar(finalFilename, isNew = true), filter, libVersion)
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
    ): LocatorResult {
        if (!manifest.dest.exists()) {
            manifest.dest.toFile().mkdirs()
        }

        val file: Path = manifest.dest.resolve(filename)

        val finalFilename = file.removeDownloadingExtension()
        if (Files.exists(finalFilename)) {
            logger.debug("$logTag A file already exists: ${finalFilename.absolutePathString()}")
            return LocatorResult.Cached(
                jar = Jar(
                    path = finalFilename,
                    isNew = false,
                ),
                filter = manifest.matchFilter,
                libVersion = libVersion,
            )
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        Files.createFile(file)

        var result: LocatorResult? = null

        return try {
            locateNewArtifact(
                file = file,
                logTag = logTag,
                manifest = manifest,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
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
    ): LocatorResult {
        return when (manifest.locator) {
            is ArtifactManifest.Locator.ByUrl -> locateNewArtifact(
                file = file,
                logTag = logTag,
                artifactUrl = manifest.locator.url,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
                filter = manifest.matchFilter,
            )

            is ArtifactManifest.Locator.ByPath -> locateNewArtifact(
                file = file,
                logTag = logTag,
                artifactPath = manifest.locator.path,
                artifactName = artifactName,
                artifactVersion = artifactVersion,
                libVersion = libVersion,
                filter = manifest.matchFilter,
            )
        }
    }

    private suspend fun locateNewArtifact(
        file: Path,
        logTag: String,
        artifactUrl: String,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        filter: MatchFilter,
    ): LocatorResult {
        val urlString = "$artifactUrl/$artifactVersion/$artifactName"
        val result = client.prepareGet(urlString).execute { httpResponse ->
            if (httpResponse.status == HttpStatusCode.NotFound) {
                return@execute LocatorResult.NotFound(
                    state = ArtifactState.NotFound("Artifact not found at $urlString"),
                    libVersion = libVersion,
                )
            }

            if (!httpResponse.status.isSuccess()) {
                return@execute LocatorResult.FailedToFetch(
                    ArtifactState.FailedToFetch(
                        "Non 200 status code (${httpResponse.status.value}) when downloading $artifactName " +
                                "from ${urlString}: ${httpResponse.bodyAsText()}"
                    ),
                    libVersion = libVersion,
                )
            }

            val requestUrl = httpResponse.request.url.toString()
            val contentLength = httpResponse.contentLength()?.toDouble() ?: 0.0

            logger.debug("$logTag Request URL: $requestUrl, size: $contentLength")

            if (contentLength == 0.0) {
                return@execute LocatorResult.FailedToFetch(
                    ArtifactState.FailedToFetch(
                        "Empty response body when downloading $artifactName from $requestUrl"
                    ),
                    libVersion = libVersion,
                )
            }

            try {
                val channel: ByteReadChannel = httpResponse.body()
                while (!channel.isClosedForRead) {
                    val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                    while (!packet.exhausted()) {
                        val bytes: ByteArray = packet.readByteArray()
                        file.appendBytes(bytes)
                        val size = Files.size(file)

                        logger.trace("$logTag Received $size / $contentLength")
                    }
                }

                LocatorResult.Cached(Jar(file, isNew = true), filter, libVersion)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                logger.error("$logTag Exception while downloading file $artifactName", e)
                LocatorResult.FailedToFetch(
                    ArtifactState.FailedToFetch(
                        "Exception while downloading file $artifactName from $urlString: ${e::class}: ${e.message}"
                    ),
                    libVersion = libVersion,
                )
            }
        }

        if (result !is LocatorResult.Cached) {
            @Suppress("BlockingMethodInNonBlockingContext")
            Files.delete(file)
            val message = when (result) {
                is LocatorResult.FailedToFetch -> result.state.message
                is LocatorResult.NotFound -> result.state.message
                else -> "Unknown error"
            }

            logger.debug("$logTag Failed to download file $artifactName: $message")

            return result
        }

        logger.debug("$logTag File downloaded successfully")

        return result
    }

    private fun locateNewArtifact(
        file: Path,
        logTag: String,
        artifactPath: Path,
        artifactName: String,
        artifactVersion: String,
        libVersion: String,
        filter: MatchFilter,
    ): LocatorResult {
        val jarPath = artifactPath.resolve(artifactVersion).resolve(artifactName)

        if (!jarPath.exists()) {
            logger.debug("$logTag File does not exist: ${jarPath.absolutePathString()}")
            return LocatorResult.NotFound(
                state = ArtifactState.NotFound("File does not exist: ${jarPath.absolutePathString()}"),
                libVersion = libVersion,
            )
        }

        Files.copy(jarPath, file, StandardCopyOption.REPLACE_EXISTING)

        return LocatorResult.Cached(Jar(file, isNew = true), filter, libVersion)
    }

    sealed interface ManifestResult {
        class Found(val versions: List<String>) : ManifestResult {
            override fun toString(): String {
                return versions.toString()
            }
        }
        class FailedToFetch(val state: ArtifactState.FailedToFetch) : ManifestResult
    }

    internal suspend fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator,
    ): ManifestResult {
        return when (artifactUrl) {
            is ArtifactManifest.Locator.ByUrl -> locateManifestAndGetVersions(logTag, artifactUrl)
            is ArtifactManifest.Locator.ByPath -> locateManifestAndGetVersions(logTag, artifactUrl)
        }
    }

    internal suspend fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator.ByUrl,
    ): ManifestResult {
        val urlString = "${artifactUrl.url}/maven-metadata.xml"
        val response = try {
            client.get(urlString)
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            logger.error("$logTag Exception downloading the manifest", e)

            return ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch("Exception downloading the manifest, ${e::class}: ${e.message}")
            )
        }

        if (response.status.value != 200) {
            logger.debug("$logTag Failed to download the manifest: ${response.status.value} ${response.bodyAsText()}")

            return ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch(
                    "Non 200 status code (${response.status.value}) when downloading the manifest from $urlString. " +
                            "Response: ${response.bodyAsText()}"
                )
            )
        }

        val manifest = response.bodyAsText()

        return ManifestResult.Found(parseManifestXmlToVersions(manifest))
    }

    internal fun locateManifestAndGetVersions(
        logTag: String,
        artifactUrl: ArtifactManifest.Locator.ByPath,
    ): ManifestResult {
        val manifestPath = artifactUrl.path.resolve("maven-metadata.xml")

        if (!manifestPath.exists()) {
            logger.debug("$logTag Manifest file does not exist: ${manifestPath.absolutePathString()}")
            return ManifestResult.FailedToFetch(
                ArtifactState.FailedToFetch(
                    "Manifest file does not exist: ${manifestPath.absolutePathString()}"
                )
            )
        }

        return ManifestResult.Found(parseManifestXmlToVersions(manifestPath.readText()))
    }
}

private const val FOR_IDE_CLASSIFIER = "for-ide"
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
