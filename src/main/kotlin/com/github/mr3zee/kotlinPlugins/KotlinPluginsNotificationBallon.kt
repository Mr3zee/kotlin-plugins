package com.github.mr3zee.kotlinPlugins

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

object KotlinPluginsNotificationBallon {
    fun notify(
        project: Project,
        disabledPlugin: String,
        mavenId: String?,
        version: String?,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kotlin External FIR Support")
            .createNotification(
                type = NotificationType.WARNING,
                content = """
                    Kotlin compiler plugin '${disabledPlugin}' has encountered an error and has been auto-disabled.
                """.trimIndent(),
            )
            .addAction(object : com.intellij.notification.NotificationAction("Open diagnostics") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: Notification,
                ) {
                    e.project?.let {
                        KotlinPluginsTreeState
                            .getInstance(it)
                            .select(disabledPlugin, mavenId, version)

                        KotlinPluginsToolWindowFactory.show(it)
                    }
                    notification.expire()
                }
            })
            .notify(project)
    }
}
