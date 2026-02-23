package com.github.mr3zee.kefs

import org.junit.Assert.*
import org.junit.Test

class ExceptionAnalysisTest {
    @Test
    fun `match finds intersection across stack frames`() {
        val exception = try {
            ExceptionClassA().bar()
            error("unreachable")
        } catch (e: Exception) {
            e
        }

        val lookup = mapOf(
            JarId("plugin", "maven", "1.0".requested(), "1.0".resolved()) to setOf(
                "com.github.mr3zee.kefs.ExceptionClassA",
                "com.github.mr3zee.kefs.ExceptionClassB",
                "com.github.mr3zee.kefs.ExceptionClassC",
            ),
            JarId("plugin", "maven", "2.0".requested(), "2.0".resolved()) to setOf(
                "com.github.mr3zee.kefs.ExceptionClassA",
                "com.github.mr3zee.kefs.ExceptionClassB",
            ),
        )

        val result = KefsExceptionAnalyzerService.match(lookup, exception)
        assertNotNull("Should find matching JarIds", result)
        // ExceptionClassC is only in "1.0" and frame from C appears in the trace,
        // so only "1.0" should survive the intersection
        val versions = result!!.map { it.requestedVersion.value }.toSet()
        assertTrue("Should contain version 1.0", versions.contains("1.0"))
    }

    @Test
    fun `match follows cause chain when no direct match`() {
        // The inner exception has the matching frames, the outer does not
        val inner = try {
            ExceptionClassA().bar()
            error("unreachable")
        } catch (e: Exception) {
            e
        }

        val outer = RuntimeException("wrapper with no plugin frames", inner)

        val lookup = mapOf(
            JarId("plugin", "maven", "1.0".requested(), "1.0".resolved()) to setOf(
                "com.github.mr3zee.kefs.ExceptionClassA",
                "com.github.mr3zee.kefs.ExceptionClassB",
                "com.github.mr3zee.kefs.ExceptionClassC",
            ),
        )

        val result = KefsExceptionAnalyzerService.match(lookup, outer)
        assertNotNull("Should find match through cause chain", result)
        assertEquals(1, result!!.size)
        assertEquals("1.0", result.first().requestedVersion.value)
    }

    @Test
    fun `match returns null when no match at all`() {
        val exception = RuntimeException("no matching frames")

        val lookup = mapOf(
            JarId("plugin", "maven", "1.0".requested(), "1.0".resolved()) to setOf(
                "com.nonexistent.SomeClass",
            ),
        )

        val result = KefsExceptionAnalyzerService.match(lookup, exception)
        assertNull("Should return null when no frames match", result)
    }

    // -- isProbablyIncompatible() tests --

    @Test
    fun `isProbablyIncompatible ClassNotFoundException returns true`() {
        val ex = ClassNotFoundException("some.Class")
        assertTrue(ex.isProbablyIncompatible())
    }

    @Test
    fun `isProbablyIncompatible NoClassDefFoundError returns true`() {
        val ex = NoClassDefFoundError("some/Class")
        assertTrue(ex.isProbablyIncompatible())
    }

    @Test
    fun `isProbablyIncompatible LinkageError returns true`() {
        val ex = LinkageError("linkage issue")
        assertTrue(ex.isProbablyIncompatible())
    }

    @Test
    fun `isProbablyIncompatible ClassCastException returns true`() {
        val ex = ClassCastException("cannot cast")
        assertTrue(ex.isProbablyIncompatible())
    }

    @Test
    fun `isProbablyIncompatible RuntimeException returns false`() {
        val ex = RuntimeException("just a runtime exception")
        assertFalse(ex.isProbablyIncompatible())
    }

    @Test
    fun `isProbablyIncompatible cause chain with nested incompatible returns true`() {
        val inner = ClassNotFoundException("some.Class")
        val outer = RuntimeException("wrapper", inner)
        assertTrue(
            "Should detect incompatibility through cause chain",
            outer.isProbablyIncompatible(),
        )
    }
}
