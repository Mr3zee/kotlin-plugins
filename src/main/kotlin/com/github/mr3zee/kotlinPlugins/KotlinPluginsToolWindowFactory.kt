package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel

class KotlinPluginsToolWindowFactory : com.intellij.openapi.wm.ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.title = DISPLAY_NAME
        toolWindow.stripeTitle = DISPLAY_NAME
        toolWindow.isShowStripeButton = true

        val toolWindowPanel = SimpleToolWindowPanel(false, true)

        val splitter = OnePixelSplitter(false, "KotlinPlugins.SplitterProportion", 0.3f, 0.7f).apply {
            firstComponent = createPanel("Diagnostics tab is empty")
            secondComponent = createPanel("Log tab is empty")

            splitterProportionKey = "KotlinPlugins.SplitterProportion"
        }

        toolWindowPanel.setContent(splitter)

        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(toolWindowPanel, "", false)
        contentManager.addContent(content)
    }

    private fun createPanel(content: String): JPanel {
        return JPanel().apply {
            layout = BorderLayout()
            add(JBLabel(content), BorderLayout.CENTER)
        }
    }

    companion object {
        const val DISPLAY_NAME = "Kotlin Plugins Diagnostics"
    }
}
