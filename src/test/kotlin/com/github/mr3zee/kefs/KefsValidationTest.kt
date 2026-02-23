package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class KefsValidationTest {

    // --- Version pattern tests ---

    @Test
    fun `empty version pattern returns error`() {
        val error = validateReplacementPatternVersion("")
        assertNotNull(error)
    }

    @Test
    fun `blank version pattern returns error`() {
        val error = validateReplacementPatternVersion("   ")
        assertNotNull(error)
    }

    @Test
    fun `version pattern missing kotlin-version returns error`() {
        val error = validateReplacementPatternVersion("<lib-version>")
        assertNotNull(error)
        assertTrue(error!!.contains("<kotlin-version>"))
    }

    @Test
    fun `version pattern missing lib-version returns error`() {
        val error = validateReplacementPatternVersion("<kotlin-version>")
        assertNotNull(error)
        assertTrue(error!!.contains("<lib-version>"))
    }

    @Test
    fun `version pattern with unmatched open angle bracket returns error`() {
        val error = validateReplacementPatternVersion("<kotlin-version>-<lib-version>-<bad")
        assertNotNull(error)
        assertTrue(error!!.contains("macro"))
    }

    @Test
    fun `version pattern with unmatched close angle bracket returns error`() {
        val error = validateReplacementPatternVersion("<kotlin-version>-<lib-version>-bad>")
        assertNotNull(error)
        assertTrue(error!!.contains("macro"))
    }

    @Test
    fun `version pattern with unknown macro returns error`() {
        val error = validateReplacementPatternVersion("<kotlin-version>-<lib-version>-<unknown>")
        assertNotNull(error)
        assertTrue(error!!.contains("macro"))
    }

    @Test
    fun `version pattern with forbidden character returns error`() {
        val error = validateReplacementPatternVersion("<kotlin-version>@<lib-version>")
        assertNotNull(error)
        assertTrue(error!!.contains("forbidden"))
    }

    @Test
    fun `valid version pattern returns null`() {
        val error = validateReplacementPatternVersion("<kotlin-version>-<lib-version>")
        assertNull(error)
    }

    @Test
    fun `valid complex version pattern with ij prefix returns null`() {
        val error = validateReplacementPatternVersion("<kotlin-version>-ij<lib-version>")
        assertNull(error)
    }

    @Test
    fun `version pattern allows dots and plus signs`() {
        val error = validateReplacementPatternVersion("<kotlin-version>+build.1-<lib-version>")
        assertNull(error)
    }

    // --- Jar pattern tests ---

    @Test
    fun `empty jar pattern returns error`() {
        val error = validateReplacementPatternJar("")
        assertNotNull(error)
    }

    @Test
    fun `jar pattern missing artifact-id returns error`() {
        val error = validateReplacementPatternJar("some-prefix")
        assertNotNull(error)
        assertTrue(error!!.contains("<artifact-id>"))
    }

    @Test
    fun `jar pattern containing dot-jar returns error`() {
        val error = validateReplacementPatternJar("<artifact-id>.jar")
        assertNotNull(error)
        assertTrue(error!!.contains(".jar"))
    }

    @Test
    fun `jar pattern with unmatched open angle bracket returns error`() {
        val error = validateReplacementPatternJar("<artifact-id>-<bad")
        assertNotNull(error)
    }

    @Test
    fun `jar pattern with unmatched close angle bracket returns error`() {
        val error = validateReplacementPatternJar("<artifact-id>-bad>")
        assertNotNull(error)
    }

    @Test
    fun `jar pattern with unknown macro returns error`() {
        val error = validateReplacementPatternJar("<artifact-id>-<unknown>")
        assertNotNull(error)
    }

    @Test
    fun `jar pattern with forbidden character dot returns error`() {
        val error = validateReplacementPatternJar("<artifact-id>.suffix")
        assertNotNull(error)
    }

    @Test
    fun `valid jar pattern returns null`() {
        val error = validateReplacementPatternJar("prefix-<artifact-id>")
        assertNull(error)
    }

    @Test
    fun `valid jar pattern with just artifact-id returns null`() {
        val error = validateReplacementPatternJar("<artifact-id>")
        assertNull(error)
    }

    // --- Regex tests ---

    @Test
    fun `mavenRegex matches valid maven coordinates`() {
        assertTrue(mavenRegex.matches("org.jetbrains:artifact-name"))
    }

    @Test
    fun `mavenRegex matches simple coordinates`() {
        assertTrue(mavenRegex.matches("com.example:my-lib"))
    }

    @Test
    fun `mavenRegex does not match string without colon`() {
        assertFalse(mavenRegex.matches("nocolon"))
    }

    @Test
    fun `mavenRegex does not match empty group`() {
        assertFalse(mavenRegex.matches(":artifact"))
    }

    @Test
    fun `mavenRegex does not match coordinates with spaces`() {
        assertFalse(mavenRegex.matches("org.jetbrains:artifact name"))
    }

    @Test
    fun `pluginNameRegex matches valid plugin name`() {
        assertTrue(pluginNameRegex.matches("my-plugin_1"))
    }

    @Test
    fun `pluginNameRegex matches simple name`() {
        assertTrue(pluginNameRegex.matches("kotlinx-rpc"))
    }

    @Test
    fun `pluginNameRegex does not match name with spaces`() {
        assertFalse(pluginNameRegex.matches("my plugin!"))
    }

    @Test
    fun `pluginNameRegex does not match name with special characters`() {
        assertFalse(pluginNameRegex.matches("plugin@1.0"))
    }

    @Test
    fun `pluginNameRegex does not match empty string`() {
        assertFalse(pluginNameRegex.matches(""))
    }
}
