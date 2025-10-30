package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.EditorNotifications
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.LayoutManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.font.TextAttribute
import java.util.function.Consumer
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.isDirectory

class KotlinPluginsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.title = DISPLAY_NAME
        toolWindow.stripeTitle = DISPLAY_NAME
        toolWindow.isShowStripeButton = true

        val toolWindowPanel = SimpleToolWindowPanel(false, true)

        val state = KotlinPluginsTreeState.getInstance(project)
        val (panel, tree) = createDiagnosticsPanel(project, state)

        val splitter = OnePixelSplitter(
            /* vertical = */ false,
            /* proportionKey = */ PROPORTION_KEY,
            /* minProp = */ 0.3f,
            /* maxProp = */ 0.7f,
        ).apply {
            firstComponent = panel
        }

        // Overview + Logs tabs
        val overviewPanel = OverviewPanel(project, state, tree)
        tree.overviewPanel = overviewPanel
        val tabs = JBTabbedPane()
        tabs.addTab("Overview", overviewPanel.overviewPanelComponent)
        tabs.addTab("Logs", createPanel("Logs tab is empty"))
        splitter.secondComponent = tabs

        // Tree selection -> state + overview refresh
        tree.addTreeSelectionListener {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            val data = node?.userObject as? NodeData ?: return@addTreeSelectionListener
            val key = data.key
            state.selectedNodeKey = key
            overviewPanel.updater.redraw()
        }
        // initial paint
        overviewPanel.updater.redraw()

        toolWindowPanel.setContent(splitter)

        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(toolWindowPanel, "", false)

        Disposer.register(content) {
            tree.dispose()
            overviewPanel.dispose()
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
        state: KotlinPluginsTreeState,
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
    }
}

internal fun showKotlinPluginsToolWindow(project: Project) {
    ToolWindowManager
        .getInstance(project)
        .getToolWindow(KotlinPluginsToolWindowFactory.ID)
        ?.show()
}

internal class OverviewPanel(
    private val project: Project,
    private val state: KotlinPluginsTreeState,
    private val tree: KotlinPluginsTree,
) : Disposable {
    val overviewPanelComponent: JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    val updater = object : KotlinPluginStatusUpdater {
        override fun updatePlugin(pluginName: String, status: ArtifactStatus) {
            refreshIfAffectsSelection(pluginName)
        }

        override fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus) {
            // artifact status will be recomputed from versions on demand
            refreshIfAffectsSelection(pluginName, mavenId)
        }

        override fun updateVersion(pluginName: String, mavenId: String, version: String, status: ArtifactStatus) {
            refreshIfAffectsSelection(pluginName, mavenId, version)
        }

        override fun reset() {
            render()
        }

        override fun redraw() {
            render()
        }
    }

    init {
        state.onSelectedState(::render)
        render()
    }

    private var exceptionsRepaint: (() -> Unit)? = null

    private fun refreshIfAffectsSelection(pluginName: String, mavenId: String? = null, version: String? = null) {
        val selectedKey = state.selectedNodeKey
        val (p, m, v) = parseKey(selectedKey)
        if (p == null) return
        if (p != pluginName) return
        if (mavenId == null || m == null || mavenId == m) {
            if (version == null || v == null || version == v) {
                val status = tree.statusForKey(selectedKey)
                val exceptionsRepaint = exceptionsRepaint
                if (status is ArtifactStatus.ExceptionInRuntime && exceptionsRepaint != null) {
                    exceptionsRepaint()
                } else {
                    this.exceptionsRepaint = null
                    ApplicationManager.getApplication().invokeLater { render() }
                }
            }
        }
    }

    private fun parseKey(key: String?): Triple<String?, String?, String?> {
        if (key == null) return Triple(null, null, null)
        val parts = key.split("::")
        return when (parts.size) {
            1 -> Triple(parts[0], null, null)
            2 -> Triple(parts[0], parts[1], null)
            else -> Triple(parts[0], parts[1], parts[2])
        }
    }

    private fun render() {
        println("render")
        exceptionsRepaint = null
        val selectedKey = state.selectedNodeKey
        val status = tree.statusForKey(selectedKey)
        val (plugin, mavenId, version) = parseKey(selectedKey)

        val parentPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }

        fun <T : JComponent> addRoot(component: T, constraints: Any? = null): T {
            if (constraints != null) {
                parentPanel.add(component, constraints)
            } else {
                parentPanel.add(component)
            }
            return component
        }

        fun addRootPanel(
            constraints: Any? = null,
            customiseComponent: (DialogPanel) -> Unit = {},
            init: Panel.() -> Unit,
        ): JScrollPane {
            val panel = panel(init).apply(customiseComponent)
            val scroll = ScrollPaneFactory.createScrollPane(
                panel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            }

            return addRoot(scroll, constraints)
        }

        when {
            plugin == null -> {
                addRoot(GrayedLabel("Select a plugin to view details."), BorderLayout.CENTER)
                    .align(SwingConstants.CENTER)
            }

            mavenId == null -> {
                addRoot(
                    component = header(
                        type = NodeType.Plugin,
                        status = status,
                        plugin = plugin,
                    ),
                    constraints = BorderLayout.NORTH,
                )

                addRoot(pluginVersionPanels(NodeType.Plugin, status, plugin))
            }

            version == null -> {
                addRoot(
                    component = header(
                        type = NodeType.Artifact,
                        status = status,
                        plugin = plugin,
                        mavenId = mavenId,
                    ),
                    constraints = BorderLayout.NORTH,
                )

                addRoot(pluginVersionPanels(NodeType.Artifact, status, plugin))
            }

            else -> {
                addRoot(
                    component = header(
                        type = NodeType.Version,
                        status = status,
                        plugin = plugin,
                        mavenId = mavenId,
                        version = version,
                    ),
                    constraints = BorderLayout.NORTH,
                )

                when (status ?: ArtifactStatus.InProgress) {
                    ArtifactStatus.InProgress -> {
                        addRoot(inProgressPanel(NodeType.Version))
                    }

                    ArtifactStatus.Disabled -> {
                        addRoot(disabledPanel(NodeType.Version, plugin))
                    }

                    ArtifactStatus.Skipped -> {
                        addRoot(skippedPanel(NodeType.Version))
                    }

                    is ArtifactStatus.Success, is ArtifactStatus.ExceptionInRuntime -> {
                        val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>()
                        if (analyzer.state.enabled) {
                            addRootPanel(customiseComponent = {
                                it.maximumSize.width = 600.scaled
                                it.minimumSize.width = 270.scaled
                                it.preferredSize.width = 600.scaled
                                it.preferredSize.height = 270.scaled
                            }) {
                                exceptionsRepaint = exceptionsPanel(plugin, mavenId, version, status)

                                if (status is ArtifactStatus.ExceptionInRuntime || status is ArtifactStatus.Success) {
                                    val model = DefaultListModel<String>()
                                    var list: JComponent? = null
                                    row {
                                        list = jbList(
                                            label = "Analyzed classes in jar:",
                                            icon = AllIcons.FileTypes.JavaClass,
                                            labelFont = { it.deriveFont(Font.BOLD) },
                                            model = model,
                                            renderer = @Suppress("UnstableApiUsage") listCellRenderer {
                                                text(value) {
                                                    font = MonospacedFont
                                                }
                                            },
                                        )
                                    }

                                    var placeholder: JLabel? = null
                                    row { placeholder = grayed("Loading analyzed classes...").component }

                                    loadFqNamesAsync(JarId(plugin, mavenId, version)) { names ->
                                        ApplicationManager.getApplication().invokeLater {
                                            if (names.isEmpty()) {
                                                placeholder?.text = "No analyzed classes found"
                                                list?.isVisible = false
                                            } else {
                                                placeholder?.isVisible = false
                                                model.clear()
                                                names.sorted().forEach { model.addElement(it) }
                                                list?.isVisible = true
                                            }
                                            overviewPanelComponent.revalidate()
                                            overviewPanelComponent.repaint()
                                        }
                                    }
                                }
                            }
                        } else {
                            addRoot(mainPanel { GridBagLayout() }).apply {
                                val label = GrayedLabel("Analysis for classes in a jar is disabled.")

                                val actionLink = ActionLink("Open Settings") {
                                    KotlinPluginsConfigurable.showGeneral(project)
                                }

                                vertical(label, actionLink)
                            }
                        }
                    }

                    is ArtifactStatus.FailedToLoad -> {
                        val sanitizedMessage = project.service<KotlinPluginsStorage>()
                            .getFailureMessageFor(plugin, mavenId, version)
                            ?: "Failed to load with unknown reason. <br/>Please check the log for details."

                        addRootPanel(
                            customiseComponent = {
                                it.maximumSize.width = 600.scaled
                                it.minimumSize.width = 270.scaled
                                it.preferredSize.width = 600.scaled
                                it.preferredSize.height = 270.scaled
                            }
                        ) {
                            var area: Cell<JBTextArea>? = null
                            row {
                                area = textArea()
                                    .label("Failed to load this jar:", LabelPosition.TOP)
                                    .align(AlignX.FILL)
                                    .rows(10)
                                    .applyToComponent {
                                        text = sanitizedMessage
                                        isEditable = false
                                        wrapStyleWord = true
                                        lineWrap = state.softWrapErrorMessages
                                    }
                            }

                            row {
                                checkBox("Soft wrap").applyToComponent {
                                    addActionListener {
                                        state.softWrapErrorMessages = isSelected
                                        area?.component?.lineWrap = isSelected
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        overviewPanelComponent.removeAll()
        overviewPanelComponent.add(parentPanel, BorderLayout.CENTER)
        overviewPanelComponent.revalidate()
        overviewPanelComponent.repaint()
    }

    private fun Row.grayed(@NlsContexts.Label text: String) = label(text).applyToComponent {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    @Suppress("FunctionName")
    private fun GrayedLabel(@NlsContexts.Label text: String) = JBLabel(text).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private fun JLabel.align(alignment: Int): JLabel {
        horizontalAlignment = alignment
        return this
    }

    private fun openCacheLink(
        isFile: Boolean,
        plugin: String,
        mavenId: String? = null,
        version: String? = null,
    ): ActionLink {
        return if (isFile) {
            ActionLink("Show in cache directory") { revealFor(plugin, mavenId, version) }
        } else {
            ActionLink("Show cache directory") { revealFor(plugin, mavenId, version) }
        }.apply {
            icon = AllIcons.General.OpenDisk
        }
    }

    private fun header(
        type: NodeType,
        status: ArtifactStatus?,
        plugin: String,
        mavenId: String? = null,
        version: String? = null,
    ): JComponent {
        return JPanel(BorderLayout()).apply {
            val insets = JBUI.CurrentTheme.Toolbar.horizontalToolbarInsets() ?: JBUI.insets(5, 7)
            border = BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom, insets.right)
            background = JBUI.CurrentTheme.ToolWindow.headerBackground()

            status?.let {
                val label = JBLabel(statusToTooltip(type, status)).apply {
                    icon = statusToIcon(status)
                }
                add(label, BorderLayout.LINE_START)
            }

            if (status != null && (status is ArtifactStatus.Success || status is ArtifactStatus.ExceptionInRuntime)) {
                add(Box.createRigidArea(JBUI.size(8, 0)), BorderLayout.CENTER)

                add(openCacheLink(type == NodeType.Version, plugin, mavenId, version), BorderLayout.LINE_END)
            }
        }
    }

    private fun mainPanel(layoutGetter: (JComponent) -> LayoutManager = { BorderLayout() }): JPanel {
        return JPanel().apply {
            layout = layoutGetter(this)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
    }

    private fun pluginVersionPanels(
        type: NodeType,
        status: ArtifactStatus?,
        pluginName: String,
    ): JPanel {
        return when (status) {
            ArtifactStatus.InProgress -> inProgressPanel(type)
            ArtifactStatus.Disabled -> disabledPanel(type, pluginName)
            ArtifactStatus.Skipped -> skippedPanel(type)
            else -> selectVersionPanel()
        }
    }

    private fun selectVersionPanel(): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val grayedLabel = GrayedLabel("Select a version to see details.")

            vertical(grayedLabel)
        }
    }

    private fun inProgressPanel(type: NodeType): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val text = GrayedLabel("Panel will display info when ${type.displayLowerCaseName} is loaded.")

            vertical(text)
        }
    }

    private fun skippedPanel(type: NodeType): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val text = GrayedLabel("This ${type.displayLowerCaseName} is not yet requested in project.")
            val description = GrayedLabel("Resolution is lazy and it might be requested later.")

            vertical(text, description)
        }
    }

    private fun disabledPanel(type: NodeType, pluginName: String): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val enableLink = ActionLink("Enable this ${type.displayLowerCaseName}") {
                project.service<KotlinPluginsSettings>().enablePlugin(pluginName)
            }

            val orLabel = GrayedLabel("Or")

            val settingsLink = ActionLink("Open the Settings") {
                KotlinPluginsConfigurable.showArtifacts(project)
            }

            vertical(enableLink, orLabel, settingsLink)
        }
    }

    private fun JPanel.vertical(
        vararg components: JComponent,
    ) {
        val gbc = GridBagConstraints()

        components.forEachIndexed { i, component ->
            gbc.gridx = 0 // Column 0
            gbc.gridy = i // Row i
            if (i != components.lastIndex) {
                gbc.insets.bottom = 7
            }
            this.add(component, gbc)
        }
    }

    @Suppress("unused")
    private fun JPanel.horizontal(
        vararg components: JComponent,
        @Suppress("SameParameterValue")
        rightInset: Int = 5,
    ) {
        val gbc = GridBagConstraints()

        components.forEachIndexed { i, component ->
            gbc.gridx = i // Column i
            gbc.gridy = 0 // Row 0
            if (i != components.lastIndex) {
                gbc.insets.right = rightInset
            }
            this.add(component, gbc)
        }
    }

    private fun loadFqNamesAsync(jarId: JarId, onReady: (Set<String>) -> Unit) {
        val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>()
        if (!analyzer.state.enabled) {
            return
        }

        project.service<KotlinPluginTreeStateService>().treeScope.launch(CoroutineName("load-fqnames-$jarId")) {
            val map = project.service<KotlinPluginsExceptionReporter>().lookFor()
            val names = map[jarId].orEmpty()
            onReady(names)
        }
    }

    private fun revealFor(plugin: String, mavenId: String? = null, version: String? = null) {
        val storage = project.service<KotlinPluginsStorage>()
        val path = storage.getLocationFor(plugin, mavenId, version)
        if (path != null) {
            runCatching {
                if (path.isDirectory()) {
                    RevealFileAction.openDirectory(path)
                } else {
                    RevealFileAction.openFile(path)
                }
            }
        }
    }

    private fun exceptionTextAndHighlights(ex: Throwable, highlights: Set<String>): Pair<String, List<IntRange>> {
        val raw = ex.stackTraceToString()
        val lines = raw.split("\r\n", "\n")
        val limit = lines.size.coerceAtMost(MAX_STACKTRACE_LINES)
        val sb = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        for (i in 0 until limit) {
            val line = lines[i]
            val shouldHighlight = if (line.startsWith("\tat ")) {
                val body = line.removePrefix("\tat ")
                val classPart = body.substringBefore('(').substringBeforeLast('.')
                highlights.contains(classPart)
            } else false
            val toAppend = line + "\n"
            if (shouldHighlight) {
                ranges.add(offset until (offset + line.length))
            }
            sb.append(toAppend)
            offset += toAppend.length
        }
        if (lines.size > limit) {
            val footer = "\n\u2026 truncated ${lines.size - limit} more lines"
            sb.append(footer)
        }
        return sb.toString() to ranges
    }

    private fun Panel.exceptionsPanel(
        plugin: String,
        mavenId: String,
        version: String,
        status: ArtifactStatus?,
    ): () -> Unit {
        val reporter = project.service<KotlinPluginsExceptionReporter>()
        val report = reporter.getExceptionsReport(plugin, mavenId, version)
        var hintLabel: JLabel? = null
        var exceptionsPanel: JComponent? = null

        if (status is ArtifactStatus.ExceptionInRuntime && report != null) {
            row {
                label("Exception(s) occurred in runtime")
                    .comment(
                        """
                            A compiler plugin must never throw an exception<br/>
                            Instead it must report errors using diagnostics<br/>
                        """.trimIndent()
                    )
            }

            if (project.service<KotlinPluginsSettings>().isEnabled(plugin)) {
                row {
                    cell(ActionLink("Disable this plugin") {
                        project.service<KotlinPluginsNotifications>().deactivate(plugin)
                        ApplicationManager.getApplication().invokeLater {
                            EditorNotifications.getInstance(project).updateAllNotifications()
                        }

                        project.service<KotlinPluginsSettings>()
                            .disablePlugin(plugin)
                    })

                    cell(ActionLink("Auto-disable") {
                        project.service<KotlinPluginsNotifications>().deactivate(plugin)
                        ApplicationManager.getApplication().invokeLater {
                            EditorNotifications.getInstance(project).updateAllNotifications()
                        }

                        project.service<KotlinPluginsExceptionAnalyzerService>()
                            .updateState(enabled = true, autoDisable = true)

                        project.service<KotlinPluginsSettings>()
                            .disablePlugin(plugin)
                    })
                }
            }

            separator()

            row {
                hintLabel = label(report.hint()).component
            }

            row {
                button("Create Failure Report") {
                    // todo
                }
            }

            separator()

            exceptionsPanel = JPanel().apply {
                layout = BorderLayout()
            }

            val exceptionPanes = mutableListOf<Pair<ExceptionEditorTextField, Throwable>>()
            exceptionsPanel.add(com.intellij.ui.dsl.builder.panel {
                report.exceptions.forEachIndexed { index, ex ->
                    val groupTitle = "#${index + 1}: ${ex::class.java.name}: ${ex.message ?: ""}"
                    var editorField: ExceptionEditorTextField? = null
                    val group = collapsibleGroup(groupTitle) {
                        val (text, _) = exceptionTextAndHighlights(ex, emptySet())
                        editorField = ExceptionEditorTextField(text, project)
                        row {
                            cell(editorField)
                                .align(AlignX.FILL)
                        }
                        exceptionPanes.add(editorField to ex)
                    }

                    group.addExpandedListener { expanded ->
                        if (expanded) {
                            val ed = editorField?.editor
                            if (ed != null) {
                                ApplicationManager.getApplication().invokeLater {
                                    // Put caret to the beginning and ensure top is visible
                                    ed.caretModel.moveToOffset(0)
                                    ed.scrollingModel.scrollTo(
                                        LogicalPosition(0, 0),
                                        ScrollType.MAKE_VISIBLE
                                    )
                                    // And/or force vertical position to 0 for good measure
                                    ed.scrollingModel.scrollVertically(0)
                                }
                            }
                        }
                    }

                    fun updateTitle() {
                        setEllipsized(this@OverviewPanel.overviewPanelComponent, labelFont, groupTitle) {
                            group.setTitle(it)
                        }
                    }

                    updateTitle()

                    overviewPanelComponent.addComponentListener(object : ComponentAdapter() {
                        override fun componentShown(e: ComponentEvent) {
                            updateTitle()
                        }

                        override fun componentResized(e: ComponentEvent) {
                            updateTitle()
                        }
                    })
                }
            })

            row { cell(exceptionsPanel) }

            loadFqNamesAsync(JarId(plugin, mavenId, version)) { names ->
                ApplicationManager.getApplication().invokeLater {
                    exceptionPanes.forEach { (pane, throwable) ->
                        updateExceptionEditor(pane, throwable, names)
                    }
                }
            }

            separator()
        } else if (status == ArtifactStatus.ExceptionInRuntime && report == null) {
            row {
                label("Failed to show exceptions.")
                    .comment("This is probably due to a bug in our IntelliJ plugin.")
            }

            separator()
        }

        return {
            println("repaint")
            val reporter = project.service<KotlinPluginsExceptionReporter>()
            val newReport = reporter.getExceptionsReport(plugin, mavenId, version)

            if (newReport != report) {
                if (newReport?.__exceptionsIds != report?.__exceptionsIds) {
                    exceptionsPanel?.revalidate()
                    exceptionsPanel?.repaint()
                }

                hintLabel?.revalidate()
                hintLabel?.repaint()
            }
        }
    }

    @Suppress("JComponentDataProvider")
    private class ExceptionEditorTextField(
        text: String,
        project: Project,
    ) : EditorTextField(text, project, null) {
        @Volatile
        var highlightRanges = emptyList<IntRange>()

        override fun createEditor(): EditorEx {
            val ed = super.createEditor()
            ed.isViewer = true
            ed.isOneLineMode = false
            ed.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            ed.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            ed.settings.isFoldingOutlineShown = false
            ed.settings.isCaretRowShown = false
            ed.settings.isUseSoftWraps = true
            ed.settings.isWhitespacesShown = false
            applyHighlights(ed, highlightRanges)
            font = MonospacedFont
            return ed
        }

        override fun onEditorAdded(editor: Editor) {
            super.onEditorAdded(editor)
            applyHighlights(editor, highlightRanges)
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            val lineHeight = (editor as? EditorEx)?.lineHeight
                ?: getFontMetrics(font).height

            // Respect HiDPI via JBUI.scale when adding pixel paddings if needed
            val insets = insets
            val maxHeight = lineHeight * MAX_STACKTRACE_LINES_IN_VIEW + insets.top + insets.bottom
            return if (base.height > maxHeight) Dimension(base.width, maxHeight) else base
        }

        override fun getFont(): Font {
            return MonospacedFont
        }

        override fun getMinimumSize(): Dimension {
            return Dimension(270.scaled, 55.scaled)
        }

        private fun applyHighlights(editor: Editor, highlightRanges: List<IntRange>) {
            val markup = editor.markupModel
            markup.removeAllHighlighters()
            val attrs = TextAttributes(
                /* foregroundColor = */ null,
                /* backgroundColor = */ JBUI.CurrentTheme.Editor.Tooltip.WARNING_BACKGROUND,
                /* effectColor = */ null,
                /* effectType = */ null,
                /* fontType = */ Font.PLAIN,
            )

            highlightRanges.forEach { range ->
                markup.addRangeHighlighter(
                    range.first,
                    range.last + 1,
                    HighlighterLayer.SELECTION - 1,
                    attrs,
                    HighlighterTargetArea.EXACT_RANGE
                )
            }
            editor.component.repaint()
        }

        fun updateHighlights() {
            editor?.let { applyHighlights(it, highlightRanges) }
        }
    }

    private fun updateExceptionEditor(editorField: ExceptionEditorTextField, ex: Throwable, fqNames: Set<String>) {
        val (text, ranges) = exceptionTextAndHighlights(ex, fqNames)
        editorField.text = text
        editorField.highlightRanges = ranges
        editorField.updateHighlights()
    }

    override fun dispose() {
        state.removeOnSelectedState(::render)
    }

    private fun setEllipsized(component: JComponent, font: Font, full: String, setText: (String) -> Unit) {
        val insets = component.insets
        val max = component.width - insets.left - insets.right
        if (max <= 0) {
            setText(full)
            return
        }

        val fm = component.getFontMetrics(font)
        if (fm.stringWidth(full) <= max) {
            setText(full)
            return
        }

        val ellipsis = "\u2026"
        var lo = 0
        var hi = full.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            val candidate = full.take(mid) + ellipsis
            if (fm.stringWidth(candidate) <= max) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }

        setText(full.take(lo) + ellipsis)
    }

    private val labelFont = JBLabel().font

    companion object {
        private const val MAX_STACKTRACE_LINES = 100
        private const val MAX_STACKTRACE_LINES_IN_VIEW = 15
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

class KotlinPluginsTreeState : BaseState() {
    var showSucceeded: Boolean by property(true)
    var showSkipped: Boolean by property(true)

    var selectedNodeKey: String? by string(null)

    var showClearCachesDialog: Boolean by property(true)
    var softWrapErrorMessages: Boolean by property(false)

    companion object {
        fun getInstance(project: Project): KotlinPluginsTreeState =
            project.service<KotlinPluginTreeStateService>().state
    }

    fun select(pluginName: String? = null, mavenId: String? = null, version: String? = null) {
        try {
            if (pluginName == null) {
                selectedNodeKey = null
                return
            }

            val key = nodeKey(pluginName, mavenId, version)
            selectedNodeKey = key
        } finally {
            actions.forEach { it() }
        }
    }

    private val actions = mutableListOf<() -> Unit>()

    fun onSelectedState(action: () -> Unit): () -> Unit {
        actions.add(action)
        return action
    }

    fun removeOnSelectedState(action: () -> Unit) {
        actions.remove(action)
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginTreeState",
    storages = [Storage(WORKSPACE_FILE)],
)
class KotlinPluginTreeStateService(
    val treeScope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsTreeState>(KotlinPluginsTreeState())

class KotlinPluginsTree(
    private val project: Project,
    val state: KotlinPluginsTreeState,
    val settings: KotlinPluginsSettings,
) : Tree(), Disposable {
    internal var overviewPanel: OverviewPanel? = null

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

    fun statusForKey(key: String?): ArtifactStatus? {
        if (key == null) {
            return null
        }

        return nodesByKey[key]?.data?.status?.takeIf { shouldIncludeStatus(it) }
    }

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
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private fun TreeNode?.selectionPath(): List<TreeNode> {
        if (this == null) {
            return emptyList()
        }

        return this.parent.selectionPath() + this
    }

    private fun reset() {
        nodesByKey.values.removeIf { it.data.status !is ArtifactStatus.ExceptionInRuntime }

        redrawModel()
        project.service<KotlinPluginsStorage>().requestStatuses()
    }

    private fun redrawModel() {
        rootNode.removeAllChildren()

        val plugins = settings.safeState().plugins

        val selectedNodeKey = state.selectedNodeKey
        var selectedNode: DefaultMutableTreeNode? = null

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
                    if (k == selectedNodeKey) {
                        selectedNode = versionNode
                    }
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

                    if (artifactKey == selectedNodeKey) {
                        selectedNode = artifactNode
                    }
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
                if (pluginKey == selectedNodeKey) {
                    selectedNode = pluginNode
                }
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

        if (selectedNodeKey != null && selectedNodeKey !in nodesByKey.keys) {
            state.selectedNodeKey = null
        }

        model.reload()

        if (selectedNode != null) {
            val path = selectedNode.selectionPath()
            if (path.isNotEmpty()) {
                selectionPath = TreePath(path.toTypedArray())
            }
        }

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

            overviewPanel?.updater?.updateArtifact(pluginName, mavenId, status)
        }

        override fun updateVersion(
            pluginName: String,
            mavenId: String,
            version: String,
            status: ArtifactStatus,
        ) = updateUi {
            this@KotlinPluginsTree.updateVersion(pluginName, mavenId, version, status)

            overviewPanel?.updater?.updateVersion(pluginName, mavenId, version, status)
        }

        override fun reset() = updateUi {
            this@KotlinPluginsTree.reset()

            expandAll()

            overviewPanel?.updater?.reset()
        }

        override fun redraw() = updateUi {
            this@KotlinPluginsTree.redrawModel()

            expandAll()

            overviewPanel?.updater?.redraw()
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

private fun nodeKey(pluginName: String, mavenId: String? = null, version: String? = null): String {
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
                        "criteria: ${status.criteria}</html>"
            }
        }

        ArtifactStatus.InProgress -> "Version is loading/refreshing"
        is ArtifactStatus.FailedToLoad -> "Version failed to load: ${status.shortMessage}"
        ArtifactStatus.ExceptionInRuntime -> "Version threw an exception during runtime"
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

private fun <T> Row.jbList(
    label: @NlsContexts.Label String?,
    icon: Icon? = null,
    labelFont: (Font) -> Font = { it },
    model: DefaultListModel<T>,
    renderer: ListCellRenderer<T>,
    patchList: Consumer<JBList<T>>? = null,
): JBList<T?> {
    val list = JBList(model)
    list.border = JBUI.Borders.customLine(JBUI.CurrentTheme.MainWindow.Tab.BORDER, 1)
    list.setCellRenderer(renderer)
    patchList?.accept(list)

    val result = cell(list).apply {
        align(AlignX.FILL)
    }
    label?.let {
        val labelComponent = JBLabel(label).apply {
            font = labelFont(font)
            if (icon != null) {
                this.icon = icon
            }
        }

        result.label(labelComponent, LabelPosition.TOP)
    }
    return list
}

private val MonospacedFont by lazy {
    val attributes = mutableMapOf<TextAttribute, Any?>()

    attributes[TextAttribute.FAMILY] = "Monospaced"
    attributes[TextAttribute.WEIGHT] = TextAttribute.WEIGHT_REGULAR
    attributes[TextAttribute.WIDTH] = TextAttribute.WIDTH_REGULAR

    JBFont.create(Font.getFont(attributes)).deriveFont(Font.PLAIN, 13.0f)
}

private val NodeType.displayLowerCaseName
    get() = when (this) {
        NodeType.Plugin -> "plugin"
        NodeType.Artifact -> "artifact"
        NodeType.Version -> "version"
    }

internal val Int.scaled get() = JBUI.scale(this)

private fun ExceptionsReport.hint(): String {
    val exceptionsAnalysis = when {
        kotlinVersionMismatch != null && isProbablyIncompatible -> {
            """
                <strong>Exceptions Analysis</strong><br/>
                <br/>
                The version of the plugin is not compatible with the version of the IDE.<br/>
                This may be the cause for the exceptions thrown.<br/>
                Compiler API is not binary compatible between Kotlin versions.<br/>
                <br/>
                Indicated Kotlin version in the jar: ${kotlinVersionMismatch.jarVersion}<br/>
                In IDE Kotlin version: ${kotlinVersionMismatch.ideVersion}<br/>
                <br/>
            """.trimIndent()
        }

        isProbablyIncompatible -> {
            val kotlinIdeVersion = service<KotlinVersionService>()
                .getKotlinIdePluginVersion()

            """
                <strong>Exceptions Analysis</strong><br/>
                <br/>
                Exceptions thrown are likely to be the sign of a binary incompatibility
                between the Kotlin version of the IDE and the Kotlin version of the plugin.<br/>
                The plugin indicates that the Kotlin version match, however this might not be the case.<br/>
                <br/>
                In IDE Kotlin version: $kotlinIdeVersion<br/>
                <br/>
            """.trimIndent()
        }

        else -> ""
    }

    val actionSuggestion = when {
        reloadedSame && isLocal -> {
            """
                <strong>Action suggestion</strong><br/>
                <br/>
                The jar was loaded from a local source 
                and was reloaded at least once with the same content so the exception persists 
                (for example, during a manual or a background update).<br/>
                If you are developing the plugin, try changing its logic 
                and republishing it locally to the same place. It will be updated automatically in IDE.<br/>
            """.trimIndent()
        }

        isLocal -> {
            """
                <strong>Action suggestion</strong><br/>
                <br/>
                The jar was loaded from a local source.<br/>
                If you are developing the plugin, try changing its logic 
                and republishing it locally to the same place. It will be updated automatically in IDE.<br/>
            """.trimIndent()
        }

        reloadedSame -> {
            """
                <strong>Action suggestion</strong><br/>
                <br/>
                The jar was loaded from a remote source 
                and was reloaded at least once with the same content so the exception persists 
                (for example, during a manual or a background update).<br/>
                If you are developing the plugin, try changing its logic 
                and republishing it to the same place and run 'Update' action<br/>
            """.trimIndent()
        }

        kotlinVersionMismatch != null -> {
            """
                <strong>Action suggestion</strong><br/>
                <br/>
                The jar was loaded unsafely, using a fallback without the compatibilities guarantee.<br/>
                If you are developing the plugin, try publishing it with the same Kotlin version as the IDE.<br/>
                If you are not the developer - you can report this problem to the plugin author using the button below.<br/>
            """.trimIndent()
        }

        else -> """
            <strong>Action suggestion</strong><br/>
            <br/>
            If you are developing the plugin - you need to check the exceptions and replace them with diagnostics.<br/>
            If you are not the developer - you can report this problem to the plugin author using the button below.<br/>
        """.trimIndent()
    }

    return """
        |<html>
        |$exceptionsAnalysis
        |$actionSuggestion
        |</html>
    """.trimMargin()
}
