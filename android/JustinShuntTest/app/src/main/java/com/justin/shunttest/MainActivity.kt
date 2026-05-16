package com.justin.shunttest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class AppState {
    IDLE,
    REQUEST_PERMISSIONS,
    WAIT_PAIR_BUTTON,
    SCANNING,
    BONDING,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

class MainActivity : Activity() {
    private val serviceUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val liveDataUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    private val ledControlUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    private val statusUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
    private val cccUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var state = AppState.IDLE
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var liveCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var ledOn = false
    private var isScanning = false
    private var scanSession = 0
    private var connectSession = 0
    private var gattRetryCount = 0
    private var liveNotifyCount = 0
    private var liveReadFallbackActive = false
    private var readInProgress = false
    private var descriptorWriteInProgress = false
    private var bondInProgress = false

    private val foundDevices = linkedMapOf<String, ScanResult>()
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
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
            val matchesService = result.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true

            if (!matchesName && !matchesService) {
                return
            }

            foundDevices[device.address] = result
            renderDevices()
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                addLog("Scan already started")
                setState(AppState.SCANNING)
            } else {
                showError("Scan failed: $errorCode")
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                return
            }

            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            if (device.address != selectedDevice?.address) {
                return
            }

            when (device.bondState) {
                BluetoothDevice.BOND_BONDING -> {
                    bondInProgress = true
                    setState(AppState.BONDING)
                    addLog("Pairing requested by Android")
                }

                BluetoothDevice.BOND_BONDED -> {
                    bondInProgress = false
                    rememberDevice(device)
                    setState(AppState.CONNECTING)
                    addLog("Bonded: ${device.address}")
                    connectGattDelayed(device, 1000)
                }

                BluetoothDevice.BOND_NONE -> {
                    bondInProgress = false
                    showError("Pairing failed or cancelled. Hold PAIR 5s and try again.")
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("GATT error: $status")
                val device = selectedDevice
                closeGatt()
                setState(AppState.DISCONNECTED)
                if (status == 133 && device != null && gattRetryCount == 0) {
                    gattRetryCount++
                    addLog("Retrying GATT once after 133")
                    connectGattDelayed(device, 1200)
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattRetryCount = 0
                    setState(AppState.DISCOVERING)
                    addLog("Connected, discovering services")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("Disconnected")
                    closeGatt()
                    setState(AppState.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                showError("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                showError("Custom Justin service not found")
                return
            }

            liveCharacteristic = service.getCharacteristic(liveDataUuid)
            statusCharacteristic = service.getCharacteristic(statusUuid)
            ledCharacteristic = service.getCharacteristic(ledControlUuid)

            if (liveCharacteristic == null || statusCharacteristic == null || ledCharacteristic == null) {
                showError("Required characteristic missing")
                return
            }

            setState(AppState.SUBSCRIBING)
            addLog("Subscribing live data and status")
            enqueueNotification(liveCharacteristic)
            enqueueNotification(statusCharacteristic)
            processNextNotification(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteInProgress = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                showError("Notification subscribe failed: $status")
                return
            }

            val name = when (descriptor.characteristic?.uuid) {
                liveDataUuid -> "Live data"
                statusUuid -> "Device status"
                else -> "Characteristic"
            }
            addLog("$name notifications enabled")

            if (notifyQueue.isEmpty()) {
                setState(AppState.CONNECTED)
                readLiveData()
                mainHandler.postDelayed({ readStatus() }, 300)
                startLiveReadFallback()
            } else {
                processNextNotification(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == liveDataUuid) {
                liveNotifyCount++
            }
            handleCharacteristicText(characteristic.uuid, value.decodeToString())
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == liveDataUuid) {
                liveNotifyCount++
            }
            @Suppress("DEPRECATION")
            handleCharacteristicText(characteristic.uuid, characteristic.value?.decodeToString().orEmpty())
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicText(characteristic.uuid, value.decodeToString())
            } else {
                readInProgress = false
                addLog("Read failed: $status")
            }
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                handleCharacteristicText(characteristic.uuid, characteristic.value?.decodeToString().orEmpty())
            } else {
                readInProgress = false
                addLog("Read failed: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != ledControlUuid) {
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("LED write OK: ${if (ledOn) "01" else "00"}")
                readStatus()
            } else {
                addLog("LED write failed: $status")
                ledOn = !ledOn
                renderLedButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("justin_shunt_ble", Context.MODE_PRIVATE)
        bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
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
        unregisterReceiver(bondReceiver)
        stopScan()
        closeGatt()
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
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                addLog("Connecting saved bonded device")
                connectGattDelayed(device, 500)
                return
            }
        }

        setState(AppState.WAIT_PAIR_BUTTON)
        connectionView.text = "Hold PAIR for 5 seconds, then scan."
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        titleView = titleText("Justin Shunt Test", 25f)
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
            text = "LED ON"
            isEnabled = false
            setOnClickListener { toggleLed() }
        }
        readStatusButton = Button(this).apply {
            text = "Read Status"
            isEnabled = false
            setOnClickListener { readStatus() }
        }
        forgetButton = Button(this).apply {
            text = "Forget Saved Device"
            setOnClickListener { forgetSavedDevice() }
        }
        clearLogButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener { clearLog() }
        }

        root.addView(titleView)
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

        closeGatt()
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
                        bondOrConnect(device)
                    }
                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondOrConnect(device: BluetoothDevice) {
        connectionView.text = "${device.address} / bond=${bondStateName(device.bondState)}"
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            rememberDevice(device)
            connectGattDelayed(device, 500)
        } else if (device.bondState == BluetoothDevice.BOND_BONDING || bondInProgress) {
            addLog("Pairing already in progress")
        } else {
            setState(AppState.BONDING)
            addLog("createBond()")
            bondInProgress = true
            if (!device.createBond()) {
                bondInProgress = false
                showError("createBond() returned false")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        stopScan()
        closeGatt()
        liveNotifyCount = 0
        selectedDevice = device
        setState(AppState.CONNECTING)
        connectionView.text = "Connecting ${device.address}"
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun connectGattDelayed(device: BluetoothDevice, delayMs: Long) {
        val thisConnectSession = ++connectSession
        addLog("Connect scheduled in ${delayMs}ms")
        mainHandler.postDelayed({
            if (connectSession == thisConnectSession) {
                connectGatt(device)
            }
        }, delayMs)
    }

    private fun enqueueNotification(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic != null) {
            notifyQueue.add(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextNotification(gatt: BluetoothGatt) {
        if (descriptorWriteInProgress) {
            return
        }

        val characteristic = if (notifyQueue.isEmpty()) null else notifyQueue.removeFirst()
        if (characteristic == null) {
            setState(AppState.CONNECTED)
            readStatus()
            return
        }

        val descriptor = characteristic.getDescriptor(cccUuid)
        if (descriptor == null) {
            showError("CCC descriptor missing")
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)
        descriptorWriteInProgress = true
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!started) {
            descriptorWriteInProgress = false
            showError("Failed to start descriptor write")
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleLed() {
        val currentGatt = gatt ?: return
        val characteristic = ledCharacteristic ?: return
        ledOn = !ledOn
        renderLedButton()
        val value = byteArrayOf(if (ledOn) 0x01 else 0x00)

        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            ledOn = !ledOn
            renderLedButton()
            addLog("LED write could not start")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readStatus() {
        val currentGatt = gatt ?: return
        val characteristic = statusCharacteristic ?: return
        if (readInProgress) {
            return
        }
        readInProgress = true
        currentGatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun readLiveData() {
        val currentGatt = gatt ?: return
        val characteristic = liveCharacteristic ?: return
        if (readInProgress) {
            return
        }
        readInProgress = true
        currentGatt.readCharacteristic(characteristic)
    }

    private fun handleCharacteristicText(uuid: UUID, text: String) {
        readInProgress = false
        if (text.isBlank()) {
            return
        }

        when (uuid) {
            liveDataUuid -> showLiveData(text)
            statusUuid -> showStatus(text)
        }
    }

    private fun startLiveReadFallback() {
        val thisConnectSession = connectSession
        liveReadFallbackActive = true
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!liveReadFallbackActive || connectSession != thisConnectSession) {
                    return
                }

                if (state == AppState.CONNECTED && liveNotifyCount == 0) {
                    addLog("Live notify missing, reading live data")
                    readLiveData()
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }, 3000)
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
            if (parts.size == 2) {
                parts[0].trim() to parts[1].trim()
            } else {
                null
            }
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
            ledButton.isEnabled = connected
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

    private fun closeGatt() {
        notifyQueue.clear()
        descriptorWriteInProgress = false
        liveReadFallbackActive = false
        liveNotifyCount = 0
        bondInProgress = false
        readInProgress = false
        gatt?.close()
        gatt = null
        liveCharacteristic = null
        statusCharacteristic = null
        ledCharacteristic = null
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
        removeKnownBonds()
        prefs.edit().clear().apply()
        selectedDevice = null
        connectSession++
        closeGatt()
        setState(AppState.WAIT_PAIR_BUTTON)
        connectionView.text = "Saved device cleared. Hold PAIR 5s, then scan."
        addLog("Saved device cleared")
    }

    @SuppressLint("MissingPermission")
    private fun removeKnownBonds() {
        if (!hasPermissions()) {
            return
        }

        val savedAddress = prefs.getString("device_address", null)
        val selectedAddress = selectedDevice?.address
        val candidates = bluetoothAdapter.bondedDevices.filter { device ->
            val name = device.name.orEmpty()
            device.address == savedAddress ||
                device.address == selectedAddress ||
                name.startsWith("Justin_Shunt", ignoreCase = true)
        }

        candidates.forEach { device ->
            if (removeBond(device)) {
                addLog("Android bond removed: ${device.address}")
            } else {
                addLog("Android bond remove failed: ${device.address}")
            }
        }
    }

    private fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
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
