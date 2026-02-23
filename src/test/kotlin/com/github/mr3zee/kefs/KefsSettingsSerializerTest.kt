package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class KefsSettingsSerializerTest {

    // --- Round-trip tests ---

    @Test
    fun `round-trip state to stored and back preserves values`() {
        val repo = KotlinArtifactsRepository("maven-central", "https://repo1.maven.org/maven2", KotlinArtifactsRepository.Type.URL)
        val localRepo = KotlinArtifactsRepository("local", "/home/user/.m2/repository", KotlinArtifactsRepository.Type.PATH)

        val plugin = KotlinPluginDescriptor(
            name = "test-plugin",
            ids = listOf(MavenId("org.example:test-artifact")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = listOf(repo),
            replacement = null,
        )

        val state = KefsSettings.State(
            repositories = listOf(repo, localRepo),
            plugins = listOf(plugin),
        )

        val stored = state.asStored()
        val restored = stored.asState()

        assertEquals(2, restored.repositories.size)

        val restoredRepo = restored.repositories[0]
        assertEquals("maven-central", restoredRepo.name)
        assertEquals("https://repo1.maven.org/maven2", restoredRepo.value)
        assertEquals(KotlinArtifactsRepository.Type.URL, restoredRepo.type)

        val restoredLocal = restored.repositories[1]
        assertEquals("local", restoredLocal.name)
        assertEquals("/home/user/.m2/repository", restoredLocal.value)
        assertEquals(KotlinArtifactsRepository.Type.PATH, restoredLocal.type)

        assertEquals(1, restored.plugins.size)
        val restoredPlugin = restored.plugins[0]
        assertEquals("test-plugin", restoredPlugin.name)
        assertEquals(KotlinPluginDescriptor.VersionMatching.SAME_MAJOR, restoredPlugin.versionMatching)
        assertTrue(restoredPlugin.enabled)
        assertFalse(restoredPlugin.ignoreExceptions)
        assertEquals(1, restoredPlugin.ids.size)
        assertEquals("org.example:test-artifact", restoredPlugin.ids[0].id)
    }

    // --- Deserialize repo tests ---

    @Test
    fun `deserialize valid URL repo`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("my-repo" to "https://example.com;URL"),
        )

        val state = stored.asState()

        assertEquals(1, state.repositories.size)
        val repo = state.repositories[0]
        assertEquals("my-repo", repo.name)
        assertEquals("https://example.com", repo.value)
        assertEquals(KotlinArtifactsRepository.Type.URL, repo.type)
    }

    @Test
    fun `deserialize valid PATH repo`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("local-repo" to "/some/path;PATH"),
        )

        val state = stored.asState()

        assertEquals(1, state.repositories.size)
        assertEquals(KotlinArtifactsRepository.Type.PATH, state.repositories[0].type)
    }

    @Test
    fun `deserialize repo with invalid type is filtered out`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("bad-repo" to "value;INVALID"),
        )

        val state = stored.asState()

        assertEquals(0, state.repositories.size)
    }

    @Test
    fun `deserialize repo with missing type is filtered out`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("bad-repo" to "just-a-value"),
        )

        val state = stored.asState()

        assertEquals(0, state.repositories.size)
    }

    // --- Deserialize plugin tests ---

    @Test
    fun `deserialize valid plugin with all fields`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("my-repo" to "https://example.com;URL"),
            plugins = mapOf(
                "my-plugin" to "LATEST;true;false;org.example:my-artifact"
            ),
            pluginsRepos = mapOf("my-plugin" to "my-repo"),
            pluginsReplacements = emptyMap(),
        )

        val state = stored.asState()

        assertEquals(1, state.plugins.size)
        val plugin = state.plugins[0]
        assertEquals("my-plugin", plugin.name)
        assertEquals(KotlinPluginDescriptor.VersionMatching.LATEST, plugin.versionMatching)
        assertTrue(plugin.enabled)
        assertFalse(plugin.ignoreExceptions)
        assertEquals(1, plugin.ids.size)
        assertEquals("org.example:my-artifact", plugin.ids[0].id)
        assertEquals(1, plugin.repositories.size)
        assertEquals("my-repo", plugin.repositories[0].name)
        assertNull(plugin.replacement)
    }

    @Test
    fun `deserialize plugin with invalid versionMatching is filtered out`() {
        val stored = KefsSettings.StoredState(
            plugins = mapOf("bad-plugin" to "NONEXISTENT;true;false;org.example:artifact"),
        )

        val state = stored.asState()

        assertEquals(0, state.plugins.size)
    }

    @Test
    fun `deserialize plugin with invalid enabled boolean is filtered out`() {
        val stored = KefsSettings.StoredState(
            plugins = mapOf("bad-plugin" to "LATEST;notabool;false;org.example:artifact"),
        )

        val state = stored.asState()

        assertEquals(0, state.plugins.size)
    }

    @Test
    fun `deserialize plugin with replacement`() {
        val stored = KefsSettings.StoredState(
            repositories = mapOf("repo" to "https://example.com;URL"),
            plugins = mapOf("rpc-plugin" to "SAME_MAJOR;true;true;org.jetbrains.kotlinx:compiler-plugin-k2"),
            pluginsReplacements = mapOf(
                "rpc-plugin" to "<kotlin-version>-<lib-version>;<artifact-id>;kotlinx-rpc-<artifact-id>"
            ),
            pluginsRepos = mapOf("rpc-plugin" to "repo"),
        )

        val state = stored.asState()

        assertEquals(1, state.plugins.size)
        val plugin = state.plugins[0]
        assertNotNull(plugin.replacement)
        assertEquals("<kotlin-version>-<lib-version>", plugin.replacement!!.version)
        assertEquals("<artifact-id>", plugin.replacement.detect)
        assertEquals("kotlinx-rpc-<artifact-id>", plugin.replacement.search)
    }

    @Test
    fun `deserialize plugin with invalid replacement is filtered to null replacement`() {
        val stored = KefsSettings.StoredState(
            plugins = mapOf("bad-plugin" to "LATEST;true;false;org.example:artifact"),
            pluginsReplacements = mapOf(
                "bad-plugin" to "invalid-version-pattern;bad-detect;bad-search"
            ),
        )

        val state = stored.asState()

        assertEquals(1, state.plugins.size)
        assertNull(state.plugins[0].replacement)
    }

    // --- Serialize tests ---

    @Test
    fun `serialize repos correctly`() {
        val state = KefsSettings.State(
            repositories = listOf(
                KotlinArtifactsRepository("remote", "https://repo.example.com", KotlinArtifactsRepository.Type.URL),
                KotlinArtifactsRepository("local", "/path/to/repo", KotlinArtifactsRepository.Type.PATH),
            ),
            plugins = emptyList(),
        )

        val stored = state.asStored()

        assertEquals("https://repo.example.com;URL", stored.repositories["remote"])
        assertEquals("/path/to/repo;PATH", stored.repositories["local"])
    }

    @Test
    fun `serialize plugins with replacement`() {
        val replacement = KotlinPluginDescriptor.Replacement(
            version = "<kotlin-version>-<lib-version>",
            detect = "<artifact-id>",
            search = "kotlinx-rpc-<artifact-id>",
        )

        val plugin = KotlinPluginDescriptor(
            name = "rpc",
            ids = listOf(MavenId("org.jetbrains.kotlinx:plugin-k2"), MavenId("org.jetbrains.kotlinx:plugin-backend")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.SAME_MAJOR,
            enabled = true,
            ignoreExceptions = false,
            repositories = emptyList(),
            replacement = replacement,
        )

        val state = KefsSettings.State(repositories = emptyList(), plugins = listOf(plugin))
        val stored = state.asStored()

        assertTrue(stored.plugins.containsKey("rpc"))
        val pluginValue = stored.plugins["rpc"]!!
        assertTrue(pluginValue.startsWith("SAME_MAJOR;true;false;"))
        assertTrue(pluginValue.contains("org.jetbrains.kotlinx:plugin-k2"))
        assertTrue(pluginValue.contains("org.jetbrains.kotlinx:plugin-backend"))

        assertEquals(
            "<kotlin-version>-<lib-version>;<artifact-id>;kotlinx-rpc-<artifact-id>",
            stored.pluginsReplacements["rpc"]
        )
    }

    @Test
    fun `serialize plugin without replacement has no entry in pluginsReplacements`() {
        val plugin = KotlinPluginDescriptor(
            name = "simple-plugin",
            ids = listOf(MavenId("org.example:artifact")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.EXACT,
            enabled = false,
            ignoreExceptions = true,
            repositories = emptyList(),
            replacement = null,
        )

        val state = KefsSettings.State(repositories = emptyList(), plugins = listOf(plugin))
        val stored = state.asStored()

        assertFalse(stored.pluginsReplacements.containsKey("simple-plugin"))

        val pluginValue = stored.plugins["simple-plugin"]!!
        assertTrue(pluginValue.startsWith("EXACT;false;true;"))
    }

    // --- distinct() tests ---

    @Test
    fun `distinct deduplicates repos by name keeping first`() {
        val repo1 = KotlinArtifactsRepository("repo", "https://first.com", KotlinArtifactsRepository.Type.URL)
        val repo2 = KotlinArtifactsRepository("repo", "https://second.com", KotlinArtifactsRepository.Type.URL)
        val repo3 = KotlinArtifactsRepository("other", "https://other.com", KotlinArtifactsRepository.Type.URL)

        val state = KefsSettings.State(
            repositories = listOf(repo1, repo2, repo3),
            plugins = emptyList(),
        )

        val result = state.distinct()

        assertEquals(2, result.repositories.size)
        assertEquals("https://first.com", result.repositories.find { it.name == "repo" }!!.value)
        assertEquals("https://other.com", result.repositories.find { it.name == "other" }!!.value)
    }

    @Test
    fun `distinct filters plugin repos to only existing repo names`() {
        val existingRepo = KotlinArtifactsRepository("existing", "https://existing.com", KotlinArtifactsRepository.Type.URL)
        val removedRepo = KotlinArtifactsRepository("removed", "https://removed.com", KotlinArtifactsRepository.Type.URL)

        val plugin = KotlinPluginDescriptor(
            name = "my-test-plugin",
            ids = listOf(MavenId("org.example:test")),
            versionMatching = KotlinPluginDescriptor.VersionMatching.LATEST,
            enabled = true,
            ignoreExceptions = false,
            repositories = listOf(existingRepo, removedRepo),
            replacement = null,
        )

        val state = KefsSettings.State(
            repositories = listOf(existingRepo), // only 'existing' is in the repo list
            plugins = listOf(plugin),
        )

        val result = state.distinct()

        assertEquals(1, result.plugins.size)
        val resultPlugin = result.plugins[0]
        assertEquals(1, resultPlugin.repositories.size)
        assertEquals("existing", resultPlugin.repositories[0].name)
    }
}
