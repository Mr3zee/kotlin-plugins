package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class JarId(
    val pluginName: String,
    val mavenId: String,
)

interface KotlinPluginsExceptionReporter {
    fun start()

    suspend fun lookFor(): Map<JarId, Set<String>>

    fun matched(id: JarId, exception: Throwable, cutoutIndex: Int)
}

class KotlinPluginsExceptionReporterImpl(
    val project: Project,
    val scope: CoroutineScope,
) : KotlinPluginsExceptionReporter, Disposable {
    private val logger by lazy { thisLogger() }

    private val connection by lazy { project.messageBus.connect(scope) }
    private var job: Job? = null
    private val initialized = CompletableDeferred<Unit>()

    private val map = ConcurrentHashMap<JarId, Set<String>>()

    private val processingQueue = Channel<KotlinPluginDiscoveryUpdater.Discovery>(Channel.UNLIMITED)

    override fun dispose() {
        connection.disconnect()
        job?.cancel()
        map.clear()
        processingQueue.close()
        processingQueue.cancel()
    }

    override fun start() {
        if (job != null) {
            return
        }

        map.clear()

        connection.subscribe(KotlinPluginDiscoveryUpdater.TOPIC, DiscoveryHandler())

        job = scope.launch(Dispatchers.IO + CoroutineName("KotlinPluginsExceptionReporterImpl.start")) {
            val jars = project.service<KotlinPluginsStorage>().requestJars()

            supervisorScope {
                jars.chunked(jars.size / 10 + 1).forEach { chunk ->
                    launch {
                        processChunk(chunk)
                    }
                }
            }

            initialized.complete(Unit)

            logger.debug("Finished initial processing of all jars")

            while (true) {
                val discovery = processingQueue.receiveCatching().getOrNull() ?: break

                processDiscovery(discovery)
            }
        }
    }

    private fun processChunk(chunk: List<KotlinPluginDiscoveryUpdater.Discovery>) {
        chunk.forEach { discovery ->
            processDiscovery(discovery)
        }

        logger.debug("Finished processing chunk of ${chunk.size} jars: ${chunk.map { "${it.pluginName} (${it.mavenId})" }}")
    }

    private fun processDiscovery(discovery: KotlinPluginDiscoveryUpdater.Discovery) {
        val jarId = JarId(discovery.pluginName, discovery.mavenId)
        map.remove(jarId)

        when (val result = KotlinPluginsJarAnalyzer.analyze(discovery.jar)) {
            is KotlinPluginsAnalyzedJar.Success -> {
                map[jarId] = result.fqNames
            }

            is KotlinPluginsAnalyzedJar.Failure -> {
                logger.debug("Jar analysis failed for ${discovery.pluginName} (${discovery.mavenId}): ${result.message}")
            }
        }
    }

    override suspend fun lookFor(): Map<JarId, Set<String>> {
        if (job == null) {
            start()
        }

        initialized.await()

        return map
    }

    override fun matched(id: JarId, exception: Throwable, cutoutIndex: Int) {
        println("exception detected for $id: ${exception.message} at index $cutoutIndex")
    }

    private inner class DiscoveryHandler : KotlinPluginDiscoveryUpdater {
        override fun discovered(discovery: KotlinPluginDiscoveryUpdater.Discovery) {
            processingQueue.trySend(discovery)
        }

        override fun reset() {
            job?.cancel()
            map.clear()
        }
    }
}
