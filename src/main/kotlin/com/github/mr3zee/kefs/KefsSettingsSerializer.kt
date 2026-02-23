package com.github.mr3zee.kefs

internal fun KefsSettings.State.distinct(): KefsSettings.State {
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

internal fun KefsSettings.State.asStored(): KefsSettings.StoredState {
    return KefsSettings.StoredState(
        repositories = repositories.associateBy { it.name }.mapValues { (_, v) ->
            "${v.value};${v.type.name}"
        },
        plugins = plugins.associateBy { it.name }.mapValues { (_, v) ->
            "${v.versionMatching.name};${v.enabled};${v.ignoreExceptions};${v.ids.joinToString(";") { it.id }}"
        },
        pluginsReplacements = plugins.associateBy { it.name }
            .filterValues { it.replacement != null }
            .mapValues { (_, v) ->
                "${v.replacement!!.version};${v.replacement.detect};${v.replacement.search}"
            },
        pluginsRepos = plugins.associateBy { it.name }.mapValues { (_, v) ->
            v.repositories.joinToString(";") { it.name }
        },
    )
}

internal fun KefsSettings.StoredState.asState(): KefsSettings.State {
    val mappedRepos = repositories.mapNotNull { (k, v) ->
        val list = v.split(";")
        val value = list.getOrNull(0) ?: return@mapNotNull null
        val type = list.getOrNull(1)?.let { typeName ->
            KotlinArtifactsRepository.Type.entries.find { it.name == typeName }
        } ?: return@mapNotNull null
        KotlinArtifactsRepository(k, value, type)
    }

    val repoByNames = mappedRepos.associateBy { it.name }

    return KefsSettings.State(
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
                repositories = pluginsRepos[name].orEmpty().split(";").mapNotNull { repoByNames[it] },
                replacement = pluginsReplacements[name]?.let {
                    val replacementList = it.split(";")

                    val version = replacementList.getOrNull(0) ?: return@let null
                    val detect = replacementList.getOrNull(1) ?: return@let null
                    val search = replacementList.getOrNull(2) ?: return@let null

                    if (validateReplacementPatternVersion(version) != null) {
                        return@let null
                    }

                    if (validateReplacementPatternJar(detect) != null) {
                        return@let null
                    }

                    if (validateReplacementPatternJar(search) != null) {
                        return@let null
                    }

                    KotlinPluginDescriptor.Replacement(version, detect, search)
                }
            )
        },
    )
}
