package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.collections.component1
import kotlin.collections.component2

class KotlinPluginsExceptionAnalyzerState : BaseState() {
    var enabled by property(true)
    var autoDisable by property(false)
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginsExceptionAnalyzer",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class KotlinPluginsExceptionAnalyzerService(
    private val project: Project,
    private val scope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsExceptionAnalyzerState>(KotlinPluginsExceptionAnalyzerState()),
    Disposable {
    private val logger by lazy { thisLogger() }
    private val handler: AtomicReference<Handler?> = AtomicReference(null)
    val supervisorJob = SupervisorJob(scope.coroutineContext.job)

    override fun dispose() {
        supervisorJob.cancel()
        val handler = handler.getAndSet(null)
        rootLogger().removeHandler(handler)
        handler?.close()
    }

    private fun startHandler() {
        val exceptionHandler = ExceptionHandler()
        if (handler.compareAndSet(null, exceptionHandler)) {
            if (!state.enabled) {
                handler.getAndSet(null)
                exceptionHandler.close()
                return
            }

            project.service<KotlinPluginsExceptionReporter>().start()
            exceptionHandler.start()
            logger.debug("Exception analyzer started")
            rootLogger().addHandler(exceptionHandler)
        }
    }

    private inner class ExceptionHandler : Handler() {
        private val channel = Channel<Throwable>(Channel.UNLIMITED)
        private val job = scope.launch(
            context = supervisorJob + CoroutineName("exception-analyzer"),
            start = CoroutineStart.LAZY,
        ) {
            while (true) {
                val exception = channel.receiveCatching().getOrNull() ?: break

                val reporter = project.service<KotlinPluginsExceptionReporter>()

                val lookup = reporter.lookFor()

                lookup.entries.find { (jarId, fqNames) ->
                    exception.stackTrace.any { it.className in fqNames }
                } ?: continue

                val ids = match(lookup, exception)
                    .takeIf { it.isNotEmpty() }
                    ?: return@launch

                logger.debug("Exception detected for $ids: ${exception.message}")
                reporter.matched(ids.toList(), exception, autoDisable = state.autoDisable)
            }
        }

        override fun publish(record: LogRecord) {
            val exception = record.thrown ?: return
            channel.trySend(exception)
        }

        fun start() {
            job.start()
        }

        override fun flush() {
            // noop
        }

        override fun close() {
            channel.close()
            channel.cancel()
            job.cancel()
        }
    }

    private fun rootLogger() = java.util.logging.Logger.getLogger("")

    fun start() {
        if (!state.enabled) {
            return
        }

        startHandler()
    }

    fun updateState(enabled: Boolean, autoDisable: Boolean) {
        state.autoDisable = autoDisable

        if (state.enabled == enabled) {
            return
        }

        state.enabled = enabled

        if (enabled) {
            startHandler()
        } else {
            val handler = handler.getAndSet(null)
            rootLogger().removeHandler(handler)
            handler?.close()
            project.service<KotlinPluginsExceptionReporter>().stop()
        }
    }

    companion object {
        internal fun match(lookup: Map<JarId, Set<String>>, exception: Throwable): Set<JarId> {
            return exception.stackTrace
                .map { trace ->
                    lookup.entries
                        .filter { (_, fqNames) -> trace.className in fqNames }
                        .map { it.key }.toSet()
                }
                .filter { it.isNotEmpty() }
                .reduce { acc, set -> acc.intersect(set) }
        }
    }
}
