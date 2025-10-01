package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.emptyText
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.TableRowSorter

class KotlinPluginsConfigurable(private val project: Project) : Configurable {
    private val repositories: MutableList<KotlinArtifactsRepository> = mutableListOf()
    private val plugins: MutableList<KotlinPluginDescriptor> = mutableListOf()
    private val pluginsEnabled: MutableMap<String, Boolean> = mutableMapOf()

    private lateinit var repoTable: JBTable
    private lateinit var pluginsTable: JBTable
    private lateinit var repoModel: ListTableModel<KotlinArtifactsRepository>
    private lateinit var pluginsModel: ListTableModel<KotlinPluginDescriptor>

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "Kotlin Plugins"

    override fun createComponent(): JComponent {
        if (rootPanel != null) return rootPanel as JPanel

        // Tables and models
        repoModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>("Name") {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinArtifactsRepository, String>("Value") {
                override fun valueOf(item: KotlinArtifactsRepository): String = item.value
            }
        )

        repoTable = JBTable(repoModel).apply {
            columnModel.getColumn(0).preferredWidth = 100
            columnModel.getColumn(1).preferredWidth = 300
            emptyText.text = "No repositories configured"
            rowSorter = TableRowSorter(repoModel).apply {
                setSortable(0, true)
                setSortable(1, false)
            }
        }

        pluginsModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, Boolean>("") {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java

                override fun valueOf(item: KotlinPluginDescriptor): Boolean = pluginsEnabled[item.name] ?: item.enabled

                override fun isCellEditable(item: KotlinPluginDescriptor?): Boolean = true

                override fun setValue(item: KotlinPluginDescriptor, value: Boolean) {
                    pluginsEnabled[item.name] = value
                }
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Name") {
                override fun valueOf(item: KotlinPluginDescriptor): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Coordinates") {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.ids.joinToString("<br/>", prefix = "<html>", postfix = "</html>") { it.id }
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Versions") {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    PluginsDialog.versionMatchingMapReversed.getValue(item.versionMatching)
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Repositories") {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.repositories.joinToString("<br/>", prefix = "<html>", postfix = "</html>") { it.name }
            }
        )

        pluginsTable = JBTable(pluginsModel).apply {
            columnModel.getColumn(0).preferredWidth = 20
            columnModel.getColumn(1).preferredWidth = 100
            columnModel.getColumn(2).preferredWidth = 300
            columnModel.getColumn(3).preferredWidth = 80
            columnModel.getColumn(4).preferredWidth = 150
            emptyText.text = "No plugins configured"
            rowSorter = TableRowSorter(pluginsModel).apply {
                setSortable(0, false)
                setSortable(1, true)
                setSortable(2, true)
                setSortable(3, false)
                setSortable(4, false)
            }
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

        val content = JPanel(BorderLayout())
        val form = FormBuilder.createFormBuilder()
            .addSeparator(5)
            .addLabeledComponent(
                JBLabel("Maven repositories", AllIcons.Nodes.Folder, SwingConstants.LEADING),
                repoPanel,
                true
            )
            .addSeparator(5)
            .addLabeledComponent(
                JBLabel("Kotlin plugins", AllIcons.Nodes.Plugin, SwingConstants.LEADING),
                pluginsPanel,
                true
            )
            .panel
        content.add(form, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Settings", content)
        rootPanel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.NORTH) }

        reset() // initialise from a persisted state
        return rootPanel as JPanel
    }

    override fun isModified(): Boolean {
        val state = project.service<KotlinPluginsSettings>().safeState()
        val reposModified = repositories != state.repositories
        val pluginsModified =
            plugins != state.plugins || pluginsEnabled != state.plugins.associateBy({ it.name }, { it.enabled })

        return reposModified || pluginsModified
    }

    override fun apply() {
        val service = project.service<KotlinPluginsSettings>()

        val enabledPlugins = plugins.map {
            it.copy(enabled = pluginsEnabled[it.name] ?: it.enabled)
        }

        service.updateToNewState(repositories, enabledPlugins)
    }

    override fun reset() {
        val state = project.service<KotlinPluginsSettings>().safeState()
        repositories.clear()
        repositories.addAll(state.repositories)
        repoModel.items = ArrayList(repositories)

        plugins.clear()
        plugins.addAll(state.plugins)
        pluginsEnabled.clear()
        pluginsEnabled.putAll(state.plugins.associateBy({ it.name }, { it.enabled }))
        pluginsModel.items = ArrayList(plugins)
    }

    // region Repository actions
    private fun onAddRepository() {
        val dialog = RepositoryDialog(
            currentNames = repositories.map { it.name },
            initial = null,
            project = project,
        )

        if (dialog.showAndGet()) {
            val entry = dialog.getResult() ?: return
            repositories.add(entry)
            repoModel.items = ArrayList(repositories)
            selectLast(repoTable)
        }
    }

    private fun onEditRepository() {
        val selected = repoTable.selectedObject(repoModel) ?: return
        val idx = repoTable.selectedRow
        val dialog = RepositoryDialog(
            currentNames = repositories.map { it.name },
            initial = selected,
            project = project,
        )

        if (dialog.showAndGet()) {
            val updated = dialog.getResult() ?: return
            repositories[idx] = updated
            repoModel.items = ArrayList(repositories)
            repoTable.selectionModel.setSelectionInterval(idx, idx)
        }
    }

    @Suppress("DuplicatedCode")
    private fun onRemoveRepository() {
        val idx = repoTable.selectedRow
        if (idx >= 0) {
            val repo = repositories[idx]
            if (repo.name in DefaultState.repositoryMap) {
                return
            }
            repositories.removeAt(idx)
            repoModel.items = ArrayList(repositories)
        }
    }
    // endregion

    // region Plugins actions
    private fun onAddPlugin() {
        val dialog = PluginsDialog(
            currentNames = plugins.map { it.name },
            availableRepositories = repositories,
            initial = null,
            enabledInitial = true,
        )

        if (dialog.showAndGet()) {
            val entry = dialog.getResult() ?: return
            plugins.add(entry)
            pluginsEnabled[entry.name] = entry.enabled
            pluginsModel.items = ArrayList(plugins)
            selectLast(pluginsTable)
        }
    }

    private fun onEditPlugin() {
        val selected = pluginsTable.selectedObject(pluginsModel) ?: return
        val idx = pluginsTable.selectedRow
        val dialog = PluginsDialog(
            currentNames = plugins.map { it.name },
            availableRepositories = repositories,
            initial = selected,
            enabledInitial = pluginsEnabled[selected.name] ?: selected.enabled,
        )

        if (dialog.showAndGet()) {
            val updated = dialog.getResult() ?: return
            plugins[idx] = updated
            pluginsEnabled[updated.name] = updated.enabled
            pluginsModel.items = ArrayList(plugins)
            pluginsTable.selectionModel.setSelectionInterval(idx, idx)
        }
    }

    @Suppress("DuplicatedCode")
    private fun onRemovePlugin() {
        val idx = pluginsTable.selectedRow
        if (idx >= 0) {
            val plugin = plugins[idx]
            if (plugin.name in DefaultState.pluginMap) {
                return
            }
            plugins.removeAt(idx)
            pluginsEnabled.remove(plugin.name)
            pluginsModel.items = ArrayList(plugins)
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
    project: Project,
) : DialogWrapper(true) {
    private val isDefault = initial?.name in DefaultState.repositoryMap

    private val warningLabel = JBLabel(
        "Default repository cannot be edited",
        AllIcons.General.Warning,
        SwingConstants.LEADING,
    ).apply {
        foreground = JBUI.CurrentTheme.Label.warningForeground()
        isVisible = isDefault
        horizontalAlignment = SwingConstants.CENTER
    }

    private val nameField = JBTextField(initial?.name.orEmpty()).apply {
        emptyText.text = "Unique name"

        minimumSize = Dimension(300, minimumSize.height)
        toolTipText = if (isDefault) "Default repository name cannot be edited" else "Name must be unique"
    }

    private val urlField = JBTextField().apply {
        emptyText.text = "Maven repository URL"

        text = if (initial?.value?.startsWith("http") == true) initial.value else ""
        minimumSize = Dimension(600, minimumSize.height)

        if (isDefault) {
            toolTipText = "Default repository url cannot be edited"
        }
    }

    private val urlLabel = JBLabel("URL:")

    private val pathField = TextFieldWithBrowseButton().apply {
        emptyText.text = "Maven artifacts directory"

        text = if (initial != null && !initial.value.startsWith("http")) initial.value else ""
        val descriptor = FileChooserDescriptorFactory
            .createSingleFolderDescriptor()

        addBrowseFolderListener(com.intellij.openapi.ui.TextBrowseFolderListener(descriptor, project))

        if (isDefault) {
            toolTipText = "Default repository path cannot be edited"
        }
    }
    private val pathLabel = JBLabel("Path:")

    private val urlRadio = JBRadioButton("URL", initial?.value?.startsWith("http") ?: true)
    private val pathRadio = JBRadioButton("File path", !(initial?.value?.startsWith("http") ?: false))

    init {
        title = if (initial == null) "Add Maven Repository" else "Edit Maven Repository"
        nameField.isEditable = !isDefault
        urlField.isEditable = !isDefault
        pathField.isEditable = !isDefault

        init()

        val group = ButtonGroup()
        group.add(urlRadio)
        group.add(pathRadio)
        toggleFields()

        urlRadio.addActionListener { toggleFields() }
        pathRadio.addActionListener { toggleFields() }
    }

    private fun toggleFields() {
        urlField.isVisible = urlRadio.isSelected
        urlLabel.isVisible = urlRadio.isSelected
        pathField.isVisible = pathRadio.isSelected
        pathLabel.isVisible = pathRadio.isSelected
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addComponent(warningLabel)
            .addLabeledComponent(JBLabel("Name:"), nameField)
            .apply {
                if (!isDefault) {
                    addLabeledComponent(JBLabel("Kind:"), JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        add(urlRadio)
                        add(Box.createHorizontalStrut(8))
                        add(pathRadio)
                    })
                }
            }
            .addLabeledComponent(urlLabel, urlField)
            .addLabeledComponent(pathLabel, pathField)
            .panel
        form.preferredSize = Dimension(650, 0)
        return form
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo("Name must not be empty", nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo("Name must be unique", nameField)
        }

        if (name.contains(";")) {
            return ValidationInfo("Name must not contain semicolons (';')", nameField)
        }

        if (urlRadio.isSelected) {
            val url = urlField.text.trim()
            if (url.isEmpty()) {
                return ValidationInfo("URL must not be empty", urlField)
            }

            @Suppress("HttpUrlsUsage")
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                return ValidationInfo("URL should start with http:// or https://", urlField)
            }

            if (url.contains(";")) {
                return ValidationInfo("URL must not contain semicolons (';')", urlField)
            }
        } else {
            val path = pathField.text.trim()
            if (path.isEmpty()) {
                return ValidationInfo("Path must not be empty", pathField)
            }

            if (path.contains(";")) {
                return ValidationInfo("Path must not contain semicolons (';')", pathField)
            }
        }

        return null
    }

    fun getResult(): KotlinArtifactsRepository? {
        if (doValidate() != null) {
            return null
        }
        val name = nameField.text.trim()
        val value = if (urlRadio.isSelected) urlField.text.trim() else pathField.text.trim()
        return KotlinArtifactsRepository(
            name = name,
            value = value,
            type = if (urlRadio.isSelected) KotlinArtifactsRepository.Type.URL else KotlinArtifactsRepository.Type.PATH,
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
        "Default plugin name and coordinates cannot be edited, default repositories cannot be removed",
        AllIcons.General.Warning,
        SwingConstants.LEADING
    ).apply {
        foreground = JBUI.CurrentTheme.Label.warningForeground()
        isVisible = isDefault
        horizontalAlignment = SwingConstants.CENTER
    }

    private val nameField = JBTextField(initial?.name.orEmpty(), 30).apply {
        emptyText.text = "Unique name"

        minimumSize = Dimension(300, minimumSize.height)

        toolTipText = if (isDefault) {
            "Default plugin name cannot be edited"
        } else {
            "Name must be unique"
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
        emptyText.text = "No Maven coordinates"

        model = idsModel

        minimumSize = Dimension(600, minimumSize.height)
        toolTipText = if (isDefault) {
            "Default plugin coordinates cannot be edited"
        } else {
            "Each entry must be in the form of 'group:artifact'"
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

    private val versionMatchingField = ComboBox<String>().apply {
        model = DefaultComboBoxModel(versionMatchingMap.keys.toTypedArray()).apply {
            val value = initial?.versionMatching ?: KotlinPluginDescriptor.VersionMatching.EXACT
            versionMatchingMapReversed[value]?.let { selectedItem = it }
        }
    }

    private val enabledCheckbox = JBCheckBox("Enable this plugin in the project", enabledInitial)

    private val repoCheckboxes: List<JBCheckBox> = availableRepositories.map { repo ->
        JBCheckBox(repo.name, initial?.repositories?.any { it.name == repo.name } == true).apply {
            toolTipText = repo.value
            if (isDefault) {
                toolTipText += " (Default plugin repository reference cannot be edited)"
            }
            isEnabled = !isDefault || DefaultState.pluginMap[initial?.name]!!.repositories.none { it.name == repo.name }
        }
    }

    private val reposContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        repoCheckboxes.forEach { add(it) }
    }

    init {
        title = if (initial == null) "Add Plugin" else "Edit Plugin"

        nameField.isEditable = !isDefault

        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent {
        val reposPanel = JScrollPane(reposContainer).apply {
            minimumSize = Dimension(300, 120)
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(warningLabel)
            .addLabeledComponent(JBLabel("Name:"), nameField)
            .addLabeledComponent(JBLabel("Coordinates:"), tablePanel)
            .addLabeledComponent(JBLabel("Version matching:"), versionMatchingField)
            .addLabeledComponent(JBLabel("Repositories:"), reposPanel, 10)
            .addComponent(enabledCheckbox)
            .panel

        form.preferredSize = Dimension(650, 0)
        return form
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo("Name must not be empty", nameField)
        }

        if (name in currentNames && name != initial?.name) {
            return ValidationInfo("Name must be unique", nameField)
        }

        if (mutableIds.isEmpty()) {
            return ValidationInfo("At least one Maven coordinate must be specified")
        }

        mutableIds.forEach { id ->
            if (!mavenRegex.matches(id)) {
                return ValidationInfo("Coordinates must be in the form group:artifact")
            }
        }

        if (repoCheckboxes.none { it.isSelected }) {
            return ValidationInfo("Select at least one URL repository")
        }

        return null
    }

    companion object {
        private val versionMatchingMap = mapOf(
            "Latest Available" to KotlinPluginDescriptor.VersionMatching.LATEST,
            "Same Major" to KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            "Exact" to KotlinPluginDescriptor.VersionMatching.EXACT,
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
            ids = mutableIds.map { MavenId( it) },
            versionMatching = versionMatchingMap.getValue(versionMatchingField.model.selectedItem as String),
            enabled = enabledCheckbox.isSelected,
            repositories = selectedRepos,
        )
    }
}

val mavenRegex = "([\\w.]+):([\\w\\-]+)".toRegex()
