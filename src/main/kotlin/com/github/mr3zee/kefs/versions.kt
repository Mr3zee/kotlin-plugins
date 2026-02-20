package com.github.mr3zee.kefs

import org.jetbrains.kotlin.config.MavenComparableVersion

@JvmInline
internal value class RequestedVersion(val value: String) {
    override fun toString(): String {
        return value
    }
}

@JvmInline
internal value class ResolvedVersion(val value: String) {
    override fun toString(): String {
        return value
    }
}

internal fun String.requested() = RequestedVersion(this)
internal fun String.resolved() = ResolvedVersion(this)
internal fun RequestedVersion.asResolvedForDiskSearch() = ResolvedVersion(this.value)

internal class JarId(
    val pluginName: String,
    val mavenId: String,
    val requestedVersion: RequestedVersion,
    val resolvedVersion: ResolvedVersion,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JarId

        if (pluginName != other.pluginName) return false
        if (mavenId != other.mavenId) return false
        if (requestedVersion != other.requestedVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pluginName.hashCode()
        result = 31 * result + mavenId.hashCode()
        result = 31 * result + requestedVersion.hashCode()
        return result
    }

    override fun toString(): String {
        return "$pluginName|$mavenId|$requestedVersion|$resolvedVersion"
    }
}

internal class PartialJarId(
    val pluginName: String? = null, // null if  no node selected
    val mavenId: String? = null, // null if no artifact selected
    val requested: RequestedVersion? = null, // null if no version selected
    val resolved: ResolvedVersion? = null, // null if the selected version was not resolved (failed to fetch, etc.)
) {
    init {
        when {
            resolved != null -> {
                requireNotNull(pluginName)
                requireNotNull(mavenId)
                requireNotNull(requested)
            }

            requested != null -> {
                requireNotNull(pluginName)
                requireNotNull(mavenId)
            }

            mavenId != null -> {
                requireNotNull(pluginName)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PartialJarId

        if (pluginName != other.pluginName) return false
        if (mavenId != other.mavenId) return false
        if (requested != other.requested) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pluginName?.hashCode() ?: 0
        result = 31 * result + (mavenId?.hashCode() ?: 0)
        result = 31 * result + (requested?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "$pluginName|$mavenId|$requested|$resolved"
    }
}

internal class FileWatcherPluginKey(
    val pluginName: String,
    val requestedVersion: RequestedVersion,
    val resolvedVersion: ResolvedVersion,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileWatcherPluginKey

        if (pluginName != other.pluginName) return false
        if (requestedVersion != other.requestedVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pluginName.hashCode()
        result = 31 * result + requestedVersion.hashCode()
        return result
    }

    override fun toString(): String {
        return "$pluginName|$requestedVersion|$resolvedVersion"
    }
}

internal class StoredJar(
    val mavenId: String,
    val resolvedVersion: ResolvedVersion?,
    val jar: Jar?,
) {
    override fun toString(): String {
        return "$mavenId|${resolvedVersion ?: "unknown"}, jar: ${jar?.path ?: "unknown"}"
    }
}

internal open class VersionedKotlinPluginDescriptor(
    open val descriptor: KotlinPluginDescriptor,
    open val requestedVersion: RequestedVersion,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VersionedKotlinPluginDescriptor

        if (descriptor.name != other.descriptor.name) return false
        if (requestedVersion != other.requestedVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = descriptor.name.hashCode()
        result = 31 * result + requestedVersion.hashCode()
        return result
    }

    override fun toString(): String {
        return "${descriptor.name}|$requestedVersion"
    }
}

internal class RequestedKotlinPluginDescriptor(
    descriptor: KotlinPluginDescriptor,
    requestedVersion: RequestedVersion,
    val artifact: MavenId,
) : VersionedKotlinPluginDescriptor(descriptor, requestedVersion) {
    override fun toString(): String {
        return "${super.toString()}|$artifact"
    }
}

internal class RequestedPluginKey(
    val mavenId: String,
    val requestedVersion: RequestedVersion,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RequestedPluginKey

        if (mavenId != other.mavenId) return false
        if (requestedVersion != other.requestedVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mavenId.hashCode()
        result = 31 * result + requestedVersion.hashCode()
        return result
    }

    override fun toString(): String {
        return "$mavenId|$requestedVersion"
    }
}

internal data class MatchFilter(
    val requestedVersion: RequestedVersion,
    val matching: KotlinPluginDescriptor.VersionMatching,
)

internal fun VersionedKotlinPluginDescriptor.asMatchFilter(): MatchFilter {
    return MatchFilter(requestedVersion, descriptor.versionMatching)
}

internal fun getMatching(
    versions: List<List<String>>,
    prefix: String,
    filter: MatchFilter,
): ResolvedVersion? {
    val transformed = versions.map { versionsPerArtifact ->
        versionsPerArtifact.filter { version -> version.startsWith(prefix) }.map { version ->
            MavenComparableVersion(version.removePrefix(prefix))
        }
    }

    if (transformed.isEmpty()) {
        return null
    }

    // exact version wins over any other
    if (transformed.all { it.any { version -> version.toString() == filter.requestedVersion.value } }) {
        return filter.requestedVersion.asResolvedForDiskSearch()
    }

    return when (filter.matching) {
        KotlinPluginDescriptor.VersionMatching.LATEST -> {
            transformed.map { it.maxOrNull() }.distinct().singleOrNull()
        }

        KotlinPluginDescriptor.VersionMatching.SAME_MAJOR -> {
            val major = filter.requestedVersion.value.substringBefore(".").takeIf {
                it != "0"
            }
            val minor = filter.requestedVersion.value.substringAfter(".").substringBefore(".")

            transformed
                .map { versionsPerArtifact ->
                    versionsPerArtifact.filter { version ->
                        val stringVersion = version.toString()
                        if (major == null) {
                            stringVersion.substringAfter(".").substringBefore(".") == minor
                        } else {
                            stringVersion.substringBefore(".") == major
                        }
                    }
                }
                .map { it.maxOrNull() }
                .distinct()
                .singleOrNull()
        }

        KotlinPluginDescriptor.VersionMatching.EXACT -> {
            null // already checked for the exact match above
        }
    }.atLeast(filter.requestedVersion)
}

private fun MavenComparableVersion?.atLeast(version: RequestedVersion): ResolvedVersion? {
    if (this == null) {
        return null
    }

    return if (this >= MavenComparableVersion(version.value)) this.toString().resolved() else null
}
