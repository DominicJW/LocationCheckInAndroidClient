package com.example.locationcheckindjwfocus

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.locationcheckindjwfocus.ui.theme.LocationCheckInDJWFocusTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocationCheckInDJWFocusTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CheckInScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun CheckInScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { CheckInPrefs(context) }

    var url by remember { mutableStateOf(prefs.url) }
    var intervalText by remember { mutableStateOf(prefs.intervalMin.toString()) }
    var running by remember { mutableStateOf(prefs.running) }
    var lastResult by remember { mutableStateOf(prefs.lastResult) }
    var lastCheckInAt by remember { mutableStateOf(prefs.lastCheckInAt) }
    var lastSeq by remember { mutableStateOf(prefs.seq) }
    var lastProvider by remember { mutableStateOf(prefs.lastProvider) }
    var errorMessage by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }

    fun refreshStatus() {
        running = prefs.running
        lastResult = prefs.lastResult
        lastCheckInAt = prefs.lastCheckInAt
        lastSeq = prefs.seq
        lastProvider = prefs.lastProvider
        refreshTick++
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshStatus()
        }
        prefs.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    val hasNotifications = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val hasBackground = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val batteryExempt = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun actuallyStart() {
        val interval = intervalText.toIntOrNull() ?: return
        prefs.saveSettings(url.trim(), interval)
        prefs.running = true
        running = true
        errorMessage = ""
        Log.d(CheckInService.TAG, "start pressed; first cycle now")
        CheckInService.startNow(context)
        refreshStatus()
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++
        if (granted) {
            actuallyStart()
        } else {
            errorMessage = "Background location must be allowed all the time. Not starting."
        }
    }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++
        if (!granted) {
            errorMessage = "Fine location permission denied. Not starting."
            return@rememberLauncherForActivityResult
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            actuallyStart()
        } else {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++
        if (!granted) {
            errorMessage = "Notification permission denied. Not starting."
            return@rememberLauncherForActivityResult
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            actuallyStart()
        }
    }

    fun validate(): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        val interval = intervalText.toIntOrNull()
            ?: return "Interval must be a number of minutes"
        if (interval < CheckInPrefs.MIN_INTERVAL_MIN) {
            return "Interval must be at least ${CheckInPrefs.MIN_INTERVAL_MIN} minutes"
        }
        return null
    }

    fun onStartStop() {
        if (running) {
            CheckInService.stop(context)
            running = false
            errorMessage = ""
            refreshStatus()
            return
        }
        val validationError = validate()
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        prefs.saveSettings(url.trim(), intervalText.toInt())
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED -> {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED -> {
                fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED -> {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> actuallyStart()
        }
    }

    val warnings = buildList {
        if (!hasBackground) add("Background location is not set to all the time.")
        if (!hasNotifications) add("Notification permission is denied.")
        if (!batteryExempt) add("Battery optimisation is not exempt.")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Location check-in", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Check-in URL") },
            singleLine = true,
            enabled = !running,
        )
        OutlinedTextField(
            value = intervalText,
            onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Interval (minutes, min ${CheckInPrefs.MIN_INTERVAL_MIN})") },
            singleLine = true,
            enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(
            onClick = { onStartStop() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Stop" else "Start")
        }
        if (errorMessage.isNotBlank()) {
            Text(errorMessage, color = Color(0xFFB00020))
        }
        warnings.forEach { warning ->
            Text(warning, color = Color(0xFFB00020), style = MaterialTheme.typography.bodyLarge)
        }
        if (running && !batteryExempt) {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    activity.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Exempt from battery optimisation")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Status", style = MaterialTheme.typography.titleMedium)
        Text("State: ${if (running) "running" else "stopped"}")
        Text("Last check-in: ${formatLocal(lastCheckInAt)}")
        Text("Result: ${lastResult.ifBlank { "—" }}")
        Text("Last seq: ${if (lastSeq > 0L) lastSeq.toString() else "—"}")
        Text("Provider: ${lastProvider.ifBlank { "—" }}")
    }
}

private fun formatLocal(timeMs: Long): String {
    if (timeMs <= 0L) return "—"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timeMs))
}
