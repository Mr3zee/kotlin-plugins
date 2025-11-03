package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import fleet.util.text.pluralizeIf
import java.util.function.Function
import javax.swing.JComponent

class KotlinPluginsEditorNotificationProvider : EditorNotificationProvider {
    override fun isDumbAware(): Boolean = true

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> {
        // Show a notification on all Kotlin files when an offending plugin was detected
        val ext = file.extension?.lowercase()

        val isKotlin = ext == "kt"

        if (!isKotlin) {
            return Function { null }
        }

        val notificationsState = project.service<KotlinPluginsNotifications>()

        val pluginNames = notificationsState.currentPlugins()

        if (pluginNames.isEmpty()) {
            return Function { null }
        }

        val grouped = pluginNames.groupBy { it.pluginName }

        fun cleanPanel() {
            notificationsState.clear()
            EditorNotifications.getInstance(project).updateAllNotifications()
        }

        return Function { editor ->
            val panel = EditorNotificationPanel(editor, EditorNotificationPanel.Status.Error)
            val pluginWord = "plugin".pluralizeIf(grouped.size > 1)
            val beWord = if (grouped.size == 1) "is" else "are"
            val pluginsText = grouped.keys.joinToString(", ") { "'${it}'" }

            panel.text = KotlinPluginsBundle.message(
                "editor.notification.plugins.throwing",
                pluginWord,
                pluginsText,
                beWord,
            )
            panel.icon(KotlinPluginsIcons.Logo)

            val disableLabel = if (grouped.size == 1) {
                KotlinPluginsBundle.message("editor.notification.disable")
            } else {
                KotlinPluginsBundle.message("editor.notification.disable.all")
            }

            panel.createActionLabel(disableLabel) {
                val settings = project.service<KotlinPluginsSettings>()
                settings.disablePlugins(pluginNames.map { it.pluginName })

                cleanPanel()
            }

            panel.createActionLabel(KotlinPluginsBundle.message("editor.notification.autoDisable")) {
                val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>()
                analyzer.updateState(enabled = true, autoDisable = true)

                val settings = project.service<KotlinPluginsSettings>()
                settings.disablePlugins(pluginNames.map { it.pluginName })

                cleanPanel()
            }

            panel.createActionLabel(KotlinPluginsBundle.message("editor.notification.openDiagnostics")) {
                val pluginOrNull = grouped.keys.singleOrNull()

                val jarIdOrNull = pluginOrNull?.let { grouped[it]?.singleOrNull() }
                val mavenIdOrNull = jarIdOrNull?.mavenId
                val versionOrNull = jarIdOrNull?.version

                KotlinPluginsTreeState.getInstance(project)
                    .select(pluginOrNull, mavenIdOrNull, versionOrNull)

                showKotlinPluginsToolWindow(project)
            }

            panel.setCloseAction {
                cleanPanel()
            }

            panel
        }
    }
}
