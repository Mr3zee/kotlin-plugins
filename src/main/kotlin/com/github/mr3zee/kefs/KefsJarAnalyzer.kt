package com.github.mr3zee.kefs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal sealed interface KefsAnalyzedJar {
    data class Success(val fqNames: Set<String>) : KefsAnalyzedJar
    data class Failure(val message: String) : KefsAnalyzedJar
}

/**
 * Analyses a JAR file and returns fully qualified names (FQNs) of all classes inside it.
 *
 * - Returns all class names found in the JAR (including nested classes, which will contain `$`).
 * - Excludes synthetic descriptors like `module-info.class` and `package-info.class`.
 * - Duplicates (e.g., from multi-release JARs) are deduplicated.
 */
internal object KefsJarAnalyzer {
    suspend fun analyze(jar: Path): KefsAnalyzedJar {
        return try {
            doAnalyze(jar)
        } catch (e: Exception) {
            KefsAnalyzedJar.Failure("Failed to analyze JAR: ${e.message}")
        }
    }

    private suspend fun doAnalyze(jar: Path): KefsAnalyzedJar = withContext(Dispatchers.IO) {
        if (!Files.exists(jar) || !Files.isRegularFile(jar)) {
            return@withContext KefsAnalyzedJar.Failure("File does not exist or is not a regular file: $jar")
        }

        JarFile(jar.toFile()).use { jarFile ->
            val result = LinkedHashSet<String>()
            val entries = jarFile.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()

                if (e.isDirectory) {
                    continue
                }

                val name = e.name
                if (!name.endsWith(".class")) {
                    continue
                }

                val base = name.removeSuffix(".class")

                // Exclude special descriptors
                if (base == "module-info" || base.endsWith("module-info")) {
                    continue
                }

                if (base == "package-info" || base.endsWith("package-info")) {
                    continue
                }

                @Suppress("CanConvertToMultiDollarString")
                if (base.contains("special$\$inlined")) {
                    continue
                }

                if (syntheticSkippableClass.matches(base)) {
                    continue
                }

                val fqn = base
                    .replace('/', '.')
                    .replace('$', '.')

                result.add(fqn)
            }

            KefsAnalyzedJar.Success(result)
        }
    }

    private val syntheticSkippableClass = Regex(".*\\$\\d")
}
