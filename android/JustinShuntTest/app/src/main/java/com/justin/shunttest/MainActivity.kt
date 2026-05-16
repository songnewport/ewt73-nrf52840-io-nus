package com.justin.shunttest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class AppState {
    IDLE,
    REQUEST_PERMISSIONS,
    WAIT_PAIR_BUTTON,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

private val JUSTIN_SERVICE_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
private val LIVE_DATA_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
private val LED_CONTROL_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
private val STATUS_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef3")

class MainActivity : Activity(), JustinBleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var state = AppState.IDLE
    private var selectedDevice: BluetoothDevice? = null
    private var bleManager: JustinBleManager? = null
    private var ledOn = false
    private var isScanning = false
    private var scanSession = 0
    private var connectSession = 0
    private var lastStatusFields: Map<String, String> = emptyMap()
    private var ledWriteInProgress = false

    private val foundDevices = linkedMapOf<String, ScanResult>()

    private lateinit var root: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var connectionView: TextView
    private lateinit var liveView: TextView
    private lateinit var statusView: TextView
    private lateinit var devicesView: LinearLayout
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var ledButton: Button
    private lateinit var readStatusButton: Button
    private lateinit var forgetButton: Button
    private lateinit var clearLogButton: Button

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            val matchesName = name.startsWith("Justin_Shunt")
            val matchesService = result.scanRecord?.serviceUuids?.any { it.uuid == JUSTIN_SERVICE_UUID } == true

            if (!matchesName && !matchesService) {
                return
            }

            foundDevices[device.address] = result
            renderDevices()
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            showError(if (errorCode == 1) "Scan already started" else "Scan failed: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("justin_shunt_ble", Context.MODE_PRIVATE)
        bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        buildUi()

        if (!hasPermissions()) {
            setState(AppState.REQUEST_PERMISSIONS)
            requestPermissions(requiredPermissions, 100)
        } else {
            startFlow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        closeManager()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && hasPermissions()) {
            startFlow()
        } else {
            showError("Bluetooth permission is required")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFlow() {
        val savedAddress = prefs.getString("device_address", null)
        if (savedAddress != null) {
            val device = bluetoothAdapter.getRemoteDevice(savedAddress)
            selectedDevice = device
            connectionView.text = "Saved device: $savedAddress"
            addLog("Connecting saved device")
            connectDevice(device)
            return
        }

        setState(AppState.WAIT_PAIR_BUTTON)
        connectionView.text = "Scan to connect. Hold PAIR only before LED control."
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        stateView = bodyText()
        connectionView = bodyText()
        liveView = monoPanel("Live data waiting...")
        statusView = monoPanel("Status waiting...")
        devicesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = monoPanel("")

        scanButton = Button(this).apply {
            text = "Scan / Reconnect"
            setOnClickListener { startScan() }
        }
        ledButton = Button(this).apply {
            text = "P19 LED ON"
            isEnabled = false
            setOnClickListener { toggleLed() }
        }
        readStatusButton = Button(this).apply {
            text = "Read Status"
            isEnabled = false
            setOnClickListener { bleManager?.readStatus() }
        }
        forgetButton = Button(this).apply {
            text = "Forget Saved Device"
            setOnClickListener { forgetSavedDevice() }
        }
        clearLogButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener { clearLog() }
        }

        root.addView(titleText("Justin Shunt Test", 25f))
        root.addView(stateView)
        root.addView(connectionView)
        root.addView(buttonRow(scanButton, ledButton))
        root.addView(buttonRow(readStatusButton, forgetButton))
        root.addView(clearLogButton)
        root.addView(sectionLabel("Live Data"))
        root.addView(liveView)
        root.addView(sectionLabel("Device Status"))
        root.addView(statusView)
        root.addView(sectionLabel("Found Devices"))
        root.addView(devicesView)
        root.addView(sectionLabel("Log"))
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun titleText(textValue: String, size: Float): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 22, 0, 6)
        }
    }

    private fun bodyText(): TextView {
        return TextView(this).apply {
            textSize = 15f
            setPadding(0, 4, 0, 4)
        }
    }

    private fun monoPanel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setPadding(18, 14, 18, 14)
        }
    }

    private fun buttonRow(left: Button, right: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions, 100)
            return
        }

        closeManager()
        connectSession++
        stopScan()
        val thisScanSession = ++scanSession
        foundDevices.clear()
        devicesView.removeAllViews()
        setState(AppState.SCANNING)
        connectionView.text = "Scanning for Justin_Shunt_Test..."
        addLog("Scan started")
        isScanning = true
        bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)

        mainHandler.postDelayed({
            if (scanSession == thisScanSession && state == AppState.SCANNING && foundDevices.isEmpty()) {
                stopScan()
                connectionView.text = "No device found. Hold PAIR 5s and scan again."
                setState(AppState.WAIT_PAIR_BUTTON)
            }
        }, 12000)
    }

    @SuppressLint("MissingPermission")
    private fun renderDevices() {
        mainHandler.post {
            devicesView.removeAllViews()
            foundDevices.values.forEach { result ->
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                devicesView.addView(Button(this).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = "$name\n${device.address}  RSSI ${result.rssi}"
                    setOnClickListener {
                        stopScan()
                        selectedDevice = device
                        connectDevice(device)
                    }
                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        stopScan()
        closeManager()
        selectedDevice = device
        setState(AppState.CONNECTING)
        connectionView.text = "Connecting ${device.address} / bond=${bondStateName(device.bondState)}"

        val thisConnectSession = ++connectSession
        val manager = JustinBleManager(this, this).also { bleManager = it }
        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                if (connectSession == thisConnectSession) {
                    setState(AppState.CONNECTING)
                    addLog("Connecting")
                }
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                if (connectSession == thisConnectSession) {
                    setState(AppState.DISCOVERING)
                    addLog("Connected, Nordic manager discovering services")
                }
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                if (connectSession == thisConnectSession) {
                    showError("Connect failed: $reason")
                }
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                if (connectSession == thisConnectSession) {
                    setState(AppState.CONNECTED)
                    connectionView.text = "Connected ${device.address}"
                    addLog("Device ready")
                    manager.readLiveData()
                    manager.readStatus()
                }
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                addLog("Disconnecting")
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                if (connectSession == thisConnectSession) {
                    setState(AppState.DISCONNECTED)
                    addLog("Disconnected: $reason")
                }
            }
        })

        manager.connect(device)
            .retry(3, 300)
            .timeout(15000)
            .fail { _, status -> showError("Connect/init failed: $status") }
            .enqueue()
    }

    private fun toggleLed() {
        val manager = bleManager ?: return
        val bondedCount = lastStatusFields["BONDED_COUNT"]?.toIntOrNull() ?: 0
        val isBonded = lastStatusFields["IS_BONDED"]?.toIntOrNull() ?: 0
        val pairMode = lastStatusFields["PAIR_MODE"]?.toIntOrNull() ?: 0
        val securityLevel = lastStatusFields["SEC_LEVEL"]?.toIntOrNull() ?: 0

        if (ledWriteInProgress) {
            addLog("LED write already in progress")
            return
        }

        if (bondedCount == 0 && isBonded == 0 && pairMode == 0) {
            addLog("Device is not bondable now. Hold PAIR 5s, wait PAIR_MODE=1, then press LED.")
            connectionView.text = "Hold PAIR 5s first. Wait PAIR_MODE=1, then press LED."
            manager.readStatus()
            return
        }

        val next = !ledOn
        ledWriteInProgress = true
        ledButton.isEnabled = false
        addLog("LED write requested: ${if (next) "01" else "00"}")
        manager.writeLed(next)
    }

    override fun onLiveData(text: String) {
        showLiveData(text)
    }

    override fun onStatus(text: String) {
        showStatus(text)
    }

    override fun onLedWriteDone(on: Boolean) {
        ledWriteInProgress = false
        ledButton.isEnabled = state == AppState.CONNECTED
        selectedDevice?.let { rememberDevice(it) }
        ledOn = on
        renderLedButton()
        addLog("LED write OK: ${if (on) "01" else "00"}")
        bleManager?.readStatus()
    }

    override fun onBleLog(message: String) {
        addLog(message)
    }

    override fun onBleError(message: String) {
        ledWriteInProgress = false
        ledButton.isEnabled = state == AppState.CONNECTED
        showError(message)
    }

    private fun showLiveData(text: String) {
        val fields = parseFields(text)
        mainHandler.post {
            liveView.text = listOf(
                "Voltage      ${fields["V"] ?: "--"} V",
                "Current      ${fields["I"] ?: "--"} mA",
                "Temperature  ${fields["T"] ?: "--"} C",
                "SOC          ${fields["SOC"] ?: "--"} %",
                "",
                "Raw: $text"
            ).joinToString("\n")
        }
    }

    private fun showStatus(text: String) {
        val fields = parseFields(text)
        lastStatusFields = fields
        val ledValue = fields["LED"]
        if (ledValue == "0" || ledValue == "1") {
            ledOn = ledValue == "1"
            renderLedButton()
        }

        mainHandler.post {
            statusView.text = listOf(
                "Pair mode    ${fields["PAIR_MODE"] ?: "--"}",
                "Bonded       ${fields["BONDED_COUNT"] ?: "--"}",
                "P19 LED      ${fields["LED"] ?: "--"}",
                "Firmware     ${fields["FW"] ?: "--"}",
                "",
                "Raw: $text"
            ).joinToString("\n")
        }
    }

    private fun parseFields(text: String): Map<String, String> {
        return text.split(",").mapNotNull { token ->
            val parts = token.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
    }

    private fun renderLedButton() {
        mainHandler.post {
            ledButton.text = if (ledOn) "P19 LED OFF" else "P19 LED ON"
        }
    }

    private fun setState(newState: AppState) {
        state = newState
        mainHandler.post {
            stateView.text = "State: $newState"
            val connected = newState == AppState.CONNECTED
            ledButton.isEnabled = connected && !ledWriteInProgress
            readStatusButton.isEnabled = connected
        }
    }

    private fun showError(message: String) {
        setState(AppState.ERROR)
        addLog(message)
        mainHandler.post {
            connectionView.text = message
        }
    }

    private fun addLog(message: String) {
        mainHandler.post {
            logView.append("${timeFormat.format(Date())}  $message\n")
        }
    }

    private fun clearLog() {
        mainHandler.post {
            logView.text = ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (hasPermissions() && isScanning) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
        isScanning = false
    }

    private fun closeManager() {
        val manager = bleManager
        bleManager = null
        ledWriteInProgress = false
        manager?.disconnect()?.then { manager.close() }?.enqueue()
        mainHandler.post {
            ledButton.isEnabled = false
            readStatusButton.isEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun rememberDevice(device: BluetoothDevice) {
        prefs.edit()
            .putString("device_address", device.address)
            .putString("device_name", device.name ?: "Justin_Shunt_Test")
            .apply()
    }

    private fun forgetSavedDevice() {
        val manager = bleManager
        prefs.edit().clear().apply()
        selectedDevice = null
        connectSession++
        if (manager != null) {
            manager.removeBondAndDisconnect()
        } else {
            closeManager()
        }
        setState(AppState.WAIT_PAIR_BUTTON)
        connectionView.text = "Saved device cleared. Hold PAIR 5s, then scan."
        addLog("Saved device cleared")
    }

    private fun bondStateName(state: Int): String {
        return when (state) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "unknown"
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

private interface JustinBleCallbacks {
    fun onLiveData(text: String)
    fun onStatus(text: String)
    fun onLedWriteDone(on: Boolean)
    fun onBleLog(message: String)
    fun onBleError(message: String)
}

private class JustinBleManager(
    context: Context,
    private val appCallbacks: JustinBleCallbacks
) : BleManager(context) {
    private var liveCharacteristic: BluetoothGattCharacteristic? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(JUSTIN_SERVICE_UUID) ?: return false
        liveCharacteristic = service.getCharacteristic(LIVE_DATA_UUID)
        ledCharacteristic = service.getCharacteristic(LED_CONTROL_UUID)
        statusCharacteristic = service.getCharacteristic(STATUS_UUID)
        return liveCharacteristic != null && ledCharacteristic != null && statusCharacteristic != null
    }

    override fun initialize() {
        setNotificationCallback(liveCharacteristic)
            .with { _, data -> appCallbacks.onLiveData(data.toUtf8()) }
        setNotificationCallback(statusCharacteristic)
            .with { _, data -> appCallbacks.onStatus(data.toUtf8()) }

        beginAtomicRequestQueue()
            .add(
                requestMtu(247)
                    .with { _, mtu -> appCallbacks.onBleLog("MTU changed: $mtu") }
                    .fail { _, status -> appCallbacks.onBleLog("MTU request failed: $status") }
            )
            .add(
                enableNotifications(liveCharacteristic)
                    .done { appCallbacks.onBleLog("Live data notifications enabled") }
            )
            .add(
                enableNotifications(statusCharacteristic)
                    .done { appCallbacks.onBleLog("Device status notifications enabled") }
            )
            .done {
                appCallbacks.onBleLog("Device ready")
                readStatus()
                readLiveData()
            }
            .fail { _, status -> appCallbacks.onBleError("GATT init failed: $status") }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        liveCharacteristic = null
        ledCharacteristic = null
        statusCharacteristic = null
    }

    fun readLiveData() {
        readCharacteristic(liveCharacteristic)
            .with { _, data -> appCallbacks.onLiveData(data.toUtf8()) }
            .fail { _, status -> appCallbacks.onBleLog("Live read failed: $status") }
            .enqueue()
    }

    fun readStatus() {
        readCharacteristic(statusCharacteristic)
            .with { _, data -> appCallbacks.onStatus(data.toUtf8()) }
            .fail { _, status -> appCallbacks.onBleLog("Status read failed: $status") }
            .enqueue()
    }

    fun removeBondAndDisconnect() {
        removeBond()
            .done { appCallbacks.onBleLog("Android bond removed") }
            .fail { _, status -> appCallbacks.onBleLog("Remove bond failed: $status") }
            .then {
                disconnect().then { close() }.enqueue()
            }
            .enqueue()
    }

    fun writeLed(on: Boolean) {
        writeCharacteristic(
            ledCharacteristic,
            byteArrayOf(if (on) 0x01 else 0x00),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
            .done { appCallbacks.onLedWriteDone(on) }
            .fail { _, status -> appCallbacks.onBleError("LED write failed: $status") }
            .enqueue()
    }
}

private fun no.nordicsemi.android.ble.data.Data.toUtf8(): String {
    return value?.decodeToString().orEmpty()
}
