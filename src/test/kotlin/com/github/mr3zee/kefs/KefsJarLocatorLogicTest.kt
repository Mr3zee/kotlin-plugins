package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class KefsJarLocatorLogicTest {

    // -- parseManifestXmlToVersions tests --

    @Test
    fun `parseManifestXmlToVersions valid maven metadata returns version list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.example</groupId>
              <artifactId>my-artifact</artifactId>
              <versioning>
                <latest>2.0.0</latest>
                <release>2.0.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.5.0</version>
                  <version>2.0.0</version>
                </versions>
                <lastUpdated>20250101000000</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()

        val versions = parseManifestXmlToVersions(xml)
        assertEquals(listOf("1.0.0", "1.5.0", "2.0.0"), versions)
    }

    @Test
    fun `parseManifestXmlToVersions malformed XML returns empty list`() {
        val xml = "this is not xml at all <><><>"
        val versions = parseManifestXmlToVersions(xml)
        assertEquals(emptyList<String>(), versions)
    }

    @Test
    fun `parseManifestXmlToVersions missing versioning returns empty list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.example</groupId>
              <artifactId>my-artifact</artifactId>
            </metadata>
        """.trimIndent()

        val versions = parseManifestXmlToVersions(xml)
        assertEquals(emptyList<String>(), versions)
    }

    // -- accumulate tests --

    @Test
    fun `accumulate single FailedToFetch returned as-is`() {
        val failedToFetch = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("connection timeout"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(failedToFetch),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        assertEquals("connection timeout", (result as LocatorResult.FailedToFetch).state.message)
    }

    @Test
    fun `accumulate multiple FailedToFetch returns aggregated message`() {
        val f1 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("timeout from repo1"),
        )
        val f2 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("timeout from repo2"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(f1, f2),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        val message = (result as LocatorResult.FailedToFetch).state.message
        assertTrue("Should contain repo1 message", message.contains("timeout from repo1"))
        assertTrue("Should contain repo2 message", message.contains("timeout from repo2"))
    }

    @Test
    fun `accumulate single NotFound returned as-is`() {
        val notFound = LocatorResult.NotFound(
            state = ArtifactState.NotFound("no matching version"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = listOf(notFound),
            cached = null,
        )

        assertTrue("Should return NotFound", result is LocatorResult.NotFound)
        assertEquals("no matching version", (result as LocatorResult.NotFound).state.message)
    }

    @Test
    fun `accumulate multiple NotFound returns aggregated message`() {
        val n1 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not in repo1"),
        )
        val n2 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not in repo2"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = listOf(n1, n2),
            cached = null,
        )

        assertTrue("Should return NotFound", result is LocatorResult.NotFound)
        val message = (result as LocatorResult.NotFound).state.message
        assertTrue("Should contain repo1 message", message.contains("not in repo1"))
        assertTrue("Should contain repo2 message", message.contains("not in repo2"))
    }

    @Test
    fun `accumulate mixed failures and notFound returns combined FailedToFetch message`() {
        val f1 = LocatorResult.FailedToFetch(
            state = ArtifactState.FailedToFetch("fetch error"),
        )
        val n1 = LocatorResult.NotFound(
            state = ArtifactState.NotFound("not found error"),
        )

        val result = KefsJarLocator.accumulate(
            failedToFetch = listOf(f1),
            notFound = listOf(n1),
            cached = null,
        )

        assertTrue("Should return FailedToFetch for mixed failures", result is LocatorResult.FailedToFetch)
        val message = (result as LocatorResult.FailedToFetch).state.message
        assertTrue("Should contain fetch error", message.contains("fetch error"))
        assertTrue("Should contain not found error", message.contains("not found error"))
    }

    @Test
    fun `accumulate nothing returns unknown error FailedToFetch`() {
        val result = KefsJarLocator.accumulate(
            failedToFetch = emptyList(),
            notFound = emptyList(),
            cached = null,
        )

        assertTrue("Should return FailedToFetch", result is LocatorResult.FailedToFetch)
        assertEquals("Unknown error", (result as LocatorResult.FailedToFetch).state.message)
    }
}
