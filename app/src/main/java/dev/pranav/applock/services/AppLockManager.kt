package dev.pranav.applock.services

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object AppLockConstants {
    val KNOWN_RECENTS_CLASSES = setOf(
        "com.android.systemui.recents.RecentsActivity",
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsView",
        "com.android.systemui.recents.RecentsPanelView",
    )

    val EXCLUDED_APPS = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "android.uid.system:1000",
        "com.google.android.googlequicksearchbox",
        "android",
        "com.google.android.gms",
        "com.google.android.webview"
    )

    val ACCESSIBILITY_SETTINGS_CLASSES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.AccessibilityMenuActivity",
        "com.android.settings.accessibility.AccessibilityShortcutActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity"
    )

    const val MAX_RESTART_ATTEMPTS = 3
    const val RESTART_COOLDOWN_MS = 30000L
    const val RESTART_INTERVAL_MS = 5000L
}

fun Context.isDeviceLocked(): Boolean {
    val keyguardManager = getSystemService(KeyguardManager::class.java)
    return keyguardManager?.isKeyguardLocked ?: false
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { serviceClass.name == it.service.className }
}

object AppLockManager {
    private const val TAG = "AppLockManager"

    var temporarilyUnlockedApp: String = ""
    val appUnlockTimes = ConcurrentHashMap<String, Long>()
    val isLockScreenShown = AtomicBoolean(false)
    var currentBiometricState: Any? = null

    // Grace period tracking
    private var recentlyLeftApp: String = ""
    private var recentlyLeftTime: Long = 0L
    private const val GRACE_PERIOD_MS = 300L

    fun setRecentlyLeftApp(packageName: String) {
        recentlyLeftApp = packageName
        recentlyLeftTime = System.currentTimeMillis()
        LogUtils.d(TAG, "Left app $packageName at $recentlyLeftTime")
    }

    fun checkAndRestoreRecentlyLeftApp(packageName: String): Boolean {
        // If we are returning to the same app we just left within the grace period
        if (packageName == recentlyLeftApp && packageName.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - recentlyLeftTime
            if (elapsed <= GRACE_PERIOD_MS) {
                LogUtils.d(TAG, "Restoring unlock state for $packageName (elapsed: ${elapsed}ms)")
                temporarilyUnlockedApp = packageName
                // Clear the tracking so it doesn't trigger again inappropriately
                recentlyLeftApp = ""
                recentlyLeftTime = 0L
                return true
            } else {
                LogUtils.d(TAG, "Grace period expired for $packageName (elapsed: ${elapsed}ms)")
                recentlyLeftApp = "" // Expired
            }
        }
        return false
    }

    private val serviceRestartAttempts = ConcurrentHashMap<String, Int>()
    private val lastRestartTime = ConcurrentHashMap<String, Long>()

    private var trackedForegroundPackageForDailyLimit: String = ""
    private var trackedForegroundStartTimeMillis: Long = 0L

    interface DailyLimitPolicyStore {
        fun hasDailyLimit(packageName: String): Boolean
        fun getDailyLimit(packageName: String): Int?
        fun getUsedSecondsForToday(packageName: String): Int
        fun incrementUsedSecondsForToday(packageName: String, additionalSeconds: Int): Int
        fun resetUsageIfDayChanged(): Boolean
    }

    enum class DailyLimitEnforcementResult {
        NO_LIMIT_CONFIGURED,
        BYPASS_ALLOWED,
        LOCK_REQUIRED
    }

    private class RepositoryDailyLimitPolicyStore(
        private val repository: AppLockRepository
    ) : DailyLimitPolicyStore {
        override fun hasDailyLimit(packageName: String): Boolean = repository.hasDailyLimit(packageName)
        override fun getDailyLimit(packageName: String): Int? = repository.getDailyLimit(packageName)
        override fun getUsedSecondsForToday(packageName: String): Int =
            repository.getUsedSecondsForToday(packageName)

        override fun incrementUsedSecondsForToday(packageName: String, additionalSeconds: Int): Int =
            repository.incrementUsedSecondsForToday(packageName, additionalSeconds)

        override fun resetUsageIfDayChanged(): Boolean = repository.resetUsageIfDayChanged()
    }

    private val ALL_APP_LOCK_SERVICES = setOf(
        ShizukuAppLockService::class.java,
        ExperimentalAppLockService::class.java
    )

    @Synchronized
    fun onForegroundAppTransition(
        repository: AppLockRepository,
        previousPackage: String?,
        currentPackage: String?,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        onForegroundAppTransition(
            RepositoryDailyLimitPolicyStore(repository),
            previousPackage,
            currentPackage,
            nowMillis
        )
    }

    @Synchronized
    internal fun onForegroundAppTransition(
        store: DailyLimitPolicyStore,
        previousPackage: String?,
        currentPackage: String?,
        nowMillis: Long
    ) {
        ensureDailyUsageIsFresh(store, nowMillis)
        accrueTrackedForegroundUsage(store, nowMillis)

        val normalizedPreviousPackage = previousPackage?.takeIf { it.isNotBlank() }
        LogUtils.d(
            TAG,
            "onForegroundAppTransition: previous=$normalizedPreviousPackage, current=$currentPackage, tracked=$trackedForegroundPackageForDailyLimit"
        )

        val normalizedCurrentPackage = currentPackage?.takeIf { it.isNotBlank() }
        if (normalizedCurrentPackage == null) {
            clearTrackedForegroundDailyLimitState()
            return
        }

        if (normalizedCurrentPackage == trackedForegroundPackageForDailyLimit) {
            // Keep current tracking window active for continuous foreground usage.
            return
        }

        val configuredLimit = store.getDailyLimit(normalizedCurrentPackage)
        if (configuredLimit == null || configuredLimit <= 0) {
            clearTrackedForegroundDailyLimitState()
            return
        }

        trackedForegroundPackageForDailyLimit = normalizedCurrentPackage
        trackedForegroundStartTimeMillis = nowMillis
    }

    @Synchronized
    fun pauseDailyLimitTracking(
        repository: AppLockRepository,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        pauseDailyLimitTracking(RepositoryDailyLimitPolicyStore(repository), nowMillis)
    }

    @Synchronized
    internal fun pauseDailyLimitTracking(store: DailyLimitPolicyStore, nowMillis: Long) {
        ensureDailyUsageIsFresh(store, nowMillis)
        accrueTrackedForegroundUsage(store, nowMillis)
        clearTrackedForegroundDailyLimitState()
    }

    @Synchronized
    fun hasDailyLimitConfigured(repository: AppLockRepository, packageName: String): Boolean {
        return hasDailyLimitConfigured(RepositoryDailyLimitPolicyStore(repository), packageName)
    }

    @Synchronized
    internal fun hasDailyLimitConfigured(store: DailyLimitPolicyStore, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        ensureDailyUsageIsFresh(store, System.currentTimeMillis())
        val dailyLimit = store.getDailyLimit(packageName)
        return dailyLimit != null && dailyLimit > 0
    }

    @Synchronized
    fun getRemainingDailyLimitSeconds(
        repository: AppLockRepository,
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Int? {
        return getRemainingDailyLimitSeconds(
            RepositoryDailyLimitPolicyStore(repository),
            packageName,
            nowMillis
        )
    }

    @Synchronized
    internal fun getRemainingDailyLimitSeconds(
        store: DailyLimitPolicyStore,
        packageName: String,
        nowMillis: Long
    ): Int? {
        if (packageName.isBlank()) return null

        ensureDailyUsageIsFresh(store, nowMillis)

        val dailyLimit = store.getDailyLimit(packageName) ?: return null
        if (dailyLimit <= 0) return null
        val usedSeconds = getEffectiveUsedSeconds(store, packageName, nowMillis)
        return (dailyLimit - usedSeconds).coerceAtLeast(0)
    }

    @Synchronized
    fun enforceDailyLimitForLockDecision(
        repository: AppLockRepository,
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): DailyLimitEnforcementResult {
        return enforceDailyLimitForLockDecision(
            RepositoryDailyLimitPolicyStore(repository),
            packageName,
            nowMillis
        )
    }

    @Synchronized
    internal fun enforceDailyLimitForLockDecision(
        store: DailyLimitPolicyStore,
        packageName: String,
        nowMillis: Long
    ): DailyLimitEnforcementResult {
        if (packageName.isBlank()) return DailyLimitEnforcementResult.NO_LIMIT_CONFIGURED

        val remaining = getRemainingDailyLimitSeconds(store, packageName, nowMillis)
            ?: return DailyLimitEnforcementResult.NO_LIMIT_CONFIGURED

        LogUtils.d(
            TAG,
            "enforceDailyLimitForLockDecision: package=$packageName, remainingSeconds=$remaining, tracked=$trackedForegroundPackageForDailyLimit"
        )

        if (remaining > 0) return DailyLimitEnforcementResult.BYPASS_ALLOWED

        // Daily-limit policy takes precedence over stale temporary unlock/grace state.
        appUnlockTimes.remove(packageName)
        if (isAppTemporarilyUnlocked(packageName)) {
            clearTemporarilyUnlockedApp()
        }
        return DailyLimitEnforcementResult.LOCK_REQUIRED
    }

    @Synchronized
    fun shouldBypassLockByDailyLimit(
        repository: AppLockRepository,
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return shouldBypassLockByDailyLimit(
            RepositoryDailyLimitPolicyStore(repository),
            packageName,
            nowMillis
        )
    }

    @Synchronized
    internal fun shouldBypassLockByDailyLimit(
        store: DailyLimitPolicyStore,
        packageName: String,
        nowMillis: Long
    ): Boolean {
        val remaining = getRemainingDailyLimitSeconds(store, packageName, nowMillis) ?: return false
        return remaining > 0
    }

    @Synchronized
    internal fun resetDailyLimitTrackingState() {
        clearTrackedForegroundDailyLimitState()
    }

    fun unlockApp(packageName: String) {
        temporarilyUnlockedApp = packageName
        appUnlockTimes[packageName] = System.currentTimeMillis()
        LogUtils.d(
            TAG,
            "App $packageName unlocked at timestamp: ${appUnlockTimes[packageName]}, current time: ${System.currentTimeMillis()}"
        )
    }

    fun temporarilyUnlockAppWithBiometrics(packageName: String) {
        unlockApp(packageName)
        reportBiometricAuthFinished()
    }

    fun reportBiometricAuthStarted() {}
    fun reportBiometricAuthFinished() {}

    fun isAppTemporarilyUnlocked(packageName: String): Boolean =
        temporarilyUnlockedApp == packageName

    fun clearTemporarilyUnlockedApp() {
        temporarilyUnlockedApp = ""
    }

    fun startFallbackServices(context: Context, failedService: Class<*>) {
        val serviceName = failedService.simpleName

        if (!shouldAttemptRestart(serviceName)) return

        val targetBackend = when (failedService) {
            ShizukuAppLockService::class.java -> BackendImplementation.USAGE_STATS
            ExperimentalAppLockService::class.java -> BackendImplementation.ACCESSIBILITY
            AppLockAccessibilityService::class.java -> null
            else -> null
        }

        when (targetBackend) {
            BackendImplementation.ACCESSIBILITY -> {
                if (AppLockAccessibilityService.isServiceRunning) return
                Log.d(
                    TAG,
                    "Attempting ACCESSIBILITY backend restart. (Manually enabled in settings)"
                )
            }

            BackendImplementation.USAGE_STATS, BackendImplementation.SHIZUKU -> {
                startServiceByBackend(context, targetBackend)
            }

            null -> {
                if (failedService == AppLockAccessibilityService::class.java) {
                    LogUtils.d(
                        TAG,
                        "Accessibility Service stopped. Waiting for system restart or manual re-enable."
                    )
                    return
                }
                showNoPermissionsToast(context)
                return
            }
        }
        recordRestartAttempt(serviceName)
    }

    fun stopAllOtherServices(context: Context, excludeService: Class<*>) {
        ALL_APP_LOCK_SERVICES
            .filter { it != excludeService }
            .forEach {
                context.stopService(Intent(context, it))
            }
        LogUtils.d(TAG, "Stopped all main app lock services except ${excludeService.simpleName}.")
    }

    fun resetRestartAttempts(serviceName: String) {
        serviceRestartAttempts.remove(serviceName)
        lastRestartTime.remove(serviceName)
        LogUtils.d(TAG, "Reset restart attempts for $serviceName")
    }

    private fun showNoPermissionsToast(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "Selected backend has insufficient permissions. Please provide necessary permissions.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shouldAttemptRestart(serviceName: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val attempts = serviceRestartAttempts[serviceName] ?: 0
        val lastRestart = lastRestartTime[serviceName] ?: 0L

        if (currentTime - lastRestart < AppLockConstants.RESTART_INTERVAL_MS) {
            Log.d(TAG, "Service $serviceName restart too recent, skipping")
            return false
        }

        if (attempts >= AppLockConstants.MAX_RESTART_ATTEMPTS) {
            if (currentTime - lastRestart > AppLockConstants.RESTART_COOLDOWN_MS) {
                Log.d(TAG, "Cooldown expired for $serviceName, resetting attempts")
                serviceRestartAttempts[serviceName] = 0
                return true
            }
            Log.d(TAG, "Max restart attempts reached for $serviceName, in cooldown")
            return false
        }

        return true
    }

    private fun recordRestartAttempt(serviceName: String) {
        val currentTime = System.currentTimeMillis()
        serviceRestartAttempts.compute(serviceName) { _, attempts -> (attempts ?: 0) + 1 }
        lastRestartTime[serviceName] = currentTime
        Log.d(
            TAG,
            "Recorded restart attempt ${serviceRestartAttempts[serviceName]} for $serviceName"
        )
    }

    private fun startServiceByBackend(context: Context, backend: BackendImplementation) {
        try {
            stopAllOtherServices(context, Nothing::class.java)

            val serviceClass = when (backend) {
                BackendImplementation.SHIZUKU -> ShizukuAppLockService::class.java
                BackendImplementation.USAGE_STATS -> ExperimentalAppLockService::class.java
                else -> return
            }

            LogUtils.d(TAG, "Starting $backend service as fallback.")
            context.startService(Intent(context, serviceClass))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fallback service for backend: $backend", e)
        }
    }

    private fun ensureDailyUsageIsFresh(store: DailyLimitPolicyStore, nowMillis: Long) {
        if (store.resetUsageIfDayChanged() && trackedForegroundPackageForDailyLimit.isNotBlank()) {
            LogUtils.d(
                TAG,
                "Day changed while tracking $trackedForegroundPackageForDailyLimit. Resetting tracking start to now."
            )
            trackedForegroundStartTimeMillis = nowMillis
        }
    }

    private fun accrueTrackedForegroundUsage(store: DailyLimitPolicyStore, nowMillis: Long) {
        val trackedPackage = trackedForegroundPackageForDailyLimit
        val trackingStart = trackedForegroundStartTimeMillis
        if (trackedPackage.isBlank() || trackingStart <= 0L || nowMillis <= trackingStart) return

        val elapsedSeconds = ((nowMillis - trackingStart) / 1000L).toInt()
        if (elapsedSeconds <= 0) return

        val updatedUsage = store.incrementUsedSecondsForToday(trackedPackage, elapsedSeconds)
        LogUtils.d(
            TAG,
            "accrueTrackedForegroundUsage: tracked=$trackedPackage, elapsedSeconds=$elapsedSeconds, usedSecondsPersisted=$updatedUsage"
        )
        trackedForegroundStartTimeMillis = trackingStart + (elapsedSeconds * 1000L)
    }

    private fun getEffectiveUsedSeconds(
        store: DailyLimitPolicyStore,
        packageName: String,
        nowMillis: Long
    ): Int {
        var usedSeconds = store.getUsedSecondsForToday(packageName)

        if (packageName == trackedForegroundPackageForDailyLimit &&
            trackedForegroundStartTimeMillis > 0L &&
            nowMillis > trackedForegroundStartTimeMillis
        ) {
            usedSeconds += ((nowMillis - trackedForegroundStartTimeMillis) / 1000L).toInt()
        }

        return usedSeconds
    }

    private fun clearTrackedForegroundDailyLimitState() {
        trackedForegroundPackageForDailyLimit = ""
        trackedForegroundStartTimeMillis = 0L
    }
}
