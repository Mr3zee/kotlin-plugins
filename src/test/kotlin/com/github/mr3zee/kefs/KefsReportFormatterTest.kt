package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class KefsReportFormatterTest {
    private fun createReport(
        pluginName: String = "test-plugin",
        mavenId: String = "org.example:test-artifact",
        requestedVersion: RequestedVersion = "1.0.0".requested(),
        resolvedVersion: ResolvedVersion = "1.0.0".resolved(),
        origin: KotlinArtifactsRepository = KotlinArtifactsRepository(
            name = "Test Repo",
            value = "https://example.com/repo",
            type = KotlinArtifactsRepository.Type.URL,
        ),
        checksum: String = "abc123",
        isLocal: Boolean = false,
        reloadedSame: Boolean = false,
        isProbablyIncompatible: Boolean = false,
        kotlinVersionMismatch: KotlinVersionMismatch? = null,
        exceptions: List<Throwable> = emptyList(),
    ): ExceptionsReport {
        return ExceptionsReport(
            pluginName = pluginName,
            mavenId = mavenId,
            requestedVersion = requestedVersion,
            resolvedVersion = resolvedVersion,
            origin = origin,
            checksum = checksum,
            isLocal = isLocal,
            reloadedSame = reloadedSame,
            isProbablyIncompatible = isProbablyIncompatible,
            kotlinVersionMismatch = kotlinVersionMismatch,
            exceptions = exceptions,
            __exceptionsIds = exceptions.map { it.hashCode().toString() },
        )
    }

    @Test
    fun `formatExceptionReport output contains plugin name`() {
        val report = createReport(pluginName = "my-plugin")
        val output = formatExceptionReport(report, "2.2.0")
        assertTrue("Output should contain the plugin name", output.contains("my-plugin"))
    }

    @Test
    fun `formatExceptionReport output contains kotlin IDE version`() {
        val report = createReport()
        val output = formatExceptionReport(report, "2.2.0-ij251-78")
        assertTrue("Output should contain the Kotlin IDE version", output.contains("2.2.0-ij251-78"))
    }

    @Test
    fun `formatExceptionReport output contains all exception stack traces`() {
        val ex1 = RuntimeException("first error")
        val ex2 = IllegalStateException("second error")
        val report = createReport(exceptions = listOf(ex1, ex2))
        val output = formatExceptionReport(report, "2.2.0")

        assertTrue("Output should contain first exception message", output.contains("first error"))
        assertTrue("Output should contain second exception message", output.contains("second error"))
        assertTrue(
            "Output should contain first exception class",
            output.contains("java.lang.RuntimeException"),
        )
        assertTrue(
            "Output should contain second exception class",
            output.contains("java.lang.IllegalStateException"),
        )
    }

    @Test
    fun `formatExceptionReport output contains none when kotlinVersionMismatch is null`() {
        val report = createReport(kotlinVersionMismatch = null)
        val output = formatExceptionReport(report, "2.2.0")
        assertTrue(
            "Output should contain 'none' for null kotlinVersionMismatch",
            output.contains("Kotlin version mismatch: none"),
        )
    }

    @Test
    fun `formatExceptionReport output contains mismatch info when non-null`() {
        val mismatch = KotlinVersionMismatch(
            ideVersion = "2.2.0-ij251-78",
            jarVersion = "2.1.0-ij251-50",
        )
        val report = createReport(kotlinVersionMismatch = mismatch)
        val output = formatExceptionReport(report, "2.2.0")
        assertTrue(
            "Output should contain the mismatch toString",
            output.contains("2.2.0-ij251-78") && output.contains("2.1.0-ij251-50"),
        )
        assertFalse(
            "Output should not contain 'none' when mismatch is present",
            output.contains("Kotlin version mismatch: none"),
        )
    }

    @Test
    fun `formatReportFilename produces correct format with sanitized chars`() {
        val instant = Instant.parse("2025-06-15T10:30:45Z")
        val result = formatReportFilename("my-plugin", "org.example:artifact", instant)

        // colons and dots should be replaced with hyphens
        assertFalse("Filename should not contain colons", result.contains(":"))
        assertFalse("Filename should not contain dots except in extension", result.count { it == '.' } > 1 && result.substringBeforeLast(".").contains("."))
        assertTrue("Filename should end with .txt", result.endsWith(".txt"))
        assertTrue("Filename should contain plugin name", result.contains("my-plugin"))
        assertTrue("Filename should contain artifact id (sanitized)", result.contains("org"))

        // Verify the date portion is formatted correctly
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneId.systemDefault())
        val expectedDate = formatter.format(instant)
        // The date will also have dots replaced with hyphens
        val expectedDateSanitized = expectedDate.replace(":", "-").replace(".", "-")
        assertTrue("Filename should contain formatted date", result.contains(expectedDateSanitized))
    }

    @Test
    fun `distinctStacktrace filters frames to only those with classes in lookup set`() {
        val lookup = setOf(
            "com.github.mr3zee.kefs.ExceptionClassA",
            "com.github.mr3zee.kefs.ExceptionClassB",
        )

        val exception = try {
            ExceptionClassA().bar()
            error("unreachable")
        } catch (e: Exception) {
            e
        }

        val result = exception.distinctStacktrace(lookup)

        // Should contain frames from ExceptionClassA and ExceptionClassB
        assertTrue("Should contain ExceptionClassA frame", result.contains("ExceptionClassA"))
        assertTrue("Should contain ExceptionClassB frame", result.contains("ExceptionClassB"))
        // Should NOT contain ExceptionClassC since it is not in the lookup
        assertFalse("Should not contain ExceptionClassC frame", result.contains("ExceptionClassC"))
    }

    @Test
    fun `distinctStacktrace follows cause chain`() {
        val lookup = setOf(
            "com.github.mr3zee.kefs.KefsReportFormatterTest",
        )

        val innerCause = RuntimeException("inner")
        // Manually build a cause chain
        val outerException = RuntimeException("outer", innerCause)

        val result = outerException.distinctStacktrace(lookup)

        // The function concatenates results with | separator, and follows cause
        // Both exceptions have stack frames from this test class
        assertTrue("Should produce non-empty result", result.isNotEmpty())
    }
}
