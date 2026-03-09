package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.Calendar

/**
 * Repository for managing locked applications and trigger exclusions.
 * Handles all app-related locking functionality.
 */
class LockedAppsRepository(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Locked Apps Management
    fun getLockedApps(): Set<String> {
        return preferences.getStringSet(KEY_LOCKED_APPS, emptySet())?.toSet() ?: emptySet()
    }

    fun addLockedApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getLockedApps() + packageName
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    fun removeLockedApp(packageName: String) {
        val updated = getLockedApps() - packageName
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    fun isAppLocked(packageName: String): Boolean {
        return getLockedApps().contains(packageName)
    }

    fun clearAllLockedApps() {
        preferences.edit { putStringSet(KEY_LOCKED_APPS, emptySet()) }
    }

    // Trigger Exclusions Management
    fun getTriggerExcludedApps(): Set<String> {
        return preferences.getStringSet(KEY_TRIGGER_EXCLUDED_APPS, emptySet())?.toSet()
            ?: emptySet()
    }

    fun addTriggerExcludedApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getTriggerExcludedApps() + packageName
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, updated) }
    }

    fun removeTriggerExcludedApp(packageName: String) {
        val updated = getTriggerExcludedApps() - packageName
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, updated) }
    }

    fun isAppTriggerExcluded(packageName: String): Boolean {
        return getTriggerExcludedApps().contains(packageName)
    }

    fun clearAllTriggerExclusions() {
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, emptySet()) }
    }

    // Anti-Uninstall Apps Management
    fun getAntiUninstallApps(): Set<String> {
        return preferences.getStringSet(KEY_ANTI_UNINSTALL_APPS, emptySet())?.toSet() ?: emptySet()
    }

    fun addAntiUninstallApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getAntiUninstallApps() + packageName
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, updated) }
    }

    fun removeAntiUninstallApp(packageName: String) {
        val updated = getAntiUninstallApps() - packageName
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, updated) }
    }

    fun isAppAntiUninstall(packageName: String): Boolean {
        return getAntiUninstallApps().contains(packageName)
    }

    fun clearAllAntiUninstallApps() {
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, emptySet()) }
    }

    // Bulk operations
    fun addMultipleLockedApps(packageNames: Set<String>) {
        val validPackageNames = packageNames.filter { it.isNotBlank() }.toSet()
        if (validPackageNames.isEmpty()) return
        val updated = getLockedApps() + validPackageNames
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    fun removeMultipleLockedApps(packageNames: Set<String>) {
        val updated = getLockedApps() - packageNames
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    // Daily Usage Limit Management
    fun setDailyLimit(packageName: String, limitSeconds: Int) {
        if (packageName.isBlank()) return

        if (limitSeconds <= 0) {
            removeDailyLimit(packageName)
            return
        }

        val limits = getDailyLimitMap().toMutableMap()
        limits[packageName] = limitSeconds
        preferences.edit { putStringSet(KEY_DAILY_LIMITS, serializeIntMap(limits)) }
    }

    fun removeDailyLimit(packageName: String) {
        val limits = getDailyLimitMap().toMutableMap()
        limits.remove(packageName)
        preferences.edit { putStringSet(KEY_DAILY_LIMITS, serializeIntMap(limits)) }
    }

    fun hasDailyLimit(packageName: String): Boolean = getDailyLimitMap().containsKey(packageName)

    fun getDailyLimit(packageName: String): Int? = getDailyLimitMap()[packageName]

    fun getAllDailyLimits(): Map<String, Int> = getDailyLimitMap()

    // Daily Usage State Management
    fun getUsedSecondsForToday(packageName: String): Int {
        resetUsageIfDayChanged()
        return getDailyUsageMap()[packageName] ?: 0
    }

    fun setUsedSecondsForToday(packageName: String, usedSeconds: Int) {
        if (packageName.isBlank()) return

        resetUsageIfDayChanged()

        val usageMap = getDailyUsageMap().toMutableMap()
        if (usedSeconds <= 0) {
            usageMap.remove(packageName)
        } else {
            usageMap[packageName] = usedSeconds
        }

        preferences.edit { putStringSet(KEY_DAILY_USAGE_SECONDS, serializeIntMap(usageMap)) }
    }

    fun incrementUsedSecondsForToday(packageName: String, additionalSeconds: Int): Int {
        if (packageName.isBlank()) return 0

        resetUsageIfDayChanged()

        if (additionalSeconds <= 0) return getDailyUsageMap()[packageName] ?: 0

        val usageMap = getDailyUsageMap().toMutableMap()
        val updated = (usageMap[packageName] ?: 0) + additionalSeconds
        usageMap[packageName] = updated
        preferences.edit { putStringSet(KEY_DAILY_USAGE_SECONDS, serializeIntMap(usageMap)) }
        return updated
    }

    fun getAllUsedSecondsForToday(): Map<String, Int> {
        resetUsageIfDayChanged()
        return getDailyUsageMap()
    }

    fun getStoredUsageDayId(): Int {
        return preferences.getInt(KEY_DAILY_USAGE_DAY_ID, getCurrentLocalDayId())
    }

    fun resetUsageIfDayChanged(): Boolean {
        val storedDayId = getStoredUsageDayId()
        val currentDayId = getCurrentLocalDayId()

        Log.d(TAG, "resetUsageIfDayChanged: storedDayId=$storedDayId, currentDayId=$currentDayId")

        if (storedDayId == currentDayId) return false

        preferences.edit {
            putStringSet(KEY_DAILY_USAGE_SECONDS, emptySet())
            putInt(KEY_DAILY_USAGE_DAY_ID, currentDayId)
        }

        Log.d(TAG, "Daily usage reset for new local day: $currentDayId")
        return true
    }

    fun resetDailyUsageForToday() {
        preferences.edit {
            putStringSet(KEY_DAILY_USAGE_SECONDS, emptySet())
            putInt(KEY_DAILY_USAGE_DAY_ID, getCurrentLocalDayId())
        }
    }

    private fun getDailyLimitMap(): Map<String, Int> =
        parseIntMap(preferences.getStringSet(KEY_DAILY_LIMITS, emptySet()) ?: emptySet())

    private fun getDailyUsageMap(): Map<String, Int> =
        parseIntMap(preferences.getStringSet(KEY_DAILY_USAGE_SECONDS, emptySet()) ?: emptySet())

    private fun parseIntMap(values: Set<String>): Map<String, Int> {
        if (values.isEmpty()) return emptyMap()

        return buildMap {
            values.forEach { entry ->
                val separatorIndex = entry.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) return@forEach

                val packageName = entry.substring(0, separatorIndex)
                val value = entry.substring(separatorIndex + 1).toIntOrNull() ?: return@forEach
                put(packageName, value)
            }
        }
    }

    private fun serializeIntMap(values: Map<String, Int>): Set<String> {
        return values
            .filter { it.key.isNotBlank() && it.value > 0 }
            .mapTo(mutableSetOf()) { (packageName, value) -> "$packageName=$value" }
    }

    private fun getCurrentLocalDayId(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val TAG = "LockedAppsRepository"
        private const val PREFS_NAME = "app_lock_prefs"
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val KEY_TRIGGER_EXCLUDED_APPS = "trigger_excluded_apps"
        private const val KEY_ANTI_UNINSTALL_APPS = "anti_uninstall_apps"
        private const val KEY_DAILY_LIMITS = "daily_limits"
        private const val KEY_DAILY_USAGE_SECONDS = "daily_usage_seconds"
        private const val KEY_DAILY_USAGE_DAY_ID = "daily_usage_day_id"
    }
}
