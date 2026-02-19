import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.serialization) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()

// Resolve IDE version properties: use explicit values if set, otherwise fall back to pluginIdeVersionMajor
val ideVersionMajor = providers.gradleProperty("pluginIdeVersionMajor").get()
val explicitIdeSuffix = providers.gradleProperty("pluginIdeSuffix").get()
val explicitSinceBuild = providers.gradleProperty("pluginSinceBuild").get()
val explicitUntilBuild = providers.gradleProperty("pluginUntilBuild").get()

val resolvedSinceBuild = ideVersionMajor.ifEmpty { explicitSinceBuild }
val resolvedUntilBuild = (if (ideVersionMajor.isNotEmpty()) "$ideVersionMajor.*" else "").ifEmpty { explicitUntilBuild }
val resolvedIdeSuffix = ideVersionMajor.ifEmpty { explicitIdeSuffix }.ifEmpty { resolvedSinceBuild }

// Compute version: base version + optional IDE suffix (e.g., "0.2.0-251")
val baseVersion = providers.gradleProperty("pluginVersion").get()
version = if (resolvedIdeSuffix.isNotEmpty()) "$baseVersion-$resolvedIdeSuffix" else baseVersion

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)

    explicitApi()

    // Add IDE-specific source directories based on sinceBuild
    sourceSets {
        main {
            if (resolvedSinceBuild.isNotEmpty()) {
                kotlin.srcDir(file("src/$resolvedSinceBuild/kotlin"))
            }
        }
    }
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    // search for com.jetbrains.intellij.idea
    intellijPlatform {
        defaultRepositories() // https://www.jetbrains.com/intellij-repository/releases, search com.jetbrains.intellij.idea
        jetbrainsRuntime()
        snapshots() // https://www.jetbrains.com/intellij-repository/snapshots
        nightly()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    implementation(libs.jsoup)
    implementation(libs.serialization)

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        val platformVersion = providers.gradleProperty("platformVersion")
        intellijIdea(platformVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
//        fleetBackend(platformVersion)

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = resolvedSinceBuild.ifEmpty { null }
            untilBuild = resolvedUntilBuild.ifEmpty { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion")
            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(
                    ProductRelease.Channel.RELEASE,
                    ProductRelease.Channel.EAP,
                    ProductRelease.Channel.BETA,
                    ProductRelease.Channel.RC,
                    ProductRelease.Channel.PREVIEW,
                )
                sinceBuild = resolvedSinceBuild.ifEmpty { null }
                untilBuild = resolvedUntilBuild.ifEmpty { null }
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

tasks.named<RunIdeTask>("runIde") {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Didea.kotlin.plugin.use.k2=true")
    }
}

tasks.named<VerifyPluginTask>("verifyPlugin") {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Didea.kotlin.plugin.use.k2=true")
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Didea.kotlin.plugin.use.k2=true",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
