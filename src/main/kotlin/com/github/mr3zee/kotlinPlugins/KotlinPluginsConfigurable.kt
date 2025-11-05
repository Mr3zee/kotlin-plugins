package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.TableRowSorter

private class LocalState {
    val repositories: MutableList<KotlinArtifactsRepository> = mutableListOf()
    val plugins: MutableList<KotlinPluginDescriptor> = mutableListOf()
    val pluginsEnabled: MutableMap<String, Boolean> = mutableMapOf()
    var showCacheClearConfirmationDialog: Boolean = true
    var exceptionAnalyzerEnabled: Boolean = false
    var autoDiablePlugins: Boolean = false

    fun isModified(
        analyzer: KotlinPluginsExceptionAnalyzerState,
        tree: KotlinPluginsTreeState,
        settings: KotlinPluginsSettings.State,
    ): Boolean {
        return repositories != settings.repositories ||
                plugins != settings.plugins ||
                pluginsEnabled != settings.pluginsEnabled ||
                exceptionAnalyzerEnabled != analyzer.enabled ||
                showCacheClearConfirmationDialog != tree.showClearCachesDialog ||
                autoDiablePlugins != analyzer.autoDisable
    }

    fun reset(
        analyzer: KotlinPluginsExceptionAnalyzerState,
        tree: KotlinPluginsTreeState,
        settings: KotlinPluginsSettings.State,
    ) {
        repositories.clear()
        repositories.addAll(settings.repositories)

        plugins.clear()
        plugins.addAll(settings.plugins)

        pluginsEnabled.clear()
        pluginsEnabled.putAll(settings.pluginsEnabled)

        exceptionAnalyzerEnabled = analyzer.enabled
        showCacheClearConfirmationDialog = tree.showClearCachesDialog
        autoDiablePlugins = analyzer.autoDisable
    }

    fun applyTo(
        analyzer: KotlinPluginsExceptionAnalyzerService,
        tree: KotlinPluginsTreeState,
        settings: KotlinPluginsSettings,
    ) {
        val enabledPlugins = plugins.map {
            it.copy(enabled = pluginsEnabled[it.name] ?: it.enabled)
        }

        settings.updateToNewState(repositories, enabledPlugins)

        analyzer.updateState(exceptionAnalyzerEnabled, autoDiablePlugins)
        tree.showClearCachesDialog = showCacheClearConfirmationDialog
    }

    private val KotlinPluginsSettings.State.pluginsEnabled: Map<String, Boolean>
        get() = plugins.associate { it.name to it.enabled }
}

internal class KotlinPluginsConfigurable(private val project: Project) : Configurable {
    private val local: LocalState = LocalState()

    private lateinit var repoTable: JBTable
    private lateinit var pluginsTable: JBTable
    private lateinit var repoModel: ListTableModel<KotlinArtifactsRepository>
    private lateinit var pluginsModel: ListTableModel<KotlinPluginDescriptor>

    private lateinit var clearCachesCheckBox: JBCheckBox
    private lateinit var enableAnalyzerCheckBox: JBCheckBox
    private lateinit var autoDisablePluginsCheckBox: JBCheckBox

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = KotlinPluginsBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        if (rootPanel != null) return rootPanel as JPanel

        clearCachesCheckBox = JBCheckBox(
            KotlinPluginsBundle.message("settings.clearCaches.showDialog"),
            local.showCacheClearConfirmationDialog,
        ).apply {
            addItemListener { local.showCacheClearConfirmationDialog = isSelected }
        }

        enableAnalyzerCheckBox = JBCheckBox(
            KotlinPluginsBundle.message("settings.enableAnalyzer"),
            local.exceptionAnalyzerEnabled,
        ).apply {
            addItemListener {
                local.exceptionAnalyzerEnabled = isSelected
                autoDisablePluginsCheckBox.isEnabled = isSelected
            }
        }

        autoDisablePluginsCheckBox = JBCheckBox(
            KotlinPluginsBundle.message("settings.autoDisable"),
            local.autoDiablePlugins,
        ).apply {
            isEnabled = enableAnalyzerCheckBox.isSelected
            addItemListener { local.autoDiablePlugins = isSelected }
        }

        // Tables and models
        repoModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>(
                KotlinPluginsBundle.message("table.repositories.column.name")
            ) {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>(
                KotlinPluginsBundle.message("table.repositories.column.value")
            ) {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.value
            }
        )

        repoTable = JBTable(repoModel).apply {
            columnModel.getColumn(0).preferredWidth = 100.scaled
            columnModel.getColumn(1).preferredWidth = 300.scaled
            emptyText.text = KotlinPluginsBundle.message("table.repositories.empty")
            rowSorter = TableRowSorter(repoModel).apply {
                setSortable(0, true)
                setSortable(1, false)
            }
        }

        pluginsModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, Boolean>("") {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java

                override fun valueOf(item: KotlinPluginDescriptor): Boolean =
                    local.pluginsEnabled[item.name] ?: item.enabled

                override fun isCellEditable(item: KotlinPluginDescriptor?): Boolean = true

                override fun setValue(item: KotlinPluginDescriptor, value: Boolean) {
                    local.pluginsEnabled[item.name] = value
                }
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KotlinPluginsBundle.message("table.plugins.column.name")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KotlinPluginsBundle.message("table.plugins.column.coordinates")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.ids.joinToString("<br/>", prefix = "<html>", postfix = "</html>") { it.id }
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KotlinPluginsBundle.message("table.plugins.column.versions")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    PluginsDialog.versionMatchingMapReversed.getValue(item.versionMatching)
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KotlinPluginsBundle.message("table.plugins.column.repositories")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.repositories.joinToString("<br/>", prefix = "<html>", postfix = "</html>") { it.name }
            }
        )

        pluginsTable = JBTable(pluginsModel).apply {
            columnModel.getColumn(0).preferredWidth = 20.scaled
            columnModel.getColumn(1).preferredWidth = 100.scaled
            columnModel.getColumn(2).preferredWidth = 300.scaled
            columnModel.getColumn(3).preferredWidth = 80.scaled
            columnModel.getColumn(4).preferredWidth = 150.scaled
            emptyText.text = KotlinPluginsBundle.message("table.plugins.empty")
            rowSorter = TableRowSorter(pluginsModel).apply {
                setSortable(0, false)
                setSortable(1, true)
                setSortable(2, true)
                setSortable(3, false)
                setSortable(4, false)
            }

            preferredScrollableViewportSize = Dimension(preferredScrollableViewportSize.width, rowHeight * 8)
        }

        val repoPanel = ToolbarDecorator.createDecorator(repoTable)
            .setAddAction { onAddRepository() }
            .setEditAction { onEditRepository() }
            .setRemoveAction { onRemoveRepository() }
            .setRemoveActionUpdater {
                repoTable.selectedRows.none {
                    val modelIndex = repoTable.convertRowIndexToModel(it)
                    repoModel.getItem(modelIndex) !in DefaultState.repositories
                }
            }
            .createPanel()

        val pluginsPanel = ToolbarDecorator.createDecorator(pluginsTable)
            .setAddAction { onAddPlugin() }
            .setEditAction { onEditPlugin() }
            .setRemoveAction { onRemovePlugin() }
            .setRemoveActionUpdater {
                pluginsTable.selectedRows.none {
                    val modelIndex = pluginsTable.convertRowIndexToModel(it)
                    pluginsModel.getItem(modelIndex) !in DefaultState.plugins
                }
            }
            .createPanel()

        val generalContent = JPanel(BorderLayout())

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val generalForm = panel {
            row {
                val copyKotlinAction = object : AnAction(AllIcons.Actions.Copy) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val selection = StringSelection(kotlinIdeVersion)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)

                        JBPopupFactory.getInstance()
                            .createBalloonBuilder(JBLabel(KotlinPluginsBundle.message("settings.version.copied")))
                            .createBalloon()
                            .show(
                                RelativePoint.getSouthOf(e.inputEvent?.component as JComponent),
                                Balloon.Position.below
                            )
                    }
                }

                actionButton(copyKotlinAction)
                    .label(KotlinPluginsBundle.message("settings.kotlin.ide.version", kotlinIdeVersion))
            }.rowComment(KotlinPluginsBundle.message("settings.kotlin.ide.version.comment"))

            group(KotlinPluginsBundle.message("group.exception.analyzer")) {
                row {
                    cell(enableAnalyzerCheckBox)
                        .comment(KotlinPluginsBundle.message("settings.enableAnalyzer.comment"))

                    cell(
                        ContextHelpLabel.createWithBrowserLink(
                            null,
                            KotlinPluginsBundle.message("settings.enableAnalyzer.contextHelp.description"),
                            KotlinPluginsBundle.message("settings.enableAnalyzer.contextHelp.linkText"),
                            URI("https://github.com/Mr3zee/kotlin-plugins/blob/main/PLUGIN_AUTHORS.md").toURL(),
                        )
                    )
                }

                indent {
                    row {
                        cell(autoDisablePluginsCheckBox)
                            .comment(KotlinPluginsBundle.message("settings.autoDisable.comment"))
                    }
                }
            }

            group(KotlinPluginsBundle.message("group.other")) {
                row {
                    cell(clearCachesCheckBox)
                }
            }
        }

        generalContent.add(generalForm, BorderLayout.NORTH)

        val artifactsForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                /* label = */
                JBLabel(
                    KotlinPluginsBundle.message("label.maven.repositories"),
                    AllIcons.Nodes.Folder,
                    SwingConstants.LEADING,
                ),
                /* component = */ repoPanel,
                /* topInset = */ 5,
                /* labelOnTop = */ true,
            )
            .addLabeledComponent(
                /* label = */
                JBLabel(
                    KotlinPluginsBundle.message("label.kotlin.plugins"),
                    AllIcons.Nodes.Plugin,
                    SwingConstants.LEADING,
                ),
                /* component = */ pluginsPanel,
                /* topInset = */ 5,
                /* labelOnTop = */ true,
            )
            .panel

        val artifactsContent = panel {
            row {
                comment(KotlinPluginsBundle.message("settings.repositories.comment"))
            }

            separator()

            row {
                cell(artifactsForm)
                    .align(AlignX.FILL)
            }
        }

        val tabs = JBTabbedPane()
        tabs.addTab(KotlinPluginsBundle.message("tab.general"), generalContent)
        tabs.addTab(KotlinPluginsBundle.message("tab.artifacts"), artifactsContent)
        if (KotlinPluginsConfigurableUtil.selectArtifactsInitially) {
            tabs.selectedIndex = 1
            KotlinPluginsConfigurableUtil.selectArtifactsInitially = false
        }
        rootPanel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.NORTH) }

        reset() // initialise from a persisted state
        return rootPanel as JPanel
    }

    override fun isModified(): Boolean {
        val settingState = project.service<KotlinPluginsSettings>().safeState()
        val analyzerState = project.service<KotlinPluginsExceptionAnalyzerService>().state
        val treeState = project.service<KotlinPluginTreeStateService>().state

        return local.isModified(analyzerState, treeState, settingState)
    }

    override fun apply() {
        val settings = project.service<KotlinPluginsSettings>()
        val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>()
        val treeState = project.service<KotlinPluginTreeStateService>().state

        local.applyTo(analyzer, treeState, settings)
    }

    override fun reset() {
        val settings = project.service<KotlinPluginsSettings>().safeState()
        val analyzer = project.service<KotlinPluginsExceptionAnalyzerService>().state
        val treeState = project.service<KotlinPluginTreeStateService>().state

        local.reset(analyzer, treeState, settings)

        repoModel.items = ArrayList(local.repositories)
        pluginsModel.items = ArrayList(local.plugins)

        clearCachesCheckBox.isSelected = local.showCacheClearConfirmationDialog
        enableAnalyzerCheckBox.isSelected = local.exceptionAnalyzerEnabled
        autoDisablePluginsCheckBox.isEnabled = enableAnalyzerCheckBox.isSelected
        autoDisablePluginsCheckBox.isSelected = local.autoDiablePlugins
    }

    // region Repository actions
    private fun onAddRepository() {
        val dialog = RepositoryDialog(
            currentNames = local.repositories.map { it.name },
            initial = null,
            project = project,
        )

        if (dialog.showAndGet()) {
            val entry = dialog.getResult() ?: return
            local.repositories.add(entry)
            repoModel.items = ArrayList(local.repositories)
            selectLast(repoTable)
        }
    }

    private fun onEditRepository() {
        val selected = repoTable.selectedObject(repoModel) ?: return
        val idx = repoTable.selectedRow
        val dialog = RepositoryDialog(
            currentNames = local.repositories.map { it.name },
            initial = selected,
            project = project,
        )

        if (dialog.showAndGet()) {
            val updated = dialog.getResult() ?: return
            local.repositories[idx] = updated
            repoModel.items = ArrayList(local.repositories)
            repoTable.selectionModel.setSelectionInterval(idx, idx)
        }
    }

    @Suppress("DuplicatedCode")
    private fun onRemoveRepository() {
        val idx = repoTable.selectedRow
        if (idx >= 0) {
            val repo = local.repositories[idx]
            if (repo.name in DefaultState.repositoryMap) {
                return
            }
            local.repositories.removeAt(idx)
            repoModel.items = ArrayList(local.repositories)
        }
    }
    // endregion

    // region Plugins actions
    private fun onAddPlugin() {
        val dialog = PluginsDialog(
            currentNames = local.plugins.map { it.name },
            availableRepositories = local.repositories,
            initial = null,
            enabledInitial = true,
        )

        if (dialog.showAndGet()) {
            val entry = dialog.getResult() ?: return
            local.plugins.add(entry)
            local.pluginsEnabled[entry.name] = entry.enabled
            pluginsModel.items = ArrayList(local.plugins)
            selectLast(pluginsTable)
        }
    }

    private fun onEditPlugin() {
        val selected = pluginsTable.selectedObject(pluginsModel) ?: return
        val idx = pluginsTable.selectedRow
        val dialog = PluginsDialog(
            currentNames = local.plugins.map { it.name },
            availableRepositories = local.repositories,
            initial = selected,
            enabledInitial = local.pluginsEnabled[selected.name] ?: selected.enabled,
        )

        if (dialog.showAndGet()) {
            val updated = dialog.getResult() ?: return
            local.plugins[idx] = updated
            local.pluginsEnabled[updated.name] = updated.enabled
            pluginsModel.items = ArrayList(local.plugins)
            pluginsTable.selectionModel.setSelectionInterval(idx, idx)
        }
    }

    @Suppress("DuplicatedCode")
    private fun onRemovePlugin() {
        val idx = pluginsTable.selectedRow
        if (idx >= 0) {
            val plugin = local.plugins[idx]
            if (plugin.name in DefaultState.pluginMap) {
                return
            }
            local.plugins.removeAt(idx)
            local.pluginsEnabled.remove(plugin.name)
            pluginsModel.items = ArrayList(local.plugins)
        }
    }
    // endregion

    private fun selectLast(table: JBTable) {
        val last = table.rowCount - 1
        if (last >= 0) {
            table.selectionModel.setSelectionInterval(last, last)
        }
    }

    private fun <T : Any> JBTable.selectedObject(model: ListTableModel<T>): T? {
        val rowIndex = convertRowIndexToModel(selectedRow)
        if (rowIndex == -1) {
            return null
        }
        return model.getItem(rowIndex)
    }
}

private class RepositoryDialog(
    private val currentNames: List<String> = emptyList(),
    private val initial: KotlinArtifactsRepository?,
    private val project: Project,
) : DialogWrapper(true) {
    private val isDefault = initial?.name in DefaultState.repositoryMap

    // UI components built via Kotlin UI DSL only
    private lateinit var nameField: JBTextField
    private lateinit var urlField: JBTextField
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var urlRadio: JBRadioButton
    private lateinit var pathRadio: JBRadioButton
    private lateinit var urlRow: Row
    private lateinit var pathRow: Row

    init {
        title = if (initial == null) {
            KotlinPluginsBundle.message("dialog.repository.title.add")
        } else {
            KotlinPluginsBundle.message("dialog.repository.title.edit")
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = panel {
            // Warning label for default repositories
            row {
                label(KotlinPluginsBundle.message("repository.default.cannot.edit")).applyToComponent {
                    icon = AllIcons.General.Warning
                    foreground = JBUI.CurrentTheme.Label.warningForeground()
                }.align(AlignX.CENTER)
            }.visible(isDefault)

            // Name
            row(KotlinPluginsBundle.message("label.name")) {
                nameField = textField()
                    .applyToComponent {
                        text = initial?.name.orEmpty()
                        emptyText.text = KotlinPluginsBundle.message("emptyText.uniqueName")
                        minimumSize = Dimension(300.scaled, minimumSize.height)
                        toolTipText = if (isDefault) {
                            KotlinPluginsBundle.message("tooltip.defaultRepoNameNotEditable")
                        } else {
                            KotlinPluginsBundle.message("tooltip.nameMustBeUnique")
                        }
                        isEditable = !isDefault
                    }
                    .component
            }

            // Kind (URL vs PATH) radios
            if (!isDefault) {
                buttonsGroup {
                    row(KotlinPluginsBundle.message("label.kind")) {
                        val initialIsUrl = initial?.value?.startsWith("http") ?: true
                        urlRadio = radioButton(KotlinPluginsBundle.message("radio.url"))
                            .applyToComponent {
                                isSelected = initialIsUrl
                            }
                            .component
                        pathRadio = radioButton(KotlinPluginsBundle.message("radio.filePath"))
                            .applyToComponent {
                                isSelected = !initialIsUrl
                            }
                            .component
                    }
                }

                // URL field
                urlRow = row(KotlinPluginsBundle.message("label.url")) {
                    urlField = textField()
                        .applyToComponent {
                            emptyText.text = KotlinPluginsBundle.message("repository.url.emptyText")
                            text = if (initial?.value?.startsWith("http") == true) initial.value else ""

                            isEditable = true
                        }
                        .align(AlignX.FILL)
                        .comment(KotlinPluginsBundle.message("settings.add.repository.comment"))
                        .component
                }

                // Path field
                pathRow = row(KotlinPluginsBundle.message("label.path")) {
                    @Suppress("UnstableApiUsage")
                    pathField = textFieldWithBrowseButton()
                        .applyToComponent {
                            emptyText.text = KotlinPluginsBundle.message("repository.path.emptyText")
                            text = if (initial != null && !initial.value.startsWith("http")) initial.value else ""

                            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))

                            isEditable = true
                        }
                        .align(AlignX.FILL)
                        .comment(KotlinPluginsBundle.message("settings.add.repository.comment"))
                        .component
                }
            } else {
                val isUrlSelected = isUrlSelected()

                row(KotlinPluginsBundle.message("label.url")) {
                    urlField = textField()
                        .applyToComponent {
                            emptyText.text = KotlinPluginsBundle.message("repository.url.emptyText")
                            text = if (isUrlSelected) initial?.value.orEmpty() else ""

                            toolTipText = KotlinPluginsBundle.message("tooltip.defaultRepoUrlNotEditable")
                            isEditable = false
                        }
                        .align(AlignX.FILL)
                        .comment(KotlinPluginsBundle.message("settings.add.repository.comment"))
                        .component
                }.visible(isUrlSelected)

                row(KotlinPluginsBundle.message("label.path")) {
                    @Suppress("UnstableApiUsage")
                    pathField = textFieldWithBrowseButton()
                        .applyToComponent {
                            emptyText.text = KotlinPluginsBundle.message("repository.path.emptyText")
                            text = if (!isUrlSelected) initial?.value.orEmpty() else ""

                            toolTipText = KotlinPluginsBundle.message("tooltip.defaultRepoPathNotEditable")
                            isEditable = false
                        }
                        .align(AlignX.FILL)
                        .comment(KotlinPluginsBundle.message("settings.add.repository.comment"))
                        .component
                }.visible(!isUrlSelected)
            }
        }

        panel.preferredSize = Dimension(650.scaled, 0.scaled)
        // Group radios and set initial visibility
        if (!isDefault) {
            val group = ButtonGroup()
            group.add(urlRadio)
            group.add(pathRadio)
            val toggle = {
                urlRow.visible(urlRadio.isSelected)
                pathRow.visible(pathRadio.isSelected)
            }
            toggle()
            urlRadio.addActionListener { toggle() }
            pathRadio.addActionListener { toggle() }
        }
        return panel
    }

    private fun isUrlSelected(): Boolean = this@RepositoryDialog::urlRadio.isInitialized && urlRadio.isSelected

    @Suppress("DuplicatedCode")
    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.empty"), nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.unique"), nameField)
        }

        if (name.contains(";")) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.noSemicolons"), nameField)
        }

        val isUrlSelected = isUrlSelected()
        if (isUrlSelected) {
            val url = urlField.text.trim()
            if (url.isEmpty()) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.url.empty"), urlField)
            }

            @Suppress("HttpUrlsUsage")
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.url.prefix"), urlField)
            }

            if (url.contains(";")) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.url.noSemicolons"), urlField)
            }
        } else {
            val path = pathField.text.trim()
            if (path.isEmpty()) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.path.empty"), pathField)
            }

            if (path.contains(";")) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.path.noSemicolons"), pathField)
            }
        }

        return null
    }

    fun getResult(): KotlinArtifactsRepository? {
        if (doValidate() != null) {
            return null
        }
        val name = nameField.text.trim()
        val isUrlSelected = isUrlSelected()
        val value = if (isUrlSelected) {
            urlField.text.trim()
        } else {
            pathField.text.trim()
        }
        return KotlinArtifactsRepository(
            name = name,
            value = value,
            type = if (isUrlSelected) KotlinArtifactsRepository.Type.URL else KotlinArtifactsRepository.Type.PATH,
        )
    }
}

private class PluginsDialog(
    private val currentNames: List<String>,
    private val availableRepositories: List<KotlinArtifactsRepository>,
    private val initial: KotlinPluginDescriptor?,
    enabledInitial: Boolean,
) : DialogWrapper(true) {
    private val isDefault = initial?.name in DefaultState.pluginMap

    private val warningLabel = JBLabel(
        KotlinPluginsBundle.message("plugin.default.cannot.edit"),
        AllIcons.General.Warning,
        SwingConstants.LEADING
    ).apply {
        foreground = JBUI.CurrentTheme.Label.warningForeground()
        isVisible = isDefault
        horizontalAlignment = SwingConstants.CENTER
    }

    private val nameField = JBTextField(initial?.name.orEmpty(), 30).apply {
        emptyText.text = KotlinPluginsBundle.message("emptyText.uniqueName")

        minimumSize = Dimension(300.scaled, minimumSize.height)

        toolTipText = if (isDefault) {
            KotlinPluginsBundle.message("tooltip.defaultPluginNameNotEditable")
        } else {
            KotlinPluginsBundle.message("tooltip.nameMustBeUnique")
        }
    }

    private val mutableIds: ArrayList<String> = ArrayList(initial?.ids.orEmpty().map { it.id })

    private val idsModel = ListTableModel<IndexedValue<String>>(
        object : com.intellij.util.ui.ColumnInfo<IndexedValue<String>, String>("") {
            override fun valueOf(item: IndexedValue<String>): String = mutableIds[item.index]
            override fun isCellEditable(item: IndexedValue<String>?): Boolean = !isDefault
            override fun setValue(item: IndexedValue<String>, value: String) {
                mutableIds[item.index] = value
            }
        },
    ).apply {
        items = mutableIds.withIndex().toList()
    }

    private val idsTable = JBTable().apply {
        emptyText.text = KotlinPluginsBundle.message("idsTable.empty")

        model = idsModel

        minimumSize = Dimension(600.scaled, minimumSize.height)
        toolTipText = if (isDefault) {
            KotlinPluginsBundle.message("tooltip.defaultPluginCoordinatesNotEditable")
        } else {
            KotlinPluginsBundle.message("tooltip.coordinatesFormat")
        }
    }

    val tablePanel = ToolbarDecorator.createDecorator(idsTable).apply {
        setAddActionUpdater { !isDefault }
        setRemoveActionUpdater { !isDefault }
        setMoveUpAction(null)
        setMoveDownAction(null)

        setAddAction {
            if (isDefault) {
                return@setAddAction
            }

            mutableIds.add("")
            idsModel.items = mutableIds.withIndex().toList()
        }

        setRemoveAction {
            if (isDefault) {
                return@setRemoveAction
            }

            mutableIds.removeAt(idsTable.selectedRow)
            idsModel.items = mutableIds.withIndex().toList()
        }
    }.createPanel()

    private val versionMatchingFieldModel = DefaultComboBoxModel(versionMatchingMap.keys.toTypedArray()).apply {
        val value = initial?.versionMatching ?: KotlinPluginDescriptor.VersionMatching.EXACT
        versionMatchingMapReversed[value]?.let { selectedItem = it }
    }

    private val versionMatchingField = panel {
        row {
            cell(ComboBox<String>().apply {
                model = versionMatchingFieldModel
            })
            cell(
                ContextHelpLabel.createWithBrowserLink(
                    null,
                    KotlinPluginsBundle.message("settings.add.plugin.version.matching.help"),
                    KotlinPluginsBundle.message("settings.add.plugin.version.matching.help.link"),
                    URI("https://github.com/Mr3zee/kotlin-plugins/blob/main/GUIDE.md").toURL(),
                )
            )
        }
    }

    private val enabledCheckbox =
        JBCheckBox(KotlinPluginsBundle.message("checkbox.enablePluginInProject"), enabledInitial)
    private val ignoreExceptionsCheckbox =
        JBCheckBox(KotlinPluginsBundle.message("checkbox.ignorePluginExceptions"), initial?.ignoreExceptions ?: false)

    private val repoCheckboxes: List<JBCheckBox> = availableRepositories.map { repo ->
        JBCheckBox(repo.name, initial?.repositories?.any { it.name == repo.name } == true).apply {
            toolTipText = repo.value
            if (isDefault) {
                toolTipText += " (" + KotlinPluginsBundle.message("tooltip.defaultPluginRepoRefNotEditable") + ")"
            }
            isEnabled = !isDefault || DefaultState.pluginMap[initial?.name]!!.repositories.none { it.name == repo.name }
        }
    }

    private val reposContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        repoCheckboxes.forEach { add(it) }
    }

    init {
        title = if (initial == null) {
            KotlinPluginsBundle.message("dialog.plugin.title.add")
        } else {
            KotlinPluginsBundle.message("dialog.plugin.title.edit")
        }

        nameField.isEditable = !isDefault

        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent {
        val reposPanel = JBScrollPane(reposContainer).apply {
            minimumSize = Dimension(300.scaled, 120.scaled)
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(warningLabel)
            .addLabeledComponent(JBLabel(KotlinPluginsBundle.message("label.name")), nameField)
            .addLabeledComponent(JBLabel(KotlinPluginsBundle.message("label.coordinates")), tablePanel)
            .addLabeledComponent(JBLabel(KotlinPluginsBundle.message("label.version.matching")), versionMatchingField)
            .addLabeledComponent(JBLabel(KotlinPluginsBundle.message("label.repositories")), reposPanel, 10.scaled)
            .addComponent(enabledCheckbox)
            .addComponent(ignoreExceptionsCheckbox)
            .panel

        form.preferredSize = Dimension(650.scaled, 0.scaled)
        return form
    }

    @Suppress("DuplicatedCode")
    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.empty"), nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.unique"), nameField)
        }

        if (!pluginNameRegex.matches(name)) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.name.regex", pluginNameRegex.pattern))
        }

        if (mutableIds.isEmpty()) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.maven.atLeastOne"))
        }

        mutableIds.forEach { id ->
            if (!mavenRegex.matches(id)) {
                return ValidationInfo(KotlinPluginsBundle.message("validation.coordinates.format"))
            }
        }

        if (repoCheckboxes.none { it.isSelected }) {
            return ValidationInfo(KotlinPluginsBundle.message("validation.select.repository"))
        }

        return null
    }

    companion object {
        private val versionMatchingMap = mapOf(
            KotlinPluginsBundle.message("version.matching.exact") to KotlinPluginDescriptor.VersionMatching.EXACT,
            KotlinPluginsBundle.message("version.matching.sameMajor") to KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            KotlinPluginsBundle.message("version.matching.latest") to KotlinPluginDescriptor.VersionMatching.LATEST,
        )

        val versionMatchingMapReversed = versionMatchingMap.entries
            .associateBy({ it.value }) { it.key }
    }

    fun getResult(): KotlinPluginDescriptor? {
        if (doValidate() != null) {
            return null
        }

        val selectedRepos = availableRepositories.zip(repoCheckboxes)
            .filter { (_, box) -> box.isSelected }
            .map { (repo, _) -> repo }

        return KotlinPluginDescriptor(
            name = nameField.text.trim(),
            ids = mutableIds.map { MavenId(it) },
            versionMatching = versionMatchingMap.getValue(versionMatchingFieldModel.selectedItem as String),
            enabled = enabledCheckbox.isSelected,
            ignoreExceptions = ignoreExceptionsCheckbox.isSelected,
            repositories = selectedRepos,
        )
    }
}

internal val mavenRegex = "([\\w.]+):([\\w\\-]+)".toRegex()
internal val pluginNameRegex = "[a-zA-Z0-9_-]+".toRegex()

internal object KotlinPluginsConfigurableUtil {
    @Volatile
    var selectArtifactsInitially: Boolean = false

    fun showArtifacts(project: Project) {
        selectArtifactsInitially = true
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KotlinPluginsConfigurable::class.java)
    }

    fun showGeneral(project: Project) {
        selectArtifactsInitially = false
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KotlinPluginsConfigurable::class.java)
    }
}
