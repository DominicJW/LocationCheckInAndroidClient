package com.example.locationcheckindjwfocus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant

object AlarmScheduler {
    const val TAG = "LocationCheckIn"

    private const val ALARM_REQUEST_CODE = 1001
    private const val SHOW_REQUEST_CODE = 1002

    fun scheduleNext(context: Context, cycleStartTimeMs: Long, intervalMin: Int) {
        val triggerAt = cycleStartTimeMs + intervalMin * 60_000L
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent(appContext))
        alarmManager.setAlarmClock(info, alarmPendingIntent(appContext))
        Log.d(TAG, "next alarm at ${Instant.ofEpochMilli(triggerAt)}")
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(alarmPendingIntent(appContext))
        alarmPendingIntent(appContext).cancel()
        Log.d(TAG, "alarm cancelled")
    }

    fun rearmIfRunning(context: Context) {
        val prefs = CheckInPrefs(context)
        if (!prefs.running) return
        val intervalMin = prefs.intervalMin.coerceAtLeast(CheckInPrefs.MIN_INTERVAL_MIN)
        val lastStart = prefs.lastCycleStartedAt
        val now = System.currentTimeMillis()
        val dueAt = if (lastStart > 0L) lastStart + intervalMin * 60_000L else now
        if (dueAt <= now) {
            Log.d(TAG, "check-in overdue; running cycle now")
            CheckInService.startCycle(context)
        } else {
            scheduleNext(context, lastStart, intervalMin)
        }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
