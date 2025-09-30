package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

object DefaultStateLoader {
    fun loadState(): DefaultStateEntry {
        return object : DefaultStateEntry {
            private val loadedXml by lazy {
                val resource = requireNotNull(DefaultStateLoader::class.java.classLoader.getResource("defaults.xml")) {
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
            // Attributes exist per schema; use safe access with require for meaningful message
            val id = requireNotNull(repoEl.getAttributeValue("id")) {
                "repository/@id missing"
            }

            val name = requireNotNull(repoEl.getAttributeValue("name")) {
                "repository/@name missing"
            }

            val value = requireNotNull(repoEl.getAttributeValue("value")) {
                "repository/@value missing"
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

            val coordinates = requireNotNull(pluginEl.getAttributeValue("coordinates")) {
                "plugin/@coordinates missing"
            }

            val versionMatchingString = requireNotNull(pluginEl.getAttributeValue("versionMatching")) {
                "plugin/@versionMatching missing"
            }

            val versionMatching = KotlinPluginDescriptor.VersionMatching.entries.find { it.name == versionMatchingString }
                ?: error("Unknown versionMatching: $versionMatchingString for plugin $name")

            val repoRefs = pluginEl.getChildren("repositoryRef").map { refEl ->
                val id = requireNotNull(refEl.getAttributeValue("id")) {
                    "repositoryRef/@id missing"
                }

                repositoriesById[id] ?: error("Unknown repositoryRef id: $id for plugin $name")
            }

            KotlinPluginDescriptor(
                name = name,
                id = coordinates,
                versionMatching = versionMatching,
                repositories = repoRefs,
            )
        }
    }
}

class KotlinArtifactsRepositoryWithId(val id: String, val repository: KotlinArtifactsRepository)
