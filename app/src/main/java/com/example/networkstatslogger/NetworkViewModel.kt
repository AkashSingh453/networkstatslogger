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

/** Number of rows fetched per page. */
private const val PAGE_SIZE = 50

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    private val _isLogging = MutableStateFlow(false)
    val isLogging = _isLogging.asStateFlow()

    private val _logIntervalMs = MutableStateFlow("5000")
    val logIntervalMs = _logIntervalMs.asStateFlow()

    private val db = AppDatabase.getDatabase(application)
    private val networkLogDao = db.networkLogDao()

    private val sharedPreferences =
        application.getSharedPreferences("NetworkLoggerPrefs", Context.MODE_PRIVATE)

    // ── Pagination state ─────────────────────────────────────────────────────

    /** The currently-visible page window — never holds the full DB in memory. */
    private val _paginatedLogs = MutableStateFlow<List<NetworkLog>>(emptyList())
    val paginatedLogs = _paginatedLogs.asStateFlow()

    /** True while a page fetch is in flight — used to show a loading spinner. */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    /** False once a fetch returns fewer rows than PAGE_SIZE (end of table). */
    private val _hasMoreLogs = MutableStateFlow(true)
    val hasMoreLogs = _hasMoreLogs.asStateFlow()

    /**
     * The `id` of the newest row that was visible when the initial page was
     * loaded. All rows with id > anchorId are considered "new" and counted by
     * the live badge, but are NOT injected into the list automatically to
     * avoid scroll jumps.
     *
     * Stored as a StateFlow so the downstream flatMapLatest can react to
     * changes without needing Compose's snapshotFlow.
     */
    private val _anchorId = MutableStateFlow(Int.MAX_VALUE)

    /**
     * Live count of rows inserted after [_anchorId]. Drives the
     * "↑ N new logs" banner. Resets to 0 whenever the user taps the banner.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val newLogsCount: StateFlow<Int> = _anchorId
        .flatMapLatest { id ->
            if (id == Int.MAX_VALUE) flowOf(0)
            else networkLogDao.countLogsNewerThan(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        loadLogInterval()
    }

    // ── Pagination functions ─────────────────────────────────────────────────

    /**
     * Load the initial page of logs. Call this once when the log list becomes
     * visible. Safe to call multiple times — subsequent calls are no-ops if
     * data is already loaded.
     */
    fun loadInitialLogs() {
        if (_paginatedLogs.value.isNotEmpty() || _isLoadingMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            val page = networkLogDao.getInitialLogs(PAGE_SIZE)
            _paginatedLogs.value = page
            // All rows newer than the initial anchor are "new" for the badge.
            _anchorId.value = page.firstOrNull()?.id ?: Int.MAX_VALUE
            _hasMoreLogs.value = page.size >= PAGE_SIZE
            _isLoadingMore.value = false
        }
    }

    /**
     * Load the next page of older rows. Called when the user scrolls near the
     * bottom of the list. Ignored if a fetch is already in flight or we've
     * reached the end of the table.
     */
    fun loadMoreLogs() {
        if (_isLoadingMore.value || !_hasMoreLogs.value) return
        val lastId = _paginatedLogs.value.lastOrNull()?.id ?: return
        viewModelScope.launch {
            _isLoadingMore.value = true
            val page = networkLogDao.getLogsBeforeId(lastId = lastId, pageSize = PAGE_SIZE)
            if (page.isNotEmpty()) {
                _paginatedLogs.value = _paginatedLogs.value + page
            }
            _hasMoreLogs.value = page.size >= PAGE_SIZE
            _isLoadingMore.value = false
        }
    }

    /**
     * Fetch all rows newer than [anchorId], prepend them to the list, and
     * advance the anchor. Called when the user taps the "N new logs" banner.
     * Uses `key`-based item tracking in the LazyColumn, so existing items
     * don't move visually.
     */
    fun prependNewLogs() {
        viewModelScope.launch {
            // Fetch new rows from DB (newest first)
            val newRows = networkLogDao.getLogsNewerThanId(anchorId = _anchorId.value)
            if (newRows.isEmpty()) return@launch
            _paginatedLogs.value = newRows + _paginatedLogs.value
            // Advance the anchor to the newest row we just loaded
            _anchorId.value = newRows.first().id
        }
    }

    /**
     * Reset pagination state — call after clearing all logs so the list
     * empties immediately and a fresh load can be triggered.
     */
    private fun resetPagination() {
        _paginatedLogs.value = emptyList()
        _hasMoreLogs.value = true
        _anchorId.value = Int.MAX_VALUE
    }

    // ── UI state updates ─────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun startUiUpdates(context: Context) {
        _appState.update {
            it.copy(
                deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ),
                deviceMake = Build.MANUFACTURER,
                deviceModel = Build.MODEL
            )
        }

        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                updateUiState(telephonyManager, subscriptionManager, connectivityManager)
            }
        }
        telephonyManager.registerTelephonyCallback(
            Executors.newSingleThreadExecutor(), callback
        )

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateLocation(it) }
            }
        }

        val interval = _logIntervalMs.value.toLongOrNull() ?: 5000L
        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval).build()
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
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
        val subInfo =
            subscriptionManager.getActiveSubscriptionInfo(defaultDataSubId) ?: return
        val tm = telephonyManager.createForSubscriptionId(subInfo.subscriptionId)
        val cellInfo = tm.allCellInfo?.firstOrNull { it.isRegistered }
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        val downSpeed = caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"
        val upSpeed = caps?.linkUpstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"

        val newSimStats = if (cellInfo == null) {
            SimStats(
                carrierName = subInfo.displayName.toString(),
                networkType = "Not Registered"
            )
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
                else -> SimStats(
                    carrierName = subInfo.displayName.toString(),
                    networkType = "Other"
                )
            }
        }
        _appState.update { it.copy(simStats = newSimStats) }
    }

    private fun updateLocation(location: Location) {
        val speedKmh =
            if (location.hasSpeed()) (location.speed * 3.6).toFloat() else 0.0f
        _appState.update {
            it.copy(
                latitude = String.format("%.6f", location.latitude),
                longitude = String.format("%.6f", location.longitude),
                velocity = "${String.format("%.2f", speedKmh)} km/h"
            )
        }
    }

    // ── Logging controls ─────────────────────────────────────────────────────

    fun startLogging(context: Context) {
        val interval = _logIntervalMs.value.toLongOrNull() ?: 5000L
        val intent = Intent(context, NetworkLoggerService::class.java).apply {
            action = NetworkLoggerService.ACTION_START
            putExtra(NetworkLoggerService.EXTRA_LOG_INTERVAL, interval)
        }
        ContextCompat.startForegroundService(context, intent)
        _isLogging.value = true
        scheduleFirebaseUpload(context)
    }

    fun stopLogging(context: Context) {
        val intent = Intent(context, NetworkLoggerService::class.java).apply {
            action = NetworkLoggerService.ACTION_STOP
        }
        context.startService(intent)
        _isLogging.value = false
    }

    private fun scheduleFirebaseUpload(context: Context) {
        val uploadWorkRequest =
            PeriodicWorkRequestBuilder<FirebaseUploader>(5, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        // Uncomment to re-enable scheduled backup:
        // WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        //     "FirebaseUploadWorker",
        //     ExistingPeriodicWorkPolicy.KEEP,
        //     uploadWorkRequest
        // )
    }

    fun backupNow(context: Context) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<FirebaseUploader>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(uploadWorkRequest)
        Toast.makeText(context, "Backup to Firebase started...", Toast.LENGTH_SHORT).show()
    }

    fun clearLogs() {
        viewModelScope.launch {
            networkLogDao.clearAllLogs()
            resetPagination()
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
            val csvHeader =
                "Timestamp,DeviceID,deviceMake,deviceModel,Network provi. , NetworkType,RSRP,RSRQ,SINR,PCI,Downlink(Mbps),Uplink(Mbps),Velocity(km/h),Latitude,Longitude\n"
            val stringBuilder = StringBuilder().append(csvHeader)
            logs.forEach { log ->
                stringBuilder.append(
                    "${log.timestamp},${log.deviceId},${log.deviceMake},${log.deviceModel}" +
                        " , ${log.carrierName},${log.networkType},${log.rsrp},${log.rsrq}," +
                        "${log.sinr},${log.pci},${log.downlinkSpeed},${log.uplinkSpeed}," +
                        "${log.velocity},${log.latitude},${log.longitude}\n"
                )
            }
            try {
                val file =
                    File(context.cacheDir, "network_logs_${System.currentTimeMillis()}.csv")
                FileWriter(file).use { it.write(stringBuilder.toString()) }
                val contentUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Logs"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun onIntervalChange(newInterval: String) {
        _logIntervalMs.value = newInterval
    }

    fun saveLogInterval() {
        val interval = _logIntervalMs.value.toLongOrNull() ?: 5000L
        sharedPreferences.edit().putLong("log_interval", interval).apply()
        Toast.makeText(getApplication(), "Interval saved!", Toast.LENGTH_SHORT).show()
    }

    private fun loadLogInterval() {
        val interval = sharedPreferences.getLong("log_interval", 5000L)
        _logIntervalMs.value = interval.toString()
    }
}