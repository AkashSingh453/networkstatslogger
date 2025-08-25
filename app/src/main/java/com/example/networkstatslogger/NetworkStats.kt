package com.example.networkstatslogger

data class SimStats(
    val carrierName: String = "N/A",
    val rsrp: String = "N/A",
    val rsrq: String = "N/A",
    val sinr: String = "N/A",
    val pci: String = "N/A",
    val networkType: String = "Unknown",
    val downlinkSpeed: String = "N/A",
    val uplinkSpeed: String = "N/A"
)

data class AppState(
    val simStats: SimStats? = null,
    val latitude: String = "N/A",
    val longitude: String = "N/A",
    val velocity: String = "N/A",
    val deviceId: String = "N/A",
    val deviceMake: String = "N/A", // NEW
    val deviceModel: String = "N/A" // NEW
)