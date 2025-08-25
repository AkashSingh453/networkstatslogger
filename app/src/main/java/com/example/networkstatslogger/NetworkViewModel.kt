package com.example.networkstatslogger

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.telephony.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    private val _isLogging = MutableStateFlow(false)
    val isLogging = _isLogging.asStateFlow()

    private val db = AppDatabase.getDatabase(application)
    private val networkLogDao = db.networkLogDao()

    val recentLogs: StateFlow<List<NetworkLog>> = networkLogDao.getRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun startUiUpdates(context: Context) {
        // Get static device info once for the UI
        _appState.update {
            it.copy(
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
                // THIS IS THE FIX: Corrected the typo from MANUFACTUFACTURER to MANUFACTURER
                deviceMake = Build.MANUFACTURER,
                deviceModel = Build.MODEL
            )
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                updateUiState(telephonyManager, subscriptionManager, connectivityManager)
            }
        }
        telephonyManager.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateLocation(it) }
            }
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun updateUiState(
        telephonyManager: TelephonyManager,
        subscriptionManager: SubscriptionManager,
        connectivityManager: ConnectivityManager
    ) {
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            _appState.update { it.copy(simStats = SimStats(networkType = "No Data SIM")) }
            return
        }
        val subInfo = subscriptionManager.getActiveSubscriptionInfo(defaultDataSubId) ?: return
        val tm = telephonyManager.createForSubscriptionId(subInfo.subscriptionId)
        val cellInfo = tm.allCellInfo?.firstOrNull { it.isRegistered }
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        val downSpeed = caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"
        val upSpeed = caps?.linkUpstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"

        val newSimStats = if (cellInfo == null) {
            SimStats(carrierName = subInfo.displayName.toString(), networkType = "Not Registered")
        } else {
            when (cellInfo) {
                is CellInfoLte -> {
                    val id = cellInfo.cellIdentity
                    val str = cellInfo.cellSignalStrength
                    SimStats(
                        carrierName = subInfo.displayName.toString(), networkType = "LTE (4G)",
                        pci = id.pci.toString(), rsrp = "${str.rsrp} dBm",
                        rsrq = "${str.rsrq} dB", sinr = "${str.rssnr} dB",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoNr -> {
                    val id = cellInfo.cellIdentity as CellIdentityNr
                    val str = cellInfo.cellSignalStrength as CellSignalStrengthNr
                    SimStats(
                        carrierName = subInfo.displayName.toString(), networkType = "5G NR",
                        pci = id.pci.toString(), rsrp = "${str.ssRsrp} dBm",
                        rsrq = "${str.ssRsrq} dB", sinr = "${str.ssSinr} dB",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoWcdma -> {
                    val str = cellInfo.cellSignalStrength
                    SimStats(
                        carrierName = subInfo.displayName.toString(), networkType = "WCDMA (3G)",
                        pci = "N/A", rsrp = "${str.dbm} dBm", rsrq = "N/A", sinr = "N/A",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoGsm -> {
                    val str = cellInfo.cellSignalStrength
                    SimStats(
                        carrierName = subInfo.displayName.toString(), networkType = "GSM (2G)",
                        pci = "N/A", rsrp = "${str.dbm} dBm", rsrq = "N/A", sinr = "N/A",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                else -> SimStats(carrierName = subInfo.displayName.toString(), networkType = "Other")
            }
        }
        _appState.update { it.copy(simStats = newSimStats) }
    }

    private fun updateLocation(location: Location) {
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toFloat() else 0.0f
        _appState.update {
            it.copy(
                latitude = String.format("%.6f", location.latitude),
                longitude = String.format("%.6f", location.longitude),
                velocity = "${String.format("%.2f", speedKmh)} km/h"
            )
        }
    }

    fun startLogging(context: Context) {
        val intent = Intent(context, NetworkLoggerService::class.java).apply { action = NetworkLoggerService.ACTION_START }
        ContextCompat.startForegroundService(context, intent)
        _isLogging.value = true
        scheduleFirebaseUpload(context)
    }

    fun stopLogging(context: Context) {
        val intent = Intent(context, NetworkLoggerService::class.java).apply { action = NetworkLoggerService.ACTION_STOP }
        context.startService(intent)
        _isLogging.value = false
      //  cancelFirebaseUpload(context)
    }

    private fun scheduleFirebaseUpload(context: Context) {
        val uploadWorkRequest = PeriodicWorkRequestBuilder<FirebaseUploader>(5, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "FirebaseUploadWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            uploadWorkRequest
        )
    }

    fun backupNow(context: Context) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<FirebaseUploader>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueue(uploadWorkRequest)
        Toast.makeText(context, "Backup to Firebase started...", Toast.LENGTH_SHORT).show()
    }

    private fun cancelFirebaseUpload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("FirebaseUploadWorker")
    }

    fun clearLogs() {
        viewModelScope.launch {
            networkLogDao.clearAllLogs()
            Toast.makeText(getApplication(), "Logs Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportLogsToCsv(context: Context) {
        viewModelScope.launch {
            val logs = networkLogDao.getAllLogs()
            if (logs.isEmpty()) {
                Toast.makeText(context, "No logs to export.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val csvHeader = "Timestamp,DeviceID,deviceMake,deviceModel,Network provi. , NetworkType,RSRP,RSRQ,SINR,PCI,Downlink(Mbps),Uplink(Mbps),Velocity(km/h),Latitude,Longitude\n"
            val stringBuilder = StringBuilder().append(csvHeader)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            logs.forEach { log ->
                stringBuilder.append("${log.timestamp},${log.deviceId},${log.deviceMake},${log.deviceModel} , ${log.carrierName},${log.networkType},${log.rsrp},${log.rsrq},${log.sinr},${log.pci},${log.downlinkSpeed},${log.uplinkSpeed},${log.velocity},${log.latitude},${log.longitude}\n")
            }
            try {
                val file = File(context.cacheDir, "network_logs_${System.currentTimeMillis()}.csv")
                FileWriter(file).use { it.write(stringBuilder.toString()) }
                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Logs"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}