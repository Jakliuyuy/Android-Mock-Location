package com.mockloc.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCheckerTest {

    @Test
    fun `denies foreground location service start when neither FINE nor COARSE location is granted`() {
        val result = PermissionChecker.evaluateStartForegroundEligibility(
            hasFineLocation = false,
            hasCoarseLocation = false,
            hasNotification = true
        )
        assertFalse(result.hasLocationPermission)
        assertFalse(result.canStartForegroundLocation)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `allows foreground location service when either FINE or COARSE is granted`() {
        val fineOnly = PermissionChecker.evaluateStartForegroundEligibility(
            hasFineLocation = true,
            hasCoarseLocation = false,
            hasNotification = false
        )
        assertTrue(fineOnly.hasLocationPermission)
        assertTrue(fineOnly.canStartForegroundLocation)
        assertFalse(fineOnly.hasNotificationPermission)
        assertNull(fineOnly.errorMessage)

        val coarseOnly = PermissionChecker.evaluateStartForegroundEligibility(
            hasFineLocation = false,
            hasCoarseLocation = true,
            hasNotification = true
        )
        assertTrue(coarseOnly.hasLocationPermission)
        assertTrue(coarseOnly.canStartForegroundLocation)
        assertTrue(coarseOnly.hasNotificationPermission)
        assertNull(coarseOnly.errorMessage)
    }
}
