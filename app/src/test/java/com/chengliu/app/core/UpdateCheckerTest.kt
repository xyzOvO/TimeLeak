package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateChecker.compareVersions("1.3.10", "1.3.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.10", "1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2", "1.2.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("v1.2.1", "1.2.1"))
        assertEquals(0, UpdateChecker.compareVersions("1.2.0", "1.2"))
    }
}
