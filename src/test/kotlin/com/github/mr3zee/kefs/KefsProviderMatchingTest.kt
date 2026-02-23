package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class KefsProviderMatchingTest {

    private val provider = KefsProvider()

    // --- Default matching tests ---

    @Test
    fun `default matching with standard group path returns descriptor`() {
        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )

        val path = Path.of("org/jetbrains/kotlinx/kotlinx-rpc-compiler-plugin-k2/1.0.0/kotlinx-rpc-compiler-plugin-k2-1.0.0.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNotNull(result)
        assertEquals("kotlinx-rpc", result!!.descriptor.name)
        assertEquals("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2", result.artifact.id)
    }

    @Test
    fun `default matching extracts version correctly`() {
        val descriptor = KotlinPluginDescriptor(
            name = "test-plugin",
            ids = listOf(MavenId("org.example:my-plugin")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )

        val path = Path.of("org/example/my-plugin/2.5.3/my-plugin-2.5.3.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNotNull(result)
        assertEquals("2.5.3", result!!.requestedVersion.value)
    }

    @Test
    fun `default matching extracts version with suffix correctly`() {
        val descriptor = KotlinPluginDescriptor(
            name = "test-plugin",
            ids = listOf(MavenId("org.example:my-plugin")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )

        val path = Path.of("org/example/my-plugin/2.5.3-dev-123/my-plugin-2.5.3-dev-123.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNotNull(result)
        assertEquals("2.5.3-dev-123", result!!.requestedVersion.value)
    }

    @Test
    fun `default matching with non-matching path returns null`() {
        val descriptor = KotlinPluginDescriptor(
            name = "test-plugin",
            ids = listOf(MavenId("org.example:my-plugin")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )

        val path = Path.of("com/other/different-plugin/1.0.0/different-plugin-1.0.0.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNull(result)
    }

    @Test
    fun `default matching with no semver in filename returns null`() {
        val descriptor = KotlinPluginDescriptor(
            name = "test-plugin",
            ids = listOf(MavenId("org.example:my-plugin")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = null,
        )

        val path = Path.of("org/example/my-plugin/latest/my-plugin-latest.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNull(result)
    }

    // --- Custom matching tests ---

    @Test
    fun `custom matching with replacement pattern returns descriptor`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:compiler-plugin-k2")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = replacement,
        )

        val path = Path.of("some/path/compiler-plugin-k2-2.2.0-0.10.2.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNotNull(result)
        assertEquals("kotlinx-rpc", result!!.descriptor.name)
        assertEquals("0.10.2", result.requestedVersion.value)
        assertEquals("org.jetbrains.kotlinx:compiler-plugin-k2", result.artifact.id)
    }

    @Test
    fun `custom matching with non-jar file returns null`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:compiler-plugin-k2")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = replacement,
        )

        val path = Path.of("some/path/compiler-plugin-k2-2.2.0-0.10.2.txt")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNull(result)
    }

    @Test
    fun `custom matching with no regex match returns null`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:compiler-plugin-k2")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = replacement,
        )

        val path = Path.of("some/path/totally-different-artifact-name.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNull(result)
    }

    @Test
    fun `custom matching with ij-style version extracts lib version`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-ij<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:compiler-plugin-k2")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = replacement,
        )

        // The detect pattern becomes: compiler-plugin-k2-<versionRegex>
        // where versionRegex is: (?<kotlinVersion>\d+\.\d+\.\d+...)-ij(?<libVersion>\d+\.\d+\.\d+...)
        val path = Path.of("some/path/compiler-plugin-k2-2.2.0-ij0.10.2.jar")

        val result = provider.locateKotlinPluginDescriptorVersionedOrNull(path, descriptor)

        assertNotNull(result)
        assertEquals("0.10.2", result!!.requestedVersion.value)
    }
}
