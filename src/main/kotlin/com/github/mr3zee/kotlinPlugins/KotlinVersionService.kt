package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service
import org.jetbrains.kotlin.idea.fir.extensions.KotlinK2BundledCompilerPlugins

@Service
public class KotlinVersionService {
    private val version by lazy {
        KotlinK2BundledCompilerPlugins::class.java.classLoader
            .getResourceAsStream("META-INF/compiler.version")?.use { stream ->
                stream.readAllBytes().decodeToString()
            }?.trim() ?: error("Kotlin version file not found")
    }

    public fun getKotlinIdePluginVersion(): String {
        return version
    }
}
