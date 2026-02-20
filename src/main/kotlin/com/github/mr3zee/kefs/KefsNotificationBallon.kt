package com.github.mr3zee.kefs

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

internal object KefsNotificationBallon {
    fun notify(
        project: Project,
        disabledPlugin: String,
        mavenId: String?,
        requestedVersion: RequestedVersion?,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("kotlin.external.fir.support")
            .createNotification(
                type = NotificationType.WARNING,
                content = KefsBundle.message(
                    "notification.plugin.autoDisabled",
                    disabledPlugin,
                ),
            )
            .addAction(object : com.intellij.notification.NotificationAction(
                KefsBundle.message("editor.notification.openDiagnostics")
            ) {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: Notification,
                ) {
                    e.project?.let {
                        KefsTreeState
                            .getInstance(it)
                            .select(project, PartialJarId(disabledPlugin, mavenId, requestedVersion))

                        showKefsToolWindow(it)
                    }
                    notification.expire()
                }
            })
            .notify(project)
    }
}
