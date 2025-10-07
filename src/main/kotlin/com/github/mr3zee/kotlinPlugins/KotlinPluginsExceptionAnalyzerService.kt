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

class KotlinPluginsExceptionAnalyzerState : BaseState() {
    var enabled by property(false)
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginsExceptionAnalyzer",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class KotlinPluginsExceptionAnalyzerService(
    private val project: Project,
    private val scope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsExceptionAnalyzerState>(KotlinPluginsExceptionAnalyzerState()), Disposable {
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

            exceptionHandler.start()
            logger.debug("Exception analyzer started")
            rootLogger().addHandler(exceptionHandler)
        }
    }

    private inner class ExceptionHandler : Handler() {
        private val channel = Channel<Throwable>(Channel.UNLIMITED)
        private val job = scope.launch(
            context = CoroutineName("exception-analyzer") + supervisorJob,
            start = CoroutineStart.LAZY,
        ) {
            while (true) {
                val exception = channel.receiveCatching().getOrNull() ?: break

                val reporter = project.service<KotlinPluginsExceptionReporter>()

                reporter.lookFor().forEach { (mavenId, fqNames) ->
                    val indexOfLast = exception.stackTrace.indexOfLast { trace ->
                        fqNames.any { fq -> trace.className == fq }
                    }

                    if (indexOfLast != -1) {
                        logger.debug("Exception detected for $mavenId: ${exception.message}")
                        reporter.matched(mavenId, exception)
                    }
                }
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

    fun updateState(enabled: Boolean) {
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
        }
    }
}
