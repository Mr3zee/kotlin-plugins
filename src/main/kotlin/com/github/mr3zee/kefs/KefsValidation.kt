package com.github.mr3zee.kefs

internal val mavenRegex = "([\\w.]+):([\\w\\-]+)".toRegex()
internal val pluginNameRegex = "[a-zA-Z0-9_-]+".toRegex()

private val replacementPatternForbiddenCharactersRegex = "[^\\w-]".toRegex()
private val replacementPatternVersionForbiddenCharactersRegex = "[^\\w.+-]".toRegex()

/**
 * Validates a replacement version pattern.
 * Must contain `<kotlin-version>` and `<lib-version>` macros.
 * Returns null if valid, error message string if invalid.
 */
internal fun validateReplacementPatternVersion(pattern: String): String? {
    return validateReplacementPattern(
        pattern = pattern,
        isVersion = true,
        mustContain = listOf(
            KotlinPluginDescriptor.Replacement.VersionMacro.KOTLIN_VERSION,
            KotlinPluginDescriptor.Replacement.VersionMacro.LIB_VERSION,
        ),
        allAvailable = KotlinPluginDescriptor.Replacement.VersionMacro.entries,
    )
}

/**
 * Validates a replacement jar pattern.
 * Must contain `<artifact-id>` macro.
 * Returns null if valid, error message string if invalid.
 */
internal fun validateReplacementPatternJar(pattern: String): String? {
    return validateReplacementPattern(
        pattern = pattern,
        isVersion = false,
        mustContain = listOf(KotlinPluginDescriptor.Replacement.JarMacro.ARTIFACT_ID),
        allAvailable = KotlinPluginDescriptor.Replacement.JarMacro.entries,
    )
}

/**
 * Validates a replacement pattern.
 * Returns null if valid, error message string if invalid.
 */
internal fun validateReplacementPattern(
    pattern: String,
    isVersion: Boolean,
    mustContain: List<KotlinPluginDescriptor.Replacement.Marco>,
    allAvailable: List<KotlinPluginDescriptor.Replacement.Marco>,
): String? {
    if (pattern.isBlank()) {
        return "Pattern must not be empty"
    }

    if (mustContain.any { !pattern.contains(it.macro) }) {
        return "Pattern must contain: ${mustContain.joinToString(", ") { it.macro }}"
    }

    if (!isVersion && pattern.contains(".jar")) {
        return "Pattern must not contain .jar extension"
    }

    var i = 0
    var invalidMacro = false
    val allAvailableMacros = allAvailable.map { it.macro }
    while (i < pattern.length) {
        val c = pattern[i]

        if (c != '<' && c != '>') {
            i++
            continue
        }

        val macro = when (c) {
            '<' -> {
                val start = i
                var next = c

                while (next != '>') {
                    if (++i >= pattern.length) {
                        invalidMacro = true
                        break
                    }

                    next = pattern[i]
                }

                pattern.substring(start, (++i).coerceAtMost(pattern.length))
            }

            '>' -> {
                invalidMacro = true
                break
            }

            else -> continue
        }

        if (macro !in allAvailableMacros) {
            invalidMacro = true
            break
        }
    }

    if (invalidMacro) {
        return "Invalid angle bracket usage. Only these macros are allowed: ${allAvailable.joinToString(", ") { it.macro }}"
    }

    var noMacros = pattern
    allAvailable.forEach {
        noMacros = noMacros.replace(it.macro, "")
    }

    if (isVersion) {
        if (noMacros.contains(replacementPatternVersionForbiddenCharactersRegex)) {
            return "Version pattern contains forbidden characters (only word characters, dots, plus, and hyphens allowed)"
        }
    } else {
        if (noMacros.contains(replacementPatternForbiddenCharactersRegex)) {
            return "Pattern contains forbidden characters (only word characters and hyphens allowed)"
        }
    }

    return null
}
