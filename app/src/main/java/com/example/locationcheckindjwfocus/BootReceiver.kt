package com.example.locationcheckindjwfocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = CheckInPrefs(context)
        if (!prefs.running) {
            Log.d(AlarmScheduler.TAG, "$action: running=false; not restarting")
            return
        }
        Log.d(AlarmScheduler.TAG, "$action: restarting service and re-arming alarm")
        CheckInService.startResume(context)
        AlarmScheduler.rearmIfRunning(context)
    }
}
