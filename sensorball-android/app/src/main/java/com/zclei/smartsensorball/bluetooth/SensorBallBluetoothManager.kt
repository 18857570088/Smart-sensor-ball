package com.zclei.smartsensorball.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

data class SensorBallDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val transport: SensorBallTransport = SensorBallTransport.Ble,
    val hasBle: Boolean = transport == SensorBallTransport.Ble,
    val hasClassic: Boolean = transport == SensorBallTransport.Classic,
    val bleAddress: String? = if (transport == SensorBallTransport.Ble) address else null,
    val classicAddress: String? = if (transport == SensorBallTransport.Classic) address else null,
)

enum class SensorBallTransport {
    Ble,
    Classic,
}

data class SensorBallTelemetry(
    val packetIndex: Int,
    val batteryRaw: Int,
    val hitCount: Int,
    val forceLow: Int,
    val forceHigh: Int,
    val forceN: Int,
) {
    val peak: Int
        get() = forceN

    val batteryText: String =
        when (batteryRaw) {
            101 -> "充电"
            102 -> "充满"
            in 0..100 -> "$batteryRaw%"
            else -> "--"
        }
}

interface SensorBallBluetoothCallback {
    fun onStatus(message: String)
    fun onDevicesChanged(devices: List<SensorBallDevice>)
    fun onConnected(device: SensorBallDevice)
    fun onDisconnected()
    fun onTelemetry(telemetry: SensorBallTelemetry)
}

class SensorBallBluetoothManager(
    context: Context,
    private val callback: SensorBallBluetoothCallback,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
    private val devices = linkedMapOf<String, SensorBallDevice>()
    private var gatt: BluetoothGatt? = null
    private var classicSocket: BluetoothSocket? = null
    private var connectedDevice: SensorBallDevice? = null
    private var desiredDevice: SensorBallDevice? = null
    private var pendingFallbackDevice: SensorBallDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingNotificationDescriptors = ArrayDeque<BluetoothGattDescriptor>()
    private val readableTelemetryCandidates = mutableListOf<BluetoothGattCharacteristic>()
    private var bleNotifyCount: Int = 0
    private var bleReadyDispatched = false
    private var scanning = false
    @Volatile
    private var classicReadLoopActive = false
    @Volatile
    private var suppressNextBleDisconnectCallback = false
    @Volatile
    private var manualDisconnectRequested = false
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var connectionWatchdogRunnable: Runnable? = null

    private val classicReceiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val name = device.name ?: return
                        if (!isBoxingDeviceName(name)) {
                            return
                        }
                        val item =
                            SensorBallDevice(
                                name = name,
                                address = device.address,
                                rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt(),
                                transport = SensorBallTransport.Classic,
                            )
                        addOrMergeDevice(item)
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (scanning) {
                            callback.onStatus("扫描完成，发现 ${devices.size} 个 SENBALL# 设备")
                        }
                    }
                }
            }
        }

    private val scanCallback =
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.extractDeviceName() ?: return
                if (!isBoxingDeviceName(name)) {
                    return
                }
                val item = SensorBallDevice(name = name, address = device.address, rssi = result.rssi, transport = SensorBallTransport.Ble)
                addOrMergeDevice(item)
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                callback.onStatus("扫描失败：$errorCode")
            }
        }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null || adapter?.isEnabled != true) {
            callback.onStatus("蓝牙未开启")
            return
        }
        if (scanning) {
            stopScan()
        }
        devices.clear()
        callback.onDevicesChanged(emptyList())
        scanning = true
        registerClassicReceiver()
        addBondedBoxingDevices()
        adapter?.cancelDiscovery()
        scanner?.startScan(null, scanSettings, scanCallback)
        callback.onStatus("正在扫描 SENBALL# 设备...")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) {
            return
        }
        scanner?.stopScan(scanCallback)
        adapter?.cancelDiscovery()
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun connect(device: SensorBallDevice) {
        manualDisconnectRequested = false
        desiredDevice = device
        reconnectAttempts = 0
        cancelReconnect()
        connectInternal(device, isReconnect = false)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(
        device: SensorBallDevice,
        isReconnect: Boolean,
    ) {
        stopScan()
        disconnectInternal(notify = false, clearReconnectTarget = false)
        val targetTransport =
            when {
                device.hasBle && device.bleAddress != null -> SensorBallTransport.Ble
                device.hasClassic && device.classicAddress != null -> SensorBallTransport.Classic
                else -> device.transport
            }
        val targetAddress = device.connectAddress(targetTransport)
        val remoteDevice =
            try {
                adapter?.getRemoteDevice(targetAddress)
            } catch (exc: IllegalArgumentException) {
                null
            }
        if (remoteDevice == null) {
            callback.onStatus("设备地址无效")
            return
        }
        val targetDevice = device.copy(transport = targetTransport)
        connectedDevice = targetDevice
        callback.onStatus(if (isReconnect) "正在重新连接 ${device.name}..." else "正在连接 ${device.name}...")
        if (targetTransport == SensorBallTransport.Classic) {
            connectClassic(remoteDevice, targetDevice)
        } else {
            pendingFallbackDevice = if (device.hasClassic && device.classicAddress != null) device.copy(transport = SensorBallTransport.Classic) else null
            connectBle(remoteDevice, targetDevice)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnectRequested = true
        desiredDevice = null
        cancelReconnect()
        disconnectInternal(notify = true, clearReconnectTarget = true)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(
        notify: Boolean,
        clearReconnectTarget: Boolean,
    ) {
        val hadConnection = connectedDevice != null || classicSocket != null || gatt != null
        cancelConnectionWatchdog()
        val targetGatt = gatt
        writeCharacteristic = null
        pendingNotificationDescriptors.clear()
        readableTelemetryCandidates.clear()
        bleNotifyCount = 0
        bleReadyDispatched = false
        connectedDevice = null
        pendingFallbackDevice = null
        if (clearReconnectTarget) {
            desiredDevice = null
        }
        classicReadLoopActive = false
        runCatching { classicSocket?.close() }
        classicSocket = null
        if (targetGatt != null) {
            suppressNextBleDisconnectCallback = true
            targetGatt.disconnect()
            targetGatt.close()
        }
        gatt = null
        if (notify && hadConnection) {
            callback.onDisconnected()
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        manualDisconnectRequested = true
        desiredDevice = null
        cancelReconnect()
        stopScan()
        unregisterClassicReceiver()
        disconnectInternal(notify = false, clearReconnectTarget = true)
    }

    @SuppressLint("MissingPermission")
    fun setGyroscopeEnabled(enabled: Boolean): Boolean {
        val payload = gyroscopePayload(enabled)
        val successMessage = if (enabled) "已发送开启陀螺仪指令" else "已发送关闭陀螺仪指令"
        return writeControlPayload(payload, successMessage = successMessage, failureMessage = "指令发送失败")
    }

    @SuppressLint("MissingPermission")
    fun requestTelemetryRefresh(
        allowGyroscopeOffFallback: Boolean,
        forceGyroscopeOffFallback: Boolean = false,
    ): Boolean {
        if (allowGyroscopeOffFallback && forceGyroscopeOffFallback) {
            return writeControlPayload(gyroscopePayload(enabled = false), successMessage = null, failureMessage = null)
        }
        val targetGatt = gatt
        val readable = bestReadableTelemetryCharacteristic()
        if (targetGatt != null && readable != null) {
            val readStarted = runCatching { targetGatt.readCharacteristic(readable) }.getOrDefault(false)
            if (readStarted) {
                return true
            }
        }
        if (!allowGyroscopeOffFallback) {
            return false
        }
        return writeControlPayload(gyroscopePayload(enabled = false), successMessage = null, failureMessage = null)
    }

    @SuppressLint("MissingPermission")
    private fun writeControlPayload(
        payload: ByteArray,
        successMessage: String?,
        failureMessage: String?,
    ): Boolean {
        val targetSocket = classicSocket
        if (targetSocket != null && targetSocket.isConnected) {
            return try {
                targetSocket.outputStream.write(payload)
                targetSocket.outputStream.flush()
                successMessage?.let(callback::onStatus)
                true
            } catch (_: IOException) {
                failureMessage?.let(callback::onStatus)
                false
            }
        }
        val targetGatt =
            gatt ?: return false.also {
                failureMessage?.let { callback.onStatus("请先连接蓝牙设备") }
            }
        val characteristic =
            writeCharacteristic ?: return false.also {
                failureMessage?.let { callback.onStatus("未找到可写入的蓝牙通道") }
            }
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetGatt.writeCharacteristic(characteristic, payload, characteristic.writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = payload
                targetGatt.writeCharacteristic(characteristic)
            }
        if (result) {
            successMessage?.let(callback::onStatus)
        } else {
            failureMessage?.let(callback::onStatus)
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(
        device: BluetoothDevice,
        item: SensorBallDevice,
    ) {
        val targetGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = targetGatt
        startConnectionWatchdog(targetGatt, item)
    }

    @SuppressLint("MissingPermission")
    private fun connectClassic(device: BluetoothDevice, item: SensorBallDevice) {
        thread(name = "sensorball-classic-connect") {
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                cancelConnectionWatchdog()
                classicSocket = socket
                connectedDevice = item
                pendingFallbackDevice = null
                reconnectAttempts = 0
                startClassicReadLoop(socket)
                callback.onConnected(item)
                callback.onStatus("蓝牙串口已就绪")
            } catch (exc: IOException) {
                if (tryClassicFallbacks(device, item)) {
                    return@thread
                }
                classicSocket = null
                callback.onStatus("经典蓝牙连接失败")
                handleUnexpectedDisconnect("classic connect failed")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryClassicFallbacks(device: BluetoothDevice, item: SensorBallDevice): Boolean {
        val candidates =
            listOf(
                runCatching { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull(),
                runCatching {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }.getOrNull(),
            ).filterNotNull()

        for (socket in candidates) {
            try {
                socket.connect()
                cancelConnectionWatchdog()
                classicSocket = socket
                connectedDevice = item
                pendingFallbackDevice = null
                reconnectAttempts = 0
                startClassicReadLoop(socket)
                callback.onConnected(item)
                callback.onStatus("蓝牙串口已就绪")
                return true
            } catch (exc: IOException) {
                runCatching { socket.close() }
            }
        }
        return false
    }

    private fun startClassicReadLoop(socket: BluetoothSocket) {
        classicReadLoopActive = true
        thread(name = "sensorball-classic-read") {
            val buffer = ByteArray(256)
            while (classicReadLoopActive && socket.isConnected) {
                try {
                    val count = socket.inputStream.read(buffer)
                    if (count > 0) {
                        val packet = buffer.copyOf(count)
                        parseTelemetry(packet)?.let(callback::onTelemetry)
                    }
                } catch (_: IOException) {
                    break
                }
            }
            val shouldNotify = classicReadLoopActive
            classicReadLoopActive = false
            if (shouldNotify) {
                handleUnexpectedDisconnect("classic read disconnected")
            }
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED && this@SensorBallBluetoothManager.gatt != gatt) {
                    if (suppressNextBleDisconnectCallback) {
                        suppressNextBleDisconnectCallback = false
                    }
                    runCatching { gatt.close() }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    callback.onStatus("已连接，正在发现服务...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (this@SensorBallBluetoothManager.gatt == gatt) {
                        this@SensorBallBluetoothManager.gatt = null
                    }
                    runCatching { gatt.close() }
                    writeCharacteristic = null
                    pendingNotificationDescriptors.clear()
                    readableTelemetryCandidates.clear()
                    bleNotifyCount = 0
                    bleReadyDispatched = false
                    if (suppressNextBleDisconnectCallback) {
                        suppressNextBleDisconnectCallback = false
                        return
                    }
                    if (!tryPendingClassicFallback("BLE disconnected status=$status")) {
                        handleUnexpectedDisconnect("BLE disconnected status=$status")
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (this@SensorBallBluetoothManager.gatt != gatt) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback.onStatus("服务发现失败：$status")
                    if (!tryPendingClassicFallback("BLE service discovery failed status=$status")) {
                        if (this@SensorBallBluetoothManager.gatt == gatt) {
                            this@SensorBallBluetoothManager.gatt = null
                        }
                        runCatching { gatt.disconnect() }
                        runCatching { gatt.close() }
                        handleUnexpectedDisconnect("BLE service discovery failed status=$status")
                    }
                    return
                }
                writeCharacteristic = null
                pendingNotificationDescriptors.clear()
                readableTelemetryCandidates.clear()
                bleNotifyCount = 0
                bleReadyDispatched = false
                val notifyCandidates = mutableListOf<BluetoothGattCharacteristic>()
                gatt.services.orEmpty().forEach { service ->
                    service.characteristics.orEmpty().forEach { characteristic ->
                        val props = characteristic.properties
                        if (writeCharacteristic == null && props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                            characteristic.writeType =
                                if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                } else {
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                }
                            writeCharacteristic = characteristic
                        }
                        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
                            notifyCandidates += characteristic
                        }
                        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_READ)) {
                            readableTelemetryCandidates += characteristic
                        }
                    }
                }
                val orderedNotifyCandidates =
                    notifyCandidates.sortedByDescending { characteristic ->
                        characteristic.uuid.toString().contains("ffe4", ignoreCase = true)
                    }
                orderedNotifyCandidates.forEach { characteristic ->
                    if (queueNotification(gatt, characteristic)) {
                        bleNotifyCount += 1
                    }
                }
                if (writeCharacteristic == null) {
                    if (tryPendingClassicFallback("BLE writable characteristic missing")) {
                        return
                    }
                }
                if (!writeNextNotificationDescriptor(gatt)) {
                    dispatchBleReady(gatt)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                parseTelemetry(value)?.let(callback::onTelemetry)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                parseTelemetry(value)?.let(callback::onTelemetry)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    parseTelemetry(characteristic.value)?.let(callback::onTelemetry)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    parseTelemetry(value)?.let(callback::onTelemetry)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
            status: Int,
            ) {
                if (!writeNextNotificationDescriptor(gatt)) {
                    dispatchBleReady(gatt)
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun dispatchBleReady(gatt: BluetoothGatt) {
        if (this.gatt != gatt) {
            return
        }
        if (bleReadyDispatched) {
            return
        }
        cancelConnectionWatchdog()
        bleReadyDispatched = true
        reconnectAttempts = 0
        connectedDevice?.let(callback::onConnected)
        callback.onStatus("蓝牙已就绪，通知通道 $bleNotifyCount 个")
        requestInitialTelemetryRead(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun requestInitialTelemetryRead(gatt: BluetoothGatt) {
        val characteristic = bestReadableTelemetryCharacteristic() ?: return
        thread(name = "sensorball-initial-telemetry-read") {
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (this.gatt == gatt) {
                runCatching { gatt.readCharacteristic(characteristic) }
            }
        }
    }

    private fun bestReadableTelemetryCharacteristic(): BluetoothGattCharacteristic? =
        readableTelemetryCandidates
            .sortedByDescending { it.uuid.toString().contains("ffe4", ignoreCase = true) }
            .firstOrNull()

    @SuppressLint("MissingPermission")
    private fun queueNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val localEnabled = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value =
            if (characteristic.properties.hasAny(BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            pendingNotificationDescriptors.add(descriptor)
        }
        return localEnabled
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor(gatt: BluetoothGatt): Boolean {
        val descriptor = pendingNotificationDescriptors.removeFirstOrNull() ?: return false
        return gatt.writeDescriptor(descriptor)
    }

    private fun parseTelemetry(value: ByteArray?): SensorBallTelemetry? {
        if (value == null || value.size < TELEMETRY_PACKET_SIZE) {
            return null
        }
        for (index in 0..(value.size - TELEMETRY_PACKET_SIZE)) {
            if ((value[index].toInt() and 0xFF) == 0xD5 && (value[index + 1].toInt() and 0xFF) == 0x5D && (value[index + 2].toInt() and 0xFF) == 0x03) {
                val forceLow = value[index + 9].toInt() and 0xFF
                val forceHigh = value[index + 10].toInt() and 0xFF
                val telemetry = SensorBallTelemetry(
                    packetIndex = value[index + 3].toInt() and 0xFF,
                    batteryRaw = value[index + 4].toInt() and 0xFF,
                    hitCount = value[index + 5].toInt() and 0xFF,
                    forceLow = forceLow,
                    forceHigh = forceHigh,
                    forceN = readUInt16LittleEndian(value, index + 9),
                )
                return telemetry
            }
        }
        return null
    }

    private fun readUInt16LittleEndian(value: ByteArray, offset: Int): Int {
        val low = value[offset].toInt() and 0xFF
        val high = value[offset + 1].toInt() and 0xFF
        return low or (high shl 8)
    }

    private fun gyroscopePayload(enabled: Boolean): ByteArray =
        byteArrayOf(0xC5.toByte(), 0x5C.toByte(), 0x04, if (enabled) 0x01 else 0x00)

    @SuppressLint("MissingPermission")
    private fun tryPendingClassicFallback(reason: String): Boolean {
        val fallback = pendingFallbackDevice ?: return false
        val address = fallback.classicAddress ?: return false
        pendingFallbackDevice = null
        cancelConnectionWatchdog()
        runCatching { gatt?.close() }
        gatt = null
        val remoteDevice =
            try {
                adapter?.getRemoteDevice(address)
            } catch (exc: IllegalArgumentException) {
                null
            } ?: return false
        callback.onStatus("BLE连接失败，尝试经典蓝牙...")
        connectClassic(remoteDevice, fallback.copy(transport = SensorBallTransport.Classic))
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startConnectionWatchdog(
        targetGatt: BluetoothGatt,
        item: SensorBallDevice,
    ) {
        cancelConnectionWatchdog()
        val runnable =
            Runnable {
                if (manualDisconnectRequested || gatt != targetGatt || bleReadyDispatched) {
                    return@Runnable
                }
                callback.onStatus("蓝牙连接超时，正在重试...")
                if (gatt == targetGatt) {
                    gatt = null
                }
                runCatching { targetGatt.disconnect() }
                runCatching { targetGatt.close() }
                connectedDevice = item
                handleUnexpectedDisconnect("BLE connection timeout")
            }
        connectionWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, BLE_CONNECTION_TIMEOUT_MS)
    }

    private fun cancelConnectionWatchdog() {
        connectionWatchdogRunnable?.let(mainHandler::removeCallbacks)
        connectionWatchdogRunnable = null
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        if (manualDisconnectRequested) {
            return
        }
        cancelConnectionWatchdog()
        writeCharacteristic = null
        pendingNotificationDescriptors.clear()
        readableTelemetryCandidates.clear()
        bleNotifyCount = 0
        bleReadyDispatched = false
        val reconnectTarget = desiredDevice ?: connectedDevice
        runCatching { gatt?.close() }
        gatt = null
        classicReadLoopActive = false
        runCatching { classicSocket?.close() }
        classicSocket = null
        connectedDevice = null
        callback.onDisconnected()
        if (reconnectTarget == null) {
            callback.onStatus("蓝牙已断开，请靠近设备后重新连接")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            callback.onStatus("蓝牙信号不稳定，自动重连已暂停，请靠近设备后重新连接")
            return
        }
        val nextAttempt = reconnectAttempts + 1
        val delayMs = reconnectDelayMs(nextAttempt)
        callback.onStatus("蓝牙信号中断，${delayMs / 1000.0} 秒后自动重连 ($nextAttempt/$MAX_RECONNECT_ATTEMPTS)")
        reconnectAttempts = nextAttempt
        cancelReconnect()
        val runnable =
            Runnable {
                reconnectRunnable = null
                if (manualDisconnectRequested) {
                    return@Runnable
                }
                connectInternal(reconnectTarget, isReconnect = true)
            }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val slot = (attempt - 1).coerceAtLeast(0)
        val delay = INITIAL_RECONNECT_DELAY_MS * (1 shl slot.coerceAtMost(4))
        return delay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun addBondedBoxingDevices() {
        adapter?.bondedDevices.orEmpty().forEach { device ->
            val name = device.name ?: return@forEach
            if (!isBoxingDeviceName(name)) {
                return@forEach
            }
            val item =
                SensorBallDevice(
                    name = name,
                    address = device.address,
                    rssi = 0,
                    transport = SensorBallTransport.Classic,
                )
            addOrMergeDevice(item)
        }
    }

    private fun ScanResult.extractDeviceName(): String? {
        val advertisedName = scanRecord?.deviceName
        if (!advertisedName.isNullOrBlank()) {
            return advertisedName
        }
        val cachedName =
            try {
                device?.name
            } catch (_: SecurityException) {
                null
            }
        if (!cachedName.isNullOrBlank()) {
            return cachedName
        }
        val rawText =
            runCatching {
                scanRecord?.bytes?.toString(Charsets.ISO_8859_1)
            }.getOrNull()
        return rawText?.let { DEVICE_NAME_REGEX.find(it)?.value }
    }

    private fun isBoxingDeviceName(name: String): Boolean {
        val normalized = name.trim()
        return normalized.startsWith(DEVICE_PREFIX, ignoreCase = true) &&
            normalized.lastOrNull()?.isAsciiDigit() == true
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun addOrMergeDevice(item: SensorBallDevice) {
        val existingKey =
            devices.entries.firstOrNull { (_, existing) ->
                existing.address.equals(item.address, ignoreCase = true) || existing.normalizedName() == item.normalizedName()
            }?.key
        if (existingKey == null) {
            devices[item.deviceKey()] = item
        } else {
            val existing = devices.getValue(existingKey)
            val preferredTransport = choosePreferredTransport(existing, item)
            val mergedBleAddress =
                when {
                    !existing.bleAddress.isNullOrBlank() -> existing.bleAddress
                    !item.bleAddress.isNullOrBlank() -> item.bleAddress
                    item.transport == SensorBallTransport.Ble -> item.address
                    existing.transport == SensorBallTransport.Ble -> existing.address
                    else -> null
                }
            val mergedClassicAddress =
                when {
                    !existing.classicAddress.isNullOrBlank() -> existing.classicAddress
                    !item.classicAddress.isNullOrBlank() -> item.classicAddress
                    item.transport == SensorBallTransport.Classic -> item.address
                    existing.transport == SensorBallTransport.Classic -> existing.address
                    else -> null
                }
            devices[existingKey] =
                SensorBallDevice(
                    name = bestDisplayName(existing.name, item.name),
                    address =
                        if (preferredTransport == SensorBallTransport.Classic) {
                            mergedClassicAddress ?: mergedBleAddress ?: existing.address
                        } else {
                            mergedBleAddress ?: mergedClassicAddress ?: existing.address
                        },
                    rssi = maxOf(existing.rssi, item.rssi),
                    transport = preferredTransport,
                    hasBle = existing.hasBle || item.hasBle || item.transport == SensorBallTransport.Ble,
                    hasClassic = existing.hasClassic || item.hasClassic || item.transport == SensorBallTransport.Classic,
                    bleAddress = mergedBleAddress,
                    classicAddress = mergedClassicAddress,
                )
        }
        callback.onDevicesChanged(devices.values.toList())
    }

    private fun choosePreferredTransport(existing: SensorBallDevice, item: SensorBallDevice): SensorBallTransport =
        when {
            existing.transport == SensorBallTransport.Classic -> SensorBallTransport.Classic
            item.transport == SensorBallTransport.Classic -> SensorBallTransport.Classic
            else -> SensorBallTransport.Ble
        }

    private fun bestDisplayName(first: String, second: String): String =
        listOf(first, second).maxByOrNull { it.length }.orEmpty().ifBlank { first }

    private fun SensorBallDevice.deviceKey(): String = normalizedName().ifBlank { address.uppercase() }

    private fun SensorBallDevice.normalizedName(): String = name.trim().uppercase()

    private fun SensorBallDevice.connectAddress(targetTransport: SensorBallTransport = transport): String =
        if (targetTransport == SensorBallTransport.Classic) {
            classicAddress ?: address
        } else {
            bleAddress ?: address
        }

    private fun registerClassicReceiver() {
        runCatching {
            unregisterClassicReceiver()
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(classicReceiver, filter)
            }
        }
    }

    private fun unregisterClassicReceiver() {
        runCatching { appContext.unregisterReceiver(classicReceiver) }
    }

    private fun Int.hasAny(vararg flags: Int): Boolean = flags.any { flag -> this and flag != 0 }

    private companion object {
        const val DEVICE_PREFIX = "SENBALL#"
        const val TELEMETRY_PACKET_SIZE = 11
        const val BLE_CONNECTION_TIMEOUT_MS = 12_000L
        const val INITIAL_RECONNECT_DELAY_MS = 800L
        const val MAX_RECONNECT_DELAY_MS = 8_000L
        const val MAX_RECONNECT_ATTEMPTS = 12
        val DEVICE_NAME_REGEX = Regex("SENBALL#[A-Za-z0-9_-]*[0-9]", RegexOption.IGNORE_CASE)
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
