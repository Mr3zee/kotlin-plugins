package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

// todo
//  - big jar
//  - malformed jar
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

        assertSameElements(
            versions,
            listOf("1.9.24-0.2.2-dev-1", "1.9.20-0.2.2-dev-1", "1.9.10-0.2.2-dev-1"),
        )
    }

    fun testVersionComparison() {
        val versions1 = listOf(
            "1.9.24-0.2.2-dev-1",
            "1.9.24-0.2.2-dev-2",
            "1.9.24-0.2.3-dev-1",
            "1.9.24-0.5.0-dev-1",
            "1.9.24-1.5.0-dev-1",
            "1.9.25-0.5.0-dev-1",
        )

        val versions2 = listOf(
            "1.9.24-0.2.2-dev-1",
            "1.9.24-0.2.2-dev-2",
            "1.9.24-0.2.3-dev-1",
            "1.9.24-0.5.0-dev-1",
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

        assertEquals("0.2.3-dev-1", getMatching(listOf(versions1, versions2), "1.9.24-", exact))
        assertEquals("0.5.0-dev-1", getMatching(listOf(versions1, versions2), "1.9.24-", sameMajor))
        assertEquals("1.5.0-dev-1", getMatching(listOf(versions1), "1.9.24-", latest))
        assertEquals(null, getMatching(listOf(versions1, versions2), "1.9.24-", latest))
    }

    fun testManifestDownload() = runBlocking {
        val manifestResult = KotlinPluginsJarLocator.locateManifestAndGetVersionsFromRemoteRepository(
            KotlinPluginsJarLocator.ArtifactManifest.Locator.ByUrl(
                "https://maven.pkg.jetbrains.space/public/p/krpc/maven/org/jetbrains/kotlinx/kotlinx-rpc-compiler-plugin"
            ),
        )

        if (manifestResult is KotlinPluginsJarLocator.ManifestResult.FailedToFetch) {
            fail("Failed to download manifest: ${manifestResult.state.message}")
        }

        val extracted = (manifestResult as KotlinPluginsJarLocator.ManifestResult.Success).versions.filter {
            it.endsWith("0.2.2-dev-1")
        }

        assertSameElements(
            extracted,
            listOf(
                "1.9.24-0.2.2-dev-1",
                "1.9.20-0.2.2-dev-1",
                "1.9.10-0.2.2-dev-1",
                "1.9.21-0.2.2-dev-1",
                "1.9.0-0.2.2-dev-1",
                "1.9.22-0.2.2-dev-1",
                "1.9.23-0.2.2-dev-1",
                "1.8.21-0.2.2-dev-1",
                "1.8.22-0.2.2-dev-1",
                "1.8.20-0.2.2-dev-1",
                "1.8.0-0.2.2-dev-1",
                "1.8.10-0.2.2-dev-1",
                "1.7.0-0.2.2-dev-1",
                "1.7.22-0.2.2-dev-1",
                "1.7.21-0.2.2-dev-1",
                "1.7.10-0.2.2-dev-1",
                "1.7.20-0.2.2-dev-1",
            ),
        )
    }

    fun testDownloadJar() = runBlocking {
        val tempFile = Files.createTempDirectory("testDownloadJar")
        val result = KotlinPluginsJarLocator.locateArtifacts(
            versioned = VersionedKotlinPluginDescriptor(
                descriptor = KotlinPluginDescriptor(
                    name = "[testDownloadJar]",
                    ids = listOf(MavenId("org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin")),
                    versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
                    enabled = true,
                    ignoreExceptions = false,
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
            known = emptyMap(),
        )

        val jarFile = tempFile.toFile().listFiles()?.firstOrNull()
        assertNotNull(jarFile)
        assertTrue(jarFile!!.exists())
        assertEquals(jarFile.toPath(), (result.locatorResults.values.single() as LocatorResult.Cached).jar.path)
    }

    fun testXmlLoading() {
        val state = KotlinPluginsDefaultStateLoader.loadState()
        val repository = KotlinArtifactsRepository(
            name = "kotlinx-rpc for IDE",
            value = "https://maven.pkg.jetbrains.space/public/p/krpc/for-ide",
            type = KotlinArtifactsRepository.Type.URL,
        )

        assertSameElements(
            state.repositories,
            listOf(repository),
        )

        assertSameElements(
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
                    ignoreExceptions = false,
                    enabled = true,
                ),
            ),
        )
    }

    fun testJarAnalyzer() {
        // kotlinx-rpc-compiler-plugin-backend-2.2.0-ij251-78-0.10.0.jar
        val k2FqNames = loadAndAnalyzeJar("kotlinx-rpc-compiler-plugin-k2-2.2.0-ij251-78-0.10.0.jar")

        assertSameElements(
            k2FqNames,
            listOf(
                "kotlinx.rpc.codegen.checkers.FirRpcServiceDeclarationCheckerVS",
                "kotlinx.rpc.codegen.checkers.FirRpcExpressionCheckers",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationHelper",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFirClassChecker",
                "kotlinx.rpc.codegen.checkers.FirRpcDeclarationCheckers",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFirFunctionCheckerVS",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationTypeParameterCheckerVS",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFirFunctionChecker",
                "kotlinx.rpc.codegen.checkers.FirRpcStrictModeClassChecker",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationTypeParameterChecker",
                "kotlinx.rpc.codegen.checkers.FirRpcAnnotationCheckerVS",
                "kotlinx.rpc.codegen.checkers.SerializablePropertiesKt",
                "kotlinx.rpc.codegen.checkers.FirRpcAnnotationChecker",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFunctionCallChecker",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFunctionCallCheckerVS",
                "kotlinx.rpc.codegen.checkers.FirSerializablePropertiesProvider",
                "kotlinx.rpc.codegen.checkers.FirRpcStrictModeClassCheckerVS",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcKtDiagnosticsContainerKt",
                "kotlinx.rpc.codegen.checkers.diagnostics.FirRpcDiagnostics",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcKtDiagnosticsContainer",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcKtDiagnosticFactoryToRendererMapKt",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcKtDiagnosticsContainerCore",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcDiagnosticRendererFactory",
                "kotlinx.rpc.codegen.checkers.diagnostics.FirRpcStrictModeDiagnostics",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcStrictModeDiagnosticRendererFactory",
                "kotlinx.rpc.codegen.checkers.diagnostics.RpcDiagnosticRendererFactoryKt",
                "kotlinx.rpc.codegen.checkers.FirCheckedAnnotationFirClassCheckerVS",
                "kotlinx.rpc.codegen.checkers.FirRpcServiceDeclarationChecker",
                "kotlinx.rpc.codegen.checkers.FirSerializableProperty",
                "kotlinx.rpc.codegen.FirCheckersContext",
                "kotlinx.rpc.codegen.FirGenerationKeysKt",
                "kotlinx.rpc.codegen.RpcFirCliOptions",
                "kotlinx.rpc.codegen.FirVersionSpecificApiImpl",
                "kotlinx.rpc.codegen.FirRpcUtilsKt",
                "kotlinx.rpc.codegen.FirRpcPredicates",
                "kotlinx.rpc.codegen.FirRpcServiceGenerator",
                "kotlinx.rpc.codegen.FirRpcExtensionRegistrar",
                "kotlinx.rpc.codegen.RpcFirConfigurationKeys",
                "kotlinx.rpc.codegen.FirRpcServiceStubCompanionObject",
                "kotlinx.rpc.codegen.RpcGeneratedStubKey",
                "kotlinx.rpc.codegen.FirRpcAdditionalCheckers",
                "kotlinx.rpc.codegen.FirVersionSpecificApi",
                "kotlinx.rpc.codegen.FirVersionSpecificApiKt",
            ),
        )

        val backendFqNames = loadAndAnalyzeJar("kotlinx-rpc-compiler-plugin-backend-2.2.0-ij251-78-0.10.0.jar")

        assertSameElements(
            backendFqNames,
            listOf(
                "kotlinx.rpc.codegen.extension.IrMemberAccessExpressionBuilder",
                "kotlinx.rpc.codegen.extension.ServiceDeclaration",
                "kotlinx.rpc.codegen.extension.ServiceDeclaration.Method",
                "kotlinx.rpc.codegen.extension.RpcDeclarationScanner",
                "kotlinx.rpc.codegen.extension.IrMemberAccessExpressionBuilder.TypeBuilder",
                "kotlinx.rpc.codegen.extension.RpcIrServiceProcessor",
                "kotlinx.rpc.codegen.extension.RpcIrContext",
                "kotlinx.rpc.codegen.extension.IrMemberAccessExpressionBuilderKt",
                "kotlinx.rpc.codegen.extension.ServiceDeclaration.Method.Argument",
                "kotlinx.rpc.codegen.extension.RpcIrContext.Functions",
                "kotlinx.rpc.codegen.extension.Stub",
                "kotlinx.rpc.codegen.extension.RpcDeclarationScannerKt",
                "kotlinx.rpc.codegen.extension.Descriptor",
                "kotlinx.rpc.codegen.extension.IrUtilsKt",
                "kotlinx.rpc.codegen.extension.RpcStubGenerator",
                "kotlinx.rpc.codegen.extension.ServiceDeclaration.Callable",
                "kotlinx.rpc.codegen.extension.IrMemberAccessExpressionBuilder.ValueBuilder",
                "kotlinx.rpc.codegen.extension.RpcIrContext.Properties",
                "kotlinx.rpc.codegen.extension.RpcIrExtension",
                "kotlinx.rpc.codegen.extension.IrMemberAccessExpressionData",
                "kotlinx.rpc.codegen.VersionSpecificApiKt",
                "kotlinx.rpc.codegen.VersionSpecificApiImpl",
                "kotlinx.rpc.codegen.VersionSpecificApi.Companion",
                "kotlinx.rpc.codegen.RpcIrServiceProcessorDelegate",
                "kotlinx.rpc.codegen.VersionSpecificApi.DefaultImpls",
                "kotlinx.rpc.codegen.VersionSpecificApi",
            ),
        )
    }

    fun testStacktraceMatcher() {
        val exception = runCatching {
            ExceptionClassA().bar()
        }.exceptionOrNull() ?: error("Failed to get exception")

        val matched = KotlinPluginsExceptionAnalyzerService.match(
            mapOf(
                JarId("1", "1", "1") to setOf(
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassA",
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassB",
                ),
                JarId("1", "1", "2") to setOf(
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassA",
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassB",
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassC",
                ),
                JarId("1", "1", "3") to setOf(
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassA",
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassB",
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassC",
                ),
                JarId("1", "1", "4") to setOf(
                    "com.github.mr3zee.kotlinPlugins.ExceptionClassA",
                ),
            ),
            exception,
        ).orEmpty().map { it.version }

        assertSameElements(matched, listOf("2", "3"))
    }

    private fun loadAndAnalyzeJar(name: String): Set<String> {
        val jarUrl = MyPluginTest::class.java.getResource("/$name")
            ?: error("Failed to load jar")

        val jar = Path.of(jarUrl.toURI())
        val result = KotlinPluginsJarAnalyzer.analyze(jar)
        assert(result is KotlinPluginsAnalyzedJar.Success)
        val analyzedJar = result as KotlinPluginsAnalyzedJar.Success
        return analyzedJar.fqNames
    }
}

class ExceptionClassA {
    fun bar() {
        ExceptionClassB().foo()
    }
}

class ExceptionClassB {
    fun foo() {
        ExceptionClassC().baz()
    }
}

class ExceptionClassC {
    fun baz() {
        throw Exception("foo")
    }
}
