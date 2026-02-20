package com.github.mr3zee.kefs

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

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
            requestedVersion = "0.2.3-dev-1".requested(),
        )

        val sameMajor = MatchFilter(
            matching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            requestedVersion = "0.2.2".requested(),
        )

        val latest = MatchFilter(
            matching = KotlinPluginDescriptor.VersionMatching.LATEST,
            requestedVersion = "0.2.3".requested(),
        )

        assertEquals("0.2.3-dev-1", getMatching(listOf(versions1, versions2), "1.9.24-", exact)?.value)
        assertEquals("0.2.3-dev-1", getMatching(listOf(versions1, versions2), "1.9.24-", sameMajor)?.value)
        assertEquals("1.5.0-dev-1", getMatching(listOf(versions1), "1.9.24-", latest)?.value)
        assertEquals(null, getMatching(listOf(versions1, versions2), "1.9.24-", latest))
    }

    fun testManifestDownload() = runBlocking {
        val repo = KotlinArtifactsRepository(
            name = "kotlinx-rpc for IDE",
            value = "https://redirector.kotlinlang.org/maven/kxrpc-for-ide",
            type = KotlinArtifactsRepository.Type.URL,
        )
        val manifestResult = KefsJarLocator.locateManifestAndGetVersionsFromRemoteRepository(
            KefsJarLocator.ArtifactManifest.Locator.ByUrl(
                repo,
                "https://redirector.kotlinlang.org/maven/kxrpc-eap/org/jetbrains/kotlinx/kotlinx-rpc-compiler-plugin",
            ),
        )

        if (manifestResult is KefsJarLocator.ManifestResult.FailedToFetch) {
            fail("Failed to download manifest: ${manifestResult.state.message}")
        }

        val extracted = (manifestResult as KefsJarLocator.ManifestResult.Success).versions.filter {
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
        val result = KefsJarLocator.locateArtifacts(
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
                            value = "https://redirector.kotlinlang.org/maven/kxrpc-eap",
                            type = KotlinArtifactsRepository.Type.URL,
                        )
                    ),
                    replacement = null,
                ),
                requestedVersion = "0.2.2-dev-1".requested(),
            ),
            kotlinIdeVersion = "1.9.24",
            dest = tempFile,
            known = emptyMap(),
        )

        val jarFile = tempFile.toFile().listFiles()?.firstOrNull {
            it.name.endsWith(".jar")
        }
        assertNotNull(jarFile)
        assertTrue(jarFile!!.exists())
        assertEquals(jarFile.toPath(), (result.locatorResults.values.single() as LocatorResult.Cached).jar.path)
    }

    fun testXmlLoading() {
        val state = KefsDefaultStateLoader.loadState()
        val repository = KotlinArtifactsRepository(
            name = "kotlinx-rpc for IDE",
            value = "https://redirector.kotlinlang.org/maven/kxrpc-for-ide",
            type = KotlinArtifactsRepository.Type.URL,
        )

        val central = KotlinArtifactsRepository(
            name = "Maven Central",
            value = "https://repo1.maven.org/maven2",
            type = KotlinArtifactsRepository.Type.URL,
        )

        assertSameElements(
            state.repositories,
            listOf(repository, central),
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
                    replacement = null,
                ),
            ),
        )
    }

    fun testJarAnalyzer() = runBlocking {
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

        val matched = KefsExceptionAnalyzerService.match(
            mapOf(
                JarId("1", "1", "1".requested(), "1".resolved()) to setOf(
                    "com.github.mr3zee.kefs.ExceptionClassA",
                    "com.github.mr3zee.kefs.ExceptionClassB",
                ),
                JarId("1", "1", "2".requested(), "2".resolved()) to setOf(
                    "com.github.mr3zee.kefs.ExceptionClassA",
                    "com.github.mr3zee.kefs.ExceptionClassB",
                    "com.github.mr3zee.kefs.ExceptionClassC",
                ),
                JarId("1", "1", "3".requested(), "3".resolved()) to setOf(
                    "com.github.mr3zee.kefs.ExceptionClassA",
                    "com.github.mr3zee.kefs.ExceptionClassB",
                    "com.github.mr3zee.kefs.ExceptionClassC",
                ),
                JarId("1", "1", "4".requested(), "4".resolved()) to setOf(
                    "com.github.mr3zee.kefs.ExceptionClassA",
                ),
            ),
            exception,
        ).orEmpty().map { it.requestedVersion.value }

        assertSameElements(matched, listOf("2", "3"))
    }

    fun testCustomReplacement() {
        val descriptor = KotlinPluginDescriptor(
            name = "kotlinx-rpc-local",
            ids = listOf(
                MavenId("org.jetbrains.kotlinx:compiler-plugin-cli"),
                MavenId("org.jetbrains.kotlinx:compiler-plugin-k2"),
                MavenId("org.jetbrains.kotlinx:compiler-plugin-backend"),
                MavenId("org.jetbrains.kotlinx:compiler-plugin-common"),
            ),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            repositories = listOf(),
            ignoreExceptions = false,
            enabled = true,
            replacement = KotlinPluginDescriptor.Replacement(
                version = "<kotlin-version>-<lib-version>",
                detect = "<artifact-id>",
                search = "kotlinx-rpc-<artifact-id>",
            ),
        )

        val requested = KefsProvider().locateKotlinPluginDescriptorVersionedOrNull(
            Path("compiler-plugin/compiler-plugin-k2/build/libs/compiler-plugin-k2-2.2.0-ij251-78-0.10.2.jar"),
            descriptor,
        ) ?: return fail("Failed to locate plugin descriptor")

        assertEquals("0.10.2", requested.requestedVersion.value)
        assertEquals(
            "compiler-plugin-k2-2.2.20-0.10.2",
            requested.descriptor.replacement?.getDetectString(
                MavenId("org.jetbrains.kotlinx:compiler-plugin-k2"),
                "2.2.20-0.10.2",
            )
        )
        assertEquals(
            "kotlinx-rpc-compiler-plugin-k2",
            requested.descriptor.replacement?.getArtifactString(
                MavenId("org.jetbrains.kotlinx:compiler-plugin-k2"),
            )
        )
    }

    private suspend fun loadAndAnalyzeJar(name: String): Set<String> {
        val jarUrl = MyPluginTest::class.java.getResource("/$name")
            ?: error("Failed to load jar")

        val jar = Path.of(jarUrl.toURI())
        val result = KefsJarAnalyzer.analyze(jar)
        assert(result is KefsAnalyzedJar.Success)
        val analyzedJar = result as KefsAnalyzedJar.Success
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
