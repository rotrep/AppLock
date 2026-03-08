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
