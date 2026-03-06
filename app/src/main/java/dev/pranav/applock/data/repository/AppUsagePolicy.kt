package dev.pranav.applock.data.repository

import org.json.JSONArray
import org.json.JSONObject

data class AppUsagePolicy(
    val hardBlockEnabled: Boolean = false,
    val masterTimeEnabled: Boolean = false,
    val masterTimeMinutes: Int = 0,
    val daySchedules: Map<Int, List<UsageWindow>> = emptyMap()
)

data class UsageWindow(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val allowedMinutes: Int
)

fun AppUsagePolicy.toJsonString(): String {
    val root = JSONObject()
    root.put("hardBlockEnabled", hardBlockEnabled)
    root.put("masterTimeEnabled", masterTimeEnabled)
    root.put("masterTimeMinutes", masterTimeMinutes)

    val schedulesJson = JSONObject()
    daySchedules.forEach { (day, windows) ->
        val windowsJson = JSONArray()
        windows.forEach { window ->
            windowsJson.put(
                JSONObject().apply {
                    put("id", window.id)
                    put("startMinutes", window.startMinutes)
                    put("endMinutes", window.endMinutes)
                    put("allowedMinutes", window.allowedMinutes)
                }
            )
        }
        schedulesJson.put(day.toString(), windowsJson)
    }

    root.put("daySchedules", schedulesJson)
    return root.toString()
}

fun String.toAppUsagePolicyOrDefault(): AppUsagePolicy {
    return runCatching {
        val root = JSONObject(this)
        val schedules = mutableMapOf<Int, List<UsageWindow>>()

        val schedulesJson = root.optJSONObject("daySchedules") ?: JSONObject()
        schedulesJson.keys().forEach { dayKey ->
            val day = dayKey.toIntOrNull() ?: return@forEach
            val windowsJson = schedulesJson.optJSONArray(dayKey) ?: JSONArray()
            val windows = buildList {
                for (i in 0 until windowsJson.length()) {
                    val item = windowsJson.optJSONObject(i) ?: continue
                    add(
                        UsageWindow(
                            id = item.optString("id", "${dayKey}_$i"),
                            startMinutes = item.optInt("startMinutes", 0),
                            endMinutes = item.optInt("endMinutes", 0),
                            allowedMinutes = item.optInt("allowedMinutes", 0)
                        )
                    )
                }
            }
            schedules[day] = windows
        }

        AppUsagePolicy(
            hardBlockEnabled = root.optBoolean("hardBlockEnabled", false),
            masterTimeEnabled = root.optBoolean("masterTimeEnabled", false),
            masterTimeMinutes = root.optInt("masterTimeMinutes", 0),
            daySchedules = schedules
        )
    }.getOrDefault(AppUsagePolicy())
}

