package dev.pranav.applock.data.repository

import java.time.DayOfWeek

data class AppUsagePolicy(
    val appPackage: String,
    val dayConfigs: Map<DayOfWeek, DayUsageConfig> = emptyMap(),
    val masterTimeEnabled: Boolean = false,
    val masterTimeMinutes: Int = 0
)

data class DayUsageConfig(
    val enabled: Boolean = false,
    val dailyLimitMinutes: Int = 0,
    val windows: List<UsageWindow> = emptyList()
)

data class UsageWindow(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val limitMinutes: Int
)

data class DayUsageStats(
    val dateKey: String,
    val totalUsedMs: Long = 0L,
    val masterUsedMs: Long = 0L,
    val windowUsedMs: Map<String, Long> = emptyMap()
)
