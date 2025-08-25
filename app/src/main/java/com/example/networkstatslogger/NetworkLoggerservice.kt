package com.example.networkstatslogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class NetworkLoggerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var connectivityManager: ConnectivityManager
    private var telephonyCallback: TelephonyCallback? = null
    private val locationCallback: LocationCallback
    private val networkLogDao by lazy { AppDatabase.getDatabase(this).networkLogDao() }

    private var currentAppState = AppState()

    companion object {
        const val ACTION_START = "com.example.networkstatslogger.ACTION_START"
        const val ACTION_STOP = "com.example.networkstatslogger.ACTION_STOP"
        const val EXTRA_LOG_INTERVAL = "com.example.networkstatslogger.EXTRA_LOG_INTERVAL"
        const val NOTIFICATION_CHANNEL_ID = "NetworkLoggerChannel"
        const val NOTIFICATION_ID = 1
        private const val DEFAULT_SAVE_INTERVAL_MS = 5000L
    }

    init {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateLocation(it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val interval = intent.getLongExtra(EXTRA_LOG_INTERVAL, DEFAULT_SAVE_INTERVAL_MS)
                startLogging(interval)
            }
            ACTION_STOP -> stopLogging()
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun startLogging(intervalMs: Long) {
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
        currentAppState = currentAppState.copy(
            deviceId = getDeviceId(this),
            deviceMake = Build.MANUFACTURER,
            deviceModel = Build.MODEL
        )

        telephonyCallback = @RequiresApi(Build.VERSION_CODES.S)
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                parseCellInfo()
            }
        }
        telephonyManager.registerTelephonyCallback(Executors.newSingleThreadExecutor(), telephonyCallback!!)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        serviceScope.launch {
            while (isActive) {
                delay(intervalMs)
                saveCurrentStatsToDb()
            }
        }
    }

    private fun stopLogging() {
        stopForeground(true)
        stopSelf()
    }

    private suspend fun saveCurrentStatsToDb() {
        currentAppState.simStats?.let { sim ->
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val currentTime = sdf.format(Date())

            val log = NetworkLog(
                timestamp = currentTime,
                deviceId = currentAppState.deviceId,
                deviceMake = currentAppState.deviceMake,
                deviceModel = currentAppState.deviceModel,
                carrierName = sim.carrierName,
                networkType = sim.networkType,
                rsrp = sim.rsrp,
                rsrq = sim.rsrq,
                sinr = sim.sinr,
                pci = sim.pci,
                downlinkSpeed = sim.downlinkSpeed,
                uplinkSpeed = sim.uplinkSpeed,
                velocity = currentAppState.velocity,
                latitude = currentAppState.latitude,
                longitude = currentAppState.longitude
            )
            networkLogDao.insert(log)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun parseCellInfo() {
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            currentAppState = currentAppState.copy(simStats = SimStats(networkType = "No Data SIM"))
            return
        }
        val defaultDataSubInfo = subscriptionManager.getActiveSubscriptionInfo(defaultDataSubId) ?: return

        val simTelephonyManager = telephonyManager.createForSubscriptionId(defaultDataSubId)
        val allCellInfoForSim = simTelephonyManager.allCellInfo ?: emptyList()
        val registeredCell = allCellInfoForSim.firstOrNull { it.isRegistered }

        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        val downSpeed = caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"
        val upSpeed = caps?.linkUpstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "N/A"

        val newSimStats = if (registeredCell == null) {
            SimStats(carrierName = defaultDataSubInfo.displayName.toString(), networkType = "Not Registered")
        } else {
            when (registeredCell) {
                is CellInfoLte -> {
                    val id = registeredCell.cellIdentity
                    val str = registeredCell.cellSignalStrength
                    SimStats(
                        carrierName = defaultDataSubInfo.displayName.toString(), networkType = "LTE (4G)",
                        pci = id.pci.toString(), rsrp = "${str.rsrp} dBm",
                        rsrq = "${str.rsrq} dB", sinr = "${str.rssnr} dB",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoNr -> {
                    val id = registeredCell.cellIdentity as CellIdentityNr
                    val str = registeredCell.cellSignalStrength as CellSignalStrengthNr
                    SimStats(
                        carrierName = defaultDataSubInfo.displayName.toString(), networkType = "5G NR",
                        pci = id.pci.toString(), rsrp = "${str.ssRsrp} dBm",
                        rsrq = "${str.ssRsrq} dB", sinr = "${str.ssSinr} dB",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoWcdma -> {
                    val str = registeredCell.cellSignalStrength
                    SimStats(
                        carrierName = defaultDataSubInfo.displayName.toString(), networkType = "WCDMA (3G)",
                        pci = "N/A", rsrp = "${str.dbm} dBm", rsrq = "N/A", sinr = "N/A",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                is CellInfoGsm -> {
                    val str = registeredCell.cellSignalStrength
                    SimStats(
                        carrierName = defaultDataSubInfo.displayName.toString(), networkType = "GSM (2G)",
                        pci = "N/A", rsrp = "${str.dbm} dBm", rsrq = "N/A", sinr = "N/A",
                        downlinkSpeed = downSpeed, uplinkSpeed = upSpeed
                    )
                }
                else -> SimStats(carrierName = defaultDataSubInfo.displayName.toString(), networkType = "Other")
            }
        }
        currentAppState = currentAppState.copy(simStats = newSimStats)
    }

    private fun updateLocation(location: Location) {
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toFloat() else 0.0f
        currentAppState = currentAppState.copy(
            latitude = String.format("%.6f", location.latitude),
            longitude = String.format("%.6f", location.longitude),
            velocity = "${String.format("%.2f", speedKmh)} km/h"
        )
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun createNotification(contentText: String): Notification {
        val icon = android.R.drawable.ic_dialog_info
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Network Logger Active")
            .setContentText(contentText)
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Network Logger Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}