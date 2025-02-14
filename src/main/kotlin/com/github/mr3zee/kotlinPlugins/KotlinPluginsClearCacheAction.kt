package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class KotlinPluginsClearCacheAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<KotlinPluginsStorageService>()?.clearCaches()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
