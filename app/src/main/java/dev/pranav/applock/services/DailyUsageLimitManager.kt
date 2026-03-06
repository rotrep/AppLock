package dev.pranav.applock.services

import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyUsageLimitManager(
    private val appLockRepository: AppLockRepository,
    private val tag: String
) {
    private var activePackage: String? = null
    private var activePackageStartMs: Long = 0L

    fun handleForegroundPackageChange(packageName: String, now: Long): UsageDecision {
        flushUsage(now)

        val remaining = appLockRepository.getRemainingDailyTimeMillis(packageName, currentDayKey(now))
        if (remaining == null) {
            activePackage = null
            activePackageStartMs = 0L
            return UsageDecision.LOCK_WITH_PASSWORD
        }

        if (remaining <= 0L) {
            activePackage = null
            activePackageStartMs = 0L
            return UsageDecision.LOCK_WITH_PASSWORD
        }

        activePackage = packageName
        activePackageStartMs = now
        return UsageDecision.ALLOW_WITHOUT_PASSWORD
    }

    fun tick(now: Long): UsageTickResult {
        val pkg = activePackage ?: return UsageTickResult.None
        val elapsed = (now - activePackageStartMs).coerceAtLeast(0L)
        if (elapsed <= 0L) return UsageTickResult.None

        val currentDay = currentDayKey(now)
        val updatedUsed = appLockRepository.addUsedDailyTimeMillis(pkg, elapsed, currentDay)
        activePackageStartMs = now

        val remaining = appLockRepository.getRemainingDailyTimeMillis(pkg, currentDay) ?: return UsageTickResult.None
        LogUtils.d(tag, "Tracked ${elapsed}ms foreground for $pkg. used=$updatedUsed, remaining=$remaining")

        return if (remaining <= 0L) {
            activePackage = null
            activePackageStartMs = 0L
            UsageTickResult.LimitReached(pkg)
        } else {
            UsageTickResult.Tracked(pkg, remaining)
        }
    }

    fun clearActivePackage() {
        activePackage = null
        activePackageStartMs = 0L
    }

    fun flushUsage(now: Long) {
        tick(now)
    }

    private fun currentDayKey(timeMillis: Long): String {
        return DATE_FORMAT.format(Date(timeMillis))
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

sealed interface UsageDecision {
    data object LOCK_WITH_PASSWORD : UsageDecision
    data object ALLOW_WITHOUT_PASSWORD : UsageDecision
}

sealed interface UsageTickResult {
    data object None : UsageTickResult
    data class Tracked(val packageName: String, val remainingMillis: Long) : UsageTickResult
    data class LimitReached(val packageName: String) : UsageTickResult
}
