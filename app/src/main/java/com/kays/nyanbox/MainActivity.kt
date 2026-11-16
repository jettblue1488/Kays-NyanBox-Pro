package com.kays.nyanbox

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var output: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private var callback: ScanCallback? = null
    private val log by lazy { getSharedPreferences("scan_history", MODE_PRIVATE) }
    private fun permitted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun line(s: String) { output.append("\n$s"); log.edit().putString("history", (log.getString("history", "") ?: "") + "\n" + s).apply() }
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 30, 28, 20); setBackgroundColor(Color.rgb(15, 15, 18)) }
        root.addView(TextView(this).apply { text = "KAY'S NYANBOX PRO"; textSize = 23f; setTextColor(Color.RED) })
        root.addView(TextView(this).apply { text = "PHONE MODE • HARDWARE MODE (PLANNED)"; setTextColor(Color.LTGRAY); textSize = 12f })
        fun button(label: String, click: () -> Unit) {
            root.addView(Button(this).apply { text = label; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(130, 12, 24))); setOnClickListener { click() } })
        }
        button("Grant scan permissions") {
            val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT) }
            requestPermissions(p.toTypedArray(), 42)
        }
        button("Scan Bluetooth LE") { scanBle() }
        button("Scan Wi-Fi") { scanWifi() }
        button("Show saved history") { output.text = log.getString("history", "No scans saved yet") }
        button("Clear history") { log.edit().remove("history").apply(); output.text = "History cleared" }
        button("Hardware connection info") { output.text = "Hardware mode requires compatible ESP32 firmware and a defined communication protocol. NRF24L01 and CC1101 cannot be accessed directly by the phone." }
        output = TextView(this).apply { text = "Ready. Grant permissions and turn on Location for Wi-Fi scans."; setTextColor(Color.WHITE); textSize = 14f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun scanBle() {
        if (Build.VERSION.SDK_INT >= 31 && (!permitted(Manifest.permission.BLUETOOTH_SCAN) || !permitted(Manifest.permission.BLUETOOTH_CONNECT))) { output.text = "Bluetooth permissions required"; return }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run { output.text = "Bluetooth unavailable"; return }
        if (!adapter.isEnabled) { output.text = "Turn Bluetooth on first"; return }
        val scanner = adapter.bluetoothLeScanner ?: run { output.text = "BLE scanner unavailable"; return }
        callback?.let { scanner.stopScan(it) }
        seen.clear(); output.text = "BLE scan started at ${stamp()} (10 seconds)"
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val id = result.device.address
                if (seen.add(id)) line("${stamp()}  #${seen.size}  ${result.scanRecord?.deviceName ?: "Unnamed BLE device"}  $id  ${result.rssi} dBm")
            }
            override fun onScanFailed(errorCode: Int) { output.text = "BLE scan error: $errorCode" }
        }
        callback = cb
        try { scanner.startScan(cb); handler.postDelayed({ scanner.stopScan(cb); if (callback === cb) callback = null }, 10000) }
        catch (e: SecurityException) { output.text = "Bluetooth permission denied" }
    }
    @Suppress("DEPRECATION")
    private fun scanWifi() {
        if (!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) { output.text = "Location permission required for Wi-Fi results"; return }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        output.text = "Wi-Fi results at ${stamp()} (Android may throttle scans):"
        try {
            wifi.startScan()
            val results = wifi.scanResults
            if (results.isEmpty()) output.append("\nNo results. Enable Wi-Fi and device Location, then try again.")
            results.forEachIndexed { i, r -> line("${stamp()}  #${i+1}  ${r.SSID.ifEmpty { "Hidden SSID" }}  ${r.BSSID}  ${r.level} dBm") }
        } catch (e: SecurityException) { output.text = "Wi-Fi permission denied" }
    }
    override fun onDestroy() {
        callback?.let { cb -> try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
