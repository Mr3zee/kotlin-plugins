package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
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

        fun cleanPanel() {
            notificationsState.clear()
            EditorNotifications.getInstance(project).updateAllNotifications()
        }

        return Function { editor ->
            val panel = EditorNotificationPanel(editor,EditorNotificationPanel.Status.Error)
            val pluginWord = "plugin".pluralizeIf(pluginNames.size > 1)
            val beWord = if (pluginNames.size == 1) "is" else "are"
            val pluginsText = pluginNames.joinToString(", ") { "'${it}'" }

            panel.text = "Kotlin external compiler $pluginWord $pluginsText $beWord throwing exceptions."
            panel.icon(KotlinPluginsIcons.Logo)

            val disableSuffix = if (pluginNames.size == 1) "" else " all"

            panel.createActionLabel("Disable$disableSuffix") {
                val settings = project.service<KotlinPluginsSettings>()
                settings.disablePlugins(pluginNames)

                cleanPanel()
            }

            panel.createActionLabel("Auto-disable") {
                val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>()
                analyzer.updateState(enabled = true, autoDisable = true)

                val settings = project.service<KotlinPluginsSettings>()
                settings.disablePlugins(pluginNames)

                cleanPanel()
            }

            panel.createActionLabel("Open diagnostics") {
                // todo update state to show error panel

                ToolWindowManager.getInstance(project)
                    .getToolWindow("Kotlin Plugins Diagnostics")
                    ?.show()

                cleanPanel()
            }

            panel.setCloseAction {
                cleanPanel()
            }

            panel
        }
    }
}
