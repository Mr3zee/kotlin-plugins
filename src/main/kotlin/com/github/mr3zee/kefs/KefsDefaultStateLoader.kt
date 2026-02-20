package com.github.mr3zee.kefs

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

internal object KefsDefaultStateLoader {
    fun loadState(): DefaultStateEntry {
        return object : DefaultStateEntry {
            private val loadedXml by lazy {
                val resource =
                    requireNotNull(KefsDefaultStateLoader::class.java.classLoader.getResource("defaults.xml")) {
                        "Failed to load defaults.xml resource"
                    }

                JDOMUtil.load(resource)
            }

            private val repositoryWithId: Map<String, KotlinArtifactsRepository> by lazy {
                parseRepositories(loadedXml).associateBy { it.id }.mapValues { (_, v) -> v.repository }
            }

            override val repositories: List<KotlinArtifactsRepository> by lazy {
                repositoryWithId.values.toList()
            }

            override val plugins: List<KotlinPluginDescriptor> by lazy {
                parsePlugins(loadedXml, repositoryWithId)
            }
        }
    }

    private fun parseRepositories(root: Element): List<KotlinArtifactsRepositoryWithId> {
        val repositoriesParent = root.getChild("repositories") ?: return emptyList()

        return repositoriesParent.getChildren("repository").map { repoEl ->
            val id = requireNotNull(repoEl.getAttributeValue("id")) {
                "repository/@id missing"
            }

            if (id.isBlank()) {
                error("repository/@id must not be blank for repository $id")
            }

            val name = requireNotNull(repoEl.getAttributeValue("name")) {
                "repository/@name missing"
            }

            if (name.isBlank()) {
                error("repository/@name must not be blank for repository $id")
            }

            val value = requireNotNull(repoEl.getAttributeValue("value")) {
                "repository/@value missing"
            }

            if (value.isBlank()) {
                error("repository/@value must not be blank for repository $name")
            }

            val typeStr = requireNotNull(repoEl.getAttributeValue("type")) {
                "repository/@type missing"
            }

            val type = when (typeStr) {
                "URL" -> KotlinArtifactsRepository.Type.URL
                "PATH" -> KotlinArtifactsRepository.Type.PATH
                else -> error("Unknown repository type: $typeStr for repository $name")
            }

            KotlinArtifactsRepositoryWithId(
                id = id,
                repository = KotlinArtifactsRepository(
                    name = name,
                    value = value,
                    type = type,
                ),
            )
        }
    }

    private fun parsePlugins(
        root: Element,
        repositoriesById: Map<String, KotlinArtifactsRepository>,
    ): List<KotlinPluginDescriptor> {
        val pluginsParent = root.getChild("plugins") ?: return emptyList()

        return pluginsParent.getChildren("plugin").map { pluginEl ->
            val name = requireNotNull(pluginEl.getAttributeValue("name")) {
                "plugin/@name missing"
            }

            if (name.isBlank()) {
                error("plugin/@name must not be blank for plugin $name")
            }

            if (!pluginNameRegex.matches(name)) {
                error("plugin/@name must comply with ${pluginNameRegex.pattern} for plugin $name")
            }

            val versionMatchingString = requireNotNull(pluginEl.getAttributeValue("versionMatching")) {
                "plugin/@versionMatching missing"
            }

            val versionMatching =
                KotlinPluginDescriptor.VersionMatching.entries.find { it.name == versionMatchingString }
                    ?: error("Unknown versionMatching: $versionMatchingString for plugin $name")

            val repoRefs = pluginEl.getChildren("repositoryRef").map { refEl ->
                val id = requireNotNull(refEl.getAttributeValue("id")) {
                    "repositoryRef/@id missing"
                }

                repositoriesById[id] ?: error("Unknown repositoryRef id: $id for plugin $name")
            }

            val ids = pluginEl.getChildren("artifact").mapNotNull { idEl ->
                val coordinates = requireNotNull(idEl.getAttributeValue("coordinates")) {
                    "plugin/@coordinates missing"
                }

                if (mavenRegex.matches(coordinates)) {
                    MavenId(coordinates)
                } else {
                    error("Invalid artifact coordinates: $coordinates for plugin $name")
                }
            }

            val replacement = pluginEl.getChild("replacement")?.let { replacementEl ->
                val version = requireNotNull(replacementEl.getChildText("version")) {
                    "replacement/version missing"
                }
                val detect = requireNotNull(replacementEl.getChildText("detect")) {
                    "replacement/detect missing"
                }
                val search = requireNotNull(replacementEl.getChildText("search")) {
                    "replacement/search missing"
                }

                val validateVersionPattern = validateReplacementPatternVersion(version)
                if (validateVersionPattern != null) {
                    error("Invalid replacement pattern replacement/version '$version': ${validateVersionPattern.message} for plugin $name")
                }

                val validateDetectPattern = validateReplacementPatternJar(detect)
                if (validateDetectPattern != null) {
                    error("Invalid replacement pattern replacement/detect '$detect': ${validateDetectPattern.message} for plugin $name")
                }

                val validateSearchPattern = validateReplacementPatternJar(search)
                if (validateSearchPattern != null) {
                    error("Invalid replacement pattern replacement/search '$search': ${validateSearchPattern.message} for plugin $name")
                }

                KotlinPluginDescriptor.Replacement(version, detect, search)
            }

            KotlinPluginDescriptor(
                name = name,
                ids = ids,
                versionMatching = versionMatching,
                repositories = repoRefs,
                enabled = true,
                ignoreExceptions = false,
                replacement = replacement,
            )
        }
    }
}

internal class KotlinArtifactsRepositoryWithId(val id: String, val repository: KotlinArtifactsRepository)
