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

internal class KotlinPluginsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.title = KotlinPluginsBundle.message("toolwindow.displayName")
        toolWindow.stripeTitle = KotlinPluginsBundle.message("toolwindow.displayName")
        toolWindow.isShowStripeButton = false
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.title = KotlinPluginsBundle.message("toolwindow.displayName")
        toolWindow.stripeTitle = KotlinPluginsBundle.message("toolwindow.displayName")

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
        tabs.addTab(KotlinPluginsBundle.message("tab.overview"), overviewPanel.overviewPanelComponent)
        tabs.addTab(KotlinPluginsBundle.message("tab.logs"), createLogPanel())
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

    private fun createLogPanel(): JPanel {
        return JPanel().apply {
            layout = GridBagLayout()

            val logNotice = GrayedLabel(KotlinPluginsBundle.message("logs.tab.notice"))

            vertical(logNotice)
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

        group.add(object : AnAction(
            KotlinPluginsBundle.message("toolbar.expandAll"),
            KotlinPluginsBundle.message("toolbar.expandAll.description"),
            AllIcons.Actions.Expandall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                tree.expandAll()
            }
        })

        group.add(object : AnAction(
            KotlinPluginsBundle.message("toolbar.collapseAll"),
            KotlinPluginsBundle.message("toolbar.collapseAll.description"),
            AllIcons.Actions.Collapseall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                tree.collapseAll()
            }
        })

        group.addSeparator()

        group.add(object : KotlinPluginsUpdateAction() {
            init {
                templatePresentation.text = KotlinPluginsBundle.message("toolbar.update")
            }
        })

        group.add(object : KotlinPluginsRefreshAction() {
            init {
                templatePresentation.text = KotlinPluginsBundle.message("toolbar.refresh")
            }
        })

        group.addSeparator()

        group.add(object : ToggleAction(
            KotlinPluginsBundle.message("toolbar.showSucceeded"),
            KotlinPluginsBundle.message("toolbar.showSucceeded.description"),
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
            KotlinPluginsBundle.message("toolbar.showSkipped"),
            KotlinPluginsBundle.message("toolbar.showSkipped.description"),
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
                templatePresentation.text = KotlinPluginsBundle.message("action.clearCaches.dialog.title")
            }
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("KotlinPluginsDiagnosticsToolbar", group, false)

        toolbar.component.border = JBUI.Borders.empty(4)
        toolbar.targetComponent = tree
        return toolbar
    }

    companion object {
        const val ID = "com.github.mr3zee.kotlinPlugins.toolWindow"
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
                addRoot(GrayedLabel(KotlinPluginsBundle.message("details.selectPlugin")), BorderLayout.CENTER)
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

                    is ArtifactStatus.PartialSuccess -> {
                        addRoot(partialSuccessPanel(NodeType.Version))
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
                                            label = KotlinPluginsBundle.message("analyzed.classes.label"),
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
                                    row {
                                        placeholder =
                                            grayed(KotlinPluginsBundle.message("analyzed.classes.loading")).component
                                    }

                                    loadFqNamesAsync(JarId(plugin, mavenId, version)) { names ->
                                        ApplicationManager.getApplication().invokeLater {
                                            if (names.isEmpty()) {
                                                placeholder?.text = KotlinPluginsBundle.message("analyzed.classes.none")
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
                                val label = GrayedLabel(KotlinPluginsBundle.message("jar.analysis.disabled"))

                                val actionLink = ActionLink(KotlinPluginsBundle.message("link.openSettings")) {
                                    KotlinPluginsConfigurableUtil.showGeneral(project)
                                }

                                vertical(label, actionLink)
                            }
                        }
                    }

                    is ArtifactStatus.FailedToLoad -> {
                        val sanitizedMessage = project.service<KotlinPluginsStorage>()
                            .getFailureMessageFor(plugin, mavenId, version)
                            ?: KotlinPluginsBundle.message("error.unknown.reason")

                        addRootPanel(
                            customiseComponent = {
                                it.maximumSize.width = 600.scaled
                                it.minimumSize.width = 270.scaled
                                it.preferredSize.width = 600.scaled
                                it.preferredSize.height = 400.scaled
                            }
                        ) {
                            var area: Cell<JBTextArea>? = null
                            row {
                                area = textArea()
                                    .label(KotlinPluginsBundle.message("jar.load.failed.title"), LabelPosition.TOP)
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
                                checkBox(KotlinPluginsBundle.message("label.softWrap")).applyToComponent {
                                    isSelected = state.softWrapErrorMessages

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
            ActionLink(KotlinPluginsBundle.message("link.showInCacheDir")) { revealFor(plugin, mavenId, version) }
        } else {
            ActionLink(KotlinPluginsBundle.message("link.showCacheDir")) { revealFor(plugin, mavenId, version) }
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
            is ArtifactStatus.PartialSuccess -> partialSuccessPanel(type)
            else -> selectVersionPanel()
        }
    }

    private fun selectVersionPanel(): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val grayedLabel = GrayedLabel(KotlinPluginsBundle.message("versions.select"))

            vertical(grayedLabel)
        }
    }

    private fun inProgressPanel(type: NodeType): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val text = GrayedLabel(KotlinPluginsBundle.message("panel.display.when.loaded", type.displayLowerCaseName))

            vertical(text)
        }
    }

    private fun skippedPanel(type: NodeType): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val text = GrayedLabel(KotlinPluginsBundle.message("panel.not.requested", type.displayLowerCaseName))
            val description = GrayedLabel(KotlinPluginsBundle.message("panel.resolution.lazy"))

            vertical(text, description)
        }
    }

    private fun disabledPanel(type: NodeType, pluginName: String): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val enableLink = ActionLink(KotlinPluginsBundle.message("link.enableType", type.displayLowerCaseName)) {
                project.service<KotlinPluginsSettings>().enablePlugin(pluginName)
            }

            val orLabel = GrayedLabel(KotlinPluginsBundle.message("label.or"))

            val settingsLink = ActionLink(KotlinPluginsBundle.message("link.openTheSettings")) {
                KotlinPluginsConfigurableUtil.showArtifacts(project)
            }

            vertical(enableLink, orLabel, settingsLink)
        }
    }

    private fun partialSuccessPanel(type: NodeType): JPanel {
        return mainPanel { GridBagLayout() }.apply {
            val text = GrayedLabel(
                KotlinPluginsBundle.message(
                    "partial.success.header",
                    type.displayLowerCaseName,
                )
            )

            val description = GrayedLabel(
                KotlinPluginsBundle.message("partial.success.description")
            )

            vertical(text, description)
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
            val footer = "\n" + KotlinPluginsBundle.message("stacktrace.truncated", lines.size - limit)
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
                label(KotlinPluginsBundle.message("exceptions.runtime.title"))
                    .comment(KotlinPluginsBundle.message("exceptions.runtime.tipHtml"))
            }

            if (project.service<KotlinPluginsSettings>().isEnabled(plugin)) {
                row {
                    cell(ActionLink(KotlinPluginsBundle.message("link.disableThisPlugin")) {
                        project.service<KotlinPluginsNotifications>().deactivate(plugin)
                        ApplicationManager.getApplication().invokeLater {
                            EditorNotifications.getInstance(project).updateAllNotifications()
                        }

                        project.service<KotlinPluginsSettings>()
                            .disablePlugin(plugin)
                    })

                    cell(ActionLink(KotlinPluginsBundle.message("editor.notification.autoDisable")) {
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
                button(KotlinPluginsBundle.message("button.createFailureReport")) {
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
                    val groupTitle = KotlinPluginsBundle.message(
                        "exception.group.title",
                        index + 1,
                        ex::class.java.name,
                        ex.message ?: ""
                    )
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
                                    // Put caret to the beginning and ensure the top is visible
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
                        setEllipsis(this@OverviewPanel.overviewPanelComponent, labelFont, groupTitle) {
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
                label(KotlinPluginsBundle.message("exceptions.show.failed"))
                    .comment(KotlinPluginsBundle.message("exceptions.show.failed.tip"))
            }

            separator()
        }

        return {
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

    private fun setEllipsis(
        component: JComponent,
        font: Font,
        @NlsContexts.Label full: String,
        setText: (String) -> Unit,
    ) {
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
    @NlsContexts.Label var label: String,
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
                            " " + KotlinPluginsBundle.message(
                                "tree.version.grayText",
                                status.criteria.toUi(),
                                status.requestedVersion,
                            ),
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

internal class KotlinPluginsTreeState : BaseState() {
    var showSucceeded: Boolean by property(true)
    var showSkipped: Boolean by property(true)

    var selectedNodeKey: String? by string(null)

    var showClearCachesDialog: Boolean by property(true)
    var softWrapErrorMessages: Boolean by property(true)

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
internal class KotlinPluginTreeStateService(
    val treeScope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsTreeState>(KotlinPluginsTreeState())

internal class KotlinPluginsTree(
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
            label = "Root",
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
    is ArtifactStatus.PartialSuccess -> AllIcons.RunConfigurations.TestPassedIgnored
    ArtifactStatus.InProgress -> AnimatedIcon.Default()
    is ArtifactStatus.FailedToLoad -> AllIcons.RunConfigurations.TestFailed
    ArtifactStatus.ExceptionInRuntime -> AllIcons.RunConfigurations.TestError
    ArtifactStatus.Disabled -> AllIcons.RunConfigurations.TestSkipped
    ArtifactStatus.Skipped -> AllIcons.RunConfigurations.TestIgnored
}

internal enum class NodeType {
    Plugin, Artifact, Version;
}

@NlsContexts.Label
@NlsContexts.Tooltip
private fun statusToTooltip(type: NodeType, status: ArtifactStatus) = when (type) {
    NodeType.Plugin -> when (status) {
        is ArtifactStatus.Success -> KotlinPluginsBundle.message("tooltip.plugin.success")
        is ArtifactStatus.PartialSuccess -> KotlinPluginsBundle.message("tooltip.plugin.partial")
        ArtifactStatus.InProgress -> KotlinPluginsBundle.message("tooltip.plugin.inProgress")
        is ArtifactStatus.FailedToLoad -> KotlinPluginsBundle.message("tooltip.plugin.failed")
        ArtifactStatus.ExceptionInRuntime -> KotlinPluginsBundle.message("tooltip.plugin.exception")
        ArtifactStatus.Disabled -> KotlinPluginsBundle.message("tooltip.plugin.disabled")
        ArtifactStatus.Skipped -> KotlinPluginsBundle.message("tooltip.plugin.skipped")
    }

    NodeType.Artifact -> when (status) {
        is ArtifactStatus.Success -> KotlinPluginsBundle.message("tooltip.artifact.success")
        is ArtifactStatus.PartialSuccess -> KotlinPluginsBundle.message("tooltip.artifact.partial")
        ArtifactStatus.InProgress -> KotlinPluginsBundle.message("tooltip.artifact.inProgress")
        is ArtifactStatus.FailedToLoad -> KotlinPluginsBundle.message("tooltip.artifact.failed")
        ArtifactStatus.ExceptionInRuntime -> KotlinPluginsBundle.message("tooltip.artifact.exception")
        ArtifactStatus.Disabled -> KotlinPluginsBundle.message("tooltip.artifact.disabled")
        ArtifactStatus.Skipped -> KotlinPluginsBundle.message("tooltip.artifact.skipped")
    }

    NodeType.Version -> when (status) {
        is ArtifactStatus.Success -> {
            if (status.actualVersion == status.requestedVersion) {
                KotlinPluginsBundle.message("tooltip.version.success.exact")
            } else {
                KotlinPluginsBundle.message(
                    "tooltip.version.success.mismatch",
                    status.requestedVersion,
                    status.actualVersion,
                    status.criteria,
                )
            }
        }

        is ArtifactStatus.PartialSuccess -> KotlinPluginsBundle.message("tooltip.version.partial")
        ArtifactStatus.InProgress -> KotlinPluginsBundle.message("tooltip.version.inProgress")
        is ArtifactStatus.FailedToLoad -> KotlinPluginsBundle.message("tooltip.version.failed", status.shortMessage)
        ArtifactStatus.ExceptionInRuntime -> KotlinPluginsBundle.message("tooltip.version.exception")
        ArtifactStatus.Disabled -> KotlinPluginsBundle.message("tooltip.version.disabled")
        ArtifactStatus.Skipped -> KotlinPluginsBundle.message("tooltip.version.skipped")
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

    if (children.any { it.status == ArtifactStatus.PartialSuccess }) {
        return ArtifactStatus.PartialSuccess
    }

    return ArtifactStatus.Skipped
}

@NlsContexts.Label
private fun KotlinPluginDescriptor.VersionMatching.toUi(): String {
    return when (this) {
        KotlinPluginDescriptor.VersionMatching.EXACT -> KotlinPluginsBundle.message("version.matching.exact")
        KotlinPluginDescriptor.VersionMatching.SAME_MAJOR -> KotlinPluginsBundle.message("version.matching.sameMajor")
        KotlinPluginDescriptor.VersionMatching.LATEST -> KotlinPluginsBundle.message("version.matching.latest")
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

@Suppress("FunctionName")
private fun GrayedLabel(@NlsContexts.Label text: String) = JBLabel(text).apply {
    foreground = JBUI.CurrentTheme.Label.disabledForeground()
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

private val MonospacedFont by lazy {
    val attributes = mutableMapOf<TextAttribute, Any?>()

    attributes[TextAttribute.FAMILY] = "Monospaced"
    attributes[TextAttribute.WEIGHT] = TextAttribute.WEIGHT_REGULAR
    attributes[TextAttribute.WIDTH] = TextAttribute.WIDTH_REGULAR

    JBFont.create(Font.getFont(attributes)).deriveFont(Font.PLAIN, 13.0f)
}

private val NodeType.displayLowerCaseName
    get() = when (this) {
        NodeType.Plugin -> KotlinPluginsBundle.message("nodeType.plugin")
        NodeType.Artifact -> KotlinPluginsBundle.message("nodeType.artifact")
        NodeType.Version -> KotlinPluginsBundle.message("nodeType.version")
    }

internal val Int.scaled get() = JBUI.scale(this)

@NlsContexts.DetailedDescription
private fun ExceptionsReport.hint(): String {
    val analysis = when {
        kotlinVersionMismatch != null && isProbablyIncompatible ->
            KotlinPluginsBundle.message("exceptions.analysis.header") +
                    KotlinPluginsBundle.message(
                        "exceptions.analysis.incompatibility.body",
                        kotlinVersionMismatch.jarVersion,
                        kotlinVersionMismatch.ideVersion,
                    )

        isProbablyIncompatible -> {
            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
            KotlinPluginsBundle.message("exceptions.analysis.header") +
                    KotlinPluginsBundle.message(
                        "exceptions.analysis.probablyIncompatible.body",
                        kotlinIdeVersion,
                    )
        }

        else -> ""
    }

    val action = when {
        reloadedSame && isLocal -> KotlinPluginsBundle.message("exceptions.action.header") +
                KotlinPluginsBundle.message("exceptions.action.local.reloaded.body")

        isLocal -> KotlinPluginsBundle.message("exceptions.action.header") +
                KotlinPluginsBundle.message("exceptions.action.local.body")

        reloadedSame -> KotlinPluginsBundle.message("exceptions.action.header") +
                KotlinPluginsBundle.message("exceptions.action.remote.reloaded.body")

        kotlinVersionMismatch != null -> KotlinPluginsBundle.message("exceptions.action.header") +
                KotlinPluginsBundle.message("exceptions.action.kotlinVersionMismatch.body")

        else -> KotlinPluginsBundle.message("exceptions.action.header") +
                KotlinPluginsBundle.message("exceptions.action.default.body")
    }

    return """
        |<html>
        |$analysis
        |$action
        |</html>
    """.trimMargin()
}
