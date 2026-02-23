package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class VersionMatchingTest {

    // --- EXACT matching ---

    @Test
    fun `EXACT version present returns correct resolved version`() {
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-1.1.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.1.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.1.0", result!!.value)
    }

    @Test
    fun `EXACT version absent returns null`() {
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-1.1.0"))
        val filter = MatchFilter("9.9.9".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNull(result)
    }

    @Test
    fun `EXACT match takes precedence even with LATEST matching`() {
        // The exact check runs before the matching strategy switch
        val versions = listOf(listOf("2.2.0-1.0.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("1.0.0", result!!.value)
    }

    // --- SAME_MAJOR matching ---

    @Test
    fun `SAME_MAJOR selects highest in same major`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-1.0.0", "prefix-1.2.0", "prefix-1.5.0", "prefix-2.0.0"))
        val filter = MatchFilter("1.0.1".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("1.5.0", result!!.value)
    }

    @Test
    fun `SAME_MAJOR with no versions in same major returns null`() {
        val versions = listOf(listOf("prefix-2.0.0", "prefix-3.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `SAME_MAJOR with 0-dot-x versions matches by minor`() {
        // When major is "0", matching uses minor version instead
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-0.1.0", "prefix-0.1.5", "prefix-0.2.0", "prefix-0.2.3"))
        val filter = MatchFilter("0.1.1".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("0.1.5", result!!.value)
    }

    @Test
    fun `SAME_MAJOR requires resolved at least requested`() {
        // Only version 1.0.0 is in major 1, but requested is 1.5.0 which is higher
        val versions = listOf(listOf("prefix-1.0.0", "prefix-2.0.0"))
        val filter = MatchFilter("1.5.0".requested(), KotlinPluginDescriptor.VersionMatching.SAME_MAJOR)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    // --- LATEST matching ---

    @Test
    fun `LATEST selects absolute highest version`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("prefix-1.0.0", "prefix-2.0.0", "prefix-3.0.0"))
        val filter = MatchFilter("0.9.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("3.0.0", result!!.value)
    }

    @Test
    fun `LATEST with different max across multiple artifact lists returns null`() {
        // Two artifact lists have different maximums, so distinct().singleOrNull() returns null
        // Use a requested version NOT in any list so the exact-match shortcut does not fire
        val versions = listOf(
            listOf("prefix-1.0.0", "prefix-3.0.0"),
            listOf("prefix-1.0.0", "prefix-2.0.0"),
        )
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `LATEST with same max across multiple artifact lists returns that version`() {
        // Use a requested version NOT in any list so the exact-match shortcut does not fire
        val versions = listOf(
            listOf("prefix-1.0.0", "prefix-3.0.0"),
            listOf("prefix-2.0.0", "prefix-3.0.0"),
        )
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNotNull(result)
        assertEquals("3.0.0", result!!.value)
    }

    @Test
    fun `LATEST requires resolved at least requested`() {
        val versions = listOf(listOf("prefix-1.0.0"))
        val filter = MatchFilter("2.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    // --- Edge cases ---

    @Test
    fun `empty version list returns null`() {
        val versions = emptyList<List<String>>()
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `empty inner list returns null`() {
        val versions = listOf(emptyList<String>())
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    @Test
    fun `prefix filtering works correctly`() {
        // Use a requested version NOT in the list so the exact-match shortcut does not fire
        val versions = listOf(listOf("2.2.0-1.0.0", "2.1.0-1.0.0", "2.2.0-2.0.0"))
        val filter = MatchFilter("0.5.0".requested(), KotlinPluginDescriptor.VersionMatching.LATEST)

        // Only versions starting with "2.2.0-" should be considered (i.e. 1.0.0 and 2.0.0)
        val result = getMatching(versions, "2.2.0-", filter)

        assertNotNull(result)
        assertEquals("2.0.0", result!!.value)
    }

    @Test
    fun `prefix filtering excludes non-matching versions`() {
        val versions = listOf(listOf("other-1.0.0", "other-2.0.0"))
        val filter = MatchFilter("1.0.0".requested(), KotlinPluginDescriptor.VersionMatching.EXACT)

        val result = getMatching(versions, "prefix-", filter)

        assertNull(result)
    }

    // --- JarId equality ---

    @Test
    fun `JarId equality ignores resolvedVersion`() {
        val id1 = JarId("plugin", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art", "1.0.0".requested(), "2.0.0".resolved())

        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `JarId not equal when pluginName differs`() {
        val id1 = JarId("plugin-a", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin-b", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    @Test
    fun `JarId not equal when mavenId differs`() {
        val id1 = JarId("plugin", "org.example:art-a", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art-b", "1.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    @Test
    fun `JarId not equal when requestedVersion differs`() {
        val id1 = JarId("plugin", "org.example:art", "1.0.0".requested(), "1.0.0".resolved())
        val id2 = JarId("plugin", "org.example:art", "2.0.0".requested(), "1.0.0".resolved())

        assertNotEquals(id1, id2)
    }

    // --- RequestedPluginKey equality ---

    @Test
    fun `RequestedPluginKey equality works correctly`() {
        val key1 = RequestedPluginKey("org.example:art", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art", "1.0.0".requested())

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `RequestedPluginKey not equal when mavenId differs`() {
        val key1 = RequestedPluginKey("org.example:art-a", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art-b", "1.0.0".requested())

        assertNotEquals(key1, key2)
    }

    @Test
    fun `RequestedPluginKey not equal when requestedVersion differs`() {
        val key1 = RequestedPluginKey("org.example:art", "1.0.0".requested())
        val key2 = RequestedPluginKey("org.example:art", "2.0.0".requested())

        assertNotEquals(key1, key2)
    }
}
