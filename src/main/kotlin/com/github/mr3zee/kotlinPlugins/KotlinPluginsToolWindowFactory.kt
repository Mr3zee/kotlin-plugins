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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.coroutines.cancellation.CancellationException

class KotlinPluginsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.title = DISPLAY_NAME
        toolWindow.stripeTitle = DISPLAY_NAME
        toolWindow.isShowStripeButton = true

        val toolWindowPanel = SimpleToolWindowPanel(false, true)

        val state = TreeState.getInstance(project)
        val (panel, tree) = createDiagnosticsPanel(project, state)

        val splitter = OnePixelSplitter(
            /* vertical = */ false,
            /* proportionKey = */ PROPORTION_KEY,
            /* minProp = */ 0.3f,
            /* maxProp = */ 0.7f,
        ).apply {
            firstComponent = panel
            secondComponent = createPanel("Log tab is empty")
        }

        toolWindowPanel.setContent(splitter)

        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(toolWindowPanel, "", false)

        Disposer.register(content) {
            tree.dispose()
        }

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
    ): Pair<JComponent, KotlinPluginsTree> {
        val panel = JPanel(BorderLayout())
        val settings = project.service<KotlinPluginsSettings>()

        val tree = KotlinPluginsTree(project, state, settings)

        val scrollPane = ScrollPaneFactory
            .createScrollPane(
                tree,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            )

        val toolbar = createDiagnosticsToolbar(tree)
        panel.add(toolbar.component, BorderLayout.WEST)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel to tree
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

        group.add(object : KotlinPluginsUpdateAction() {
            init {
                templatePresentation.text = "Update"
            }
        })

        group.add(object : KotlinPluginsRefreshAction() {
            init {
                templatePresentation.text = "Refresh"
            }
        })

        group.addSeparator()

        group.add(object : ToggleAction(
            "Show Successful",
            "Show items with 'Success' status",
            AllIcons.RunConfigurations.ShowPassed,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun isSelected(e: AnActionEvent): Boolean = tree.state.showSucceeded
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.state.showSucceeded = state
                tree.updater.redraw()
            }
        })

        group.add(object : ToggleAction(
            "Show Skipped/Disabled",
            "Show items with 'Skipped/Disabled' status",
            AllIcons.RunConfigurations.ShowIgnored,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun isSelected(e: AnActionEvent): Boolean = tree.state.showSkipped
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                tree.state.showSkipped = state
                tree.updater.redraw()
            }
        })

        group.addSeparator()

        group.add(object : KotlinPluginsClearCachesAction() {
            init {
                templatePresentation.text = "Clear Caches"
            }
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("KotlinPluginsDiagnosticsToolbar", group, false)

        toolbar.component.border = JBUI.Borders.empty(4)
        toolbar.targetComponent = tree
        return toolbar
    }

    companion object {
        const val ID = "Kotlin Plugins Diagnostics"
        const val DISPLAY_NAME = "Kotlin Plugins Diagnostics"
        const val PROPORTION_KEY = "KotlinPlugins.Proportion"

        fun show(project: Project) {
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(ID)
                ?.show()
        }
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
                        addText(
                            " ${KotlinPluginDescriptor.VersionMatching.EXACT.toUi()}",
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        )
                    } else {
                        addText(status.actualVersion, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        addText(
                            " ${status.criteria.toUi()}, Requested: ${status.requestedVersion}",
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        )
                    }
                }

                is ArtifactStatus.FailedToLoad -> {
                    addText(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    addText(" ${status.shortMessage}", SimpleTextAttributes.ERROR_ATTRIBUTES)
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
class KotlinPluginTreeStateService(
    val treeScope: CoroutineScope,
) : SimplePersistentStateComponent<TreeState>(TreeState())

class KotlinPluginsTree(
    private val project: Project,
    val state: TreeState,
    val settings: KotlinPluginsSettings,
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

    private val updaters = Channel<() -> Unit>(Channel.UNLIMITED)

    override fun dispose() {
        connection.dispose()
        nodesByKey.clear()
        model.setRoot(null)
        updaterJob?.cancel()
        updaters.close()
        updaters.cancel()
    }

    val updater = StatusUpdater()

    init {
        model.setAsksAllowsChildren(false)
        setModel(model)
        isRootVisible = false
        showsRootHandles = true

        // Speed search in the tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

        startUpdatingUi()

        updater.reset()

        connection.subscribe(KotlinPluginStatusUpdater.TOPIC, updater)
    }

    private fun reset() {
        nodesByKey.clear()
        redrawModel()
        project.service<KotlinPluginsStorage>().requestStatuses()
    }

    private fun redrawModel() {
        state.selectedNodeKey = null

        rootNode.removeAllChildren()

        val plugins = settings.safeState().plugins

        for (plugin in plugins) {
            val rootData = rootNode.userObject as NodeData

            val pluginKey = nodeKey(plugin.name)
            val pluginNodeData = nodesByKey[pluginKey]?.data ?: NodeData(
                project = project,
                parent = rootData,
                key = pluginKey,
                label = plugin.name,
                status = ArtifactStatus.Skipped,
                type = NodeType.Plugin,
            )

            val pluginNode = DefaultMutableTreeNode(pluginNodeData)

            // children: maven ids
            val artifactNodes = plugin.ids.map { id ->
                val artifactKey = nodeKey(plugin.name, id.id)

                val versionNodes = nodesByKey.entries.filter { (k, _) ->
                    k.startsWith(artifactKey) && k != artifactKey
                }.map { (k, v) ->
                    val versionNode = DefaultMutableTreeNode(v.data)
                    nodesByKey[k] = versionNode
                    versionNode
                }

                val artifactNodeData = nodesByKey[artifactKey]?.data ?: NodeData(
                    project = project,
                    parent = pluginNodeData,
                    key = artifactKey,
                    label = id.id,
                    status = ArtifactStatus.Skipped,
                    type = NodeType.Artifact,
                )
                val artifactNode = DefaultMutableTreeNode(artifactNodeData)

                val artifactStatus = when {
                    !plugin.enabled -> ArtifactStatus.Disabled
                    versionNodes.isEmpty() -> artifactNodeData.status
                    else -> parentStatus(versionNodes.map { it.data })
                }

                artifactNodeData.status = artifactStatus

                if (shouldIncludeStatus(artifactStatus)) {
                    pluginNode.add(artifactNode)
                    versionNodes.forEach { artifactNode.add(it) }
                }

                nodesByKey[artifactKey] = artifactNode
                artifactNode
            }

            val pluginStatus = when {
                !plugin.enabled -> ArtifactStatus.Disabled
                artifactNodes.isEmpty() -> ArtifactStatus.Skipped
                else -> parentStatus(artifactNodes.map { it.data })
            }

            pluginNodeData.status = pluginStatus

            // include a plugin if it passes filter (by its own status) or has any children
            if (shouldIncludeStatus(pluginStatus) || pluginNode.childCount > 0) {
                rootNode.add(pluginNode)
            }

            nodesByKey[pluginKey] = pluginNode
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

    inner class StatusUpdater : KotlinPluginStatusUpdater {
        override fun updatePlugin(pluginName: String, status: ArtifactStatus) = updateUi {
            this@KotlinPluginsTree.updatePlugin(pluginName, status)
        }

        override fun updateArtifact(
            pluginName: String,
            mavenId: String,
            status: ArtifactStatus,
        ) = updateUi {
            this@KotlinPluginsTree.updateArtifact(pluginName, mavenId, status)
        }

        override fun updateVersion(
            pluginName: String,
            mavenId: String,
            version: String,
            status: ArtifactStatus,
        ) = updateUi {
            this@KotlinPluginsTree.updateVersion(pluginName, mavenId, version, status)
        }

        override fun reset() = updateUi {
            this@KotlinPluginsTree.reset()

            expandAll()
        }

        override fun redraw() = updateUi {
            this@KotlinPluginsTree.redrawModel()

            expandAll()
        }
    }

    private fun updateUi(body: () -> Unit) {
        updaters.trySend(body)
    }

    private var updaterJob: Job? = null
    private val logger = thisLogger()

    private fun startUpdatingUi() {
        updaterJob = project.service<KotlinPluginTreeStateService>().treeScope
            .launch(Dispatchers.EDT + CoroutineName("KotlinPluginsTreeUpdater")) {
                while (true) {
                    val updater = updaters.receiveCatching().getOrNull() ?: break

                    try {
                        updater.invoke()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.error("Error while updating Tree UI", e)
                    }
                }
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
            updater.redraw()
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

        val newParentStatus = parentStatus(children)

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

private fun parentStatus(children: List<NodeData>): ArtifactStatus {
    if (children.isEmpty()) {
        return ArtifactStatus.Skipped
    }

    if (children.all { it.status is ArtifactStatus.Success }) {
        return ArtifactStatus.Success("", "", KotlinPluginDescriptor.VersionMatching.EXACT)
    }

    if (children.any { it.status == ArtifactStatus.InProgress }) {
        return ArtifactStatus.InProgress
    }

    if (children.any { it.status is ArtifactStatus.FailedToLoad }) {
        return ArtifactStatus.FailedToLoad("")
    }

    if (children.any { it.status == ArtifactStatus.ExceptionInRuntime }) {
        return ArtifactStatus.ExceptionInRuntime
    }

    return ArtifactStatus.Skipped
}

private fun KotlinPluginDescriptor.VersionMatching.toUi(): String {
    return when (this) {
        KotlinPluginDescriptor.VersionMatching.EXACT -> "Exact"
        KotlinPluginDescriptor.VersionMatching.SAME_MAJOR -> "Same Major"
        KotlinPluginDescriptor.VersionMatching.LATEST -> "Latest"
    }
}
