package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.intellij.util.messages.SimpleMessageBusConnection
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class JarId(
    val pluginName: String,
    val mavenId: String,
    val version: String,
)

interface KotlinPluginsExceptionReporter {
    fun start()

    fun stop()

    suspend fun lookFor(): Map<JarId, Set<String>>

    fun matched(ids: List<JarId>, exception: Throwable, autoDisable: Boolean, isProbablyIncompatible: Boolean)

    fun hasExceptions(pluginName: String, mavenId: String, version: String): Boolean

    fun getExceptionsReport(pluginName: String, mavenId: String, version: String): ExceptionsReport?
}

class ExceptionsReport(
    // The jar is from a local repo
    val isLocal: Boolean,
    // The jar reloaded after to the same one exception occurred
    val reloadedSame: Boolean,
    // Exceptions that occurred point to the binary incompatibility
    val isProbablyIncompatible: Boolean,
    // The Kotlin version of the jar is different from the Kotlin version in the IDE
    val kotlinVersionMismatch: KotlinVersionMismatch?,
    val exceptions: List<Throwable>,
)

class KotlinPluginsExceptionReporterImpl(
    val project: Project,
    val scope: CoroutineScope,
) : KotlinPluginsExceptionReporter, Disposable {
    private val logger by lazy { thisLogger() }

    private val statusPublisher by lazy { project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC) }

    inner class State(
        private val job: Job,
    ) {
        private var closed = false

        private val connection: Lazy<SimpleMessageBusConnection> = lazy {
            if (closed) {
                error("State already closed")
            }

            val connection = project.messageBus.connect()
            connection.subscribe(KotlinPluginDiscoveryUpdater.TOPIC, DiscoveryHandler())
            connection
        }

        fun start() {
            job.start()

            runCatching { connection.value }
        }

        suspend fun join() {
            job.join()
        }

        fun close() {
            job.cancel()

            closed = true

            if (connection.isInitialized()) {
                connection.value.disconnect()
            }
        }
    }

    private val state = AtomicReference<State?>(null)

    private val stackTraceMap = ConcurrentHashMap<JarId, Set<String>>()
    private val metadata = ConcurrentHashMap<JarId, JarMetadata>()

    private val caughtExceptions = ConcurrentHashMap<String, List<CaughtException>>()

    private class CaughtException(
        val jarId: JarId,
        val exception: Throwable,
    )

    private data class JarMetadata(
        val checksum: String,
        val isLocal: Boolean,
        val kotlinVersionMismatch: KotlinVersionMismatch?,
        val reloadedSame: Boolean,
        val isProbablyIncompatible: Boolean,
    )

    override fun dispose() {
        state.getAndSet(null)?.close()
        stackTraceMap.clear()
        metadata.clear()
    }

    override fun start() {
        val newState = initializationState()
        if (!state.compareAndSet(null, newState)) {
            return
        }

        stackTraceMap.clear()
    }

    override fun stop() {
        state.getAndSet(null)?.close()
        stackTraceMap.clear()
        caughtExceptions.clear()
        metadata.clear()

        project.service<KotlinPluginsNotifications>().clear()
        refreshNotifications()
    }

    private fun initializationState() = scope.launch(
        context = Dispatchers.IO + CoroutineName("KotlinPluginsExceptionReporterImpl.start"),
        start = CoroutineStart.LAZY,
    ) {
        val jars = project.service<KotlinPluginsStorage>().requestJars()

        supervisorScope {
            jars.chunked(jars.size / 10 + 1).forEach { chunk ->
                launch {
                    processChunk(chunk)
                }
            }
        }

        logger.debug("Finished initial processing of all jars")
    }.let(::State)

    private fun processChunk(chunk: List<KotlinPluginDiscoveryUpdater.Discovery>) {
        chunk.forEach { discovery ->
            val jarId = JarId(discovery.pluginName, discovery.mavenId, discovery.version)

            metadata.compute(jarId) { _, old ->
                computeMetadata(old, discovery)
            }

            processDiscovery(discovery)
        }

        logger.debug("Finished processing chunk of ${chunk.size} jars: ${chunk.map { "${it.pluginName} (${it.mavenId})" }}")
    }

    private fun computeMetadata(
        old: JarMetadata?,
        discovery: KotlinPluginDiscoveryUpdater.Discovery,
        ifNew: () -> Unit = {},
    ): JarMetadata? {
        // the same checksum -> the same exception will be thrown eventually
        return if (old == null || old.checksum != discovery.checksum) {
            ifNew()
            JarMetadata(
                checksum = discovery.checksum,
                isLocal = discovery.isLocal,
                kotlinVersionMismatch = discovery.kotlinVersionMismatch,
                reloadedSame = false,
                isProbablyIncompatible = false,
            )
        } else {
            JarMetadata(
                checksum = discovery.checksum,
                isLocal = discovery.isLocal,
                kotlinVersionMismatch = discovery.kotlinVersionMismatch,
                reloadedSame = true,
                isProbablyIncompatible = old.isProbablyIncompatible,
            )
        }
    }

    private fun processDiscovery(discovery: KotlinPluginDiscoveryUpdater.Discovery) {
        val jarId = JarId(discovery.pluginName, discovery.mavenId, discovery.version)
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
        val current = state.get()
        if (current == null) {
            start()
        }

        val new = state.get() ?: return stackTraceMap.toMap()
        new.start()
        new.join()

        return stackTraceMap.toMap()
    }

    override fun matched(
        ids: List<JarId>,
        exception: Throwable,
        autoDisable: Boolean,
        isProbablyIncompatible: Boolean,
    ) {
        val settings = project.service<KotlinPluginsSettings>()

        ids.groupBy { it.pluginName }.forEach { (pluginName, ids) ->
            matched(settings, pluginName, ids, exception, autoDisable, isProbablyIncompatible)
        }
    }

    private fun matched(
        settings: KotlinPluginsSettings,
        pluginName: String,
        ids: List<JarId>,
        exception: Throwable,
        autoDisable: Boolean,
        isProbablyIncompatible: Boolean,
    ) {
        val plugin = settings.pluginByName(pluginName) ?: return

        // store anyway to display in UI
        caughtExceptions.compute(pluginName) { _, old ->
            val exceptions = ids.map { CaughtException(it, exception) }
            (old.orEmpty() + exceptions).let { new ->
                new.drop((new.size - EXCEPTIONS_CACHE_SIZE).coerceAtLeast(0))
            }
        }

        ids.forEach { id ->
            metadata.compute(id) { _, old ->
                old?.copy(isProbablyIncompatible = isProbablyIncompatible)
            }

            statusPublisher.updateVersion(id.pluginName, id.mavenId, id.version, ArtifactStatus.ExceptionInRuntime)
        }

        if (plugin.ignoreExceptions) {
            return
        }

        if (autoDisable) {
            settings.disablePlugin(plugin.name)

            KotlinPluginsNotificationBallon.notify(
                project = project,
                disabledPlugin = plugin.name,
                mavenId = ids.singleOrNull()?.mavenId,
                version = ids.singleOrNull()?.version,
            )
        } else {
            // trigger editor notification across all Kotlin files
            project.service<KotlinPluginsNotifications>().activate(ids)
            refreshNotifications()
        }
    }

    override fun hasExceptions(pluginName: String, mavenId: String, version: String): Boolean {
        return caughtExceptions[pluginName].orEmpty().any { it.jarId.mavenId == mavenId && it.jarId.version == version }
    }

    override fun getExceptionsReport(pluginName: String, mavenId: String, version: String): ExceptionsReport? {
        val jarId = JarId(pluginName, mavenId, version)
        val lookup = stackTraceMap[jarId].orEmpty()

        val list = caughtExceptions[pluginName].orEmpty()
            .filter { it.jarId.mavenId == mavenId && it.jarId.version == version }
            .map { it.exception }
        // Deduplicate by exception "signature": class + message + full stacktrace
        val exceptions = list.distinctBy { ex ->
            buildString {
                append(ex::class.java.name)
                append('|')
                append(ex.message ?: "")
                append('|')
                append(ex.distinctStacktrace(lookup))
            }
        }

        return if (exceptions.isEmpty()) {
            null
        } else {
            val metadata = metadata[JarId(pluginName, mavenId, version)] ?: return null

            ExceptionsReport(
                exceptions = exceptions,
                isLocal = metadata.isLocal,
                reloadedSame = metadata.reloadedSame,
                isProbablyIncompatible = metadata.isProbablyIncompatible,
                kotlinVersionMismatch = metadata.kotlinVersionMismatch,
            )
        }
    }

    private fun Throwable.distinctStacktrace(lookup: Set<String>): String {
        return stackTrace.filter { it.className in lookup }.joinToString("|") { it.toString() } +
                "|" +
                cause?.distinctStacktrace(lookup).orEmpty()
    }

    private inner class DiscoveryHandler : KotlinPluginDiscoveryUpdater {
        override fun discoveredSync(discovery: KotlinPluginDiscoveryUpdater.Discovery) {
            val jarId = JarId(discovery.pluginName, discovery.mavenId, discovery.version)

            var new = false
            metadata.compute(jarId) { _, old ->
                computeMetadata(old, discovery) {
                    new = true
                    caughtExceptions.compute(discovery.pluginName) { _, old ->
                        old.orEmpty().filterNot {
                            it.jarId == jarId
                        }
                    }
                }
            }

            if (new) {
                project.service<KotlinPluginsNotifications>()
                    .deactivate(discovery.pluginName, discovery.mavenId, discovery.version)

                refreshNotifications()

                processDiscovery(discovery)
            }
        }

        override fun reset() {
            state.getAndSet(null)?.close()
            stackTraceMap.clear()
        }
    }

    fun clearAll() {
        state.getAndSet(null)?.close()
        stackTraceMap.clear()
        caughtExceptions.clear()
        metadata.clear()

        project.service<KotlinPluginsNotifications>().clear()
        refreshNotifications()
    }

    private fun refreshNotifications() {
        ApplicationManager.getApplication().invokeLater {
            EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    companion object {
        const val EXCEPTIONS_CACHE_SIZE = 20
    }
}
