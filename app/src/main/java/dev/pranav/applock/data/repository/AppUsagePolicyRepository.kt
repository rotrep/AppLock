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
        val day = now.dayOfWeek
        val dayConfig = policy.dayConfigs[day] ?: return false
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
            return policy.masterTimeEnabled && stats.masterUsedMs < policy.masterTimeMinutes * 60_000L
        }

        return policy.masterTimeEnabled && stats.masterUsedMs < policy.masterTimeMinutes * 60_000L
    }

    fun consumeUsage(packageName: String, startMillis: Long, endMillis: Long) {
        if (endMillis <= startMillis) return
        val policy = getPolicy(packageName) ?: return

        var cursor = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault())
        val end = Instant.ofEpochMilli(endMillis).atZone(ZoneId.systemDefault())

        while (cursor.isBefore(end)) {
            val dayEnd = cursor.toLocalDate().plusDays(1).atStartOfDay(cursor.zone)
            val chunkEnd = if (dayEnd.isBefore(end)) dayEnd else end
            val durationMs = chunkEnd.toInstant().toEpochMilli() - cursor.toInstant().toEpochMilli()

            val dayConfig = policy.dayConfigs[cursor.dayOfWeek]
            if (dayConfig != null && dayConfig.enabled && durationMs > 0L) {
                consumeForDay(
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
    }

    private fun consumeForDay(
        packageName: String,
        date: LocalDate,
        dayConfig: DayUsageConfig,
        policy: AppUsagePolicy,
        startMinute: Int,
        durationMs: Long
    ) {
        val stats = getOrCreateStats(packageName, date)
        var remaining = durationMs

        if (dayConfig.dailyLimitMinutes > 0) {
            val dayRemaining = (dayConfig.dailyLimitMinutes * 60_000L) - stats.totalUsedMs
            if (dayRemaining <= 0L) {
                saveStats(packageName, stats)
                return
            }
            remaining = minOf(remaining, dayRemaining)
        }

        var totalDelta = 0L
        var masterDelta = 0L
        val windowUsage = stats.windowUsedMs.toMutableMap()

        val activeWindow = dayConfig.windows.firstOrNull { startMinute in it.startMinutes until it.endMinutes }
        if (activeWindow != null) {
            val currentUsed = windowUsage[activeWindow.id] ?: 0L
            val windowRemaining = (activeWindow.limitMinutes * 60_000L) - currentUsed
            if (windowRemaining > 0L) {
                val consumedWindow = minOf(remaining, windowRemaining)
                windowUsage[activeWindow.id] = currentUsed + consumedWindow
                totalDelta += consumedWindow
                remaining -= consumedWindow
            }
        }

        if (remaining > 0L) {
            val consumedMaster = consumeMaster(stats.masterUsedMs + masterDelta, remaining, policy).first
            totalDelta += consumedMaster
            masterDelta += consumedMaster
            remaining -= consumedMaster
        }

        if (totalDelta > 0L) {
            saveStats(
                packageName,
                stats.copy(
                    totalUsedMs = stats.totalUsedMs + totalDelta,
                    masterUsedMs = stats.masterUsedMs + masterDelta,
                    windowUsedMs = windowUsage
                )
            )
        } else {
            saveStats(packageName, stats)
        }
    }

    private fun consumeMaster(currentMasterMs: Long, desiredMs: Long, policy: AppUsagePolicy): Pair<Long, Long> {
        if (!policy.masterTimeEnabled || desiredMs <= 0) return 0L to desiredMs
        val remaining = (policy.masterTimeMinutes * 60_000L) - currentMasterMs
        if (remaining <= 0L) return 0L to desiredMs
        val consumed = minOf(remaining, desiredMs)
        return consumed to (desiredMs - consumed)
    }

    private fun getOrCreateStats(packageName: String, date: LocalDate): DayUsageStats {
        val key = statsKey(packageName)
        val raw = prefs.getString(key, null)
        val dateKey = date.toString()
        if (raw.isNullOrBlank()) {
            return DayUsageStats(dateKey)
        }
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

        return AppUsagePolicy(
            appPackage = pkg,
            dayConfigs = dayMap,
            masterTimeEnabled = masterEnabled,
            masterTimeMinutes = masterMinutes
        )
    }

    private fun policyToJson(policy: AppUsagePolicy): JSONObject {
        val root = JSONObject()
        root.put("package", policy.appPackage)
        root.put("masterEnabled", policy.masterTimeEnabled)
        root.put("masterMinutes", policy.masterTimeMinutes)

        val days = JSONObject()
        policy.dayConfigs.forEach { (day, config) ->
            val node = JSONObject()
                .put("enabled", config.enabled)
                .put("dailyLimit", config.dailyLimitMinutes)

            val windows = JSONArray()
            config.windows.forEach { window ->
                windows.put(
                    JSONObject()
                        .put("id", window.id)
                        .put("start", window.startMinutes)
                        .put("end", window.endMinutes)
                        .put("limit", window.limitMinutes)
                )
            }
            node.put("windows", windows)
            days.put(day.name, node)
        }
        root.put("days", days)
        return root
    }

    private fun parseStats(json: JSONObject): DayUsageStats {
        val date = json.optString("date", LocalDate.now().toString())
        val total = json.optLong("total", 0L)
        val master = json.optLong("master", 0L)
        val windowsJson = json.optJSONObject("windows") ?: JSONObject()
        val map = mutableMapOf<String, Long>()
        windowsJson.keys().forEach { key ->
            map[key] = windowsJson.optLong(key, 0L)
        }
        return DayUsageStats(date, total, master, map)
    }

    private fun statsToJson(stats: DayUsageStats): JSONObject {
        val windows = JSONObject()
        stats.windowUsedMs.forEach { (id, value) -> windows.put(id, value) }
        return JSONObject()
            .put("date", stats.dateKey)
            .put("total", stats.totalUsedMs)
            .put("master", stats.masterUsedMs)
            .put("windows", windows)
    }

    private fun policyKey(packageName: String) = "policy_$packageName"
    private fun statsKey(packageName: String) = "stats_$packageName"

    companion object {
        private const val PREFS_NAME = "app_usage_policy"
    }
}
