package com.example.locationcheckindjwfocus

import android.content.Context
import android.content.SharedPreferences

class CheckInPrefs(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var intervalMin: Int
        get() = prefs.getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_MIN, value).apply()

    var running: Boolean
        get() = prefs.getBoolean(KEY_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RUNNING, value).commit()
        }

    var seq: Long
        get() = prefs.getLong(KEY_SEQ, 0L)
        set(value) = prefs.edit().putLong(KEY_SEQ, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    var lastCheckInAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKIN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKIN_AT, value).apply()

    var lastProvider: String
        get() = prefs.getString(KEY_LAST_PROVIDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PROVIDER, value).apply()

    var lastCycleStartedAt: Long
        get() = prefs.getLong(KEY_LAST_CYCLE_STARTED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CYCLE_STARTED_AT, value).commit()
        }

    var lastCycleCompletedAt: Long
        get() = prefs.getLong(KEY_LAST_CYCLE_COMPLETED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CYCLE_COMPLETED_AT, value).commit()
        }

    var cycleInProgress: Boolean
        get() = prefs.getBoolean(KEY_CYCLE_IN_PROGRESS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CYCLE_IN_PROGRESS, value).commit()
        }

    fun saveSettings(url: String, apiKey: String, intervalMin: Int) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_API_KEY, apiKey)
            .putInt(KEY_INTERVAL_MIN, intervalMin)
            .apply()
    }

    fun saveCheckInResult(
        result: String,
        timeMs: Long,
        provider: String,
        seq: Long,
    ) {
        prefs.edit()
            .putString(KEY_LAST_RESULT, result)
            .putLong(KEY_LAST_CHECKIN_AT, timeMs)
            .putString(KEY_LAST_PROVIDER, provider)
            .putLong(KEY_SEQ, seq)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "checkin"
        const val DEFAULT_INTERVAL_MIN = 15
        const val MIN_INTERVAL_MIN = 5

        private const val KEY_URL = "url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_INTERVAL_MIN = "interval_min"
        private const val KEY_RUNNING = "running"
        private const val KEY_SEQ = "seq"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_CHECKIN_AT = "last_checkin_at"
        private const val KEY_LAST_PROVIDER = "last_provider"
        private const val KEY_LAST_CYCLE_STARTED_AT = "last_cycle_started_at"
        private const val KEY_LAST_CYCLE_COMPLETED_AT = "last_cycle_completed_at"
        private const val KEY_CYCLE_IN_PROGRESS = "cycle_in_progress"
    }
}
