package org.klaud

import android.content.Context
import android.content.SharedPreferences

object SyncPreferences {
    private const val PREFS_NAME = "klaudsyncprefs"
    private const val KEY_INTERVAL_HOURS = "sync_interval_hours"

    val OPTIONS: List<Pair<Long, String>> = listOf(
        -1L  to "Only on file change",
         1L  to "Every hour",
         6L  to "Every 6 hours",
        12L  to "Every 12 hours",
        24L  to "Daily (Default)",
        72L  to "Every 3 days",
       168L  to "Weekly"
    )

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getIntervalHours(): Long = prefs.getLong(KEY_INTERVAL_HOURS, 24L)

    fun setIntervalHours(hours: Long) {
        prefs.edit().putLong(KEY_INTERVAL_HOURS, hours).apply()
    }

    fun getLabelForHours(hours: Long): String =
        OPTIONS.firstOrNull { it.first == hours }?.second ?: "Täglich (Standard)"
}
