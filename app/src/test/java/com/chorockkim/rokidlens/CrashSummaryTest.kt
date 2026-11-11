package com.chorockkim.rokidlens

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashSummaryTest {
    @Test
    fun summaryNamesTheRootCauseAndWhereItHappened() {
        val e = RuntimeException("wrapper", OutOfMemoryError("Failed to allocate 48 MB"))
        val s = RokidLensApp.crashSummary(e)
        assertTrue(s, s.startsWith("OutOfMemoryError: Failed to allocate 48 MB"))
        assertTrue(s, s.contains("CrashSummaryTest.kt:"))
    }
}
