package com.github.mr3zee.kefs

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
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
    var autoUpdateEnabled: Boolean = true
    var autoUpdateInterval: Int = 20

    fun isModified(
        analyzer: KefsExceptionAnalyzerState,
        tree: KefsTreeState,
        settings: KefsSettings.State,
        storage: KefsStorageState,
    ): Boolean {
        return repositories != settings.repositories ||
                plugins != settings.plugins ||
                pluginsEnabled != settings.pluginsEnabled ||
                exceptionAnalyzerEnabled != analyzer.enabled ||
                showCacheClearConfirmationDialog != tree.showClearCachesDialog ||
                autoDiablePlugins != analyzer.autoDisable ||
                autoUpdateEnabled != storage.autoUpdate ||
                autoUpdateInterval != storage.updateInterval
    }

    fun reset(
        analyzer: KefsExceptionAnalyzerState,
        tree: KefsTreeState,
        settings: KefsSettings.State,
        storage: KefsStorageState,
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
        autoUpdateEnabled = storage.autoUpdate
        autoUpdateInterval = storage.updateInterval
    }

    fun applyTo(
        analyzer: KefsExceptionAnalyzerService,
        tree: KefsTreeState,
        settings: KefsSettings,
        storage: KefsStorage,
    ) {
        val enabledPlugins = plugins.map {
            it.copy(enabled = pluginsEnabled[it.name] ?: it.enabled)
        }

        settings.updateToNewState(repositories, enabledPlugins)

        analyzer.updateState(exceptionAnalyzerEnabled, autoDiablePlugins)
        tree.showClearCachesDialog = showCacheClearConfirmationDialog
        storage.updateState(autoUpdateEnabled, autoUpdateInterval)
    }

    private val KefsSettings.State.pluginsEnabled: Map<String, Boolean>
        get() = plugins.associate { it.name to it.enabled }
}

internal class KefsConfigurable(private val project: Project) : Configurable {
    private val local: LocalState = LocalState()

    private lateinit var repoTable: JBTable
    private lateinit var pluginsTable: JBTable
    private lateinit var repoModel: ListTableModel<KotlinArtifactsRepository>
    private lateinit var pluginsModel: ListTableModel<KotlinPluginDescriptor>

    private lateinit var clearCachesCheckBox: JBCheckBox
    private lateinit var enableAnalyzerCheckBox: JBCheckBox
    private lateinit var autoDisablePluginsCheckBox: JBCheckBox

    private lateinit var autoUpdateCheckBox: JBCheckBox
    private lateinit var autoUpdateInterval: JBIntSpinner

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = KefsBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        if (rootPanel != null) return rootPanel as JPanel

        clearCachesCheckBox = JBCheckBox(
            KefsBundle.message("settings.clearCaches.showDialog"),
            local.showCacheClearConfirmationDialog,
        ).apply {
            addItemListener { local.showCacheClearConfirmationDialog = isSelected }
        }

        enableAnalyzerCheckBox = JBCheckBox(
            KefsBundle.message("settings.enableAnalyzer"),
            local.exceptionAnalyzerEnabled,
        ).apply {
            addItemListener {
                local.exceptionAnalyzerEnabled = isSelected
                autoDisablePluginsCheckBox.isEnabled = isSelected
            }
        }

        autoDisablePluginsCheckBox = JBCheckBox(
            KefsBundle.message("settings.autoDisable"),
            local.autoDiablePlugins,
        ).apply {
            isEnabled = enableAnalyzerCheckBox.isSelected
            addItemListener { local.autoDiablePlugins = isSelected }
        }

        // Tables and models
        repoModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>(
                KefsBundle.message("table.repositories.column.name")
            ) {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>(
                KefsBundle.message("table.repositories.column.value")
            ) {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.value
            }
        )

        repoTable = JBTable(repoModel).apply {
            columnModel.getColumn(0).preferredWidth = 100.scaled
            columnModel.getColumn(1).preferredWidth = 300.scaled
            emptyText.text = KefsBundle.message("table.repositories.empty")
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
                KefsBundle.message("table.plugins.column.name")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KefsBundle.message("table.plugins.column.coordinates")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.ids.joinToString("<br/>", prefix = "<html>", postfix = "</html>") { it.id }
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KefsBundle.message("table.plugins.column.versions")
            ) {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    PluginsDialog.versionMatchingMapReversed.getValue(item.versionMatching)
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>(
                KefsBundle.message("table.plugins.column.repositories")
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
            emptyText.text = KefsBundle.message("table.plugins.empty")
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
                            .createBalloonBuilder(JBLabel(KefsBundle.message("settings.version.copied")))
                            .createBalloon()
                            .show(
                                RelativePoint.getSouthOf(e.inputEvent?.component as JComponent),
                                Balloon.Position.below
                            )
                    }
                }

                actionButton(copyKotlinAction)
                    .label(KefsBundle.message("settings.kotlin.ide.version", kotlinIdeVersion))
            }.rowComment(KefsBundle.message("settings.kotlin.ide.version.comment"))

            group(KefsBundle.message("group.exception.analyzer")) {
                row {
                    cell(enableAnalyzerCheckBox)
                        .comment(KefsBundle.message("settings.enableAnalyzer.comment"))

                    cell(
                        ContextHelpLabel.createWithBrowserLink(
                            null,
                            KefsBundle.message("settings.enableAnalyzer.contextHelp.description"),
                            KefsBundle.message("settings.enableAnalyzer.contextHelp.linkText"),
                            URI("https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/PLUGIN_AUTHORS.md").toURL(),
                        )
                    )
                }

                indent {
                    row {
                        cell(autoDisablePluginsCheckBox)
                            .comment(KefsBundle.message("settings.autoDisable.comment"))
                    }
                }
            }

            group(KefsBundle.message("group.other")) {
                row {
                    autoUpdateCheckBox = checkBox(KefsBundle.message("settings.enable.auto.updates"))
                        .comment(KefsBundle.message("settings.enable.auto.updates.comment"))
                        .applyToComponent {
                            isSelected = local.autoUpdateEnabled
                            addItemListener { local.autoUpdateEnabled = isSelected }
                        }
                        .component
                }

                indent {
                    row {
                        val range = 1..120
                        autoUpdateInterval = spinner(range)
                            .label(KefsBundle.message("settings.auto.update.interval"))
                            .comment(
                                KefsBundle.message(
                                    "settings.auto.update.interval.comment",
                                    range.first,
                                    range.last
                                )
                            )
                            .enabledIf(autoUpdateCheckBox.selected)
                            .applyToComponent {
                                number = local.autoUpdateInterval
                                addChangeListener {
                                    local.autoUpdateInterval = number
                                }
                            }
                            .component
                    }
                }

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
                    KefsBundle.message("label.maven.repositories"),
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
                    KefsBundle.message("label.kotlin.plugins"),
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
                comment(KefsBundle.message("settings.repositories.comment"))
            }

            separator()

            row {
                cell(artifactsForm)
                    .align(AlignX.FILL)
            }
        }

        val tabs = JBTabbedPane()
        tabs.addTab(KefsBundle.message("tab.general"), generalContent)
        tabs.addTab(KefsBundle.message("tab.artifacts"), artifactsContent)
        if (KefsConfigurableUtil.selectArtifactsInitially) {
            tabs.selectedIndex = 1
            KefsConfigurableUtil.selectArtifactsInitially = false
        }
        rootPanel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.NORTH) }

        reset() // initialise from a persisted state
        return rootPanel as JPanel
    }

    override fun isModified(): Boolean {
        val settingState = project.service<KefsSettings>().safeState()
        val analyzerState = project.service<KefsExceptionAnalyzerService>().state
        val treeState = project.service<KefsTreeStateService>().state
        val storageState = project.service<KefsStorage>().state

        return local.isModified(analyzerState, treeState, settingState, storageState)
    }

    override fun apply() {
        val settings = project.service<KefsSettings>()
        val analyzer = project.service<KefsExceptionAnalyzerService>()
        val treeState = project.service<KefsTreeStateService>().state
        val storage = project.service<KefsStorage>()

        local.applyTo(analyzer, treeState, settings, storage)
    }

    override fun reset() {
        val settings = project.service<KefsSettings>().safeState()
        val analyzer = project.service<KefsExceptionAnalyzerService>().state
        val treeState = project.service<KefsTreeStateService>().state
        val storage = project.service<KefsStorage>().state

        local.reset(analyzer, treeState, settings, storage)

        repoModel.items = ArrayList(local.repositories)
        pluginsModel.items = ArrayList(local.plugins)

        clearCachesCheckBox.isSelected = local.showCacheClearConfirmationDialog
        enableAnalyzerCheckBox.isSelected = local.exceptionAnalyzerEnabled
        autoDisablePluginsCheckBox.isEnabled = enableAnalyzerCheckBox.isSelected
        autoDisablePluginsCheckBox.isSelected = local.autoDiablePlugins
        autoUpdateCheckBox.isSelected = local.autoUpdateEnabled
        autoUpdateInterval.number = local.autoUpdateInterval
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
            project = project,
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
            project = project,
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
            KefsBundle.message("dialog.repository.title.add")
        } else {
            KefsBundle.message("dialog.repository.title.edit")
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = panel {
            // Warning label for default repositories
            row {
                label(KefsBundle.message("repository.default.cannot.edit")).applyToComponent {
                    icon = AllIcons.General.Warning
                    foreground = JBUI.CurrentTheme.Label.warningForeground()
                }.align(AlignX.CENTER)
            }.visible(isDefault)

            // Name
            row(KefsBundle.message("label.name")) {
                nameField = textField()
                    .applyToComponent {
                        text = initial?.name.orEmpty()
                        emptyText.text = KefsBundle.message("emptyText.uniqueName")
                        minimumSize = Dimension(300.scaled, minimumSize.height)
                        toolTipText = if (isDefault) {
                            KefsBundle.message("tooltip.defaultRepoNameNotEditable")
                        } else {
                            KefsBundle.message("tooltip.nameMustBeUnique")
                        }
                        isEditable = !isDefault
                    }
                    .component
            }

            // Kind (URL vs PATH) radios
            if (!isDefault) {
                buttonsGroup {
                    row(KefsBundle.message("label.kind")) {
                        val initialIsUrl = initial?.value?.startsWith("http") ?: true
                        urlRadio = radioButton(KefsBundle.message("radio.url"))
                            .applyToComponent {
                                isSelected = initialIsUrl
                            }
                            .component
                        pathRadio = radioButton(KefsBundle.message("radio.filePath"))
                            .applyToComponent {
                                isSelected = !initialIsUrl
                            }
                            .component
                    }
                }

                // URL field
                urlRow = row(KefsBundle.message("label.url")) {
                    urlField = textField()
                        .applyToComponent {
                            emptyText.text = KefsBundle.message("repository.url.emptyText")
                            text = if (initial?.value?.startsWith("http") == true) initial.value else ""

                            isEditable = true
                        }
                        .align(AlignX.FILL)
                        .comment(KefsBundle.message("settings.add.repository.comment"))
                        .component
                }

                // Path field
                pathRow = row(KefsBundle.message("label.path")) {
                    @Suppress("UnstableApiUsage")
                    pathField = textFieldWithBrowseButton()
                        .applyToComponent {
                            emptyText.text = KefsBundle.message("repository.path.emptyText")
                            text = if (initial != null && !initial.value.startsWith("http")) initial.value else ""

                            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))

                            isEditable = true
                        }
                        .align(AlignX.FILL)
                        .comment(KefsBundle.message("settings.add.repository.comment"))
                        .component
                }
            } else {
                val isUrlSelected = isUrlSelected()

                row(KefsBundle.message("label.url")) {
                    urlField = textField()
                        .applyToComponent {
                            emptyText.text = KefsBundle.message("repository.url.emptyText")
                            text = if (isUrlSelected) initial?.value.orEmpty() else ""

                            toolTipText = KefsBundle.message("tooltip.defaultRepoUrlNotEditable")
                            isEditable = false
                        }
                        .align(AlignX.FILL)
                        .comment(KefsBundle.message("settings.add.repository.comment"))
                        .component
                }.visible(isUrlSelected)

                row(KefsBundle.message("label.path")) {
                    @Suppress("UnstableApiUsage")
                    pathField = textFieldWithBrowseButton()
                        .applyToComponent {
                            emptyText.text = KefsBundle.message("repository.path.emptyText")
                            text = if (!isUrlSelected) initial?.value.orEmpty() else ""

                            toolTipText = KefsBundle.message("tooltip.defaultRepoPathNotEditable")
                            isEditable = false
                        }
                        .align(AlignX.FILL)
                        .comment(KefsBundle.message("settings.add.repository.comment"))
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

    override fun continuousValidation(): Boolean {
        return true
    }

    @Suppress("DuplicatedCode")
    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo(KefsBundle.message("validation.name.empty"), nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo(KefsBundle.message("validation.name.unique"), nameField)
        }

        if (name.contains(";")) {
            return ValidationInfo(KefsBundle.message("validation.name.noSemicolons"), nameField)
        }

        val isUrlSelected = isUrlSelected()
        if (isUrlSelected) {
            val url = urlField.text.trim()
            if (url.isEmpty()) {
                return ValidationInfo(KefsBundle.message("validation.url.empty"), urlField)
            }

            @Suppress("HttpUrlsUsage")
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                return ValidationInfo(KefsBundle.message("validation.url.prefix"), urlField)
            }

            if (url.contains(";")) {
                return ValidationInfo(KefsBundle.message("validation.url.noSemicolons"), urlField)
            }
        } else {
            val path = pathField.text.trim()
            if (path.isEmpty()) {
                return ValidationInfo(KefsBundle.message("validation.path.empty"), pathField)
            }

            if (path.contains(";")) {
                return ValidationInfo(KefsBundle.message("validation.path.noSemicolons"), pathField)
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
    private val project: Project,
    private val currentNames: List<String>,
    private val availableRepositories: List<KotlinArtifactsRepository>,
    private val initial: KotlinPluginDescriptor?,
    enabledInitial: Boolean,
) : DialogWrapper(true) {
    private val isDefault = initial?.name in DefaultState.pluginMap

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
        emptyText.text = KefsBundle.message("idsTable.empty")

        model = idsModel

        minimumSize = Dimension(800.scaled, minimumSize.height)
        toolTipText = if (isDefault) {
            KefsBundle.message("tooltip.defaultPluginCoordinatesNotEditable")
        } else {
            KefsBundle.message("tooltip.coordinatesFormat")
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
            val lastRow = idsTable.rowCount - 1
            if (lastRow >= 0) {
                idsTable.editCellAt(lastRow, 0)
            }
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

    private lateinit var nameField: JBTextField
    private lateinit var useReplacementCheckbox: JBCheckBox
    private lateinit var replacementVersionField: TextFieldWithAutoCompletion<String>
    private lateinit var replacementDetectField: TextFieldWithAutoCompletion<String>
    private lateinit var replacementSearchField: TextFieldWithAutoCompletion<String>

    private val versionReplacementIsValid by lazy {
        AtomicBooleanProperty(showVersionReplacementExample())
    }

    private val detectReplacementIsValid by lazy {
        AtomicBooleanProperty(showDetectReplacementExample())
    }

    private val searchReplacementIsValid by lazy {
        AtomicBooleanProperty(showSearchReplacementExample())
    }

    // DSL state
    private var enableInProject: Boolean = enabledInitial
    private var ignoreExceptions: Boolean = initial?.ignoreExceptions ?: false

    private val selectedRepoNames: MutableSet<String> = initial?.repositories?.map { it.name }?.toMutableSet()
        ?: mutableSetOf()

    private val defaultRepoNames: Set<String> = if (isDefault && initial != null) {
        DefaultState.pluginMap[initial.name]?.repositories?.map { it.name }?.toSet()
            ?: emptySet()
    } else {
        emptySet()
    }

    init {
        title = if (initial == null) {
            KefsBundle.message("dialog.plugin.title.add")
        } else {
            KefsBundle.message("dialog.plugin.title.edit")
        }

        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent {
        // Build repositories list using Kotlin UI DSL and wrap in a scroll pane
        val reposListPanel = panel {
            availableRepositories.forEach { repo ->
                val disabledForDefault = repo.name in defaultRepoNames
                row {
                    checkBox(repo.name)
                        .applyToComponent {
                            isSelected = selectedRepoNames.contains(repo.name)
                            toolTipText = if (disabledForDefault) {
                                repo.value + " (" + KefsBundle.message("tooltip.defaultPluginRepoRefNotEditable") + ")"
                            } else repo.value
                            addItemListener {
                                if (isSelected) selectedRepoNames.add(repo.name) else selectedRepoNames.remove(repo.name)
                            }
                        }
                        .enabled(!disabledForDefault)
                }
            }
        }

        val reposPanel = JBScrollPane(reposListPanel).apply {
            minimumSize = Dimension(300.scaled, 120.scaled)
        }

        val panel = panel {
            row {
                label(KefsBundle.message("plugin.default.cannot.edit")).applyToComponent {
                    icon = AllIcons.General.Warning
                    foreground = JBUI.CurrentTheme.Label.warningForeground()
                }.align(AlignX.CENTER)
            }.visible(isDefault)

            row(KefsBundle.message("label.name")) {
                nameField = textField().applyToComponent {
                    emptyText.text = KefsBundle.message("emptyText.uniqueName")
                    text = initial?.name.orEmpty()

                    columns = 30
                    minimumSize = Dimension(300.scaled, minimumSize.height)

                    isEditable = !isDefault

                    toolTipText = if (isDefault) {
                        KefsBundle.message("tooltip.defaultPluginNameNotEditable")
                    } else {
                        KefsBundle.message("tooltip.nameMustBeUnique")
                    }
                }.component
            }

            row(KefsBundle.message("label.coordinates")) {
                cell(tablePanel)
                    .align(AlignX.FILL)
            }

            row(KefsBundle.message("label.version.matching")) {
                comboBox(versionMatchingFieldModel)
                cell(
                    ContextHelpLabel.createWithBrowserLink(
                        null,
                        KefsBundle.message("settings.add.plugin.version.matching.help"),
                        KefsBundle.message("settings.add.plugin.version.matching.help.link"),
                        URI("https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/GUIDE.md#version-matching").toURL(),
                    )
                )
            }

            row(KefsBundle.message("label.repositories")) {
                cell(reposPanel)
                    .applyToComponent {
                        insets.top = 10.scaled
                    }
                    .align(AlignX.FILL)
            }

            gap(RightGap.SMALL)
            separator()
            gap(RightGap.SMALL)

            // Options
            row {
                checkBox(KefsBundle.message("checkbox.enablePluginInProject"))
                    .applyToComponent {
                        isSelected = enableInProject
                        addItemListener { enableInProject = isSelected }
                    }
                    .comment(KefsBundle.message("checkbox.enablePluginInProject.comment"))
            }
            row {
                checkBox(KefsBundle.message("checkbox.ignorePluginExceptions"))
                    .applyToComponent {
                        isSelected = ignoreExceptions
                        addItemListener { ignoreExceptions = isSelected }
                    }
                    .comment(KefsBundle.message("checkbox.ignorePluginExceptions.comment"))
            }

            collapsibleGroup(KefsBundle.message("label.advanced")) {
                row {
                    useReplacementCheckbox = checkBox(KefsBundle.message("label.useReplacement"))
                        .applyToComponent {
                            isSelected = initial?.replacement != null
                            isEnabled = !isDefault

                            if (isDefault) {
                                toolTipText = KefsBundle.message("tooltip.replacementNotEditable")
                            }

                            addChangeListener {
                                versionReplacementIsValid.set(showVersionReplacementExample())
                                detectReplacementIsValid.set(showDetectReplacementExample())
                                searchReplacementIsValid.set(showSearchReplacementExample())
                            }
                        }
                        .comment(KefsBundle.message("checkbox.useReplacement.comment"))
                        .component
                }

                indent {
                    row(KefsBundle.message("label.replacement.version")) {
                        replacementVersionField =
                            textFieldWithReplacementCompletion<KotlinPluginDescriptor.Replacement.VersionMacro>(
                                project = project,
                                initialValue = initial?.replacement?.version.orEmpty(),
                            ).apply {
                                setPlaceholder(placeholderReplacement.version)

                                onChange {
                                    versionReplacementIsValid.set(showVersionReplacementExample())
                                    detectReplacementIsValid.set(showDetectReplacementExample())
                                    searchReplacementIsValid.set(showSearchReplacementExample())
                                }
                            }

                        cell(replacementVersionField)
                            .align(AlignX.FILL)
                    }

                    exampleComment(versionReplacementIsValid, false) {
                        val example = versionReplacementExample(it)
                        KefsBundle.message(
                            "settings.add.plugin.replacement.example.render.version",
                            example.version1,
                            example.version2,
                            example.version3,
                        )
                    }

                    row(KefsBundle.message("label.replacement.detect")) {
                        replacementDetectField =
                            textFieldWithReplacementCompletion<KotlinPluginDescriptor.Replacement.JarMacro>(
                                project = project,
                                initialValue = initial?.replacement?.detect.orEmpty(),
                            ).apply {
                                setPlaceholder(placeholderReplacement.detect)

                                onChange {
                                    detectReplacementIsValid.set(showDetectReplacementExample())
                                }
                            }

                        cell(replacementDetectField)
                            .align(AlignX.FILL)
                    }

                    exampleComment(detectReplacementIsValid, true) { replacement ->
                        KefsBundle.message(
                            "settings.add.plugin.replacement.example.render.detect",
                            detectReplacementExample(mutableIds.firstOrNull { mavenRegex.matches(it) }, replacement)
                        )
                    }

                    row(KefsBundle.message("label.replacement.search")) {
                        replacementSearchField =
                            textFieldWithReplacementCompletion<KotlinPluginDescriptor.Replacement.JarMacro>(
                                project = project,
                                initialValue = initial?.replacement?.search.orEmpty(),
                            ).apply {
                                setPlaceholder(placeholderReplacement.search)

                                onChange {
                                    searchReplacementIsValid.set(showSearchReplacementExample())
                                }
                            }

                        cell(replacementSearchField)
                            .align(AlignX.FILL)
                    }

                    exampleComment(searchReplacementIsValid, true) { replacement ->
                        KefsBundle.message(
                            "settings.add.plugin.replacement.example.render.search",
                            searchReplacementExample(mutableIds.firstOrNull { mavenRegex.matches(it) }, replacement)
                        )
                    }
                }.visibleIf(useReplacementCheckbox.selected)
            }
        }

        panel.preferredSize = Dimension(650.scaled, 0.scaled)
        return panel
    }

    override fun continuousValidation(): Boolean {
        return true
    }

    @Suppress("DuplicatedCode")
    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo(KefsBundle.message("validation.name.empty"), nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo(KefsBundle.message("validation.name.unique"), nameField)
        }

        if (!pluginNameRegex.matches(name)) {
            return ValidationInfo(KefsBundle.message("validation.name.regex", pluginNameRegex.pattern))
        }

        if (mutableIds.isEmpty()) {
            return ValidationInfo(KefsBundle.message("validation.maven.atLeastOne"))
        }

        mutableIds.forEach { id ->
            if (!mavenRegex.matches(id)) {
                return ValidationInfo(KefsBundle.message("validation.coordinates.format"))
            }
        }

        if (selectedRepoNames.isEmpty()) {
            return ValidationInfo(KefsBundle.message("validation.select.repository"))
        }

        if (useReplacementCheckbox.isSelected) {
            if (replacementVersionField.text.isNotEmpty()) {
                validateReplacementPatternVersion(
                    replacementVersionField.text,
                    replacementVersionField,
                )?.let { return it }
            }

            if (replacementDetectField.text.isNotEmpty()) {
                validateReplacementPatternJar(
                    replacementDetectField.text,
                    replacementDetectField,
                )?.let { return it }
            }

            if (replacementSearchField.text.isNotEmpty()) {
                validateReplacementPatternJar(
                    replacementSearchField.text,
                    replacementSearchField,
                )?.let { return it }
            }
        }

        return null
    }

    private fun Panel.exampleComment(
        observable: ObservableProperty<Boolean>,
        needsMavenId: Boolean,
        updater: (KotlinPluginDescriptor.Replacement) -> String,
    ) {
        row {
            comment("")
                .applyToComponent {
                    fun updateComment() {
                        if (observable.get()) {
                            text = if (
                                needsMavenId && (mutableIds.isEmpty() || mutableIds.none { mavenRegex.matches(it) })
                            ) {
                                KefsBundle.message("settings.add.plugin.replacement.example.render.empty")
                            } else {
                                updater(currentReplacement())
                            }
                        }
                    }

                    idsModel.addTableModelListener { updateComment() }
                    observable.afterChange { updateComment() }
                    updateComment()
                }
        }.visibleIf(observable)
    }

    private fun currentReplacement(): KotlinPluginDescriptor.Replacement {
        val version = if (::replacementVersionField.isInitialized && replacementVersionField.text.isNotEmpty()) {
            replacementVersionField.text
        } else null

        val detect = if (::replacementDetectField.isInitialized && replacementDetectField.text.isNotEmpty()) {
            replacementDetectField.text
        } else null

        val search = if (::replacementSearchField.isInitialized && replacementSearchField.text.isNotEmpty()) {
            replacementSearchField.text
        } else null

        return KotlinPluginDescriptor.Replacement(
            version = version ?: placeholderReplacement.version,
            detect = detect ?: placeholderReplacement.detect,
            search = search ?: placeholderReplacement.search,
        )
    }

    private fun showVersionReplacementExample(): Boolean {
        return useReplacementCheckbox.isSelected && (replacementVersionField.text.isEmpty() ||
                validateReplacementPatternVersion(replacementVersionField.text, replacementVersionField) == null)
    }

    private fun showDetectReplacementExample(): Boolean {
        return useReplacementCheckbox.isSelected && (replacementVersionField.text.isEmpty() ||
                validateReplacementPatternVersion(replacementVersionField.text, replacementVersionField) == null) &&
                (replacementDetectField.text.isEmpty() ||
                        validateReplacementPatternJar(replacementDetectField.text, replacementDetectField) == null)
    }

    private fun showSearchReplacementExample(): Boolean {
        return useReplacementCheckbox.isSelected && (replacementVersionField.text.isEmpty() ||
                validateReplacementPatternVersion(replacementVersionField.text, replacementVersionField) == null) &&
                (replacementSearchField.text.isEmpty() ||
                        validateReplacementPatternJar(replacementSearchField.text, replacementSearchField) == null)
    }

    private class VersionReplacementExamples(
        val version1: String,
        val version2: String,
        val version3: String,
    )

    private fun versionReplacementExample(
        replacement: KotlinPluginDescriptor.Replacement?,
    ): VersionReplacementExamples {
        if (replacement == null) {
            return VersionReplacementExamples("", "", "")
        }

        return VersionReplacementExamples(
            version1 = replacement.getVersionString("2.2.0", "1.0.0"),
            version2 = replacement.getVersionString("2.3.0-Beta2", "1.0.0"),
            version3 = replacement.getVersionString("2.3.0-Beta2", "1.0.0-rc-dev.1234"),
        )
    }

    private fun detectReplacementExample(
        idString: String?,
        replacement: KotlinPluginDescriptor.Replacement?,
    ): String {
        if (replacement == null || idString == null) {
            return ""
        }

        val version = replacement.getVersionString("2.2.0", "1.0.0")
        return "${replacement.getDetectString(MavenId(idString), version)}.jar"
    }

    private fun searchReplacementExample(
        idString: String?,
        replacement: KotlinPluginDescriptor.Replacement?,
    ): String {
        if (replacement == null || idString == null) {
            return ""
        }

        val version = replacement.getVersionString("2.2.0", "1.0.0")

        val id = MavenId(idString)
        val artifactString = replacement.getArtifactString(id)
        return "${id.getPluginGroupPath()}/$artifactString/$version/$artifactString-$version.jar"
    }

    companion object {
        private val versionMatchingMap = mapOf(
            KefsBundle.message("version.matching.exact") to KotlinPluginDescriptor.VersionMatching.EXACT,
            KefsBundle.message("version.matching.sameMajor") to KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            KefsBundle.message("version.matching.latest") to KotlinPluginDescriptor.VersionMatching.LATEST,
        )

        val versionMatchingMapReversed = versionMatchingMap.entries
            .associateBy({ it.value }) { it.key }
    }

    fun getResult(): KotlinPluginDescriptor? {
        if (doValidate() != null) {
            return null
        }

        val selectedRepos = availableRepositories.filter { it.name in selectedRepoNames }

        return KotlinPluginDescriptor(
            name = nameField.text.trim(),
            ids = mutableIds.map { MavenId(it) },
            versionMatching = versionMatchingMap.getValue(versionMatchingFieldModel.selectedItem as String),
            enabled = enableInProject,
            ignoreExceptions = ignoreExceptions,
            repositories = selectedRepos,
            replacement = if (useReplacementCheckbox.isSelected) {
                currentReplacement()
            } else {
                null
            },
        )
    }
}

private val placeholderReplacement = KotlinPluginDescriptor.Replacement(
    version = KotlinPluginDescriptor.Replacement.VersionMacro.KOTLIN_VERSION.macro +
            "-" +
            KotlinPluginDescriptor.Replacement.VersionMacro.LIB_VERSION.macro,
    detect = KotlinPluginDescriptor.Replacement.JarMacro.ARTIFACT_ID.macro,
    search = KotlinPluginDescriptor.Replacement.JarMacro.ARTIFACT_ID.macro,
)

internal val mavenRegex = "([\\w.]+):([\\w\\-]+)".toRegex()
internal val pluginNameRegex = "[a-zA-Z0-9_-]+".toRegex()

internal object KefsConfigurableUtil {
    @Volatile
    var selectArtifactsInitially: Boolean = false

    fun showArtifacts(project: Project) {
        selectArtifactsInitially = true
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KefsConfigurable::class.java)
    }

    fun showGeneral(project: Project) {
        selectArtifactsInitially = false
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KefsConfigurable::class.java)
    }
}

internal fun validateReplacementPatternVersion(
    pattern: String,
    component: JComponent? = null,
): ValidationInfo? {
    return validateReplacementPattern(
        pattern = pattern,
        isVersion = true,
        mustContain = listOf(
            KotlinPluginDescriptor.Replacement.VersionMacro.KOTLIN_VERSION,
            KotlinPluginDescriptor.Replacement.VersionMacro.LIB_VERSION,
        ),
        allAvailable = KotlinPluginDescriptor.Replacement.VersionMacro.entries,
        component = component,
    )
}

internal fun validateReplacementPatternJar(
    pattern: String,
    component: JComponent? = null,
): ValidationInfo? {
    return validateReplacementPattern(
        pattern = pattern,
        isVersion = false,
        mustContain = listOf(KotlinPluginDescriptor.Replacement.JarMacro.ARTIFACT_ID),
        allAvailable = KotlinPluginDescriptor.Replacement.JarMacro.entries,
        component = component,
    )
}

internal fun validateReplacementPattern(
    pattern: String,
    isVersion: Boolean,
    mustContain: List<KotlinPluginDescriptor.Replacement.Marco>,
    allAvailable: List<KotlinPluginDescriptor.Replacement.Marco>,
    component: JComponent? = null,
): ValidationInfo? {
    if (pattern.isBlank()) {
        return ValidationInfo(KefsBundle.message("validation.replacement.empty"), component)
    }

    if (mustContain.any { !pattern.contains(it.macro) }) {
        return ValidationInfo(
            KefsBundle.message(
                "validation.replacement.must.contain.macros",
                mustContain.joinToString(", ") { it.macro }.sanitizeAngleBracketsForHtml(),
            ),
            component,
        )
    }

    if (!isVersion && pattern.contains(".jar")) {
        return ValidationInfo(KefsBundle.message("validation.replacement.jar.extension"), component)
    }

    var i = 0
    var invalidMacro = false
    val allAvailableMacros = allAvailable.map { it.macro }
    while (i < pattern.length) {
        val c = pattern[i]

        if (c != '<' && c != '>') {
            i++
            continue
        }

        val macro = when (c) {
            '<' -> {
                val start = i
                var next = c

                while (next != '>') {
                    if (++i >= pattern.length) {
                        invalidMacro = true
                        break
                    }

                    next = pattern[i]
                }

                pattern.substring(start, (++i).coerceAtMost(pattern.length))
            }

            '>' -> {
                invalidMacro = true
                break
            }

            else -> continue
        }

        if (macro !in allAvailableMacros) {
            invalidMacro = true
            break
        }
    }

    if (invalidMacro) {
        return ValidationInfo(
            KefsBundle.message(
                "validation.replacement.invalid.angle.brackets",
                "&lt;",
                "&gt;",
                allAvailable.joinToString(", ").sanitizeAngleBracketsForHtml(),
            ),
            component,
        )
    }

    var noMacros = pattern
    allAvailable.forEach {
        noMacros = noMacros.replace(it.macro, "")
    }

    if (isVersion) {
        if (noMacros.contains(replacementPatternVersionForbiddenCharactersRegex)) {
            return ValidationInfo(
                KefsBundle.message("validation.replacement.invalid.symbols.version"),
                component,
            )
        }
    } else {
        if (noMacros.contains(replacementPatternForbiddenCharactersRegex)) {
            return ValidationInfo(
                KefsBundle.message("validation.replacement.invalid.symbols"),
                component,
            )
        }
    }

    return null
}

private inline fun <reified M> textFieldWithReplacementCompletion(
    project: Project,
    initialValue: String,
): TextFieldWithAutoCompletion<String> where M : Enum<M>, M : KotlinPluginDescriptor.Replacement.Marco {
    return textFieldWithReplacementCompletion(project, initialValue, enumValues<M>())
}

private fun textFieldWithReplacementCompletion(
    project: Project,
    initialValue: String,
    macros: Array<out KotlinPluginDescriptor.Replacement.Marco>,
) = TextFieldWithAutoCompletion(
    /* project = */ project,
    /* provider = */
    object : TextFieldWithAutoCompletionListProvider<String>(macros.map { it.macro }) {
        override fun getLookupString(item: String): String {
            return item
        }

        override fun getPrefix(text: String, offset: Int): String {
            val i = text.lastIndexOf('<', offset - 1)
            return text.substring(i, offset)
        }
    },
    /* showCompletionHint = */ false,
    /* text = */ initialValue,
)

private fun TextFieldWithAutoCompletion<*>.onChange(action: () -> Unit) {
    addDocumentListener(object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            action()
        }
    })
}

private val replacementPatternForbiddenCharactersRegex = "[^\\w-]".toRegex()
private val replacementPatternVersionForbiddenCharactersRegex = "[^\\w.+-]".toRegex()

private fun String.sanitizeAngleBracketsForHtml(): String {
    return replace("<", "&lt;").replace(">", "&gt;")
}
