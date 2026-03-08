package dev.pranav.applock.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockManagerDailyLimitTest {

    @After
    fun tearDown() {
        AppLockManager.resetDailyLimitTrackingState()
        AppLockManager.clearTemporarilyUnlockedApp()
        AppLockManager.appUnlockTimes.clear()
    }

    @Test
    fun `remaining time is computed from limit minus used seconds`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.app" to 300),
            dailyUsage = mutableMapOf("com.example.app" to 120)
        )

        val remaining = AppLockManager.getRemainingDailyLimitSeconds(
            store = store,
            packageName = "com.example.app",
            nowMillis = 10_000L
        )

        assertEquals(180, remaining)
        assertTrue(AppLockManager.shouldBypassLockByDailyLimit(store, "com.example.app", 10_000L))
    }

    @Test
    fun `package without configured limit returns null remaining and no bypass`() {
        val store = FakeDailyLimitStore()

        val remaining = AppLockManager.getRemainingDailyLimitSeconds(
            store = store,
            packageName = "com.example.none",
            nowMillis = 2_000L
        )

        assertNull(remaining)
        assertFalse(AppLockManager.shouldBypassLockByDailyLimit(store, "com.example.none", 2_000L))
    }

    @Test
    fun `zero or invalid limit does not act as configured limited app`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf(
                "com.example.zero" to 0,
                "com.example.negative" to -30
            ),
            dailyUsage = mutableMapOf(
                "com.example.zero" to 100,
                "com.example.negative" to 100
            )
        )

        assertFalse(AppLockManager.hasDailyLimitConfigured(store, "com.example.zero"))
        assertFalse(AppLockManager.hasDailyLimitConfigured(store, "com.example.negative"))
        assertNull(AppLockManager.getRemainingDailyLimitSeconds(store, "com.example.zero", 10_000L))
        assertNull(AppLockManager.getRemainingDailyLimitSeconds(store, "com.example.negative", 10_000L))
    }

    @Test
    fun `day reset is triggered before policy queries`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.app" to 60),
            dailyUsage = mutableMapOf("com.example.app" to 50),
            shouldResetOnCheck = true
        )

        val remaining = AppLockManager.getRemainingDailyLimitSeconds(
            store = store,
            packageName = "com.example.app",
            nowMillis = 5_000L
        )

        assertTrue(store.resetInvoked)
        assertEquals(60, remaining)
    }

    @Test
    fun `usage accrues on foreground transitions for tracked limited app`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf(
                "com.example.limited" to 600
            )
        )

        AppLockManager.onForegroundAppTransition(
            store = store,
            previousPackage = "",
            currentPackage = "com.example.limited",
            nowMillis = 1_000L
        )

        AppLockManager.onForegroundAppTransition(
            store = store,
            previousPackage = "com.example.limited",
            currentPackage = "com.example.other",
            nowMillis = 6_500L
        )

        assertEquals(5, store.getUsedSecondsForToday("com.example.limited"))
    }

    @Test
    fun `quick app switching accrues usage only while limited app is foreground`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.limited" to 600)
        )

        AppLockManager.onForegroundAppTransition(store, "", "com.example.limited", 1_000L)
        AppLockManager.onForegroundAppTransition(store, "com.example.limited", "com.example.other", 1_900L)
        AppLockManager.onForegroundAppTransition(store, "com.example.other", "com.example.limited", 2_000L)
        AppLockManager.onForegroundAppTransition(store, "com.example.limited", "com.example.other", 3_100L)

        assertEquals(1, store.getUsedSecondsForToday("com.example.limited"))
    }

    @Test
    fun `pause tracking simulates screen off or process death and accrues once`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.limited" to 600)
        )

        AppLockManager.onForegroundAppTransition(store, "", "com.example.limited", 10_000L)
        AppLockManager.pauseDailyLimitTracking(store, 13_250L)
        AppLockManager.onForegroundAppTransition(store, "", "com.example.limited", 20_000L)
        AppLockManager.onForegroundAppTransition(store, "com.example.limited", "com.example.other", 21_050L)

        assertEquals(4, store.getUsedSecondsForToday("com.example.limited"))
    }

    @Test
    fun `bypass is false when daily limit is exhausted`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.app" to 120),
            dailyUsage = mutableMapOf("com.example.app" to 120)
        )

        assertEquals(
            0,
            AppLockManager.getRemainingDailyLimitSeconds(
                store,
                "com.example.app",
                nowMillis = 10_000L
            )
        )
        assertFalse(AppLockManager.shouldBypassLockByDailyLimit(store, "com.example.app", 10_000L))
    }

    @Test
    fun `daily limit exhausted clears stale temporary unlock and grace timestamp`() {
        val store = FakeDailyLimitStore(
            dailyLimits = mutableMapOf("com.example.app" to 60),
            dailyUsage = mutableMapOf("com.example.app" to 60)
        )

        AppLockManager.temporarilyUnlockedApp = "com.example.app"
        AppLockManager.appUnlockTimes["com.example.app"] = 1234L

        val result = AppLockManager.enforceDailyLimitForLockDecision(
            store,
            packageName = "com.example.app",
            nowMillis = 10_000L
        )

        assertEquals(AppLockManager.DailyLimitEnforcementResult.LOCK_REQUIRED, result)
        assertFalse(AppLockManager.isAppTemporarilyUnlocked("com.example.app"))
        assertFalse(AppLockManager.appUnlockTimes.containsKey("com.example.app"))
    }

    @Test
    fun `app with limit removed keeps normal unlock state and no longer uses daily policy`() {
        val store = FakeDailyLimitStore(
            dailyUsage = mutableMapOf("com.example.app" to 120)
        )

        AppLockManager.temporarilyUnlockedApp = "com.example.app"
        AppLockManager.appUnlockTimes["com.example.app"] = 2222L

        val result = AppLockManager.enforceDailyLimitForLockDecision(
            store,
            packageName = "com.example.app",
            nowMillis = 10_000L
        )

        assertEquals(AppLockManager.DailyLimitEnforcementResult.NO_LIMIT_CONFIGURED, result)
        assertTrue(AppLockManager.isAppTemporarilyUnlocked("com.example.app"))
        assertTrue(AppLockManager.appUnlockTimes.containsKey("com.example.app"))
    }

    @Test
    fun `foreground transition does not accrue for package without configured limit`() {
        val store = FakeDailyLimitStore()

        AppLockManager.onForegroundAppTransition(
            store = store,
            previousPackage = "",
            currentPackage = "com.example.unlimited",
            nowMillis = 1_000L
        )

        AppLockManager.onForegroundAppTransition(
            store = store,
            previousPackage = "com.example.unlimited",
            currentPackage = "com.example.other",
            nowMillis = 6_000L
        )

        assertEquals(0, store.getUsedSecondsForToday("com.example.unlimited"))
    }

    private class FakeDailyLimitStore(
        private val dailyLimits: MutableMap<String, Int> = mutableMapOf(),
        private val dailyUsage: MutableMap<String, Int> = mutableMapOf(),
        private val shouldResetOnCheck: Boolean = false
    ) : AppLockManager.DailyLimitPolicyStore {
        var resetInvoked: Boolean = false

        override fun hasDailyLimit(packageName: String): Boolean = dailyLimits.containsKey(packageName)

        override fun getDailyLimit(packageName: String): Int? = dailyLimits[packageName]

        override fun getUsedSecondsForToday(packageName: String): Int = dailyUsage[packageName] ?: 0

        override fun incrementUsedSecondsForToday(packageName: String, additionalSeconds: Int): Int {
            val updated = (dailyUsage[packageName] ?: 0) + additionalSeconds
            dailyUsage[packageName] = updated
            return updated
        }

        override fun resetUsageIfDayChanged(): Boolean {
            resetInvoked = true
            if (!shouldResetOnCheck) return false
            dailyUsage.clear()
            return true
        }
    }
}
