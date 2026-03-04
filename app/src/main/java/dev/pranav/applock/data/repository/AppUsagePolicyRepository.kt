package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AppUsagePolicyRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPolicy(packageName: String): AppUsagePolicy? {
        val raw = prefs.getString(policyKey(packageName), null) ?: return null
        return runCatching { parsePolicy(JSONObject(raw)) }.getOrNull()
    }

    fun setPolicy(policy: AppUsagePolicy) {
        prefs.edit { putString(policyKey(policy.appPackage), policyToJson(policy).toString()) }
    }

    fun clearPolicy(packageName: String) {
        prefs.edit { remove(policyKey(packageName)); remove(statsKey(packageName)) }
    }

    fun copyConfigToDays(packageName: String, sourceDay: DayOfWeek, targetDays: Set<DayOfWeek>) {
        val current = getPolicy(packageName) ?: return
        val source = current.dayConfigs[sourceDay] ?: return
        val nextMap = current.dayConfigs.toMutableMap()
        targetDays.forEach { nextMap[it] = source }
        setPolicy(current.copy(dayConfigs = nextMap))
    }

    fun canUseAppNow(packageName: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val policy = getPolicy(packageName) ?: return false
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val dayConfig = policy.dayConfigs[now.dayOfWeek] ?: return false

        // If day is disabled, policy does not apply (normal AppLock flow)
        if (!dayConfig.enabled) return false

        val stats = getOrCreateStats(packageName, now.toLocalDate())
        val nowMinutes = now.hour * 60 + now.minute

        if (dayConfig.dailyLimitMinutes > 0 && stats.totalUsedMs >= dayConfig.dailyLimitMinutes * 60_000L) {
            return false
        }

        val activeWindow = dayConfig.windows.firstOrNull { nowMinutes in it.startMinutes until it.endMinutes }
        if (activeWindow != null) {
            val used = stats.windowUsedMs[activeWindow.id] ?: 0L
            if (used < activeWindow.limitMinutes * 60_000L) {
                return true
            }
        }

        return policy.masterTimeEnabled && stats.masterUsedMs < policy.masterTimeMinutes * 60_000L
    }

    fun consumeUsage(packageName: String, startMillis: Long, endMillis: Long): List<UsageAlert> {
        if (endMillis <= startMillis) return emptyList()
        val policy = getPolicy(packageName) ?: return emptyList()

        var cursor = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault())
        val end = Instant.ofEpochMilli(endMillis).atZone(ZoneId.systemDefault())
        val alerts = mutableListOf<UsageAlert>()

        while (cursor.isBefore(end)) {
            val dayEnd = cursor.toLocalDate().plusDays(1).atStartOfDay(cursor.zone)
            val chunkEnd = if (dayEnd.isBefore(end)) dayEnd else end
            val durationMs = chunkEnd.toInstant().toEpochMilli() - cursor.toInstant().toEpochMilli()

            val dayConfig = policy.dayConfigs[cursor.dayOfWeek]
            if (dayConfig != null && dayConfig.enabled && durationMs > 0L) {
                alerts += consumeForDay(
                    packageName = packageName,
                    date = cursor.toLocalDate(),
                    dayConfig = dayConfig,
                    policy = policy,
                    startMinute = cursor.hour * 60 + cursor.minute,
                    durationMs = durationMs
                )
            }

            cursor = chunkEnd
        }

        return alerts
    }

    private fun consumeForDay(
        packageName: String,
        date: LocalDate,
        dayConfig: DayUsageConfig,
        policy: AppUsagePolicy,
        startMinute: Int,
        durationMs: Long
    ): List<UsageAlert> {
        val stats = getOrCreateStats(packageName, date)
        var remaining = durationMs

        if (dayConfig.dailyLimitMinutes > 0) {
            val dayRemaining = (dayConfig.dailyLimitMinutes * 60_000L) - stats.totalUsedMs
            if (dayRemaining <= 0L) return emptyList()
            remaining = minOf(remaining, dayRemaining)
        }

        var totalDelta = 0L
        var masterDelta = 0L
        val windowUsage = stats.windowUsedMs.toMutableMap()
        val notified = stats.notifiedThresholds.toMutableMap().mapValues { it.value.toMutableSet() }.toMutableMap()
        val alerts = mutableListOf<UsageAlert>()

        val activeWindow = dayConfig.windows.firstOrNull { startMinute in it.startMinutes until it.endMinutes }
        if (activeWindow != null) {
            val currentUsed = windowUsage[activeWindow.id] ?: 0L
            val windowRemaining = (activeWindow.limitMinutes * 60_000L) - currentUsed
            if (windowRemaining > 0L) {
                val consumedWindow = minOf(remaining, windowRemaining)
                val newUsed = currentUsed + consumedWindow
                windowUsage[activeWindow.id] = newUsed
                totalDelta += consumedWindow
                remaining -= consumedWindow

                alerts += evaluateThresholdAlerts(
                    packageName = packageName,
                    scopeKey = activeWindow.id,
                    scopeLabel = "Window ${minutesToTime(activeWindow.startMinutes)}-${minutesToTime(activeWindow.endMinutes)}",
                    totalMs = activeWindow.limitMinutes * 60_000L,
                    beforeUsed = currentUsed,
                    afterUsed = newUsed,
                    notified = notified,
                    isMaster = false
                )
            }
        }

        if (remaining > 0L && policy.masterTimeEnabled) {
            val currentMaster = stats.masterUsedMs + masterDelta
            val masterRemaining = (policy.masterTimeMinutes * 60_000L) - currentMaster
            if (masterRemaining > 0L) {
                val consumedMaster = minOf(masterRemaining, remaining)
                totalDelta += consumedMaster
                masterDelta += consumedMaster
                remaining -= consumedMaster

                alerts += UsageAlert(packageName, "Master time", -1, true)
                alerts += evaluateThresholdAlerts(
                    packageName = packageName,
                    scopeKey = MASTER_SCOPE,
                    scopeLabel = "Master time",
                    totalMs = policy.masterTimeMinutes * 60_000L,
                    beforeUsed = currentMaster,
                    afterUsed = currentMaster + consumedMaster,
                    notified = notified,
                    isMaster = true
                )
            }
        }

        if (totalDelta > 0L) {
            saveStats(
                packageName,
                stats.copy(
                    totalUsedMs = stats.totalUsedMs + totalDelta,
                    masterUsedMs = stats.masterUsedMs + masterDelta,
                    windowUsedMs = windowUsage,
                    notifiedThresholds = notified
                )
            )
        }

        return alerts
    }

    private fun evaluateThresholdAlerts(
        packageName: String,
        scopeKey: String,
        scopeLabel: String,
        totalMs: Long,
        beforeUsed: Long,
        afterUsed: Long,
        notified: MutableMap<String, MutableSet<Int>>,
        isMaster: Boolean
    ): List<UsageAlert> {
        if (totalMs <= 0L) return emptyList()
        val result = mutableListOf<UsageAlert>()
        val thresholds = listOf(50, 25, 10)
        val already = notified.getOrPut(scopeKey) { mutableSetOf() }

        thresholds.forEach { pct ->
            val beforeRem = totalMs - beforeUsed
            val afterRem = totalMs - afterUsed
            val thresholdMs = totalMs * pct / 100
            if (beforeRem > thresholdMs && afterRem <= thresholdMs && already.add(pct)) {
                result += UsageAlert(packageName, scopeLabel, pct, isMaster)
            }
        }

        if (totalMs - afterUsed <= 0L && already.add(0)) {
            result += UsageAlert(packageName, scopeLabel, 0, isMaster)
        }

        return result
    }

    private fun getOrCreateStats(packageName: String, date: LocalDate): DayUsageStats {
        val raw = prefs.getString(statsKey(packageName), null)
        val dateKey = date.toString()
        if (raw.isNullOrBlank()) return DayUsageStats(dateKey)
        val parsed = runCatching { parseStats(JSONObject(raw)) }.getOrNull() ?: return DayUsageStats(dateKey)
        return if (parsed.dateKey == dateKey) parsed else DayUsageStats(dateKey)
    }

    private fun saveStats(packageName: String, stats: DayUsageStats) {
        prefs.edit { putString(statsKey(packageName), statsToJson(stats).toString()) }
    }

    private fun parsePolicy(json: JSONObject): AppUsagePolicy {
        val pkg = json.getString("package")
        val masterEnabled = json.optBoolean("masterEnabled", false)
        val masterMinutes = json.optInt("masterMinutes", 0)
        val dayObj = json.optJSONObject("days") ?: JSONObject()
        val dayMap = mutableMapOf<DayOfWeek, DayUsageConfig>()

        DayOfWeek.entries.forEach { day ->
            val node = dayObj.optJSONObject(day.name) ?: return@forEach
            val windows = node.optJSONArray("windows") ?: JSONArray()
            val parsedWindows = buildList {
                for (i in 0 until windows.length()) {
                    val w = windows.optJSONObject(i) ?: continue
                    add(
                        UsageWindow(
                            id = w.optString("id", "${day.name}_$i"),
                            startMinutes = w.optInt("start", 0),
                            endMinutes = w.optInt("end", 0),
                            limitMinutes = w.optInt("limit", 0)
                        )
                    )
                }
            }
            dayMap[day] = DayUsageConfig(
                enabled = node.optBoolean("enabled", false),
                dailyLimitMinutes = node.optInt("dailyLimit", 0),
                windows = parsedWindows
            )
        }

        return AppUsagePolicy(pkg, dayMap, masterEnabled, masterMinutes)
    }

    private fun policyToJson(policy: AppUsagePolicy): JSONObject {
        val root = JSONObject()
            .put("package", policy.appPackage)
            .put("masterEnabled", policy.masterTimeEnabled)
            .put("masterMinutes", policy.masterTimeMinutes)

        val days = JSONObject()
        policy.dayConfigs.forEach { (day, config) ->
            val windows = JSONArray()
            config.windows.forEach { w ->
                windows.put(
                    JSONObject()
                        .put("id", w.id)
                        .put("start", w.startMinutes)
                        .put("end", w.endMinutes)
                        .put("limit", w.limitMinutes)
                )
            }
            days.put(
                day.name,
                JSONObject()
                    .put("enabled", config.enabled)
                    .put("dailyLimit", config.dailyLimitMinutes)
                    .put("windows", windows)
            )
        }
        return root.put("days", days)
    }

    private fun parseStats(json: JSONObject): DayUsageStats {
        val date = json.optString("date", LocalDate.now().toString())
        val total = json.optLong("total", 0L)
        val master = json.optLong("master", 0L)

        val windowsJson = json.optJSONObject("windows") ?: JSONObject()
        val windows = mutableMapOf<String, Long>()
        windowsJson.keys().forEach { windows[it] = windowsJson.optLong(it, 0L) }

        val notifiedJson = json.optJSONObject("notified") ?: JSONObject()
        val notified = mutableMapOf<String, Set<Int>>()
        notifiedJson.keys().forEach { key ->
            val arr = notifiedJson.optJSONArray(key) ?: JSONArray()
            val set = mutableSetOf<Int>()
            for (i in 0 until arr.length()) set += arr.optInt(i)
            notified[key] = set
        }

        return DayUsageStats(date, total, master, windows, notified)
    }

    private fun statsToJson(stats: DayUsageStats): JSONObject {
        val windows = JSONObject()
        stats.windowUsedMs.forEach { (k, v) -> windows.put(k, v) }

        val notified = JSONObject()
        stats.notifiedThresholds.forEach { (k, set) ->
            val arr = JSONArray()
            set.sortedDescending().forEach { arr.put(it) }
            notified.put(k, arr)
        }

        return JSONObject()
            .put("date", stats.dateKey)
            .put("total", stats.totalUsedMs)
            .put("master", stats.masterUsedMs)
            .put("windows", windows)
            .put("notified", notified)
    }

    private fun policyKey(packageName: String) = "policy_$packageName"
    private fun statsKey(packageName: String) = "stats_$packageName"

    private fun minutesToTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    companion object {
        private const val PREFS_NAME = "app_usage_policy"
        private const val MASTER_SCOPE = "MASTER"
    }
}
