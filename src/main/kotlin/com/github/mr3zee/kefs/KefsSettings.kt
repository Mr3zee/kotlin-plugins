package com.github.mr3zee.kefs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.ConcurrentHashMap
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
internal class KefsSettings(
    private val project: Project,
) : SerializablePersistentStateComponent<KefsSettings.StoredState>(
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
            project.service<KefsStorage>().clearState()
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
        val pluginsReplacements: Map<String, String> = emptyMap(),
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
    val replacement: Replacement?,
) {
    enum class VersionMatching {
        EXACT,
        SAME_MAJOR,
        LATEST,
    }

    // <PROJECT_ROOT>/compiler-plugin/compiler-plugin-k2/build/libs/compiler-plugin-k2-2.2.0-ij251-78-0.10.2.jar
    // compiler-plugin-k2-2.2.0-ij251-78-0.10.2.jar
    //
    // detect: <artifactId>-<kotlin-version>-<lib-version>
    // regex: (?<artifactId>[\w-]+?)-(?<kotlin_version>\d+\.\d+\.\d+(?:[\w-]+)?)-(?<lib_version>\d+\.\d+\.\d+(?:[\w-]+)?)
    // search: kotlinx-rpc-<artifactId>-<kotlin-version>-<lib-version>
    data class Replacement(
        val version: String,
        val detect: String,
        val search: String,
    ) {
        private val versionRegex by lazy {
            version
                .replace(
                    VersionMacro.KOTLIN_VERSION.macro,
                    "(?<${KOTLIN_VERSION_GROUP}>\\d+\\.\\d+\\.\\d+(?:(?:\\.|\\+|-)[\\w.+-]+)?)"
                )
                .replace(
                    VersionMacro.LIB_VERSION.macro,
                    "(?<${LIB_VERSION_GROUP}>\\d+\\.\\d+\\.\\d+(?:(?:\\.|\\+|-)[\\w.+-]+)?)"
                )
        }

        private val detectRegexMap by lazy { ConcurrentHashMap<String, Regex>() }

        fun getDetectPattern(mavenId: MavenId): Regex = detectRegexMap.computeIfAbsent(mavenId.id) {
            detect
                .replace(JarMacro.ARTIFACT_ID.macro, mavenId.artifactId)
                .plus("-${versionRegex}")
                .toRegex()
        }

        fun getVersionString(
            kotlinVersion: String,
            libVersion: String,
        ): String {
            return version
                .replace(VersionMacro.KOTLIN_VERSION.macro, kotlinVersion)
                .replace(VersionMacro.LIB_VERSION.macro, libVersion)
        }

        fun getDetectString(
            mavenId: MavenId,
            version: String,
        ): String {
            return detect
                .replace(JarMacro.ARTIFACT_ID.macro, mavenId.artifactId)
                .plus("-$version")
        }

        fun getArtifactString(
            mavenId: MavenId,
        ): String {
            return search
                .replace(JarMacro.ARTIFACT_ID.macro, mavenId.artifactId)
        }

        interface Marco {
            val macro: String
        }

        enum class VersionMacro(macro: String) : Marco {
            KOTLIN_VERSION("kotlin-version"),
            LIB_VERSION("lib-version"),
            ;

            override val macro: String = "<$macro>"
        }

        enum class JarMacro(macro: String) : Marco {
            ARTIFACT_ID("artifact-id"),
            ;

            override val macro: String = "<$macro>"
        }

        companion object {
            const val KOTLIN_VERSION_GROUP = "kotlinVersion"
            const val LIB_VERSION_GROUP = "libVersion"
        }
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

internal interface DefaultStateEntry {
    val repositories: List<KotlinArtifactsRepository>
    val plugins: List<KotlinPluginDescriptor>
}

internal object DefaultState : DefaultStateEntry by KefsDefaultStateLoader.loadState() {
    val repositoryMap = repositories.associateBy { it.name }
    val pluginMap = plugins.associateBy { it.name }
}
