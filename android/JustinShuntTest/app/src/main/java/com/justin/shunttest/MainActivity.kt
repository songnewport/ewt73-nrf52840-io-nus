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
    GATT_INIT,
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
private val SECURE_INFO_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef4")

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
    private var activeCallbackToken = 0
    private var lastStatusFields: Map<String, String> = emptyMap()
    private var ledWriteInProgress = false
    private var secureReady = false

    private val foundDevices = linkedMapOf<String, ScanResult>()

    private lateinit var root: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var connectionView: TextView
    private lateinit var liveView: TextView
    private lateinit var secureView: TextView
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
        connectionView.text = "First use: hold PAIR until blinking, then scan and tap the shunt."
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        stateView = bodyText()
        connectionView = bodyText()
        liveView = monoPanel("Live data waiting...")
        secureView = monoPanel("Secure info waiting...")
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
        root.addView(sectionLabel("Secure Info"))
        root.addView(secureView)
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

        if (state == AppState.CONNECTING || state == AppState.DISCOVERING ||
            state == AppState.GATT_INIT || state == AppState.CONNECTED) {
            addLog("Scan ignored while $state")
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
                connectionView.text = "No shunt found. Move closer, hold PAIR until blinking, then scan again."
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
        if (state == AppState.CONNECTING || state == AppState.DISCOVERING ||
            state == AppState.GATT_INIT || state == AppState.CONNECTED) {
            addLog("Connect ignored while $state")
            return
        }

        stopScan()
        closeManager()
        selectedDevice = device
        secureReady = false
        setState(AppState.CONNECTING)
        connectionView.text = "Connecting ${device.address} / bond=${bondStateName(device.bondState)}"

        val thisConnectSession = ++connectSession
        activeCallbackToken = thisConnectSession
        val manager = JustinBleManager(this, this, thisConnectSession).also { bleManager = it }
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
                    rememberDevice(device)
                    addLog("BLE manager initialized")
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
            .retry(1, 300)
            .timeout(15000)
            .fail { _, status ->
                if (connectSession == thisConnectSession) {
                    showError("Connect/init failed: $status")
                }
            }
            .enqueue()
    }

    private fun toggleLed() {
        val manager = bleManager ?: return
        val bondedCount = lastStatusFields["BONDED_COUNT"]?.toIntOrNull() ?: 0
        val isBonded = lastStatusFields["IS_BONDED"]?.toIntOrNull() ?: 0
        val securityLevel = lastStatusFields["SEC_LEVEL"]?.toIntOrNull() ?: 0

        if (ledWriteInProgress) {
            addLog("LED write already in progress")
            return
        }

        if (bondedCount == 0 && isBonded == 0 && securityLevel < 2) {
            addLog("Secure read not ready. Hold PAIR until blinking, reconnect, and accept pairing.")
            connectionView.text = "Hold PAIR until blinking, reconnect, and accept pairing."
            manager.readStatus()
            return
        }

        val next = !ledOn
        ledWriteInProgress = true
        ledButton.isEnabled = false
        addLog("LED write requested: ${if (next) "01" else "00"}")
        manager.writeLed(next)
    }

    override fun onLiveData(token: Int, text: String) {
        if (!isActiveToken(token)) return
        showLiveData(text)
    }

    override fun onSecureInfo(token: Int, text: String) {
        if (!isActiveToken(token)) return
        secureReady = true
        showSecureInfo(text)
        setState(AppState.CONNECTED)
    }

    override fun onStatus(token: Int, text: String) {
        if (!isActiveToken(token)) return
        showStatus(text)
    }

    override fun onLedWriteDone(token: Int, on: Boolean) {
        if (!isActiveToken(token)) return
        ledWriteInProgress = false
        ledButton.isEnabled = state == AppState.CONNECTED
        selectedDevice?.let { rememberDevice(it) }
        ledOn = on
        renderLedButton()
        addLog("LED write OK: ${if (on) "01" else "00"}")
        bleManager?.readStatus()
    }

    override fun onGattInit(token: Int) {
        if (!isActiveToken(token)) return
        setState(AppState.GATT_INIT)
    }

    override fun onBleLog(token: Int, message: String) {
        if (!isActiveToken(token)) return
        addLog(message)
    }

    override fun onBleError(token: Int, message: String) {
        if (!isActiveToken(token)) return
        ledWriteInProgress = false
        ledButton.isEnabled = state == AppState.CONNECTED
        if (message.contains("137")) {
            showPairRequired("Secure read failed: 137. Hold PAIR until blinking, reconnect, and accept pairing.")
            return
        }
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

    private fun showSecureInfo(text: String) {
        val fields = parseFields(text)
        mainHandler.post {
            connectionView.text = "Secure connection ready"
            secureView.text = listOf(
                "Serial       ${fields["SERIAL"] ?: "--"}",
                "Firmware     ${fields["FW"] ?: "--"}",
                "Provisioned  ${fields["PROVISIONED"] ?: "--"}",
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
            val busy = newState == AppState.CONNECTING ||
                newState == AppState.DISCOVERING ||
                newState == AppState.GATT_INIT ||
                newState == AppState.CONNECTED
            val connected = newState == AppState.CONNECTED
            scanButton.isEnabled = !busy
            ledButton.isEnabled = connected && secureReady && !ledWriteInProgress
            readStatusButton.isEnabled = connected && secureReady
        }
    }

    private fun showError(message: String) {
        setState(AppState.ERROR)
        addLog(message)
        mainHandler.post {
            connectionView.text = message
        }
    }

    private fun showPairRequired(message: String) {
        setState(AppState.WAIT_PAIR_BUTTON)
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
        activeCallbackToken = ++connectSession
        val manager = bleManager
        bleManager = null
        ledWriteInProgress = false
        secureReady = false
        manager?.disconnect()?.then { manager.close() }?.enqueue()
        mainHandler.post {
            ledButton.isEnabled = false
            readStatusButton.isEnabled = false
        }
    }

    private fun isActiveToken(token: Int): Boolean {
        return token == activeCallbackToken
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
        bleManager = null
        ledWriteInProgress = false
        secureReady = false
        prefs.edit().clear().apply()
        selectedDevice = null
        connectSession++
        if (manager != null) {
            manager.removeBondAndDisconnect()
        } else {
            closeManager()
        }
        setState(AppState.WAIT_PAIR_BUTTON)
        connectionView.text = "Saved device cleared. Hold PAIR until blinking, then scan."
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
    fun onLiveData(token: Int, text: String)
    fun onSecureInfo(token: Int, text: String)
    fun onStatus(token: Int, text: String)
    fun onLedWriteDone(token: Int, on: Boolean)
    fun onGattInit(token: Int)
    fun onBleLog(token: Int, message: String)
    fun onBleError(token: Int, message: String)
}

private class JustinBleManager(
    context: Context,
    private val appCallbacks: JustinBleCallbacks,
    private val callbackToken: Int
) : BleManager(context) {
    private var liveCharacteristic: BluetoothGattCharacteristic? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var secureInfoCharacteristic: BluetoothGattCharacteristic? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(JUSTIN_SERVICE_UUID) ?: return false
        liveCharacteristic = service.getCharacteristic(LIVE_DATA_UUID)
        ledCharacteristic = service.getCharacteristic(LED_CONTROL_UUID)
        statusCharacteristic = service.getCharacteristic(STATUS_UUID)
        secureInfoCharacteristic = service.getCharacteristic(SECURE_INFO_UUID)
        return liveCharacteristic != null && ledCharacteristic != null &&
            statusCharacteristic != null && secureInfoCharacteristic != null
    }

    override fun initialize() {
        setNotificationCallback(liveCharacteristic)
            .with { _, data -> appCallbacks.onLiveData(callbackToken, data.toUtf8()) }
        setNotificationCallback(statusCharacteristic)
            .with { _, data -> appCallbacks.onStatus(callbackToken, data.toUtf8()) }

        beginAtomicRequestQueue()
            .add(
                requestMtu(247)
                    .with { _, mtu -> appCallbacks.onBleLog(callbackToken, "MTU changed: $mtu") }
                    .fail { _, status -> appCallbacks.onBleLog(callbackToken, "MTU request failed: $status") }
            )
            .add(
                readCharacteristic(statusCharacteristic)
                    .with { _, data -> appCallbacks.onStatus(callbackToken, data.toUtf8()) }
                    .fail { _, status -> appCallbacks.onBleLog(callbackToken, "Initial status read failed: $status") }
            )
            .done {
                appCallbacks.onBleLog(callbackToken, "Public init ready")
                readSecureInfoOnce()
            }
            .fail { _, status -> appCallbacks.onBleError(callbackToken, "GATT init failed: $status") }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        liveCharacteristic = null
        ledCharacteristic = null
        statusCharacteristic = null
        secureInfoCharacteristic = null
    }

    fun readLiveData() {
        readCharacteristic(liveCharacteristic)
            .with { _, data -> appCallbacks.onLiveData(callbackToken, data.toUtf8()) }
            .fail { _, status -> appCallbacks.onBleLog(callbackToken, "Live read failed: $status") }
            .enqueue()
    }

    fun readStatus() {
        readCharacteristic(statusCharacteristic)
            .with { _, data -> appCallbacks.onStatus(callbackToken, data.toUtf8()) }
            .fail { _, status -> appCallbacks.onBleLog(callbackToken, "Status read failed: $status") }
            .enqueue()
    }

    private fun readSecureInfoOnce() {
        readCharacteristic(secureInfoCharacteristic)
            .before { appCallbacks.onGattInit(callbackToken) }
            .with { _, data ->
                appCallbacks.onBleLog(callbackToken, "Secure read OK")
                appCallbacks.onSecureInfo(callbackToken, data.toUtf8())
            }
            .done { enableAppNotifications() }
            .fail { _, status -> appCallbacks.onBleError(callbackToken, "Secure read failed: $status") }
            .enqueue()
    }

    private fun enableAppNotifications() {
        beginAtomicRequestQueue()
            .add(
                enableNotifications(liveCharacteristic)
                    .done { appCallbacks.onBleLog(callbackToken, "Live data notifications enabled") }
            )
            .add(
                enableNotifications(statusCharacteristic)
                    .done { appCallbacks.onBleLog(callbackToken, "Device status notifications enabled") }
            )
            .done {
                appCallbacks.onBleLog(callbackToken, "Device ready")
                readStatus()
                readLiveData()
            }
            .fail { _, status -> appCallbacks.onBleError(callbackToken, "Notify init failed: $status") }
            .enqueue()
    }

    fun removeBondAndDisconnect() {
        removeBond()
            .done { appCallbacks.onBleLog(callbackToken, "Android bond removed") }
            .fail { _, status -> appCallbacks.onBleLog(callbackToken, "Remove bond failed: $status") }
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
            .done { appCallbacks.onLedWriteDone(callbackToken, on) }
            .fail { _, status -> appCallbacks.onBleError(callbackToken, "LED write failed: $status") }
            .enqueue()
    }
}

private fun no.nordicsemi.android.ble.data.Data.toUtf8(): String {
    return value?.decodeToString().orEmpty()
}
