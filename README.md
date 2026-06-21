# 📡 Network Stats Logger

## Description
The Network Stats Logger is a background-first Android application designed to continuously monitor and log deep cellular network statistics and GPS coordinates. Built to ensure high uptime, it captures critical telecommunication metrics alongside location data, allowing users to export comprehensive CSV logs for offline network analysis, coverage mapping, and troubleshooting.

## ✨ Key Features
* **Deep Telecom Metrics:** Logs granular network details including Provider, Network Type (LTE, 5G), RSRP, RSRQ, SINR, and PCI.
* **Location & Velocity Tracking:** Simultaneously records GPS coordinates (Latitude/Longitude) and real-time movement speed (km/h).
* **Persistent Background Logging:** Designed to run reliably in the background, ensuring data is captured continuously without killing the battery.
* **Customizable Intervals:** Granular control over data collection with adjustable logging intervals (in milliseconds).
* **CSV Export:** One-tap export to generate clean, highly detailed CSV files for use in external data analysis tools.

## 🛠️ Tech Stack
* **UI Toolkit:** Jetpack Compose
* **Language:** Kotlin
* **Local Storage:** Room Database
* **Core APIs:** Android SDK, TelephonyManager, Fused Location Provider

---

## 📱 App Interface & Data Export

<div align="center">
  <h3>Home Dashboard</h3>
  <img src="https://github.com/user-attachments/assets/7f8a3ee3-63fd-4fda-b874-a90eed18e97e" width="300" alt="Home Screen"/>
  <br/><br/>
  <h3>CSV Export Format</h3>
  <img src="https://github.com/user-attachments/assets/e35798da-8146-4dff-99c1-701b7f82dcb6" width="100%" alt="CSV Export"/>
</div>

<br/>

<div align="center">
  <h3>Video Demo</h3>
  <video src="https://github.com/user-attachments/assets/209c5cc4-14d5-495e-ad5c-940706e91f62" controls="controls" style="max-width: 100%;"></video>
</div>

---

## 🚀 Installation

1. Clone the repository:
   ```bash
   git clone [https://github.com/AkashSingh453/networkstatslogger.git](https://github.com/AkashSingh453/networkstatslogger.git)
