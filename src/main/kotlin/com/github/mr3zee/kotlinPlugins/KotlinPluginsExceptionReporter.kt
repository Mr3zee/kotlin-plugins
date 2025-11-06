package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

internal interface KotlinPluginsExceptionReporter : KotlinPluginDiscoveryUpdater {
    fun start()

    fun stop()

    suspend fun lookFor(): Map<JarId, Set<String>>

    fun matched(ids: List<JarId>, exception: Throwable, autoDisable: Boolean, isProbablyIncompatible: Boolean)

    fun hasExceptions(pluginName: String, mavenId: String, requestedVersion: RequestedVersion): Boolean

    fun getExceptionsReport(jarId: JarId): ExceptionsReport?

    suspend fun createReportFile(report: ExceptionsReport): Path?
}

internal class ExceptionsReport(
    val pluginName: String,
    val mavenId: String,
    val requestedVersion: RequestedVersion,
    val resolvedVersion: ResolvedVersion,
    val origin: KotlinArtifactsRepository,
    val checksum: String,

    // The jar is from a local repo
    val isLocal: Boolean,
    // The jar reloaded after to the same one exception occurred
    val reloadedSame: Boolean,
    // Exceptions that occurred point to the binary incompatibility
    val isProbablyIncompatible: Boolean,
    // The Kotlin version of the jar is different from the Kotlin version in the IDE
    val kotlinVersionMismatch: KotlinVersionMismatch?,
    val exceptions: List<Throwable>,
    // only for comparison
    @Suppress("PropertyName")
    val __exceptionsIds: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExceptionsReport

        if (isLocal != other.isLocal) return false
        if (reloadedSame != other.reloadedSame) return false
        if (isProbablyIncompatible != other.isProbablyIncompatible) return false
        if (kotlinVersionMismatch != other.kotlinVersionMismatch) return false
        if (__exceptionsIds != other.__exceptionsIds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isLocal.hashCode()
        result = 31 * result + reloadedSame.hashCode()
        result = 31 * result + isProbablyIncompatible.hashCode()
        result = 31 * result + (kotlinVersionMismatch?.hashCode() ?: 0)
        result = 31 * result + __exceptionsIds.hashCode()
        return result
    }
}

internal class KotlinPluginsExceptionReporterImpl(
    val project: Project,
    val scope: CoroutineScope,
) : KotlinPluginsExceptionReporter, Disposable, KotlinPluginDiscoveryUpdater {
    private val logger by lazy { thisLogger() }

    private val statusPublisher by lazy { project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC) }

    private val discoveryHandler = DiscoveryHandler()

    override suspend fun discoveredSync(
        discovery: KotlinPluginDiscoveryUpdater.Discovery,
        redrawAfterUpdate: Boolean,
    ) {
        discoveryHandler.discoveredSync(discovery, redrawAfterUpdate)
    }

    private val state = AtomicReference<Job?>(null)

    private val stackTraceMap = ConcurrentHashMap<JarId, Set<String>>()
    private val metadata = ConcurrentHashMap<JarId, JarMetadata>()

    private val caughtExceptions = ConcurrentHashMap<String, Set<CaughtException>>()

    private class CaughtException(
        val jarId: JarId,
        val exceptionId: String,
        val exception: Throwable,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CaughtException

            if (jarId != other.jarId) return false
            if (exceptionId != other.exceptionId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = jarId.hashCode()
            result = 31 * result + exceptionId.hashCode()
            return result
        }
    }

    private data class JarMetadata(
        val requestedVersion: RequestedVersion,
        val resolvedVersion: ResolvedVersion,
        val origin: KotlinArtifactsRepository,
        val checksum: String,
        val isLocal: Boolean,
        val kotlinVersionMismatch: KotlinVersionMismatch?,
        val reloadedSame: Boolean,
        val isProbablyIncompatible: Boolean,
    )

    override fun dispose() {
        state.getAndSet(null)?.cancel()
        stackTraceMap.clear()
        caughtExceptions.clear()
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
        state.getAndSet(null)?.cancel()
        stackTraceMap.clear()
        caughtExceptions.clear()
        metadata.clear()

        project.service<KotlinPluginsNotifications>().clear()
        refreshNotifications()
    }

    private fun initializationState() = scope.launch(
        context = CoroutineName("KotlinPluginsExceptionReporterImpl.start"),
        start = CoroutineStart.LAZY,
    ) {
        val jars = project.service<KotlinPluginsStorage>().requestDiscovery()

        supervisorScope {
            jars.chunked(jars.size / 10 + 1).forEach { chunk ->
                launch {
                    processChunk(chunk)
                }
            }
        }

        statusPublisher.redraw()

        logger.debug("Finished initial processing of all jars")
    }

    private suspend fun processChunk(chunk: List<KotlinPluginDiscoveryUpdater.Discovery>) {
        chunk.forEach { discovery ->
            discoveryHandler.discoveredSync(discovery, redrawAfterUpdate = false)
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
                requestedVersion = discovery.requestedVersion,
                resolvedVersion = discovery.resolvedVersion,
                checksum = discovery.checksum,
                origin = discovery.origin,
                isLocal = discovery.isLocal,
                kotlinVersionMismatch = discovery.kotlinVersionMismatch,
                reloadedSame = false,
                isProbablyIncompatible = false,
            )
        } else {
            JarMetadata(
                requestedVersion = discovery.requestedVersion,
                resolvedVersion = discovery.resolvedVersion,
                checksum = discovery.checksum,
                origin = discovery.origin,
                isLocal = discovery.isLocal,
                kotlinVersionMismatch = discovery.kotlinVersionMismatch,
                reloadedSame = true,
                isProbablyIncompatible = old.isProbablyIncompatible,
            )
        }
    }

    private suspend fun processDiscovery(
        discovery: KotlinPluginDiscoveryUpdater.Discovery,
        redrawUpdateUpdate: Boolean,
    ) {
        stackTraceMap.remove(discovery.jarId)

        val plugin = project.service<KotlinPluginsSettings>().pluginByName(discovery.pluginName) ?: return
        if (plugin.ignoreExceptions) {
            return
        }

        when (val result = KotlinPluginsJarAnalyzer.analyze(discovery.jar)) {
            is KotlinPluginsAnalyzedJar.Success -> {
                stackTraceMap[discovery.jarId] = result.fqNames

                if (redrawUpdateUpdate) {
                    statusPublisher.redraw()
                }
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

        if (plugin.ignoreExceptions) {
            return
        }

        val newExceptions = mutableSetOf<JarId>()

        val exceptionId = buildString {
            append(exception::class.java.name)
            append('|')
            append(exception.message ?: "")
            append('|')
            append(exception.distinctStacktrace(stackTraceMap[ids.first()].orEmpty()))
        }

        // store anyway to display in UI
        caughtExceptions.compute(pluginName) { _, old ->
            val caughtExceptions = ids.map {
                CaughtException(it, exceptionId, exception)
            }

            if (old == null) {
                newExceptions.addAll(ids)
            } else {
                caughtExceptions.forEach {
                    if (it !in old) {
                        newExceptions.add(it.jarId)
                    }
                }
            }

            (old.orEmpty() + caughtExceptions).let { new ->
                new.drop((new.size - EXCEPTIONS_CACHE_SIZE).coerceAtLeast(0)).toSet()
            }
        }

        ids.forEach { id ->
            metadata.compute(id) { _, old ->
                old?.copy(isProbablyIncompatible = isProbablyIncompatible)
            }
        }

        newExceptions.forEach { id ->
            statusPublisher.updateVersion(
                pluginName = id.pluginName,
                mavenId = id.mavenId,
                requestedVersion = id.requestedVersion,
                status = ArtifactStatus.ExceptionInRuntime(
                    jarId = id,
                ),
            )
        }

        if (autoDisable) {
            if (settings.disablePlugin(plugin.name)) {
                KotlinPluginsNotificationBallon.notify(
                    project = project,
                    disabledPlugin = plugin.name,
                    mavenId = ids.singleOrNull()?.mavenId,
                    requestedVersion = ids.singleOrNull()?.requestedVersion,
                )
            }
        } else {
            // trigger editor notification across all Kotlin files
            project.service<KotlinPluginsNotifications>().activate(ids)
            refreshNotifications()
        }
    }

    override fun hasExceptions(pluginName: String, mavenId: String, requestedVersion: RequestedVersion): Boolean {
        return caughtExceptions[pluginName].orEmpty()
            .any { it.jarId.mavenId == mavenId && it.jarId.requestedVersion == requestedVersion }
    }

    override fun getExceptionsReport(jarId: JarId): ExceptionsReport? {
        val exceptions = caughtExceptions[jarId.pluginName].orEmpty()
            .filter { it.jarId == jarId }

        return if (exceptions.isEmpty()) {
            null
        } else {
            val metadata = metadata[jarId] ?: return null

            ExceptionsReport(
                pluginName = jarId.pluginName,
                mavenId = jarId.mavenId,
                requestedVersion = metadata.requestedVersion,
                resolvedVersion = metadata.resolvedVersion,
                origin = metadata.origin,
                checksum = metadata.checksum,

                exceptions = exceptions.map { it.exception },
                isLocal = metadata.isLocal,
                reloadedSame = metadata.reloadedSame,
                isProbablyIncompatible = metadata.isProbablyIncompatible,
                kotlinVersionMismatch = metadata.kotlinVersionMismatch,
                __exceptionsIds = exceptions.map { it.exceptionId },
            )
        }
    }

    override suspend fun createReportFile(report: ExceptionsReport): Path? = runCatchingExceptCancellation {
        val storage = project.service<KotlinPluginsStorage>()
        val reportsDir = storage.reportsDir() ?: return@runCatchingExceptCancellation null

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneId.systemDefault())

        val nowFormatted = formatter.format(Instant.now())

        val reportFilename = "${report.pluginName}-${report.mavenId}-${nowFormatted}"
            .replace(":", "-")
            .replace(".", "-")
            .plus(".txt")

        val reportFile = reportsDir.resolve(reportFilename)

        withContext(Dispatchers.IO) {
            if (reportFile.exists()) {
                reportFile.createFile()
            }

            reportFile.writeText(
                """
KEFS Report for ${report.pluginName} (${report.mavenId})

Kotlin IDE version: ${service<KotlinVersionService>().getKotlinIdePluginVersion()}
Kotlin version mismatch: ${report.kotlinVersionMismatch ?: "none"}
Requested version: ${report.requestedVersion}
Resolved version: ${report.resolvedVersion}
Origin repository: ${report.origin.value}
Checksum: ${report.checksum}
Is probably incompatible: ${report.isProbablyIncompatible}

Exceptions:
${report.exceptions.joinToString("$NL$NL") { it.stackTraceToString() }}
            """.trimIndent()
            )
        }

        reportFile
    }.let {
        if (it.isSuccess) {
            it.getOrThrow()
        } else {
            logger.error("Failed to create report file", it.exceptionOrNull())
            null
        }
    }

    private fun Throwable.distinctStacktrace(lookup: Set<String>): String {
        return stackTrace.filter { it.className in lookup }.joinToString("|") { it.toString() } +
                "|" +
                cause?.distinctStacktrace(lookup).orEmpty()
    }

    private inner class DiscoveryHandler : KotlinPluginDiscoveryUpdater {
        override suspend fun discoveredSync(
            discovery: KotlinPluginDiscoveryUpdater.Discovery,
            redrawAfterUpdate: Boolean,
        ) {
            var new = false
            metadata.compute(discovery.jarId) { _, old ->
                computeMetadata(old, discovery) {
                    new = true
                    caughtExceptions.compute(discovery.pluginName) { _, old ->
                        old.orEmpty().filterNot { it.jarId == discovery.jarId }.toSet()
                    }
                }
            }

            if (new) {
                project.service<KotlinPluginsNotifications>().deactivate(discovery.jarId)

                refreshNotifications()

                processDiscovery(discovery, redrawAfterUpdate)
            }
        }
    }

    fun clearAll() {
        state.getAndSet(null)?.cancel()
        stackTraceMap.clear()
        caughtExceptions.clear()
        metadata.clear()

        project.service<KotlinPluginsNotifications>().clear()
        refreshNotifications()
    }

    private fun refreshNotifications() {
        // Ensure notifications refresh happens on EDT using the service coroutine scope
        scope.launch(Dispatchers.EDT) {
            EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    companion object {
        const val EXCEPTIONS_CACHE_SIZE = 20
        private val NL = System.lineSeparator()
    }
}
