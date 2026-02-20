package com.github.mr3zee.kefs

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

internal class KefsEditorNotificationProvider : EditorNotificationProvider {
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

        val notificationsState = project.service<KefsNotifications>()

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
            val count = grouped.size
            val pluginsText = grouped.keys.joinToString(", ") { "'${it}'" }

            panel.text = KefsBundle.message(
                "editor.notification.plugins.throwing.choice",
                count,
                pluginsText,
            )
            panel.icon(KefsIcons.Logo)

            val disableLabel = if (grouped.size == 1) {
                KefsBundle.message("editor.notification.disable")
            } else {
                KefsBundle.message("editor.notification.disable.all")
            }

            panel.createActionLabel(disableLabel) {
                val settings = project.service<KefsSettings>()
                settings.disablePlugins(pluginNames.map { it.pluginName })

                cleanPanel()
            }

            panel.createActionLabel(KefsBundle.message("editor.notification.autoDisable")) {
                val analyzer = project.service<KefsExceptionAnalyzerService>()
                analyzer.updateState(enabled = true, autoDisable = true)

                val settings = project.service<KefsSettings>()
                settings.disablePlugins(pluginNames.map { it.pluginName })

                cleanPanel()
            }

            panel.createActionLabel(KefsBundle.message("editor.notification.openDiagnostics")) {
                val pluginOrNull = grouped.keys.singleOrNull()

                val jarIdOrNull = pluginOrNull?.let { grouped[it]?.singleOrNull() }
                val mavenIdOrNull = jarIdOrNull?.mavenId
                val requestedVersionOrNull = jarIdOrNull?.requestedVersion

                KefsTreeState.getInstance(project)
                    .select(project, PartialJarId(pluginOrNull, mavenIdOrNull, requestedVersionOrNull))

                showKefsToolWindow(project)
            }

            panel.setCloseAction {
                cleanPanel()
            }

            panel
        }
    }
}
