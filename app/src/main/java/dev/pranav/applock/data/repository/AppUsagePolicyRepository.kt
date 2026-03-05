package dev.pranav.applock.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import dev.pranav.applock.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Stores per-app usage windows and remaining time counters.
 */
class AppUsagePolicyRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    data class TimeWindow(
        val id: String,
        val startMinute: Int,
        val endMinute: Int,
        val budgetMinutes: Int,
    )

    data class DayPolicy(
        val enabled: Boolean,
        val windows: List<TimeWindow>,
    )

    data class AppPolicy(
        val scheduleEnabled: Boolean,
        val masterEnabled: Boolean,
        val masterMinutes: Int,
        val hardBlockEnabled: Boolean,
        val days: Map<DayOfWeek, DayPolicy>,
    ) {
        companion object {
            fun default(): AppPolicy = AppPolicy(
                scheduleEnabled = false,
                masterEnabled = false,
                masterMinutes = 0,
                hardBlockEnabled = false,
                days = DayOfWeek.entries.associateWith { DayPolicy(false, emptyList()) }
            )
        }
    }

    data class AccessDecision(
        val allowWithoutLock: Boolean,
        val exhausted: Boolean,
        val activeWindow: TimeWindow?,
        val usingMasterTime: Boolean,
    )

    fun getPolicy(packageName: String): AppPolicy {
        val raw = prefs.getString("$KEY_POLICY_PREFIX$packageName", null) ?: return AppPolicy.default()
        return runCatching { parsePolicy(JSONObject(raw)) }.getOrElse { AppPolicy.default() }
    }

    fun setPolicy(packageName: String, policy: AppPolicy) {
        prefs.edit { putString("$KEY_POLICY_PREFIX$packageName", toJson(policy).toString()) }
    }

    fun evaluate(packageName: String, nowMillis: Long = System.currentTimeMillis()): AccessDecision {
        val policy = getPolicy(packageName)
        if (policy.hardBlockEnabled) {
            return AccessDecision(false, true, null, false)
        }
        if (!policy.scheduleEnabled) {
            return AccessDecision(false, true, null, false)
        }

        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        val dayPolicy = policy.days[dateTime.dayOfWeek] ?: DayPolicy(false, emptyList())
        if (!dayPolicy.enabled) {
            return AccessDecision(false, true, null, false)
        }

        val minuteNow = dateTime.hour * 60 + dateTime.minute
        val activeWindow = dayPolicy.windows.firstOrNull { minuteNow in it.startMinute until it.endMinute }
        val date = dateTime.toLocalDate()

        if (activeWindow != null) {
            val remaining = getWindowRemainingMinutes(packageName, date, activeWindow.id, activeWindow.budgetMinutes)
            if (remaining > 0) return AccessDecision(true, false, activeWindow, false)
            val canUseMaster = policy.masterEnabled && getMasterRemainingMinutes(packageName, date, policy.masterMinutes) > 0
            return AccessDecision(canUseMaster, !canUseMaster, activeWindow, canUseMaster)
        }

        val canUseMaster = policy.masterEnabled && getMasterRemainingMinutes(packageName, date, policy.masterMinutes) > 0
        return AccessDecision(canUseMaster, !canUseMaster, null, canUseMaster)
    }

    fun consumeUsage(packageName: String, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        val decision = evaluate(packageName, endMs)
        if (!decision.allowWithoutLock) return

        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs), ZoneId.systemDefault())
        val date = dt.toLocalDate()
        val consumedMinutes = ((endMs - startMs) / 60_000L).coerceAtLeast(1L).toInt()

        if (decision.activeWindow != null && !decision.usingMasterTime) {
            val base = windowUsageKey(packageName, date, decision.activeWindow.id)
            val used = prefs.getInt(base, 0) + consumedMinutes
            prefs.edit { putInt(base, used) }
            maybeNotify(packageName, decision.activeWindow.budgetMinutes, used, false, decision.activeWindow)
            return
        }

        if (decision.usingMasterTime) {
            val policy = getPolicy(packageName)
            val key = masterUsageKey(packageName, date)
            val used = prefs.getInt(key, 0) + consumedMinutes
            prefs.edit { putInt(key, used) }
            maybeNotify(packageName, policy.masterMinutes, used, true, decision.activeWindow)
        }
    }

    private fun maybeNotify(
        packageName: String,
        totalMinutes: Int,
        usedMinutes: Int,
        isMaster: Boolean,
        window: TimeWindow?,
    ) {
        if (totalMinutes <= 0) return
        val remainingPct = ((totalMinutes - usedMinutes).coerceAtLeast(0) * 100) / totalMinutes
        val firedKey = "$KEY_WARNED_PREFIX${packageName}_${LocalDate.now()}_${if (isMaster) "master" else window?.id ?: "none"}"
        val fired = prefs.getStringSet(firedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        val thresholds = listOf(50, 25, 10)
        val hit = thresholds.firstOrNull { remainingPct <= it && !fired.contains(it.toString()) } ?: return
        fired.add(hit.toString())
        prefs.edit { putStringSet(firedKey, fired) }

        ensureChannel()
        val body = if (isMaster) {
            "$packageName is now using master time. $remainingPct% remaining."
        } else {
            val slot = window?.let { " (${formatMinute(it.startMinute)}-${formatMinute(it.endMinute)})" } ?: ""
            "$packageName has $remainingPct% time remaining$slot."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_shield_24)
            .setContentTitle("App usage warning")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((packageName + body).hashCode(), notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Usage time alerts", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    private fun getWindowRemainingMinutes(packageName: String, date: LocalDate, windowId: String, budget: Int): Int {
        val used = prefs.getInt(windowUsageKey(packageName, date, windowId), 0)
        return (budget - used).coerceAtLeast(0)
    }

    private fun getMasterRemainingMinutes(packageName: String, date: LocalDate, budget: Int): Int {
        val used = prefs.getInt(masterUsageKey(packageName, date), 0)
        return (budget - used).coerceAtLeast(0)
    }

    private fun windowUsageKey(packageName: String, date: LocalDate, windowId: String): String =
        "$KEY_WINDOW_USAGE_PREFIX${packageName}_${date}_$windowId"

    private fun masterUsageKey(packageName: String, date: LocalDate): String =
        "$KEY_MASTER_USAGE_PREFIX${packageName}_$date"

    private fun toJson(policy: AppPolicy): JSONObject {
        val json = JSONObject()
        json.put("scheduleEnabled", policy.scheduleEnabled)
        json.put("masterEnabled", policy.masterEnabled)
        json.put("masterMinutes", policy.masterMinutes)
        json.put("hardBlockEnabled", policy.hardBlockEnabled)
        val days = JSONObject()
        DayOfWeek.entries.forEach { day ->
            val dayPolicy = policy.days[day] ?: DayPolicy(false, emptyList())
            val dayJson = JSONObject()
            dayJson.put("enabled", dayPolicy.enabled)
            val windowsJson = JSONArray()
            dayPolicy.windows.forEach { window ->
                val w = JSONObject()
                w.put("id", window.id)
                w.put("startMinute", window.startMinute)
                w.put("endMinute", window.endMinute)
                w.put("budgetMinutes", window.budgetMinutes)
                windowsJson.put(w)
            }
            dayJson.put("windows", windowsJson)
            days.put(day.name, dayJson)
        }
        json.put("days", days)
        return json
    }

    private fun parsePolicy(json: JSONObject): AppPolicy {
        val daysJson = json.optJSONObject("days") ?: JSONObject()
        val days = DayOfWeek.entries.associateWith { day ->
            val d = daysJson.optJSONObject(day.name) ?: JSONObject()
            val windowsArray = d.optJSONArray("windows") ?: JSONArray()
            val windows = buildList {
                for (i in 0 until windowsArray.length()) {
                    val w = windowsArray.optJSONObject(i) ?: continue
                    add(
                        TimeWindow(
                            id = w.optString("id", "w$i"),
                            startMinute = w.optInt("startMinute", 0),
                            endMinute = w.optInt("endMinute", 0),
                            budgetMinutes = w.optInt("budgetMinutes", 0)
                        )
                    )
                }
            }
            DayPolicy(d.optBoolean("enabled", false), windows)
        }

        return AppPolicy(
            scheduleEnabled = json.optBoolean("scheduleEnabled", false),
            masterEnabled = json.optBoolean("masterEnabled", false),
            masterMinutes = json.optInt("masterMinutes", 0),
            hardBlockEnabled = json.optBoolean("hardBlockEnabled", false),
            days = days,
        )
    }

    private fun formatMinute(minute: Int): String {
        val t = LocalTime.of(minute / 60, minute % 60)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    companion object {
        private const val PREFS_NAME = "app_lock_usage_policies"
        private const val KEY_POLICY_PREFIX = "policy_"
        private const val KEY_WINDOW_USAGE_PREFIX = "used_window_"
        private const val KEY_MASTER_USAGE_PREFIX = "used_master_"
        private const val KEY_WARNED_PREFIX = "warned_"
        private const val CHANNEL_ID = "usage_time_alerts"
    }
}
