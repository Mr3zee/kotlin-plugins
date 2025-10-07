package com.github.mr3zee.kotlinPlugins

interface KotlinPluginsExceptionReporter {
    // maven id -> set of fqNames
    suspend fun lookFor(): Map<String, Set<String>>
    fun matched(id: String, exception: Throwable)
}

class KotlinPluginsExceptionReporterImpl : KotlinPluginsExceptionReporter {
    override suspend fun lookFor(): Map<String, Set<String>> {
        return emptyMap()
    }

    override fun matched(id: String, exception: Throwable) {
        println("exception detected for $id: ${exception.message}")
    }
}
