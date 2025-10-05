package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.Disposable
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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentManager
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
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
        val contentManager = toolWindow.contentManager

        val state = TreeState.getInstance(project)
        val splitter = OnePixelSplitter(
            false,
            PROPORTION_KEY,
            0.3f,
            0.7f
        ).apply {
            firstComponent = createDiagnosticsPanel(project, contentManager, state)
            secondComponent = createPanel("Log tab is empty")
        }

        toolWindowPanel.setContent(splitter)

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
        contentManager: ContentManager,
        state: TreeState,
    ): JComponent {
        val panel = JPanel(BorderLayout())
        val settings = project.service<KotlinPluginsSettings>()

        val tree = KotlinPluginsTree(project, state, settings)

        settings.addOnUpdateHook(TREE_HOOK_KEY) {
            tree.reloadModel()
        }

        Disposer.register(contentManager) {
            tree.dispose()
            settings.removeOnUpdateHook(TREE_HOOK_KEY)
        }

        val scrollPane = ScrollPaneFactory
            .createScrollPane(
                tree,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            )

        val toolbar = createDiagnosticsToolbar(tree)
        panel.add(toolbar.component, BorderLayout.WEST)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    @Suppress("DuplicatedCode")
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

        group.add(object : AnAction("Refresh", "Refresh data", AllIcons.General.Refresh) {
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

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("KotlinPluginsDiagnosticsToolbar", group, false)

        toolbar.component.border = JBUI.Borders.empty(4)
        toolbar.targetComponent = tree
        return toolbar
    }

    companion object {
        const val DISPLAY_NAME = "Kotlin Plugins Diagnostics"
        const val TREE_HOOK_KEY = "KotlinPluginsTree"
        const val PROPORTION_KEY = "KotlinPlugins.Proportion"
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

private class NodeData(
    project: Project,
    val parent: NodeData?,
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
    private val settings: KotlinPluginsSettings,
) : Tree(), Disposable {
    private val rootNode = DefaultMutableTreeNode(NodeData(project, null, "root", "Kotlin Plugins", ArtifactStatus.IN_PROGRESS, false))
    private val model = DefaultTreeModel(rootNode)

    private val nodesByKey: MutableMap<String, DefaultMutableTreeNode> = mutableMapOf()
    private val connection = project.messageBus.connect()

    private val storage: KotlinPluginsStorage = project.service()

    override fun dispose() {
        connection.dispose()
        nodesByKey.clear()
        model.setRoot(null)
    }

    init {
        model.setAsksAllowsChildren(false)
        setModel(model)
        isRootVisible = false
        showsRootHandles = true

        // Speed search in the tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

        reloadModel()

        connection.subscribe(
            KotlinPluginStatusChangeListener.TOPIC,
            EventListener(),
        )
    }

    fun reloadModel() {
        state.selectedNodeKey = null

        rootNode.removeAllChildren()
        nodesByKey.clear()

        val plugins = settings.safeState().plugins

        for (plugin in plugins) {
            val defaultStatus = if (plugin.enabled) ArtifactStatus.SKIPPED else ArtifactStatus.DISABLED
            val rootData = rootNode.userObject as NodeData

            val pluginKey = nodeKey(plugin.name)
            val pluginNodeData = NodeData(project, rootData, pluginKey, plugin.name, defaultStatus, true)
            val pluginNode = DefaultMutableTreeNode(pluginNodeData)

            // children: maven ids
            for (id in plugin.ids) {
                val artifactKey = nodeKey(plugin.name, id.id)
                val artifactNodeData = NodeData(project, pluginNodeData, artifactKey, id.id, defaultStatus, false)

                if (shouldIncludeStatus(artifactNodeData.status)) {
                    val artifactNode = DefaultMutableTreeNode(artifactNodeData)
                    pluginNode.add(artifactNode)
                    nodesByKey[artifactKey] = artifactNode
                }
            }

            // include plugin if it passes filter (by its own status) or has any children
            if (shouldIncludeStatus(pluginNodeData.status) || pluginNode.childCount > 0) {
                rootNode.add(pluginNode)
                nodesByKey[pluginKey] = pluginNode
            }
        }

        // root status handled separately
        val rootData = rootNode.userObject as NodeData
        rootData.status = ArtifactStatus.SUCCESS

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

    private val DefaultMutableTreeNode.data get(): NodeData = userObject as NodeData

    inner class EventListener : KotlinPluginStatusChangeListener {
        override fun actualizerRequested(pluginName: String) {
            updatePlugin(pluginName, ArtifactStatus.IN_PROGRESS)
        }

        override fun actualizerFailed(pluginName: String) {
            updatePlugin(pluginName, ArtifactStatus.FAILED_TO_LOAD)
        }

        override fun notFound(pluginName: String, mavenId: MavenId) {
            updateArtifact(pluginName, mavenId.id, ArtifactStatus.FAILED_TO_LOAD)
        }

        override fun found(pluginName: String, mavenId: MavenId) {
            updateArtifact(pluginName, mavenId.id, ArtifactStatus.SUCCESS)
        }

        override fun reset() {
            reloadModel()
            TreeUtil.expandAll(this@KotlinPluginsTree)
        }
    }

    private fun updatePlugin(pluginName: String, status: ArtifactStatus) {
        val key = nodeKey(pluginName)
        nodesByKey[key]?.let { parentNode ->
            if (!update(parentNode, status)) {
                return
            }

            val plugin = settings.pluginByName(parentNode.data.key) ?: return
            val children = plugin.ids.mapNotNull { nodesByKey[nodeKey(plugin.name, it.id)] }
            children.forEach { childNode ->
                update(childNode, status)
            }
        }
    }

    private fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus) {
        val key = nodeKey(pluginName, mavenId)

        nodesByKey[key]?.let { artifactNode ->
            if (!update(artifactNode, status)) {
                return
            }

            val parentData = artifactNode.data.parent
            if (parentData == null || parentData.key == "root" || !parentData.isPlugin) {
                return
            }

            val plugin = settings.pluginByName(parentData.key) ?: return
            val parentNode = nodesByKey[parentData.key] ?: return
            val children = plugin.ids.mapNotNull { nodesByKey[nodeKey(plugin.name, it.id)]?.data }

            val newParentStatus = pluginStatus(!plugin.enabled, children)

            update(parentNode, newParentStatus)
        }
    }

    private fun update(node: DefaultMutableTreeNode, status: ArtifactStatus): Boolean {
        val data = node.data

        if (data.status == status) {
            return false
        }

        data.status = status
        model.nodeChanged(node)
        data.update()

        return true
    }
}

fun nodeKey(pluginName: String, mavenId: String? = null): String {
    return if (mavenId != null) {
        "$pluginName::$mavenId"
    } else {
        pluginName
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
