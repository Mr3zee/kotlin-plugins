package com.github.mr3zee.intellijcompilerpluginswap

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.autoimport.*
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.diagnostic.telemetry.EDT
import com.intellij.platform.runtime.loader.IntellijLoader.launch
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.idea.configuration.GRADLE_SYSTEM_ID
import java.util.concurrent.ConcurrentHashMap

class KotlinPluginsProjectAware(val project: Project) : ExternalSystemProjectAware {
    override val projectId: ExternalSystemProjectId = ExternalSystemProjectId(
        systemId = ProjectSystemId("KOTLIN_PLUGINS", "Kotlin Plugins"),
        externalProjectPath = project.basePath ?: "",
    )

    private val gradleExternalSystemId = ExternalSystemProjectId(
        systemId = GRADLE_SYSTEM_ID,
        externalProjectPath = project.basePath ?: "",
    )

    override fun reloadProject(context: ExternalSystemProjectReloadContext) {
        val provider = KotlinCompilerPluginsProvider.getInstance(project)

        if (provider is Disposable) {
            provider.dispose() // clear Kotlin plugin caches
            service<PluginStorageService>().clearCacheMisses()
        }

        // todo check if can be done in a more efficient manner,
        //  for example runPartialGradleImport(project, root)

        ExternalSystemProjectTracker
            .getInstance(project)
            .markDirty(gradleExternalSystemId)

        @Suppress("UnstableApiUsage")
        ProjectRefreshAction.Manager.refreshProject(project)
    }

    override fun subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable) {}
    override val settingsFiles: Set<String> = emptySet()

    class StartupActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                // in unit test mode, activities can block project opening and cause timeouts
                return
            }

            val projectAware = getInstance(project)
            val projectTracker = ExternalSystemProjectTracker.getInstance(project)
            projectTracker.register(projectAware, object : Disposable.Default {})
            projectTracker.activate(projectAware.projectId)

//            service<PluginStorageService>().actualizePlugins()
        }
    }

    companion object {
        private val instances = ConcurrentHashMap<Project, KotlinPluginsProjectAware>()

        fun getInstance(project: Project): KotlinPluginsProjectAware = instances.computeIfAbsent(project) {
            KotlinPluginsProjectAware(project)
        }
    }
}
