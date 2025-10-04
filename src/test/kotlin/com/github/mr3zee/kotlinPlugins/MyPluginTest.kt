package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class MyPluginTest : BasePlatformTestCase() {
    fun testKotlinVersion() {
        val version = service<KotlinVersionService>().getKotlinIdePluginVersion()
        assertTrue(version.isNotEmpty())
        println(version)
    }

    fun testManifestParsing() {
        val versions = parseManifestXmlToVersions(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.jetbrains.kotlinx</groupId>
              <artifactId>kotlinx-rpc-compiler-plugin</artifactId>
              <versioning>
                <latest>1.9.24-0.2.2-dev-1</latest>
                <release>1.9.24-0.2.2-dev-1</release>
                <versions>
                  <version>1.9.24-0.2.2-dev-1</version>
                  <version>1.9.20-0.2.2-dev-1</version>
                  <version>1.9.10-0.2.2-dev-1</version>
                </versions>
                <lastUpdated>20240809074201</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()
        )

        assertContainsOrdered(
            versions,
            listOf("1.9.24-0.2.2-dev-1", "1.9.20-0.2.2-dev-1", "1.9.10-0.2.2-dev-1"),
        )
    }

    fun testVersionComparison() {
        val versions = listOf(
            "1.9.24-0.2.2-dev-1",
            "1.9.24-0.2.2-dev-2",
            "1.9.24-0.2.3-dev-1",
            "1.9.24-0.5.0-dev-1",
            "1.9.24-1.5.0-dev-1",
            "1.9.25-0.5.0-dev-1",
        )

        val exact = MatchFilter(
            matching = KotlinPluginDescriptor.VersionMatching.EXACT,
            version = "0.2.3-dev-1",
        )

        val sameMajor = MatchFilter(
            matching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            version = "0.2.2",
        )

        val latest = MatchFilter(
            matching = KotlinPluginDescriptor.VersionMatching.LATEST,
            version = "0.2.3",
        )

        assertEquals("0.2.3-dev-1", getMatching(versions, "1.9.24-", exact))
        assertEquals("0.5.0-dev-1", getMatching(versions, "1.9.24-", sameMajor))
        assertEquals("1.5.0-dev-1", getMatching(versions, "1.9.24-", latest))
    }

    fun testManifestDownload() = runBlocking {
        val versions = KotlinPluginJarLocator.locateManifestAndGetVersions(
            "[testManifestDownload]",
            KotlinPluginJarLocator.ArtifactManifest.Locator.ByUrl(
                "https://maven.pkg.jetbrains.space/public/p/krpc/maven/org/jetbrains/kotlinx/kotlinx-rpc-compiler-plugin"
            ),
        ) ?: return@runBlocking fail("Failed to download manifest")

        assertContainsElements(
            versions,
            listOf("1.9.24-0.2.2-dev-1", "1.9.20-0.2.2-dev-1", "1.9.10-0.2.2-dev-1"),
        )
    }

    fun testDownloadJar() = runBlocking {
        val tempFile = Files.createTempDirectory("testDownloadJar")
        val result = KotlinPluginJarLocator.locateArtifacts(
            project = project,
            versioned = VersionedKotlinPluginDescriptor(
                descriptor = KotlinPluginDescriptor(
                    name = "[testDownloadJar]",
                    ids = listOf(MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin")),
                    versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
                    enabled = true,
                    repositories = listOf(
                        KotlinArtifactsRepository(
                            name = "[testDownloadJar]",
                            value = "https://maven.pkg.jetbrains.space/public/p/krpc/maven",
                            type = KotlinArtifactsRepository.Type.URL,
                        )
                    ),
                ),
                version = "0.2.2-dev-1",
            ),
            kotlinIdeVersion = "1.9.24",
            dest = tempFile,
        )

        val jarFile = tempFile.toFile().listFiles()?.firstOrNull()
        assertNotNull(jarFile)
        assertTrue(jarFile!!.exists())
        assertEquals(jarFile.toPath(), result.jars.values.single()?.path)
    }

    fun testXmlLoading() {
        val state = DefaultStateLoader.loadState()
        val repository = KotlinArtifactsRepository(
            name = "kotlinx-rpc for IDE",
            value = "https://maven.pkg.jetbrains.space/public/p/krpc/for-ide",
            type = KotlinArtifactsRepository.Type.URL,
        )

        assertContainsElements(
            state.repositories,
            listOf(repository),
        )

        assertContainsElements(
            state.plugins,
            listOf(
                KotlinPluginDescriptor(
                    name = "kotlinx-rpc",
                    ids = listOf(
                        MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-cli"),
                        MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2"),
                        MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-backend"),
                        MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-common"),
                    ),
                    versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
                    repositories = listOf(repository),
                    enabled = true,
                ),
            ),
        )
    }
}
