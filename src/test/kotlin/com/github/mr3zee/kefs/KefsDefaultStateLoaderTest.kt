package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class KefsDefaultStateLoaderTest {
    private val state = KefsDefaultStateLoader.loadState()

    @Test
    fun `loadState returns non-empty repositories list`() {
        assertTrue(
            "Repositories list should not be empty",
            state.repositories.isNotEmpty(),
        )
    }

    @Test
    fun `loadState returns non-empty plugins list`() {
        assertTrue(
            "Plugins list should not be empty",
            state.plugins.isNotEmpty(),
        )
    }

    @Test
    fun `loadState default repos have correct types - both URL`() {
        for (repo in state.repositories) {
            assertEquals(
                "Default repository '${repo.name}' should be of type URL",
                KotlinArtifactsRepository.Type.URL,
                repo.type,
            )
        }
    }

    @Test
    fun `loadState default plugin has correct version matching - EXACT`() {
        for (plugin in state.plugins) {
            assertEquals(
                "Default plugin '${plugin.name}' should use EXACT version matching",
                KotlinPluginDescriptor.VersionMatching.EXACT,
                plugin.versionMatching,
            )
        }
    }

    @Test
    fun `loadState all default plugin artifact IDs match maven regex`() {
        for (plugin in state.plugins) {
            for (id in plugin.ids) {
                assertTrue(
                    "Artifact ID '${id.id}' in plugin '${plugin.name}' should match maven regex pattern",
                    mavenRegex.matches(id.id),
                )
            }
        }
    }
}
