package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
class KotlinPluginsSettingsService(
    private val project: Project,
) : SerializablePersistentStateComponent<KotlinPluginsSettingsService.StoredState>(
    State(
        repositories = DefaultState.repositories,
        plugins = DefaultState.plugins,
    ).asStored()
) {
    private fun updateWithDetectChanges(updateFunction: (currentState: State) -> State): State {
        val currentState = state
        val newState = updateState {
            updateFunction(currentState.asState()).asStored()
        }.asState()
        if (currentState != newState) {
            project.service<KotlinPluginsStorageService>().clearCaches()
        }
        return newState
    }

    init {
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

data class KotlinArtifactsRepository(
    val name: String,
    val value: String,
    val type: Type,
) {
    enum class Type { URL, PATH }
}

data class KotlinPluginDescriptor(
    val name: String,
    val id: String,
    val versionMatching: VersionMatching,
    val enabled: Boolean,
    val repositories: List<KotlinArtifactsRepository> = emptyList(),
) {
    val groupId: String get() = id.substringBefore(':')
    val artifactId: String get() = id.substringAfter(':')

    enum class VersionMatching {
        EXACT,
        SAME_MAJOR,
        LATEST,
    }
}

private fun KotlinPluginsSettingsService.State.distinct(): KotlinPluginsSettingsService.State {
    val distinctRepositories = repositories.distinctBy { it.name }
    val distinctRepositoriesNames = distinctRepositories.map { it.name }.toSet()

    // default plugins can't have updated names or coordinates
    val updatedDefaultPlugins = plugins.filter {
        val defaultPlugin = DefaultState.pluginMap[it.name]
        defaultPlugin != null && (it.repositories != defaultPlugin.repositories ||
                it.versionMatching != defaultPlugin.versionMatching ||
                it.enabled != defaultPlugin.enabled)
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

private fun KotlinPluginsSettingsService.State.asStored(): KotlinPluginsSettingsService.StoredState {
    return KotlinPluginsSettingsService.StoredState(
        repositories = repositories.associateBy { it.name }.mapValues { (_, v) ->
            "${v.value};${v.type.name}"
        },
        plugins = plugins.associateBy { it.name }.mapValues {
            "${it.value.id};${it.value.versionMatching.name};${it.value.enabled}"
        },
        pluginsRepos = plugins.associateBy { it.name }.mapValues { (_, v) ->
            v.repositories.joinToString(";") { it.name }
        },
    )
}

fun KotlinPluginsSettingsService.StoredState.asState(): KotlinPluginsSettingsService.State {
    val mappedRepos = repositories.mapNotNull { (k, v) ->
        val list = v.split(";")
        val value = list.getOrNull(0) ?: return@mapNotNull null
        val type = list.getOrNull(1)?.let { typeName ->
            KotlinArtifactsRepository.Type.entries.find { it.name == typeName }
        } ?: return@mapNotNull null
        KotlinArtifactsRepository(k, value, type)
    }

    val repoByNames = mappedRepos.associateBy { it.name }

    return KotlinPluginsSettingsService.State(
        repositories = mappedRepos,
        plugins = plugins.mapNotNull { (k, v) ->
            val list = v.split(";")
            val coordinates = list.getOrNull(0) ?: return@mapNotNull null
            val versionMatching = list.getOrNull(1)?.let { enumName ->
                KotlinPluginDescriptor.VersionMatching.entries.find { it.name == enumName }
            } ?: return@mapNotNull null
            val enabled = list.getOrNull(2)?.toBooleanStrictOrNull() ?: return@mapNotNull null

            KotlinPluginDescriptor(
                name = k,
                id = coordinates,
                versionMatching = versionMatching,
                enabled = enabled,
                repositories = pluginsRepos[k].orEmpty().split(";").mapNotNull { repoByNames[it] }
            )
        },
    )
}

interface DefaultStateEntry {
    val repositories: List<KotlinArtifactsRepository>
    val plugins: List<KotlinPluginDescriptor>
}

object DefaultState : DefaultStateEntry by DefaultStateLoader.loadState() {
    val repositoryMap = repositories.associateBy { it.name }
    val pluginMap = plugins.associateBy { it.name }
}

data class KotlinPluginDescriptorVersioned(
    val descriptor: KotlinPluginDescriptor,
    val version: String?,
)
