package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.max

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
        preferences.edit {
            putStringSet(KEY_LOCKED_APPS, updated)
            remove("$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName")
            remove("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName")
            remove("$KEY_DAILY_USAGE_DATE_PREFIX$packageName")
        }
    }

    fun isAppLocked(packageName: String): Boolean {
        return getLockedApps().contains(packageName)
    }

    fun clearAllLockedApps() {
        val existingLockedApps = getLockedApps()
        preferences.edit {
            putStringSet(KEY_LOCKED_APPS, emptySet())
            existingLockedApps.forEach { packageName ->
                remove("$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_DATE_PREFIX$packageName")
            }
        }
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
        preferences.edit {
            putStringSet(KEY_LOCKED_APPS, updated)
            packageNames.forEach { packageName ->
                remove("$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_DATE_PREFIX$packageName")
            }
        }
    }

    fun setDailyLimitMinutes(packageName: String, minutes: Int?) {
        if (packageName.isBlank()) return

        preferences.edit {
            if (minutes == null || minutes <= 0) {
                remove("$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName")
                remove("$KEY_DAILY_USAGE_DATE_PREFIX$packageName")
            } else {
                putInt("$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName", max(1, minutes))
            }
        }
    }

    fun getDailyLimitMinutes(packageName: String): Int? {
        val key = "$KEY_DAILY_LIMIT_MINUTES_PREFIX$packageName"
        return if (preferences.contains(key)) preferences.getInt(key, 0).takeIf { it > 0 } else null
    }

    fun isDailyLimitEnabled(packageName: String): Boolean {
        return getDailyLimitMinutes(packageName) != null
    }

    fun getUsedDailyTimeMillis(packageName: String, currentDay: String): Long {
        resetDailyUsageIfNeeded(packageName, currentDay)
        return preferences.getLong("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName", 0L)
    }

    fun addUsedDailyTimeMillis(packageName: String, millisToAdd: Long, currentDay: String): Long {
        if (millisToAdd <= 0L) return getUsedDailyTimeMillis(packageName, currentDay)
        resetDailyUsageIfNeeded(packageName, currentDay)

        val key = "$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName"
        val current = preferences.getLong(key, 0L)
        val updated = current + millisToAdd
        preferences.edit { putLong(key, updated) }
        return updated
    }

    fun getRemainingDailyTimeMillis(packageName: String, currentDay: String): Long? {
        val limitMinutes = getDailyLimitMinutes(packageName) ?: return null
        val limitMillis = limitMinutes * 60_000L
        val usedMillis = getUsedDailyTimeMillis(packageName, currentDay)
        return (limitMillis - usedMillis).coerceAtLeast(0L)
    }

    private fun resetDailyUsageIfNeeded(packageName: String, currentDay: String) {
        val dateKey = "$KEY_DAILY_USAGE_DATE_PREFIX$packageName"
        val storedDay = preferences.getString(dateKey, null)
        if (storedDay == currentDay) return

        preferences.edit {
            putString(dateKey, currentDay)
            putLong("$KEY_DAILY_USAGE_MILLIS_PREFIX$packageName", 0L)
        }
    }

    companion object {
        private const val PREFS_NAME = "app_lock_prefs"
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val KEY_TRIGGER_EXCLUDED_APPS = "trigger_excluded_apps"
        private const val KEY_ANTI_UNINSTALL_APPS = "anti_uninstall_apps"
        private const val KEY_DAILY_LIMIT_MINUTES_PREFIX = "daily_limit_minutes_"
        private const val KEY_DAILY_USAGE_MILLIS_PREFIX = "daily_used_millis_"
        private const val KEY_DAILY_USAGE_DATE_PREFIX = "daily_used_date_"
    }
}
