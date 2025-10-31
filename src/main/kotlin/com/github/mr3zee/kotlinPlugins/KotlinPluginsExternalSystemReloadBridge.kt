package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bridges External System (Gradle/Maven) finished reloads to our Kotlin plugins actualization.
 *
 * We listen only for finished external system tasks and trigger re-actualization once (debounced),
 * deferring execution until indices are ready (Smart mode).
 */
@Service(Service.Level.PROJECT)
class KotlinPluginsExternalSystemReloadBridgeService(
    private val project: Project,
    parentScope: CoroutineScope,
) : Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val logger by lazy { thisLogger() }

    private var debounceJob: Job? = null
    private val notifications = Channel<Unit>(Channel.UNLIMITED)

    init {
        debounceJob = scope.launch(CoroutineName("external-system-reload")) {
            var scheduledRun = false
            @OptIn(FlowPreview::class)
            notifications.consumeAsFlow().debounce(50.milliseconds).collect {
                if (project.isDisposed || scheduledRun) {
                    return@collect
                }

                scheduledRun = true
                // Defer until indices are ready to avoid extra work during dumb mode
                DumbService.getInstance(project).runWhenSmart {
                    if (project.isDisposed) {
                        return@runWhenSmart
                    }

                    logger.debug("Project model changed, resetting storage state")
                    project.service<KotlinPluginsStorage>().clearState()
                    scheduledRun = false
                }
            }
        }
    }

    private val listener: ExternalSystemTaskNotificationListener = object : ExternalSystemTaskNotificationListener {
        override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
            notifications.trySend(Unit)
        }
    }

    init {
        // Subscribe to external system notifications for this project
        ExternalSystemProgressNotificationManager.getInstance()
            .addNotificationListener(listener, this)
    }

    override fun dispose() {
        notifications.close()
        notifications.cancel()
        debounceJob?.cancel()
        ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(listener)
    }
}

class ExternalSystemReloadBridgeActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Instantiate the service to set up listeners
        project.service<KotlinPluginsExternalSystemReloadBridgeService>()
    }
}
