package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class KotlinPluginsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.title = DISPLAY_NAME
        toolWindow.stripeTitle = DISPLAY_NAME
        toolWindow.isShowStripeButton = true

        val toolWindowPanel = SimpleToolWindowPanel(false, true)

        val splitter = OnePixelSplitter(false, "KotlinPlugins.SplitterProportion", 0.3f, 0.7f).apply {
            firstComponent = createDiagnosticsPanel(project)
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

    private fun createDiagnosticsPanel(project: Project): JComponent {
        val panel = JPanel(BorderLayout())

        val tree = KotlinPluginsTree(project)
        val scrollPane = ScrollPaneFactory.createScrollPane(tree, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)

        val toolbar = createDiagnosticsToolbar(tree)
        panel.add(toolbar.component, BorderLayout.WEST)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun createDiagnosticsToolbar(tree: KotlinPluginsTree): ActionToolbar {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Expand All", "Expand all items", AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) {
                tree.expandAll()
            }
        })

        group.add(object : AnAction("Collapse All", "Collapse all items", AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) {
                tree.collapseAll()
            }
        })

        group.add(object : AnAction("Refresh", "Refresh data", KotlinPluginsIcons.RefreshChanges) {
            override fun actionPerformed(e: AnActionEvent) {
                // no-op as per requirements
            }
        })

        group.addSeparator()

        group.add(object : ToggleAction("Show Done", "Show items with Done status", AllIcons.RunConfigurations.TestPassed) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun isSelected(e: AnActionEvent): Boolean = tree.showDone
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.showDone = state
                tree.reloadModel()
                TreeUtil.expandAll(tree)
            }
        })

        group.add(object : ToggleAction("Show Skipped", "Show items with Skipped status", AllIcons.RunConfigurations.TestIgnored) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
            override fun isSelected(e: AnActionEvent): Boolean = tree.showSkipped
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.showSkipped = state
                tree.reloadModel()
                TreeUtil.expandAll(tree)
            }
        })

        group.addSeparator()

        group.add(object : AnAction("Clear Caches", "Clear caches (not implemented)", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                // no-op as per requirements
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("KotlinPluginsDiagnosticsToolbar", group, false)
        toolbar.component.border = JBUI.Borders.empty(4)
        toolbar.targetComponent = tree
        return toolbar
    }

    companion object {
        const val DISPLAY_NAME = "Kotlin Plugins Diagnostics"
    }
}

private enum class NodeStatus {
    DONE, IN_PROGRESS, FAILED, SKIPPED;
}

private class NodeData(
    project: Project,
    parent: PresentableNodeDescriptor<*>?,
    val key: String,
    var label: String,
    var status: NodeStatus,
) : PresentableNodeDescriptor<NodeData>(project, parent) {
    override fun update(presentation: PresentationData) {
        presentation.presentableText = label
        presentation.setIcon(statusToIcon(status))
    }

    override fun getElement(): NodeData = this

    override fun createPresentation(): PresentationData {
        return PresentationData(label, null, statusToIcon(status), null)
    }
}

class KotlinPluginsTree(
    private val project: Project,
) : Tree() {

    // filter toggles
    var showDone: Boolean = true
    var showSkipped: Boolean = true

    private val statusByKey = mutableMapOf<String, NodeStatus>()

    private val rootNode = DefaultMutableTreeNode(NodeData(project, null, "root", "Kotlin Plugins", NodeStatus.IN_PROGRESS))
    private val model = DefaultTreeModel(rootNode)

    init {
        model.setAsksAllowsChildren(false)
        setModel(model)
        isRootVisible = false
        showsRootHandles = true

        // Speed search in the tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

//        putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

        // context menu for setting status
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? NodeData ?: return
                val menu = JPopupMenu()
                menu.add(createSetStatusItem("Done", NodeStatus.DONE, node, data))
                menu.add(createSetStatusItem("In Progress", NodeStatus.IN_PROGRESS, node, data))
                menu.add(createSetStatusItem("Failed", NodeStatus.FAILED, node, data))
                menu.add(createSetStatusItem("Skipped", NodeStatus.SKIPPED, node, data))
                menu.show(this@KotlinPluginsTree, e.x, e.y)
            }

            private fun createSetStatusItem(text: String, status: NodeStatus, node: DefaultMutableTreeNode, data: NodeData): JMenuItem {
                val item = JMenuItem(text, statusToIcon(status))
                item.addActionListener {
                    data.status = status
                    statusByKey[data.key] = status
                    model.nodeChanged(node)
                    data.update()
                }
                return item
            }
        })

        reloadModel()
    }

    fun reloadModel() {
        rootNode.removeAllChildren()

        val settings = project.service<KotlinPluginsSettings>()
        val plugins = settings.safeState().plugins

        for (plugin in plugins) {
            val pluginStatus = statusByKey[plugin.name] ?: NodeStatus.IN_PROGRESS
            val rootData = rootNode.userObject as NodeData
            val pluginNodeData = NodeData(project, rootData, plugin.name, plugin.name, pluginStatus)
            val pluginNode = DefaultMutableTreeNode(pluginNodeData)

            // children: maven ids
            for (id in plugin.ids) {
                val artifactLabel = id.id
                val artifactKey = plugin.name + "::" + artifactLabel
                val artifactStatus = statusByKey[artifactKey] ?: NodeStatus.IN_PROGRESS
                val artifactNodeData = NodeData(project, pluginNodeData, artifactKey, artifactLabel, artifactStatus)
                val artifactNode = DefaultMutableTreeNode(artifactNodeData)

                if (shouldIncludeStatus(artifactStatus)) {
                    pluginNode.add(artifactNode)
                }
            }

            // include plugin if it passes filter (by its own status) or has any children
            if (shouldIncludeStatus(pluginStatus) || pluginNode.childCount > 0) {
                rootNode.add(pluginNode)
            }
        }

        // root status handled separately
        val rootData = rootNode.userObject as NodeData
        val rootStatus = statusByKey[rootData.key] ?: rootData.status
        rootData.status = rootStatus
        statusByKey[rootData.key] = rootData.status

        model.reload()
        repaint()
    }

    private fun shouldIncludeStatus(status: NodeStatus): Boolean {
        return when (status) {
            NodeStatus.DONE -> showDone
            NodeStatus.SKIPPED -> showSkipped
            NodeStatus.IN_PROGRESS, NodeStatus.FAILED -> true
        }
    }

    fun expandAll() {
        TreeUtil.expandAll(this)
    }

    fun collapseAll() {
        TreeUtil.collapseAll(this, 0)
    }
}

private fun statusToIcon(status: NodeStatus) = when (status) {
    NodeStatus.DONE -> AllIcons.RunConfigurations.TestPassed
    NodeStatus.IN_PROGRESS -> AllIcons.Actions.Execute
    NodeStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
    NodeStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
}
