package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * A hack, until a [Project] argument is provided in
 * [org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider.provideBundledPluginJar]
 */
object KotlinPluginsProjectsMap {
    val instances = ConcurrentHashMap<Project, Unit>()

    class StartupActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                // in unit test mode, activities can block project opening and cause timeouts
                return
            }

            instances.computeIfAbsent(project) { }
        }
    }
}
