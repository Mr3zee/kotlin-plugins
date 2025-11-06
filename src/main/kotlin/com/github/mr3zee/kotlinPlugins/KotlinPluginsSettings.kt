package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.collections.distinctBy

@Service(Service.Level.PROJECT)
@State(
    name = "KotlinPluginsSettings",
    storages = [
        Storage("kotlin-plugins.xml"),
    ],
    category = SettingsCategory.PLUGINS,
    reloadable = true,
)
internal class KotlinPluginsSettings(
    private val project: Project,
) : SerializablePersistentStateComponent<KotlinPluginsSettings.StoredState>(
    State(
        repositories = DefaultState.repositories,
        plugins = DefaultState.plugins,
    ).asStored()
) {
    private val logger by lazy { thisLogger() }

    private fun updateWithDetectChanges(updateFunction: (currentState: State) -> State): State {
        val currentState = state

        val newState = updateState {
            updateFunction(currentState.asState()).asStored()
        }

        if (currentState != newState) {
            project.service<KotlinPluginsStorage>().clearState()
        }

        return newState.asState()
    }

    init {
        logger.debug("Initializing Kotlin Plugins Settings Service")
        safeState()
    }

    fun safeState(): State {
        return updateWithDetectChanges {
            // preserves the first entries, so default ones
            val reposWithDefaults = DefaultState.repositories + it.repositories
            val pluginsWithDefaults = DefaultState.plugins + it.plugins

            State(reposWithDefaults.toList(), pluginsWithDefaults.toList()).distinct()
        }
    }

    fun pluginByName(name: String): KotlinPluginDescriptor? {
        return safeState().plugins.find { it.name == name }
    }

    fun enablePlugin(pluginName: String) {
        enablePlugins(setOf(pluginName))
    }

    fun enablePlugins(pluginNames: Iterable<String>) {
        val set = pluginNames.toSet()
        updateWithDetectChanges {
            it.copy(
                plugins = it.plugins.map { p ->
                    if (p.name in set) p.copy(enabled = true) else p
                }
            )
        }
    }

    fun disablePlugin(pluginName: String): Boolean {
        return disablePlugins(setOf(pluginName))
    }

    fun disablePlugins(pluginNames: Iterable<String>): Boolean {
        val set = pluginNames.toSet()
        var disabled = false
        updateWithDetectChanges {
            it.copy(
                plugins = it.plugins.map { p ->
                    if (p.name in set) {
                        disabled = p.enabled
                        p.copy(enabled = false)
                    } else p
                }
            )
        }
        return disabled
    }

    fun isEnabled(pluginName: String): Boolean {
        return safeState().plugins.find { it.name == pluginName }?.enabled ?: false
    }

    fun updateToNewState(
        repositories: List<KotlinArtifactsRepository>,
        plugins: List<KotlinPluginDescriptor>,
    ) {
        updateWithDetectChanges {
            // preserves the first entries, so default ones
            val reposWithDefaults = DefaultState.repositories + repositories
            val pluginsWithDefaults = DefaultState.plugins + plugins

            State(reposWithDefaults.toList(), pluginsWithDefaults.toList()).distinct()
        }
    }

    data class State(
        val repositories: List<KotlinArtifactsRepository>,
        val plugins: List<KotlinPluginDescriptor>,
    )

    data class StoredState(
        @JvmField
        val repositories: Map<String, String> = emptyMap(),
        @JvmField
        val plugins: Map<String, String> = emptyMap(),
        @JvmField
        val pluginsRepos: Map<String, String> = emptyMap(),
    )
}

internal data class KotlinArtifactsRepository(
    val name: String,
    val value: String,
    val type: Type,
) {
    enum class Type { URL, PATH }

    override fun toString(): String {
        val prefix = when (type) {
            Type.URL -> "Remote"
            Type.PATH -> "Local"
        }

        return "$prefix repository ($name): $value"
    }
}

internal data class KotlinPluginDescriptor(
    val name: String,
    val ids: List<MavenId>,
    val versionMatching: VersionMatching,
    val enabled: Boolean,
    val ignoreExceptions: Boolean,
    val repositories: List<KotlinArtifactsRepository>,
) {
    enum class VersionMatching {
        EXACT,
        SAME_MAJOR,
        LATEST,
    }
}

internal fun KotlinPluginDescriptor.hasArtifact(artifact: String): Boolean {
    return ids.any { it.id == artifact }
}

internal data class MavenId(val id: String) {
    val groupId: String = id.substringBefore(":")
    val artifactId: String = id.substringAfter(":")

    fun getPluginGroupPath(): Path {
        val group = groupId.split(".")
        return Path.of(group[0], *group.drop(1).toTypedArray())
    }
}

private fun KotlinPluginsSettings.State.distinct(): KotlinPluginsSettings.State {
    val distinctRepositories = repositories.distinctBy { it.name }
    val distinctRepositoriesNames = distinctRepositories.map { it.name }.toSet()

    // default plugins can't have updated names or coordinates
    val updatedDefaultPlugins = plugins.filter {
        val defaultPlugin = DefaultState.pluginMap[it.name]
        defaultPlugin != null && (it.repositories != defaultPlugin.repositories ||
                it.versionMatching != defaultPlugin.versionMatching ||
                it.enabled != defaultPlugin.enabled ||
                it.ignoreExceptions != defaultPlugin.ignoreExceptions)
    }.associateBy { it.name }.mapValues { (k, v) ->
        v.copy(
            repositories = (DefaultState.pluginMap[k]!!.repositories + v.repositories).distinctBy { it.name },
        )
    }

    val newPlugins = plugins
        .distinctBy { it.name }
        .filter { it.name !in updatedDefaultPlugins } + updatedDefaultPlugins.values

    val distinctPlugins = newPlugins
        .map {
            it.copy(
                repositories = it.repositories.filter { r -> r.name in distinctRepositoriesNames }
            )
        }

    return copy(
        repositories = distinctRepositories,
        plugins = distinctPlugins,
    )
}

private fun KotlinPluginsSettings.State.asStored(): KotlinPluginsSettings.StoredState {
    return KotlinPluginsSettings.StoredState(
        repositories = repositories.associateBy { it.name }.mapValues { (_, v) ->
            "${v.value};${v.type.name}"
        },
        plugins = plugins.associateBy { it.name }.mapValues { (_, v) ->
            "${v.versionMatching.name};${v.enabled};${v.ignoreExceptions};${v.ids.joinToString(";") { it.id }}"
        },
        pluginsRepos = plugins.associateBy { it.name }.mapValues { (_, v) ->
            v.repositories.joinToString(";") { it.name }
        },
    )
}

internal fun KotlinPluginsSettings.StoredState.asState(): KotlinPluginsSettings.State {
    val mappedRepos = repositories.mapNotNull { (k, v) ->
        val list = v.split(";")
        val value = list.getOrNull(0) ?: return@mapNotNull null
        val type = list.getOrNull(1)?.let { typeName ->
            KotlinArtifactsRepository.Type.entries.find { it.name == typeName }
        } ?: return@mapNotNull null
        KotlinArtifactsRepository(k, value, type)
    }

    val repoByNames = mappedRepos.associateBy { it.name }

    return KotlinPluginsSettings.State(
        repositories = mappedRepos,
        plugins = plugins.mapNotNull { (name, v) ->
            val list = v.split(";")
            val versionMatching = list.getOrNull(0)?.let { enumName ->
                KotlinPluginDescriptor.VersionMatching.entries.find { it.name == enumName }
            } ?: return@mapNotNull null
            val enabled = list.getOrNull(1)?.toBooleanStrictOrNull() ?: return@mapNotNull null
            val ignoreExceptions = list.getOrNull(2)?.toBooleanStrictOrNull() ?: return@mapNotNull null
            val coordinates = list.drop(3).mapNotNull {
                if (mavenRegex.matches(it)) {
                    MavenId(it)
                } else null
            }

            KotlinPluginDescriptor(
                name = name,
                ids = coordinates,
                versionMatching = versionMatching,
                enabled = enabled,
                ignoreExceptions = ignoreExceptions,
                repositories = pluginsRepos[name].orEmpty().split(";").mapNotNull { repoByNames[it] }
            )
        },
    )
}

internal interface DefaultStateEntry {
    val repositories: List<KotlinArtifactsRepository>
    val plugins: List<KotlinPluginDescriptor>
}

internal object DefaultState : DefaultStateEntry by KotlinPluginsDefaultStateLoader.loadState() {
    val repositoryMap = repositories.associateBy { it.name }
    val pluginMap = plugins.associateBy { it.name }
}
