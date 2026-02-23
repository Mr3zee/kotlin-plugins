package com.github.mr3zee.kefs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class KefsDiskScannerTest {
    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kefs-disk-scanner-test")
    }

    @OptIn(ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- findMatchingJar tests ---

    @Test
    fun `findMatchingJar finds exact version`() {
        val cacheDir = tempDir.resolve("cache").also { it.createDirectories() }
        createJar(cacheDir, "my-artifact-2.2.0-1.0.0.jar")
        createJar(cacheDir, "my-artifact-2.2.0-1.1.0.jar")

        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT),
        )

        assertNotNull("Should find exact version", result)
        assertEquals("1.0.0", result!!.first.value)
        assertTrue(result.third.toString().contains("my-artifact-2.2.0-1.0.0.jar"))
    }

    @Test
    fun `findMatchingJar finds latest version`() {
        val cacheDir = tempDir.resolve("cache").also { it.createDirectories() }
        createJar(cacheDir, "my-artifact-2.2.0-1.0.0.jar")
        createJar(cacheDir, "my-artifact-2.2.0-1.1.0.jar")
        createJar(cacheDir, "my-artifact-2.2.0-2.0.0.jar")

        // Use a requested version NOT in the list to avoid exact-match short-circuit
        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST),
        )

        assertNotNull("Should find latest version", result)
        assertEquals("2.0.0", result!!.first.value)
    }

    @Test
    fun `findMatchingJar finds same major version`() {
        val cacheDir = tempDir.resolve("cache").also { it.createDirectories() }
        createJar(cacheDir, "my-artifact-2.2.0-1.0.0.jar")
        createJar(cacheDir, "my-artifact-2.2.0-1.5.0.jar")
        createJar(cacheDir, "my-artifact-2.2.0-2.0.0.jar")

        // Use a requested version NOT in the list to avoid exact-match short-circuit
        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("1.2.0".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR),
        )

        assertNotNull("Should find same major version", result)
        assertEquals("1.5.0", result!!.first.value)
    }

    @Test
    fun `findMatchingJar returns null when no match`() {
        val cacheDir = tempDir.resolve("cache").also { it.createDirectories() }
        createJar(cacheDir, "my-artifact-2.2.0-2.0.0.jar")

        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT),
        )

        assertNull("Should not find non-matching version", result)
    }

    @Test
    fun `findMatchingJar returns null for nonexistent directory`() {
        val result = KefsDiskScanner.findMatchingJar(
            basePath = tempDir.resolve("nonexistent"),
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST),
        )

        assertNull("Should return null for nonexistent directory", result)
    }

    @Test
    fun `findMatchingJar returns null for empty directory`() {
        val cacheDir = tempDir.resolve("empty").also { it.createDirectories() }

        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:my-artifact"),
            kotlinIdeVersion = "2.2.0",
            replacement = null,
            matchFilter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST),
        )

        assertNull("Should return null for empty directory", result)
    }

    @Test
    fun `findMatchingJar with replacement pattern`() {
        val cacheDir = tempDir.resolve("cache").also { it.createDirectories() }
        createJar(cacheDir, "kotlinx-rpc-compiler-plugin-k2-2.2.0-1.0.0.jar")

        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val result = KefsDiskScanner.findMatchingJar(
            basePath = cacheDir,
            artifact = MavenId("org.example:compiler-plugin-k2"),
            kotlinIdeVersion = "2.2.0",
            replacement = replacement,
            matchFilter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT),
        )

        assertNotNull("Should find with replacement pattern", result)
        assertEquals("1.0.0", result!!.first.value)
    }

    // --- validateCachedJar tests ---

    @Test
    fun `validateCachedJar succeeds with valid metadata`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar")
        jarPath.writeText("fake jar content")

        val metadataPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar.$METADATA_EXTENSION")
        metadataPath.writeText(Json.encodeToString(JarDiskMetadata("test-repo")))

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.2.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNotNull("Should validate successfully", result)
        assertEquals("1.0.0", result!!.resolvedVersion.value)
        assertEquals("test-repo", result.origin.name)
        assertFalse("Should not be local (no link file)", result.jar.isLocal)
    }

    @Test
    fun `validateCachedJar returns null when metadata missing`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar")
        jarPath.writeText("fake jar content")

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.2.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNull("Should return null when metadata missing", result)
    }

    @Test
    fun `validateCachedJar returns null when origin repo not in list`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar")
        jarPath.writeText("fake jar content")

        val metadataPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar.$METADATA_EXTENSION")
        metadataPath.writeText(Json.encodeToString(JarDiskMetadata("unknown-repo")))

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.2.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNull("Should return null when origin repo not in allowed list", result)
    }

    @Test
    fun `validateCachedJar returns null when metadata is malformed`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar")
        jarPath.writeText("fake jar content")

        val metadataPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar.$METADATA_EXTENSION")
        metadataPath.writeText("not valid json")

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.2.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNull("Should return null when metadata is malformed JSON", result)
    }

    @Test
    fun `validateCachedJar computes correct checksum`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar")
        jarPath.writeText("deterministic content")

        val metadataPath = cacheDir.resolve("my-artifact-2.2.0-1.0.0.jar.$METADATA_EXTENSION")
        metadataPath.writeText(Json.encodeToString(JarDiskMetadata("test-repo")))

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.2.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNotNull(result)
        val expectedChecksum = md5(jarPath).asChecksum()
        assertEquals(expectedChecksum, result!!.jar.checksum)
    }

    @Test
    fun `validateCachedJar detects kotlin version mismatch`() {
        val cacheDir = tempDir.resolve("validate").also { it.createDirectories() }
        val jarPath = cacheDir.resolve("my-artifact-2.1.0-1.0.0.jar")
        jarPath.writeText("fake jar content")

        val metadataPath = cacheDir.resolve("my-artifact-2.1.0-1.0.0.jar.$METADATA_EXTENSION")
        metadataPath.writeText(Json.encodeToString(JarDiskMetadata("test-repo")))

        val repo = KotlinArtifactsRepository("test-repo", "/some/path", KotlinArtifactsRepository.Type.PATH)

        val result = KefsDiskScanner.validateCachedJar(
            jarPath = jarPath,
            kotlinIdeVersion = "2.2.0",
            resolvedKotlinVersion = "2.1.0",
            resolvedVersion = "1.0.0".resolved(),
            repositories = listOf(repo),
        )

        assertNotNull("Should validate successfully even with mismatch", result)
        assertNotNull("Should report kotlin version mismatch", result!!.jar.kotlinVersionMismatch)
        assertEquals("2.2.0", result.jar.kotlinVersionMismatch!!.ideVersion)
        assertEquals("2.1.0", result.jar.kotlinVersionMismatch!!.jarVersion)
    }

    // --- helpers ---

    private fun createJar(dir: Path, name: String) {
        dir.resolve(name).writeText("fake jar content for $name")
    }
}
