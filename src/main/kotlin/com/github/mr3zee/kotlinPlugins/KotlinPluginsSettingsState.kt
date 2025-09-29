package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.Serializable
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
class KotlinPluginsSettingsService : SerializablePersistentStateComponent<KotlinPluginsSettingsService.StoredState>(
    State(
        repositories = DefaultState.repositories,
        plugins = DefaultState.plugins,
    ).asStored()
) {
    init {
        updateState {
            val asState = it.asState()

            // preserves the first entries, so default ones
            val reposWithDefaults = DefaultState.repositories + asState.repositories
            val pluginsWithDefaults = DefaultState.plugins + asState.plugins

            State(reposWithDefaults.toList(), pluginsWithDefaults.toList()).distinct().asStored()
        }
    }

    fun updateToNewState(
        repositories: List<KotlinArtifactsRepository>,
        plugins: List<KotlinPluginDescriptor>,
    ) {
        updateState {
            // preserves the first entries, so default ones
            val reposWithDefaults = DefaultState.repositories + repositories
            val pluginsWithDefaults = DefaultState.plugins + plugins

            State(reposWithDefaults.toList(), pluginsWithDefaults.toList()).distinct().asStored()
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
        val pluginsCoordinates: Map<String, String> = emptyMap(),
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
    val repositories: List<KotlinArtifactsRepository> = emptyList(),
) : Serializable {
    val groupId: String get() = id.substringBefore(':')
    val artifactId: String get() = id.substringAfter(':')
}

private fun KotlinPluginsSettingsService.State.distinct(): KotlinPluginsSettingsService.State {
    val distinctRepositories = repositories.distinctBy { it.name }
    val distinctRepositoriesNames = distinctRepositories.map { it.name }.toSet()

    // default plugins can have updated repos
    val updatedDefaultPlugins = plugins.filter {
        it.name in DefaultState.pluginMap && it.repositories != DefaultState.pluginMap[it.name]!!.repositories
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
        pluginsCoordinates = plugins.associateBy { it.name }.mapValues { it.value.id },
        pluginsRepos = plugins.associateBy { it.name }.mapValues { (_, v) -> v.repositories.joinToString(";") { it.name } },
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
        plugins = pluginsCoordinates.mapNotNull { (k, v) ->
            KotlinPluginDescriptor(k, v, pluginsRepos[k].orEmpty().split(";").mapNotNull { repoByNames[it] })
        },
    )
}

interface DefaultStateEntry {
    val repositories: List<KotlinArtifactsRepository>
    val plugins: List<KotlinPluginDescriptor>
}

object DefaultState : DefaultStateEntry {
    val defaults: List<DefaultStateEntry> = listOf(
        KotlinxRpcDefaults,
    )

    override val repositories: List<KotlinArtifactsRepository> = defaults
        .flatMap { it.repositories }
        .distinctBy { it.name }

    val repositoryMap = repositories.associateBy { it.name }

    override val plugins: List<KotlinPluginDescriptor> = defaults
        .flatMap { it.plugins }
        .distinctBy { it.name }

    val pluginMap = plugins.associateBy { it.name }
}

object KotlinxRpcDefaults : DefaultStateEntry {
    const val REPO_URL = "https://maven.pkg.jetbrains.space/public/p/krpc/for-ide"

    val devRepo = KotlinArtifactsRepository(
        name = "kotlinx-rpc for IDE",
        value = REPO_URL,
        type = KotlinArtifactsRepository.Type.URL,
    )

    fun pluginDescriptor(suffix: String) = KotlinPluginDescriptor(
        name = "kotlinx-rpc $suffix",
        id = "org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-$suffix",
        repositories = listOf(devRepo),
    )

    val pluginDescriptorCli = pluginDescriptor("cli")
    val pluginDescriptorK2 = pluginDescriptor("k2")
    val pluginDescriptorBackend = pluginDescriptor("backend")
    val pluginDescriptorCommon = pluginDescriptor("common")

    override val repositories = listOf(
        devRepo,
    )

    override val plugins = listOf(
        pluginDescriptorCli,
        pluginDescriptorK2,
        pluginDescriptorBackend,
        pluginDescriptorCommon,
    )
}

data class KotlinPluginDescriptorVersioned(
    val descriptor: KotlinPluginDescriptor,
    val version: String?,
)
