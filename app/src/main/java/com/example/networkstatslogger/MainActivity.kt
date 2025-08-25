package com.example.networkstatslogger

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.networkstatslogger.ui.theme.NetworkstatsloggerTheme
import com.google.accompanist.permissions.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: NetworkViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetworkstatsloggerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val permissions = remember {
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ACCESS_NETWORK_STATE
                        )
                    }
                    val permissionsState = rememberMultiplePermissionsState(permissions)

                    LaunchedEffect(Unit) {
                        permissionsState.launchMultiplePermissionRequest()
                    }

                    when {
                        permissionsState.allPermissionsGranted -> StatsScreen(viewModel)
                        else -> PermissionRationale { permissionsState.launchMultiplePermissionRequest() }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun StatsScreen(viewModel: NetworkViewModel) {
    val isLogging by viewModel.isLogging.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()
    val logInterval by viewModel.logIntervalMs.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startUiUpdates(context)
        onDispose {}
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text("Network & GPS Logger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${appState.deviceMake} ${appState.deviceModel} (ID: ${appState.deviceId})",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
         //   CurrentStatsView(appState = appState)
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            // NEW: Interval Settings UI
            IntervalSettings(
                interval = logInterval,
                onIntervalChange = { viewModel.onIntervalChange(it) },
                onSaveClick = { viewModel.saveLogInterval() },
                isLogging = isLogging
            )
            Spacer(Modifier.height(16.dp))
            LoggingControls(
                isLogging = isLogging,
                onStartClick = { viewModel.startLogging(context) },
                onStopClick = { viewModel.stopLogging(context) }
            )
            Spacer(Modifier.height(16.dp))
            DataManagementControls(
                onExportClick = { viewModel.exportLogsToCsv(context) },
                onClearClick = { viewModel.clearLogs() },
                onBackupClick = { viewModel.backupNow(context) }
            )
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text("Recent Logs", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    LogHeader()
                    Divider()
                    if (recentLogs.isEmpty()) {
                        Text(
                            "Start logging to see recent entries here.",
                            modifier = Modifier.width(1400.dp).padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        recentLogs.forEach { log ->
                            LogItem(log = log)
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IntervalSettings(
    interval: String,
    onIntervalChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    isLogging: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = interval,
            onValueChange = onIntervalChange,
            label = { Text("Log Interval (ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            enabled = !isLogging
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSaveClick, enabled = !isLogging) {
            Text("Save")
        }
    }
}

@Composable
fun CurrentStatsView(appState: AppState) {
    // It takes the current AppState, which contains all the live data.
    val primarySim = appState.simStats

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // It checks if there's a valid SIM signal or if the phone is in airplane mode.
        if (primarySim == null || primarySim.networkType in listOf("Airplane Mode", "No SIM", "No Data SIM", "Data SIM Inactive", "Not Registered")) {
            Text(
                text = primarySim?.networkType ?: "No Signal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            // If there's a valid signal, it calls SimStatColumn to display the details.
            SimStatColumn(sim = primarySim)
        }
        Spacer(Modifier.height(16.dp))
        // It always displays the current GPS and velocity data at the bottom.
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            StatCard("Latitude", appState.latitude, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("Longitude", appState.longitude, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("Velocity", appState.velocity, Modifier.weight(1f))
        }
    }
}

@Composable
fun SimStatColumn(sim: SimStats, modifier: Modifier = Modifier) {
    // This function determines the correct label for the primary signal strength metric.
    val strengthLabel = when {
        sim.networkType.contains("GSM") -> "RSSI"
        sim.networkType.contains("WCDMA") -> "RSCP"
        else -> "RSRP"
    }

    // It then lays out all the individual stat cards in a column.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(sim.carrierName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(sim.networkType, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            StatCard(strengthLabel, sim.rsrp, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("RSRQ", sim.rsrq, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            StatCard("SINR", sim.sinr, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("PCI", sim.pci, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            StatCard("Downlink", sim.downlinkSpeed, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("Uplink", sim.uplinkSpeed, Modifier.weight(1f))
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun LogHeader() {
    Row(
        modifier = Modifier
            .width(1400.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Time", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
        Text("Device ID", modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("Carrier", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("RSRP/...", modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("DL(Mbps)", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("UL(Mbps)", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("Velocity", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("Latitude", modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text("Longitude", modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LogItem(log: NetworkLog) {
    val timeOnly = remember(log.timestamp) {
        log.timestamp.substringAfter(" ")
    }
    Row(
        modifier = Modifier
            .width(1400.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = timeOnly, modifier = Modifier.weight(1.5f))
        Text(text = log.deviceId, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
        Text(text = log.carrierName, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = log.rsrp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
        Text(text = log.downlinkSpeed, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = log.uplinkSpeed, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = log.velocity, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = log.latitude, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
        Text(text = log.longitude, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
    }
}

@Composable
fun LoggingControls(isLogging: Boolean, onStartClick: () -> Unit, onStopClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isLogging) "Status: Logging..." else "Status: Stopped",
            color = if (isLogging) Color(0xFF388E3C) else Color.Gray,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = onStartClick, enabled = !isLogging) { Text("Start Logging") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onStopClick,
                enabled = isLogging,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Stop Logging") }
        }
    }
}

@Composable
fun DataManagementControls(
    onExportClick: () -> Unit,
    onClearClick: () -> Unit,
    onBackupClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Button(onClick = onExportClick) { Text("Export to CSV") }
            Spacer(Modifier.width(16.dp))
            // NEW: Added Backup Now button
            Button(onClick = onBackupClick) { Text("Backup Now") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClearClick) {
            Text("Clear All Saved Logs", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun PermissionRationale(onPermissionRequested: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Permissions Required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "This app needs Location, Phone, and Network State permissions to read network signal information. Please grant these permissions to continue.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPermissionRequested) { Text("Grant Permissions") }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    NetworkstatsloggerTheme {
        StatsScreen(viewModel = NetworkViewModel(Application()))
    }
}
