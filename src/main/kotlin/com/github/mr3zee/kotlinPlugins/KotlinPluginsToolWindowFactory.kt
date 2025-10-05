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
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

        val state = TreeState.getInstance(project)
        val splitter = OnePixelSplitter(false, "KotlinPlugins.SplitterProportion", 0.3f, 0.7f).apply {
            firstComponent = createDiagnosticsPanel(project, state)
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

    private fun createDiagnosticsPanel(
        project: Project,
        state: TreeState,
    ): JComponent {
        val panel = JPanel(BorderLayout())

        val tree = KotlinPluginsTree(project, state)
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

        group.addSeparator()

        group.add(object : AnAction("Refresh", "Refresh data", KotlinPluginsIcons.RefreshChanges) {
            override fun actionPerformed(e: AnActionEvent) {
                e.project?.service<KotlinPluginsStorage>()?.runActualization()
            }
        })

        group.addSeparator()

        group.add(object : ToggleAction("Show Successful", "Show items with 'Success' status", AllIcons.RunConfigurations.ShowPassed) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun isSelected(e: AnActionEvent): Boolean = tree.state.showSucceeded
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.state.showSucceeded = state
                tree.reloadModel()
                TreeUtil.expandAll(tree)
            }
        })

        group.add(object : ToggleAction("Show Skipped/Disabled", "Show items with 'Skipped/Disabled' status", AllIcons.RunConfigurations.ShowIgnored) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
            override fun isSelected(e: AnActionEvent): Boolean = tree.state.showSkipped
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.state.showSkipped = state
                tree.reloadModel()
                TreeUtil.expandAll(tree)
            }
        })

        group.addSeparator()

        group.add(object : AnAction("Clear Caches", "Clear caches (not implemented)", AllIcons.Actions.ClearCash) {
            override fun actionPerformed(e: AnActionEvent) {
                val clear = !tree.state.showClearCachesDialog || run {
                    val (clear, dontShowAgain) = ClearCachesDialog.show()
                    tree.state.showClearCachesDialog = !dontShowAgain
                    clear
                }

                if (clear) {
                    e.project?.service<KotlinPluginsStorage>()?.clearCaches()
                }
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

enum class ArtifactStatus {
    SUCCESS,
    IN_PROGRESS,
    FAILED_TO_LOAD,
    DISABLED,
    SKIPPED,
    EXCEPTION_IN_RUNTIME,
    ;
}

private fun pluginStatus(disabled: Boolean, artifacts: List<NodeData>): ArtifactStatus {
    if (disabled) {
        return ArtifactStatus.DISABLED
    }

    if (artifacts.isEmpty()) {
        return ArtifactStatus.SKIPPED
    }

    if (artifacts.all { it.status == ArtifactStatus.SUCCESS }) {
        return ArtifactStatus.SUCCESS
    }

    if (artifacts.any { it.status == ArtifactStatus.IN_PROGRESS }) {
        return ArtifactStatus.IN_PROGRESS
    }

    if (artifacts.any { it.status == ArtifactStatus.FAILED_TO_LOAD }) {
        return ArtifactStatus.FAILED_TO_LOAD
    }

    if (artifacts.any { it.status == ArtifactStatus.EXCEPTION_IN_RUNTIME }) {
        return ArtifactStatus.EXCEPTION_IN_RUNTIME
    }

    return ArtifactStatus.SKIPPED
}

private class NodeData(
    project: Project,
    parent: PresentableNodeDescriptor<*>?,
    val key: String,
    var label: String,
    var status: ArtifactStatus,
    val isPlugin: Boolean,
) : PresentableNodeDescriptor<NodeData>(project, parent) {
    override fun update(presentation: PresentationData) {
        presentation.presentableText = label
        presentation.setIcon(statusToIcon(status))
    }

    override fun getElement(): NodeData = this

    override fun createPresentation(): PresentationData {
        return PresentationData(label, null, statusToIcon(status), null).apply {
            tooltip = statusToTooltip(isPlugin, status)
        }
    }
}

class TreeState : BaseState() {
    var showSucceeded: Boolean by property(true)
    var showSkipped: Boolean by property(true)

    var selectedNodeKey: String? by string(null)

    var showClearCachesDialog: Boolean by property(true)

    companion object {
        fun getInstance(project: Project): TreeState = project.service<KotlinPluginTreeStateService>().state
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginTreeState",
    storages = [Storage(WORKSPACE_FILE)],
)
class KotlinPluginTreeStateService :  SimplePersistentStateComponent<TreeState>(TreeState())

class KotlinPluginsTree(
    private val project: Project,
    val state: TreeState,
) : Tree() {
    private val statusByKey = mutableMapOf<String, ArtifactStatus>()

    private val rootNode = DefaultMutableTreeNode(NodeData(project, null, "root", "Kotlin Plugins", ArtifactStatus.IN_PROGRESS, false))
    private val model = DefaultTreeModel(rootNode)

    init {
        model.setAsksAllowsChildren(false)
        setModel(model)
        isRootVisible = false
        showsRootHandles = true

        // Speed search in the tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

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
                menu.add(createSetStatusItem("Done", ArtifactStatus.SUCCESS, node, data))
                menu.add(createSetStatusItem("In Progress", ArtifactStatus.IN_PROGRESS, node, data))
                menu.add(createSetStatusItem("Failed", ArtifactStatus.FAILED_TO_LOAD, node, data))
                menu.add(createSetStatusItem("Disabled", ArtifactStatus.DISABLED, node, data))
                menu.add(createSetStatusItem("Skipped", ArtifactStatus.SKIPPED, node, data))
                menu.add(createSetStatusItem("Exception", ArtifactStatus.EXCEPTION_IN_RUNTIME, node, data))
                menu.show(this@KotlinPluginsTree, e.x, e.y)
            }

            private fun createSetStatusItem(text: String, status: ArtifactStatus, node: DefaultMutableTreeNode, data: NodeData): JMenuItem {
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
            val pluginStatus = statusByKey[plugin.name] ?: ArtifactStatus.IN_PROGRESS
            val rootData = rootNode.userObject as NodeData
            val pluginNodeData = NodeData(project, rootData, plugin.name, plugin.name, pluginStatus, true)
            val pluginNode = DefaultMutableTreeNode(pluginNodeData)

            // children: maven ids
            for (id in plugin.ids) {
                val artifactLabel = id.id
                val artifactKey = plugin.name + "::" + artifactLabel
                val artifactStatus = statusByKey[artifactKey] ?: ArtifactStatus.IN_PROGRESS
                val artifactNodeData = NodeData(project, pluginNodeData, artifactKey, artifactLabel, artifactStatus, false)
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

    private fun shouldIncludeStatus(status: ArtifactStatus): Boolean {
        return when (status) {
            ArtifactStatus.SUCCESS -> state.showSucceeded
            ArtifactStatus.DISABLED, ArtifactStatus.SKIPPED -> state.showSkipped
            else -> true
        }
    }

    fun expandAll() {
        TreeUtil.expandAll(this)
    }

    fun collapseAll() {
        TreeUtil.collapseAll(this, 0)
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

private fun statusToIcon(status: ArtifactStatus) = when (status) {
    ArtifactStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
    ArtifactStatus.IN_PROGRESS -> AnimatedIcon.Default()
    ArtifactStatus.FAILED_TO_LOAD -> AllIcons.RunConfigurations.TestFailed
    ArtifactStatus.EXCEPTION_IN_RUNTIME -> AllIcons.RunConfigurations.TestError
    ArtifactStatus.DISABLED -> AllIcons.RunConfigurations.TestSkipped
    ArtifactStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
}

private fun statusToTooltip(isPlugin: Boolean, status: ArtifactStatus) = when (isPlugin) {
    true -> when (status) {
        ArtifactStatus.SUCCESS -> "All plugin artifacts are loaded successfully"
        ArtifactStatus.IN_PROGRESS -> "Plugin is loading/refreshing"
        ArtifactStatus.FAILED_TO_LOAD -> "Plugin failed to load at least one artifact"
        ArtifactStatus.EXCEPTION_IN_RUNTIME -> "Plugin threw an exception during runtime"
        ArtifactStatus.DISABLED -> "Plugin is disabled in settings"
        ArtifactStatus.SKIPPED -> "Plugin is not requested in the project yet"
    }

    false -> when (status) {
        ArtifactStatus.SUCCESS -> "Artifact loaded successfully"
        ArtifactStatus.IN_PROGRESS -> "Artifact is loading/refreshing"
        ArtifactStatus.FAILED_TO_LOAD -> "Artifact failed to load (click for more details)"
        ArtifactStatus.EXCEPTION_IN_RUNTIME -> "Artifact threw an exception during runtime (click for more details)"
        ArtifactStatus.DISABLED -> "Artifact is disabled in settings"
        ArtifactStatus.SKIPPED -> "Artifact is not requested in the project yet"
    }
}
