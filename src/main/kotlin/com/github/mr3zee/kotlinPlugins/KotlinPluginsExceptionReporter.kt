package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.jetbrains.rd.util.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class JarId(
    val pluginName: String,
    val mavenId: String,
)

interface KotlinPluginsExceptionReporter {
    fun start()

    suspend fun lookFor(): Map<JarId, Set<String>>

    fun matched(id: JarId, exception: Throwable, cutoutIndex: Int, autoDisable: Boolean)

    fun hasExceptions(pluginName: String, mavenId: String): Boolean
}

class KotlinPluginsExceptionReporterImpl(
    val project: Project,
    val scope: CoroutineScope,
) : KotlinPluginsExceptionReporter, Disposable {
    private val logger by lazy { thisLogger() }

    private val connection by lazy { project.messageBus.connect(scope) }
    private val statusPublisher by lazy { project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC) }
    private var job: Job? = null
    private val initialized = CompletableDeferred<Unit>()

    private val stackTraceMap = ConcurrentHashMap<JarId, Set<String>>()

    override fun dispose() {
        connection.disconnect()
        job?.cancel()
        stackTraceMap.clear()
    }

    override fun start() {
        if (job != null) {
            return
        }

        stackTraceMap.clear()

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
        stackTraceMap.remove(jarId)

        when (val result = KotlinPluginsJarAnalyzer.analyze(discovery.jar)) {
            is KotlinPluginsAnalyzedJar.Success -> {
                stackTraceMap[jarId] = result.fqNames
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

        return stackTraceMap
    }

    private val caughtExceptions = mutableMapOf<String, List<CaughtException>>()

    private class CaughtException(
        val mavenId: String,
        val exception: Throwable,
        val cutoutIndex: Int,
    )

    override fun matched(id: JarId, exception: Throwable, cutoutIndex: Int, autoDisable: Boolean) {
        val settings = project.service<KotlinPluginsSettings>()
        val plugin = settings.pluginByName(id.pluginName) ?: return

        // store anyway to display in UI
        caughtExceptions.compute(id.pluginName) { _, old ->
            (old.orEmpty() + CaughtException(id.mavenId, exception, cutoutIndex)).let { new ->
                new.drop((new.size - EXCEPTIONS_CACHE_SIZE).coerceAtLeast(0))
            }
        }

        statusPublisher.updateArtifact(id.pluginName, id.mavenId, ArtifactStatus.ExceptionInRuntime)

        if (plugin.ignoreExceptions) {
            return
        }

        if (autoDisable) {
            settings.disablePlugins(plugin.name)

            KotlinPluginsNotificationBallon.notify(project, disabledPlugin = plugin.name)
        } else {
            // trigger editor notification across all Kotlin files
            project.service<KotlinPluginsNotifications>().activate(plugin.name)

            ApplicationManager.getApplication().invokeLater {
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }

    override fun hasExceptions(pluginName: String, mavenId: String): Boolean {
        return caughtExceptions[pluginName].orEmpty().any { it.mavenId == mavenId }
    }

    private inner class DiscoveryHandler : KotlinPluginDiscoveryUpdater {
        override fun discoveredSync(discovery: KotlinPluginDiscoveryUpdater.Discovery) {
            processDiscovery(discovery)

            caughtExceptions.compute(discovery.pluginName) { _, old ->
                old.orEmpty().filterNot { it.mavenId == discovery.mavenId }
            }
        }

        override fun reset() {
            job?.cancel()
            stackTraceMap.clear()
            caughtExceptions.clear()
        }
    }

    companion object {
        const val EXCEPTIONS_CACHE_SIZE = 20
    }
}
