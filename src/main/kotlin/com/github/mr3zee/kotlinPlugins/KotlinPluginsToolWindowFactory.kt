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
import com.intellij.ui.SimpleTextAttributes
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
            tree.reset()
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

        group.add(object : AnAction("Update", "Update plugin data", AllIcons.Vcs.Fetch) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

            override fun actionPerformed(e: AnActionEvent) {
                e.project?.service<KotlinPluginsStorage>()?.runActualization()

                tree.redrawModel()
                tree.expandAll()
            }
        })

        group.add(object : AnAction("Refresh", "Refresh IDE indices", AllIcons.General.Refresh) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

            override fun actionPerformed(e: AnActionEvent) {
                e.project?.service<KotlinPluginsStorage>()?.invalidateKotlinPluginCache()
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
                tree.redrawModel()
                tree.expandAll()
            }
        })

        group.add(object : ToggleAction("Show Skipped/Disabled", "Show items with 'Skipped/Disabled' status", AllIcons.RunConfigurations.ShowIgnored) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
            override fun isSelected(e: AnActionEvent): Boolean = tree.state.showSkipped
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.state.showSkipped = state
                tree.redrawModel()
                tree.expandAll()
            }
        })

        group.addSeparator()

        group.add(object : AnAction("Clear Caches", "Clear caches (not implemented)", AllIcons.Actions.ClearCash) {
            override fun actionPerformed(e: AnActionEvent) {
                val clear = run {
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

private class NodeData(
    project: Project,
    val parent: NodeData?,
    val key: String,
    var label: String,
    var status: ArtifactStatus,
    val type: NodeType,
) : PresentableNodeDescriptor<NodeData>(project, parent) {
    override fun update(presentation: PresentationData) {
        presentation.presentableText = label
        presentation.setIcon(statusToIcon(status))
        presentation.updatePresentation()
    }

    override fun getElement(): NodeData = this

    override fun createPresentation(): PresentationData {
        return PresentationData(label, null, statusToIcon(status), null).apply {
            updatePresentation()
        }
    }

    private fun PresentationData.updatePresentation() {
        clearText()

        tooltip = statusToTooltip(type, status)

        if (type == NodeType.Version) {
            when (val status = status) {
                is ArtifactStatus.Success -> {
                    if (status.actualVersion == status.requestedVersion) {
                        addText(status.actualVersion, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        addText(" Exact", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        addText(status.actualVersion, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        addText(" (${status.criteria}, requested: ${status.requestedVersion})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }

                is ArtifactStatus.FailedToLoad -> {
                    addText(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    addText(status.shortMessage, SimpleTextAttributes.ERROR_ATTRIBUTES)
                }

                // fallback to presentable text
                else -> {}
            }
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
    private val rootNode = DefaultMutableTreeNode(
        NodeData(
            project = project,
            parent = null,
            key = "root",
            label = "Kotlin Plugins",
            status = ArtifactStatus.InProgress,
            type = NodeType.Plugin,
        )
    )

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

        redrawModel()

        connection.subscribe(
            KotlinPluginStatusChangeListener.TOPIC,
            EventListener(),
        )

        storage.requestStatuses()
    }

    fun reset() {
        nodesByKey.clear()
        redrawModel()
    }

    fun redrawModel() {
        state.selectedNodeKey = null

        rootNode.removeAllChildren()

        val plugins = settings.safeState().plugins

        for (plugin in plugins) {
            val defaultStatus = if (plugin.enabled) ArtifactStatus.Skipped else ArtifactStatus.Disabled
            val rootData = rootNode.userObject as NodeData

            val pluginKey = nodeKey(plugin.name)
            val pluginNodeData = nodesByKey[pluginKey]?.data ?: NodeData(
                project = project,
                parent = rootData,
                key = pluginKey,
                label = plugin.name,
                status = defaultStatus,
                type = NodeType.Plugin,
            )

            val pluginNode = DefaultMutableTreeNode(pluginNodeData)

            // children: maven ids
            for (id in plugin.ids) {
                val artifactKey = nodeKey(plugin.name, id.id)
                val artifactNodeData = nodesByKey[artifactKey]?.data ?: NodeData(
                    project = project,
                    parent = pluginNodeData,
                    key = artifactKey,
                    label = id.id,
                    status = defaultStatus,
                    type = NodeType.Artifact,
                )

                if (shouldIncludeStatus(artifactNodeData.status)) {
                    val artifactNode = DefaultMutableTreeNode(artifactNodeData)
                    pluginNode.add(artifactNode)
                    nodesByKey[artifactKey] = artifactNode

                    nodesByKey.entries.filter { (k, _) ->
                        k.startsWith(artifactKey) && k != artifactKey
                    }.forEach { (k, v) ->
                        val versionNode = DefaultMutableTreeNode(v.data)
                        artifactNode.add(versionNode)
                        nodesByKey[k] = versionNode
                    }
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
        rootData.status = ArtifactStatus.Success(
            requestedVersion = "",
            actualVersion = "",
            criteria = KotlinPluginDescriptor.VersionMatching.EXACT,
        )

        model.reload()
        repaint()
    }

    private fun shouldIncludeStatus(status: ArtifactStatus): Boolean {
        return when (status) {
            is ArtifactStatus.Success -> state.showSucceeded
            ArtifactStatus.Disabled, ArtifactStatus.Skipped -> state.showSkipped
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
        override fun updatePlugin(pluginName: String, status: ArtifactStatus) {
            this@KotlinPluginsTree.updatePlugin(pluginName, status)
        }

        override fun updateArtifact(
            pluginName: String,
            mavenId: String,
            status: ArtifactStatus,
        ) {
            this@KotlinPluginsTree.updateArtifact(pluginName, mavenId, status)
        }

        override fun updateVersion(
            pluginName: String,
            mavenId: String,
            version: String,
            status: ArtifactStatus,
        ) {
            this@KotlinPluginsTree.updateVersion(pluginName, mavenId, version, status)
        }

        override fun reset() {
            this@KotlinPluginsTree.reset()

            expandAll()
        }
    }

    private fun updatePlugin(pluginName: String, status: ArtifactStatus) {
        val key = nodeKey(pluginName)

        val pluginNode = nodesByKey[key]
        if (pluginNode != null) {
            if (!update(pluginNode, status)) {
                return
            }

            updateChildren(pluginNode)
        }
    }

    private fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus) {
        val key = nodeKey(pluginName, mavenId)

        val artifactNode = nodesByKey[key]
        if (artifactNode != null) {
            if (!update(artifactNode, status)) {
                return
            }

            updateChildren(artifactNode)
            updateParents(artifactNode)
        }
    }

    private fun updateVersion(pluginName: String, mavenId: String, version: String, status: ArtifactStatus) {
        val key = nodeKey(pluginName, mavenId, version)
        var new = false

        val versionNode = nodesByKey[key] ?: run {
            new = true
            DefaultMutableTreeNode(
                NodeData(
                    project = project,
                    parent = nodesByKey[nodeKey(pluginName, mavenId)]?.data,
                    key = key,
                    label = version,
                    status = status,
                    type = NodeType.Version,
                )
            ).also {
                nodesByKey[key] = it
            }
        }

        if (!update(versionNode, status) && !new) {
            return
        }

        updateParents(versionNode)

        if (new) {
            redrawModel()
        }
    }

    private fun updateChildren(node: DefaultMutableTreeNode) {
        node.nodeChildren().forEach { child ->
            update(child, node.data.status)

            updateChildren(child)
        }
    }

    private fun updateParents(node: DefaultMutableTreeNode) {
        val parentData = node.data.parent
        if (parentData == null || parentData.key == rootNode.data.key) {
            return
        }

        val parentNode = nodesByKey[parentData.key] ?: return
        val children = parentNode.nodeChildren().map { it.data }

        val newParentStatus = pluginStatus(children)

        update(parentNode, newParentStatus)

        updateParents(parentNode)
    }

    private fun DefaultMutableTreeNode.nodeChildren(): List<DefaultMutableTreeNode> {
        return children().toList().filterIsInstance<DefaultMutableTreeNode>()
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

fun nodeKey(pluginName: String, mavenId: String? = null, version: String? = null): String {
    if (mavenId == null && version != null) {
        error("MavenId is null, but version is not null, $version, $pluginName")
    }

    return when {
        version != null -> {
            "$pluginName::$mavenId::$version"
        }
        mavenId != null -> {
            "$pluginName::$mavenId"
        }
        else -> {
            pluginName
        }
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
    is ArtifactStatus.Success -> AllIcons.RunConfigurations.TestPassed
    ArtifactStatus.InProgress -> AnimatedIcon.Default()
    is ArtifactStatus.FailedToLoad -> AllIcons.RunConfigurations.TestFailed
    ArtifactStatus.ExceptionInRuntime -> AllIcons.RunConfigurations.TestError
    ArtifactStatus.Disabled -> AllIcons.RunConfigurations.TestSkipped
    ArtifactStatus.Skipped -> AllIcons.RunConfigurations.TestIgnored
}

enum class NodeType {
    Plugin, Artifact, Version;
}

private fun statusToTooltip(type: NodeType, status: ArtifactStatus) = when (type) {
    NodeType.Plugin -> when (status) {
        is ArtifactStatus.Success -> "All plugin artifacts are loaded successfully"
        ArtifactStatus.InProgress -> "Plugin is loading/refreshing"
        is ArtifactStatus.FailedToLoad -> "Plugin failed to load at least one artifact"
        ArtifactStatus.ExceptionInRuntime -> "Plugin threw an exception during runtime"
        ArtifactStatus.Disabled -> "Plugin is disabled in settings"
        ArtifactStatus.Skipped -> "Plugin is not requested in the project yet"
    }

    NodeType.Artifact -> when (status) {
        is ArtifactStatus.Success -> "Artifact loaded successfully"
        ArtifactStatus.InProgress -> "Artifact is loading/refreshing"
        is ArtifactStatus.FailedToLoad -> "Artifact failed to load"
        ArtifactStatus.ExceptionInRuntime -> "Artifact threw an exception during runtime"
        ArtifactStatus.Disabled -> "Artifact is disabled in settings"
        ArtifactStatus.Skipped -> "Artifact is not requested in the project yet"
    }

    NodeType.Version -> when (status) {
        is ArtifactStatus.Success -> {
            if (status.actualVersion == status.requestedVersion) {
                "Requested version loaded successfully"
            } else {
                "<html>Version loaded successfully. <br/>" +
                        "Requested ${status.requestedVersion}, " +
                        "actual is ${status.actualVersion}, " +
                        "criteria: ${status.criteria}"
            }
        }
        ArtifactStatus.InProgress -> "Version is loading/refreshing"
        is ArtifactStatus.FailedToLoad -> "Version failed to load: ${status.shortMessage} (click for more details)"
        ArtifactStatus.ExceptionInRuntime -> "Version threw an exception during runtime (click for more details)"
        ArtifactStatus.Disabled -> "Version is disabled in settings"
        ArtifactStatus.Skipped -> "Version is not requested in the project yet"
    }
}

private fun pluginStatus(artifacts: List<NodeData>): ArtifactStatus {
    if (artifacts.isEmpty()) {
        return ArtifactStatus.Skipped
    }

    if (artifacts.all { it.status is ArtifactStatus.Success }) {
        return ArtifactStatus.Success("", "", KotlinPluginDescriptor.VersionMatching.EXACT)
    }

    if (artifacts.any { it.status == ArtifactStatus.InProgress }) {
        return ArtifactStatus.InProgress
    }

    if (artifacts.any { it.status is ArtifactStatus.FailedToLoad }) {
        return ArtifactStatus.FailedToLoad("")
    }

    if (artifacts.any { it.status == ArtifactStatus.ExceptionInRuntime }) {
        return ArtifactStatus.ExceptionInRuntime
    }

    return ArtifactStatus.Skipped
}
