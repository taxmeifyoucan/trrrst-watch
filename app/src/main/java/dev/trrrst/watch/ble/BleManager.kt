package dev.trrrst.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, READY, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state
    private val _statusLine = MutableStateFlow("")
    val statusLine: StateFlow<String> = _statusLine
    private val _found = MutableStateFlow<BluetoothDevice?>(null)
    val found: StateFlow<BluetoothDevice?> = _found

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private val mutex = Mutex()

    /** notifications, per characteristic */
    private val afQueue = Channel<ByteArray>(Channel.UNLIMITED)       // 0x2D
    private val drspQueue = Channel<ByteArray>(Channel.UNLIMITED)     // 0x23
    private val convoyQueue = Channel<ByteArray>(Channel.UNLIMITED)   // 0x24

    /** find-phone ring events (true = start, false = stop) */
    val findPhoneEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    private var writeWait: CompletableDeferred<Boolean>? = null

    // ------------------------------------------------ scan & connect
    fun scan(timeoutMs: Long = 90_000) {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        _found.value = null   // StateFlow drops re-emits of the same device: reset first
        _state.value = ConnState.SCANNING
        _statusLine.value = "scanning — press the button on the watch"
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: return
                if (name.startsWith("CASIO F-B100W")) {
                    scanner.stopScan(this)
                    _found.value = r.device
                }
            }

            override fun onScanFailed(code: Int) {
                _state.value = ConnState.ERROR
                _statusLine.value = "scan failed: $code"
            }
        }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb
        )
        scope.launch {
            delay(timeoutMs)
            if (_state.value == ConnState.SCANNING) {
                scanner.stopScan(cb)
                _state.value = ConnState.DISCONNECTED
                _statusLine.value = "watch not found"
            }
        }
    }

    suspend fun connect(dev: BluetoothDevice): Boolean = suspendCancellableCoroutine { cont ->
        device = dev
        _state.value = ConnState.CONNECTING
        _statusLine.value = "connecting…"
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt = g
                        g.requestMtu(512)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _state.value = ConnState.DISCONNECTED
                        _statusLine.value = "disconnected"
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                scope.launch {
                    val ok = runCatching {
                        enableNotify(Protocol.CHR_ALL_FEATURES)
                    }.isSuccess
                    if (ok) {
                        _state.value = ConnState.READY
                        _statusLine.value = "connected"
                    } else {
                        _statusLine.value = "notify setup failed"
                    }
                    if (cont.isActive) cont.resume(ok) {}
                }
            }

            @Deprecated("deprecated")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.let { dispatch(c.uuid, it) }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
            ) = dispatch(c.uuid, value)

            override fun onCharacteristicWrite(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
            ) {
                writeWait?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
            ) {
                writeWait?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
        dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun dispatch(uuid: UUID, value: ByteArray) {
        when (uuid.toString()) {
            Protocol.CHR_ALL_FEATURES -> {
                if (value.size > 1 && value[0] == Protocol.OP_ALERT)
                    findPhoneEvents.tryEmit(value[1].toInt() == 2)
                afQueue.trySend(value)
            }
            Protocol.CHR_DATA_REQ -> drspQueue.trySend(value)
            Protocol.CHR_CONVOY -> convoyQueue.trySend(value)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _found.value = null
        _state.value = ConnState.DISCONNECTED
    }

    suspend fun pair() {
        device?.createBond()
    }

    // ------------------------------------------------ primitives
    private suspend fun enableNotify(uuidStr: String): Boolean {
        val g = gatt ?: return false
        val c = g.getService(UUID.fromString(Protocol.SERVICE))
            ?.getCharacteristic(UUID.fromString(uuidStr)) ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: return false
        return awaitWrite {
            if (Build.VERSION.SDK_INT >= 33)
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(d)
            }
        }
    }

    private suspend fun awaitWrite(run: () -> Boolean): Boolean {
        val w = CompletableDeferred<Boolean>()
        writeWait = w
        val started = run()
        if (!started) {
            writeWait = null
            return false
        }
        return withTimeoutOrNull(8_000) { w.await() } == true
    }

    private suspend fun writeChar(uuidStr: String, value: ByteArray, noRsp: Boolean): Boolean {
        val g = gatt ?: return false
        val c = g.getService(UUID.fromString(Protocol.SERVICE))
            ?.getCharacteristic(UUID.fromString(uuidStr)) ?: return false
        return awaitWrite {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    c, value,
                    if (noRsp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType =
                    if (noRsp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") c.value = value
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
        }
    }

    // ------------------------------------------------ protocol ops
    /** Read: write [op] to 0x2C, answer arrives on 0x2D with the same first byte. */
    suspend fun read(op: Byte, timeoutMs: Long = 10_000): ByteArray = mutex.withLock {
        while (afQueue.tryReceive().isSuccess) Unit // drain stale
        if (!writeChar(Protocol.CHR_READ_REQ, byteArrayOf(op), noRsp = true))
            error("read-req write failed")
        withTimeout(timeoutMs) {
            while (true) {
                val v = afQueue.receive()
                if (v.isNotEmpty() && v[0] == op) return@withTimeout v
                if (v.isNotEmpty() && v[0] == 0xFF.toByte()) error("watch error 0xFF for 0x${"%02x".format(op)}")
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    suspend fun write(payload: ByteArray): Boolean = mutex.withLock {
        android.util.Log.d("TrrrstBLE", "write ${payload.toHex()}")
        check(writeChar(Protocol.CHR_ALL_FEATURES, payload, noRsp = false)) {
            "watch write failed: ${payload.toHex()}"
        }
        true
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Bulk transfer via 0x23 (control) + 0x24 (data). commit=false sends ABORT (peek). */
    suspend fun receive(cat: Int, fixedLen: Int? = null, commit: Boolean = true): ByteArray =
        mutex.withLock {
            while (drspQueue.tryReceive().isSuccess) Unit
            while (convoyQueue.tryReceive().isSuccess) Unit
            enableNotify(Protocol.CHR_DATA_REQ)
            enableNotify(Protocol.CHR_CONVOY)
            try {
                if (!writeChar(Protocol.CHR_DATA_REQ, Protocol.drsp(0, cat, fixedLen ?: 0), noRsp = false))
                    error("drsp start failed")
                val length = fixedLen ?: run {
                    val ann = withTimeout(15_000) { drspQueue.receive() }
                    Protocol.parseDrsp(ann).third
                }
                if (length == 0) return@withLock ByteArray(0)
                val out = ByteArrayOutputStream(length)
                withTimeout(30_000) {
                    while (out.size() < length) out.write(convoyQueue.receive())
                }
                writeChar(
                    Protocol.CHR_DATA_REQ,
                    Protocol.drsp(if (commit) 4 else 3, cat, fixedLen ?: 0), noRsp = false
                )
                out.toByteArray().copyOf(length)
            } finally {
                // leave CCCDs on; cheap and harmless for the session
            }
        }
}
