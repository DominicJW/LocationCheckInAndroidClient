package com.example.locationcheckindjwfocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.IBinder
import android.os.PowerManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

class CheckInService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + job)
    private lateinit var prefs: CheckInPrefs
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        prefs = CheckInPrefs(this)
        createNotificationChannel()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.setReferenceCounted(false)
        startInForeground("Location check-in running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(currentNotificationText())
        if (!prefs.running) {
            Log.d(TAG, "service started but running=false; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopCheckIn()
                return START_NOT_STICKY
            }
            ACTION_CYCLE, ACTION_START -> {
                scope.launch { runCycle() }
            }
            ACTION_RESUME, null -> {
                AlarmScheduler.rearmIfRunning(this)
            }
            else -> {
                AlarmScheduler.rearmIfRunning(this)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopCheckIn() {
        prefs.running = false
        prefs.cycleInProgress = false
        AlarmScheduler.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "stopped")
    }

    private suspend fun runCycle() {
        val now = System.currentTimeMillis()
        if (prefs.cycleInProgress) {
            val started = prefs.lastCycleStartedAt
            val age = now - started
            if (started > 0L && age < MAX_CYCLE_DURATION_MS) {
                Log.d(TAG, "skip overlapping cycle (running for ${age}ms)")
                return
            }
            Log.e(TAG, "previous cycle flagged running for ${age}ms; treating as dead")
        }

        val cycleStart = System.currentTimeMillis()
        prefs.cycleInProgress = true
        prefs.lastCycleStartedAt = cycleStart

        val intervalMin = prefs.intervalMin.coerceAtLeast(CheckInPrefs.MIN_INTERVAL_MIN)
        AlarmScheduler.scheduleNext(this, cycleStart, intervalMin)
        Log.d(TAG, "cycle start")

        acquireWakeLock()
        try {
            val fix = obtainLocation()
            val providerUsed = fix.providerLabel
            if (fix.location != null) {
                Log.d(
                    TAG,
                    "provider=$providerUsed accuracy=${fix.location.accuracy} stale=${fix.stale}",
                )
            } else {
                Log.d(TAG, "provider=none (no location)")
            }
            val seq = prefs.seq + 1
            val sendTime = System.currentTimeMillis()
            val postResult = postCheckIn(prefs.url, fix, seq, intervalMin, sendTime)
            val resultText = when {
                fix.location == null && postResult.ok -> "No location; POST ok"
                fix.location == null -> "No location; POST failed: ${postResult.reason}"
                postResult.ok -> "POST ok"
                else -> "POST failed: ${postResult.reason}"
            }
            if (postResult.ok) {
                Log.d(TAG, "POST result=$resultText")
            } else {
                Log.e(TAG, "POST result=$resultText")
            }
            prefs.saveCheckInResult(
                result = resultText,
                timeMs = sendTime,
                provider = providerUsed,
                seq = seq,
            )
            startInForeground("$resultText · ${isoUtc(sendTime)}")
        } catch (t: Throwable) {
            Log.e(TAG, "cycle failed", t)
            val sendTime = System.currentTimeMillis()
            val seq = prefs.seq + 1
            val resultText = "POST failed: ${t.message ?: t.javaClass.simpleName}"
            prefs.saveCheckInResult(resultText, sendTime, "", seq)
            startInForeground("$resultText · ${isoUtc(sendTime)}")
        } finally {
            prefs.lastCycleCompletedAt = System.currentTimeMillis()
            prefs.cycleInProgress = false
            releaseWakeLock()
        }
    }

    private data class LocationFix(
        val location: Location?,
        val providerKind: String,
        val stale: Boolean,
        val error: String?,
    ) {
        val providerLabel: String
            get() = if (location == null) "" else providerKind
    }

    private suspend fun obtainLocation(): LocationFix {
        val lm = getSystemService(LocationManager::class.java)
        val newestKnown = newestLastKnown(lm)
        if (newestKnown != null) {
            val age = System.currentTimeMillis() - newestKnown.time
            if (age in 0 until LAST_KNOWN_MAX_AGE_MS) {
                return LocationFix(
                    location = newestKnown,
                    providerKind = PROVIDER_LAST_KNOWN,
                    stale = false,
                    error = null,
                )
            }
        }

        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val network = requestCurrentLocation(
                lm,
                LocationManager.NETWORK_PROVIDER,
                NETWORK_TIMEOUT_MS,
            )
            if (network != null && network.hasAccuracy() && network.accuracy <= NETWORK_MAX_ACCURACY_M) {
                return LocationFix(network, PROVIDER_NETWORK, stale = false, error = null)
            }
            if (network != null) {
                Log.d(TAG, "network fix rejected accuracy=${network.accuracy}")
            }
        }

        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val gps = requestCurrentLocation(lm, LocationManager.GPS_PROVIDER, GPS_TIMEOUT_MS)
            if (gps != null) {
                return LocationFix(gps, PROVIDER_GPS, stale = false, error = null)
            }
        }

        if (newestKnown != null) {
            return LocationFix(newestKnown, PROVIDER_LAST_KNOWN, stale = true, error = null)
        }
        return LocationFix(null, "", stale = true, error = "No location")
    }

    private fun newestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in lm.allProviders) {
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (t: Throwable) {
                Log.e(TAG, "getLastKnownLocation($provider) failed", t)
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) {
                best = loc
            }
        }
        return best
    }

    private suspend fun requestCurrentLocation(
        lm: LocationManager,
        provider: String,
        timeoutMs: Long,
    ): Location? {
        val signal = CancellationSignal()
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        lm.getCurrentLocation(provider, signal, mainExecutor) { location ->
                            if (cont.isActive) {
                                cont.resume(location)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "getCurrentLocation($provider) failed", t)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            if (!signal.isCanceled) {
                signal.cancel()
            }
        }
    }

    private fun postCheckIn(
        urlString: String,
        fix: LocationFix,
        seq: Long,
        intervalMin: Int,
        sendTimeMs: Long,
    ): PostResult {
        val loc = fix.location
        val body = JSONObject().apply {
            if (loc != null) {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy.toDouble())
                put("provider", fix.providerKind)
                put("stale", fix.stale)
                put("fix_timestamp", isoUtc(loc.time))
            } else {
                put("lat", JSONObject.NULL)
                put("lon", JSONObject.NULL)
                put("accuracy", JSONObject.NULL)
                put("provider", JSONObject.NULL)
                put("stale", true)
                put("fix_timestamp", JSONObject.NULL)
                put("error", fix.error ?: "No location")
            }
            put("client_timestamp", isoUtc(sendTimeMs))
            put("battery", batteryPercent())
            put("seq", seq)
            put("interval_min", intervalMin)
        }
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS.toInt()
                readTimeout = HTTP_TIMEOUT_MS.toInt()
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            if (code in 200..299) {
                PostResult(ok = true, reason = "HTTP $code")
            } else {
                PostResult(ok = false, reason = "HTTP $code")
            }
        } catch (t: Throwable) {
            PostResult(ok = false, reason = t.message ?: t.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private data class PostResult(val ok: Boolean, val reason: String)

    private fun batteryPercent(): Int {
        val bm = getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wake lock release failed", t)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground(text: String) {
        val pending = PendingIntent.getActivity(
            this,
            NOTIF_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_checkin)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun currentNotificationText(): String {
        val result = prefs.lastResult
        val time = prefs.lastCheckInAt
        return if (result.isNotBlank() && time > 0L) {
            "$result · ${isoUtc(time)}"
        } else {
            "Location check-in running"
        }
    }

    companion object {
        const val TAG = "LocationCheckIn"
        const val ACTION_START = "com.example.locationcheckindjwfocus.ACTION_START"
        const val ACTION_CYCLE = "com.example.locationcheckindjwfocus.ACTION_CYCLE"
        const val ACTION_RESUME = "com.example.locationcheckindjwfocus.ACTION_RESUME"
        const val ACTION_STOP = "com.example.locationcheckindjwfocus.ACTION_STOP"

        private const val CHANNEL_ID = "location_checkin"
        private const val NOTIFICATION_ID = 1
        private const val NOTIF_REQUEST_CODE = 1003
        private const val WAKE_LOCK_TAG = "LocationCheckInDJWFocus:cycle"

        const val NETWORK_MAX_ACCURACY_M = 300f
        const val NETWORK_TIMEOUT_MS = 20_000L
        const val GPS_TIMEOUT_MS = 270_000L
        const val HTTP_TIMEOUT_MS = 10_000L
        const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 1000L
        const val LAST_KNOWN_MAX_AGE_MS = 2 * 60 * 1000L
        const val MAX_CYCLE_DURATION_MS =
            NETWORK_TIMEOUT_MS + GPS_TIMEOUT_MS + HTTP_TIMEOUT_MS + 60_000L

        private const val PROVIDER_NETWORK = "network"
        private const val PROVIDER_GPS = "gps"
        private const val PROVIDER_LAST_KNOWN = "last_known"

        private val ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

        fun isoUtc(timeMs: Long): String = ISO_UTC.format(Instant.ofEpochMilli(timeMs))

        fun startNow(context: Context) {
            val intent = Intent(context, CheckInService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun startCycle(context: Context) {
            val intent = Intent(context, CheckInService::class.java).apply {
                action = ACTION_CYCLE
            }
            context.startForegroundService(intent)
        }

        fun startResume(context: Context) {
            val intent = Intent(context, CheckInService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            AlarmScheduler.cancel(context)
            CheckInPrefs(context).running = false
            val intent = Intent(context, CheckInService::class.java).apply {
                action = ACTION_STOP
            }
            context.startForegroundService(intent)
        }
    }
}
