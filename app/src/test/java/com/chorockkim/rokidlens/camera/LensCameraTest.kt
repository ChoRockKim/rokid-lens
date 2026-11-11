package com.chorockkim.rokidlens.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensCameraTest {
    @Test
    fun picksTheSlowestFrameRateThatStillReachesTenFps() {
        assertEquals(10 to 15, LensCamera.pickFpsRange(listOf(15 to 15, 10 to 15, 30 to 30, 7 to 30, 5 to 8)))
        assertEquals(15 to 15, LensCamera.pickFpsRange(listOf(30 to 30, 15 to 15, 15 to 30)))
        assertEquals(10 to 10, LensCamera.pickFpsRange(listOf(10 to 10, 30 to 30)))
        assertNull(LensCamera.pickFpsRange(listOf(5 to 8)))
        assertNull(LensCamera.pickFpsRange(emptyList()))
    }
}
