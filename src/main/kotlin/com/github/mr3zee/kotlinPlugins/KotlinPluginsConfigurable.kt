package com.github.mr3zee.kotlinPlugins

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.emptyText
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import kotlin.collections.map

class KotlinPluginsConfigurable(private val project: Project) : Configurable {
    private val repositories: MutableList<KotlinArtifactsRepository> = mutableListOf()
    private val plugins: MutableList<KotlinPluginDescriptor> = mutableListOf()

    private lateinit var repoTable: TableView<KotlinArtifactsRepository>
    private lateinit var pluginsTable: TableView<KotlinPluginDescriptor>
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
        repoTable = TableView(repoModel).apply {
            columnModel.getColumn(0).preferredWidth = 100
            columnModel.getColumn(1).preferredWidth = 300
        }
        repoTable.emptyText.text = "No repositories configured"

        pluginsModel = ListTableModel(
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Name") {
                override fun valueOf(item: KotlinPluginDescriptor): String = item.name
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Coordinates") {
                override fun valueOf(item: KotlinPluginDescriptor): String = item.id
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Versions") {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    PluginsDialog.versionMatchingMapReversed.getValue(item.versionMatching)
            },
            object : com.intellij.util.ui.ColumnInfo<KotlinPluginDescriptor, String>("Repositories") {
                override fun valueOf(item: KotlinPluginDescriptor): String =
                    item.repositories.joinToString(", ") { it.name }
            }
        )
        pluginsTable = TableView(pluginsModel).apply {
            columnModel.getColumn(0).preferredWidth = 100
            columnModel.getColumn(1).preferredWidth = 300
            columnModel.getColumn(2).preferredWidth = 80
            columnModel.getColumn(3).preferredWidth = 150
        }
        pluginsTable.emptyText.text = "No plugins configured"

        val repoPanel = ToolbarDecorator.createDecorator(repoTable)
            .setAddAction { onAddRepository() }
            .setEditAction { onEditRepository() }
            .setRemoveAction { onRemoveRepository() }
            .setRemoveActionUpdater {
                val selected = repoTable.selectedObject
                selected != null && selected.name !in DefaultState.repositoryMap
            }
            .createPanel()

        val pluginsPanel = ToolbarDecorator.createDecorator(pluginsTable)
            .setAddAction { onAddPlugin() }
            .setEditAction { onEditPlugin() }
            .setRemoveAction { onRemovePlugin() }
            .setRemoveActionUpdater {
                val selected = pluginsTable.selectedObject
                selected != null && selected.name !in DefaultState.pluginMap
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
        val state = project.service<KotlinPluginsSettingsService>().safeState()
        val reposModified = repositoriesPairs().toSet() != state.repositories.map { it.name to it.value }.toSet()
        val pluginsModified = pluginsTriples().toSet() != state.plugins.map {
            Triple(
                it.name,
                it.id,
                it.repositories.map { r -> r.name }
            )
        }.toSet()
        return reposModified || pluginsModified
    }

    private fun repositoriesPairs(): List<Pair<String, String>> = repositories.map { it.name to it.value }
    private fun pluginsTriples(): List<Triple<String, String, List<String>>> =
        plugins.map { Triple(it.name, it.id, it.repositories.map { r -> r.name }) }

    override fun apply() {
        val service = project.service<KotlinPluginsSettingsService>()

        service.updateToNewState(repositories, plugins)
    }

    override fun reset() {
        val state = project.service<KotlinPluginsSettingsService>().safeState()
        repositories.clear()
        repositories.addAll(state.repositories)
        repoModel.items = ArrayList(repositories)

        plugins.clear()
        plugins.addAll(state.plugins)
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
        val selected = repoTable.selectedObject ?: return
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
        )

        if (dialog.showAndGet()) {
            val entry = dialog.getResult() ?: return
            plugins.add(entry)
            pluginsModel.items = ArrayList(plugins)
            selectLast(pluginsTable)
        }
    }

    private fun onEditPlugin() {
        val selected = pluginsTable.selectedObject ?: return
        val idx = pluginsTable.selectedRow
        val dialog = PluginsDialog(
            currentNames = plugins.map { it.name },
            availableRepositories = repositories,
            initial = selected,
        )

        if (dialog.showAndGet()) {
            val updated = dialog.getResult() ?: return
            plugins[idx] = updated
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
            pluginsModel.items = ArrayList(plugins)
        }
    }
    // endregion

    private fun selectLast(table: TableView<*>) {
        val last = table.rowCount - 1
        if (last >= 0) {
            table.selectionModel.setSelectionInterval(last, last)
        }
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

    private val idField = JBTextField().apply {
        emptyText.text = "Maven coordinates"

        text = initial?.id.orEmpty()

        minimumSize = Dimension(600, minimumSize.height)
        toolTipText = if (isDefault) {
            "Default plugin coordinates cannot be edited"
        } else {
            "Must be in the form of group:artifact"
        }
    }

    // todo add comment component
    private val versionMatchingField = ComboBox<String>().apply {
        model = DefaultComboBoxModel(versionMatchingMap.keys.toTypedArray()).apply {
            val value = initial?.versionMatching ?: KotlinPluginDescriptor.VersionMatching.EXACT
            versionMatchingMapReversed[value]?.let { selectedItem = it }
        }
    }

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
        idField.isEditable = !isDefault

        init()
    }

    override fun createCenterPanel(): JComponent {
        val reposPanel = JScrollPane(reposContainer).apply {
            minimumSize = Dimension(300, 120)
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(warningLabel)
            .addLabeledComponent(JBLabel("Name:"), nameField)
            .addLabeledComponent(JBLabel("Coordinates:"), idField)
            .addLabeledComponent(JBLabel("Version matching:"), versionMatchingField)
            .addLabeledComponent(JBLabel("Repositories:"), reposPanel, 10)
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

        val id = idField.text.trim()
        if (id.isEmpty()) {
            return ValidationInfo("Coordinates must not be empty", idField)
        }

        if (!mavenRegex.matches(id)) {
            return ValidationInfo("Coordinates must be in the form group:artifact", idField)
        }

        if (repoCheckboxes.none { it.isSelected }) {
            return ValidationInfo("Select at least one URL repository", reposContainer)
        }

        return null
    }

    companion object {
        private val mavenRegex = "([\\w.]+):([\\w\\-]+)".toRegex()

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
            id = idField.text.trim(),
            versionMatching = versionMatchingMap.getValue(versionMatchingField.model.selectedItem as String),
            repositories = selectedRepos,
        )
    }
}
