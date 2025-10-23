package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

open class KotlinPluginsClearCachesAction : AnAction(AllIcons.Actions.ClearCash) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val treeState = KotlinPluginsTreeState.getInstance(project)

        val clear = !treeState.showClearCachesDialog || run {
            val (clear, dontShowAgain) = ClearCachesDialog.show()
            treeState.showClearCachesDialog = !dontShowAgain
            clear
        }

        if (clear) {
            e.project?.service<KotlinPluginsStorage>()?.clearCaches()
        }
    }
}

open class KotlinPluginsRefreshAction : AnAction(AllIcons.General.Refresh) {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<KotlinPluginsStorage>()?.clearState()
    }
}

open class KotlinPluginsUpdateAction : AnAction(AllIcons.Vcs.Fetch) {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<KotlinPluginsStorage>()?.runActualization()
    }
}

private class ClearCachesDialog : DialogWrapper(true) {
    private var dontAskAgain: Boolean = false

    init {
        init()
        title = "Clear Caches"
    }

    override fun isResizable(): Boolean {
        return false
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("Are you sure you want to clear plugin caches?")
        }

        row {
            checkBox("Don't ask again").applyToComponent {
                addItemListener { dontAskAgain = isSelected }
            }
        }
    }

    companion object {
        fun show(): Pair<Boolean, Boolean> {
            val dialog = ClearCachesDialog()
            return dialog.showAndGet() to dialog.dontAskAgain
        }
    }
}
