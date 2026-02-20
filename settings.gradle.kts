pluginManagement {
    // Resolve Kotlin version: explicit -Pkotlin.lang takes precedence, otherwise look up from IDE version map
    val ideVersionMajor = providers.gradleProperty("pluginIdeVersionMajor").orNull?.takeIf { it.isNotBlank() }
    val kotlinLang = providers.gradleProperty("kotlin.lang").orNull?.takeIf { it.isNotBlank() }
        ?: ideVersionMajor?.let { providers.gradleProperty("ide.$it.kotlinLang").orNull }

    if (kotlinLang != null) {
        resolutionStrategy {
            eachPlugin {
                if (requested.id.id.startsWith("org.jetbrains.kotlin.")) {
                    useVersion(kotlinLang)
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Kotlin Plugins"
