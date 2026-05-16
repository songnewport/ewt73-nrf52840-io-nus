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
import java.util.UUID

enum class AppState {
    IDLE,
    REQUEST_PERMISSIONS,
    WAIT_PAIR_BUTTON,
    SCANNING,
    FOUND_DEVICE,
    BONDING,
    BONDED,
    CONNECTING_GATT,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    HOME_CONNECTED,
    ERROR
}

class MainActivity : Activity() {
    private val serviceUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val liveDataUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    private val ledControlUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    private val statusUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
    private val cccUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var state = AppState.IDLE
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var ledOn = false

    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var dataView: TextView
    private lateinit var logView: TextView
    private lateinit var primaryButton: Button
    private lateinit var ledButton: Button

    private val foundDevices = linkedMapOf<String, ScanResult>()

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
            setState(AppState.FOUND_DEVICE)
            showDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            setError("Scan failed: $errorCode")
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
                    setState(AppState.BONDING)
                    appendLog("Pairing...")
                }

                BluetoothDevice.BOND_BONDED -> {
                    setState(AppState.BONDED)
                    prefs.edit()
                        .putString("device_address", device.address)
                        .putString("device_name", device.name ?: "Justin_Shunt_Test")
                        .putLong("last_connected", System.currentTimeMillis())
                        .apply()
                    appendLog("Bonded successfully")
                    connectGatt(device)
                }

                BluetoothDevice.BOND_NONE -> {
                    setError("Pairing failed or was cancelled")
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setError("GATT connection error: $status")
                closeGatt()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState(AppState.DISCOVERING_SERVICES)
                appendLog("Connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("Disconnected")
                closeGatt()
                setState(AppState.WAIT_PAIR_BUTTON)
                renderIntro()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setError("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                setError("Justin Smart Shunt service not found")
                return
            }

            ledCharacteristic = service.getCharacteristic(ledControlUuid)
            val live = service.getCharacteristic(liveDataUuid)
            if (ledCharacteristic == null || live == null) {
                setError("Required characteristic not found")
                return
            }

            setState(AppState.SUBSCRIBING)
            subscribeToLiveData(gatt, live)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleLiveText(value.decodeToString())
        }

        @Deprecated("Used on older Android releases")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleLiveText(characteristic.value?.decodeToString().orEmpty())
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == cccUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setState(AppState.HOME_CONNECTED)
                    appendLog("Live data subscribed")
                    renderHome()
                } else {
                    setError("Subscribe failed: $status")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == ledControlUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    appendLog("LED write OK")
                } else {
                    appendLog("LED write failed: $status")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("justin_shunt_ble", Context.MODE_PRIVATE)
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager.adapter
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
            setError("Bluetooth permissions are required")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFlow() {
        val savedAddress = prefs.getString("device_address", null)
        if (savedAddress != null) {
            val device = bluetoothAdapter.getRemoteDevice(savedAddress)
            selectedDevice = device
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                appendLog("Using saved bonded device: $savedAddress")
                connectGatt(device)
                return
            }
        }

        setState(AppState.WAIT_PAIR_BUTTON)
        renderIntro()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        titleView = TextView(this).apply {
            textSize = 24f
            text = "Justin Shunt Test"
        }
        stateView = TextView(this).apply { textSize = 15f }
        dataView = TextView(this).apply {
            textSize = 20f
            setPadding(0, 18, 0, 18)
        }
        primaryButton = Button(this)
        ledButton = Button(this).apply {
            visibility = View.GONE
            text = "Turn LED ON"
            setOnClickListener { toggleLed() }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 18, 0, 0)
        }

        root.addView(titleView)
        root.addView(stateView)
        root.addView(dataView)
        root.addView(primaryButton)
        root.addView(ledButton)
        root.addView(logView)

        val scroll = ScrollView(this).apply {
            addView(root)
            fillViewport = true
        }
        setContentView(scroll)
    }

    private fun renderIntro() {
        mainHandler.post {
            titleView.text = "Welcome"
            dataView.text = "Please press and hold the PAIR button on the Smart Shunt for 5 seconds.\n\nWhen the board enters pair mode, tap Continue."
            primaryButton.text = "Continue / Scan"
            primaryButton.visibility = View.VISIBLE
            primaryButton.setOnClickListener { startScan() }
            ledButton.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions, 100)
            return
        }

        foundDevices.clear()
        setState(AppState.SCANNING)
        appendLog("Scanning for Justin_Shunt_Test")
        bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
        mainHandler.postDelayed({
            if (state == AppState.SCANNING && foundDevices.isEmpty()) {
                stopScan()
                setError("No device found. Enter pair mode and scan again.")
            }
        }, 12000)

        mainHandler.post {
            titleView.text = "Scanning"
            dataView.text = "Looking for Justin_Shunt_Test..."
            primaryButton.visibility = View.GONE
            ledButton.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceList() {
        mainHandler.post {
            titleView.text = "Select Device"
            root.removeViews(3, root.childCount - 3)

            foundDevices.values.forEach { result ->
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val button = Button(this).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = "$name\n${device.address}  RSSI ${result.rssi}"
                    setOnClickListener {
                        stopScan()
                        selectedDevice = device
                        bondOrConnect(device)
                    }
                }
                root.addView(button)
            }

            root.addView(ledButton)
            root.addView(logView)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondOrConnect(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            setState(AppState.BONDED)
            appendLog("Already bonded")
            connectGatt(device)
        } else {
            setState(AppState.BONDING)
            appendLog("Starting createBond()")
            if (!device.createBond()) {
                setError("createBond() returned false")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        closeGatt()
        selectedDevice = device
        setState(AppState.CONNECTING_GATT)
        appendLog("Connecting GATT: ${device.address}")
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToLiveData(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(cccUuid)
        if (descriptor == null) {
            setError("CCC descriptor not found")
            return
        }

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleLed() {
        val g = gatt ?: return
        val ch = ledCharacteristic ?: return
        ledOn = !ledOn
        val value = byteArrayOf(if (ledOn) 0x01 else 0x00)
        ledButton.text = if (ledOn) "Turn LED OFF" else "Turn LED ON"

        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun renderHome() {
        mainHandler.post {
            titleView.text = "Smart Shunt"
            primaryButton.visibility = View.GONE
            ledButton.visibility = View.VISIBLE
            if (dataView.text.isBlank()) {
                dataView.text = "Waiting for live data..."
            }
        }
    }

    private fun handleLiveText(text: String) {
        if (text.isBlank()) {
            return
        }

        mainHandler.post {
            dataView.text = parseLiveText(text)
        }
    }

    private fun parseLiveText(text: String): String {
        val map = text.split(",")
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()

        return listOf(
            "Voltage: ${map["V"] ?: "--"} V",
            "Current: ${map["I"] ?: "--"} A",
            "Temperature: ${map["T"] ?: "--"} C",
            "SOC: ${map["SOC"] ?: "--"} %",
            "Connection: Connected / Bonded",
            "LED State: ${if (ledOn) "ON" else "OFF"}"
        ).joinToString("\n")
    }

    private fun setState(newState: AppState) {
        state = newState
        mainHandler.post { stateView.text = "State: $newState" }
    }

    private fun appendLog(message: String) {
        mainHandler.post {
            logView.append("${System.currentTimeMillis() % 100000}: $message\n")
        }
    }

    private fun setError(message: String) {
        setState(AppState.ERROR)
        appendLog(message)
        mainHandler.post {
            titleView.text = "Error"
            dataView.text = message
            primaryButton.text = "Restart"
            primaryButton.visibility = View.VISIBLE
            primaryButton.setOnClickListener { startFlow() }
            ledButton.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (hasPermissions()) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        ledCharacteristic = null
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
