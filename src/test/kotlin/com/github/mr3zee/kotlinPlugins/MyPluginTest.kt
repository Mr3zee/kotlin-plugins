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
            "1.9.25-0.5.0-dev-1",
        )
        assertEquals("1.9.24-0.2.3-dev-1", getLatestVersion(versions, "1.9.24"))
    }

    fun testManifestDownload() = runBlocking {
        val versions = KotlinPluginsJarDownloader.downloadManifestAndGetVersions(
            "[testManifestDownload]",
            "https://maven.pkg.jetbrains.space/public/p/krpc/maven/org/jetbrains/kotlinx/kotlinx-rpc-compiler-plugin",
        ) ?: return@runBlocking fail("Failed to download manifest")

        assertContainsElements(
            versions,
            listOf("1.9.24-0.2.2-dev-1", "1.9.20-0.2.2-dev-1", "1.9.10-0.2.2-dev-1"),
        )
    }

    fun testDownloadJar() = runBlocking {
        val tempFile = Files.createTempDirectory("testDownloadJar")
        val result = KotlinPluginsJarDownloader.downloadArtifactIfNotExists(
            project = project,
            repoUrl = "https://maven.pkg.jetbrains.space/public/p/krpc/maven",
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-rpc-compiler-plugin",
            kotlinIdeVersion = "1.9.24",
            dest = tempFile,
            optionalPreferredLibVersions = { emptySet() },
        ).firstOrNull()

        val jarFile = tempFile.toFile().listFiles()?.firstOrNull()
        assertNotNull(jarFile)
        assertTrue(jarFile!!.exists())
        assertEquals(jarFile.toPath(), result?.path)
    }
}
