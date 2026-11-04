package com.example.locationcheckindjwfocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ALARM) return
        val prefs = CheckInPrefs(context)
        if (!prefs.running) {
            Log.d(AlarmScheduler.TAG, "alarm fired but running=false; ignoring")
            return
        }
        Log.d(AlarmScheduler.TAG, "alarm fired; starting cycle")
        CheckInService.startCycle(context)
    }

    companion object {
        const val ACTION_ALARM = "com.example.locationcheckindjwfocus.ACTION_ALARM"
    }
}
