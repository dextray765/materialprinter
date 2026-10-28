package com.dextray.materialprinter.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dextray.materialprinter.image.ProcessedImageResult
import com.dextray.materialprinter.protocol.DensityLevel
import com.dextray.materialprinter.protocol.InkSaving
import com.dextray.materialprinter.protocol.PrinterClass
import com.dextray.materialprinter.protocol.PrinterProtocol
import com.dextray.materialprinter.protocol.PrinterReplies
import com.dextray.materialprinter.protocol.RasterTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
    data class Printing(val message: String, val progress: Float) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Owns the Bluetooth connection to the printer.
 *
 * One instance lives in [com.dextray.materialprinter.PrinterApp], so the connection survives
 * Activity re-creation (screen rotation, theme change, ...).
 */
class BluetoothPrinterManager(appContext: Context) {

    companion object {
        const val TAG = "MaterialPrinterBT"
        private const val HANDSHAKE_REPLY_TIMEOUT_MS = 1500L
        private const val PAPER_QUERY_TIMEOUT_MS = 500L
        private const val PREF_DENSITY_LEVEL = "density_level_v2"
        private const val PREF_DENSITY_LEVEL_LEGACY = "density_level"
        private const val PREF_INK_SAVING = "ink_saving_v2"
    }

    private val context: Context = appContext.applicationContext

    // The manager is a process-wide singleton, so this scope intentionally lives as long as the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // --- connection resources -------------------------------------------------------------------
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var inputStream: InputStream? = null
    private var connectJob: Job? = null
    private var readerJob: Job? = null

    /** Bytes received from the printer, consumed by [query]. The reader coroutine is the only producer. */
    // Bounded: if the printer keeps talking while nobody waits for a reply, the oldest chunks are dropped
    private val rxChannel = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Serializes print / feed operations so they can never interleave on the wire. */
    private val printLock = Mutex()

    /** Set when the app was closed during a print: the connection is dropped as soon as the print is over. */
    @Volatile private var releaseWhenIdle = false

    /** Last "err:" status byte reported by the printer (0 = no error). */
    @Volatile private var lastErrorCode: Int = 0

    // --- observable state -----------------------------------------------------------------------
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Print width in dots, taken from the printer's status reply (384 unless the printer says 576). */
    private val _printerWidth = MutableStateFlow(PrinterProtocol.PRINTER_WIDTH)
    val printerWidth: StateFlow<Int> = _printerWidth.asStateFlow()

    /** Text of the last problem reported by the printer itself (no paper, overheat, ...), or null. */
    private val _printerWarning = MutableStateFlow<String?>(null)
    val printerWarning: StateFlow<String?> = _printerWarning.asStateFlow()

    /** RFCOMM channel used by the next connection attempt. */
    private val _rfcommPort = MutableStateFlow(PrinterProtocol.DEFAULT_RFCOMM_PORT)
    val rfcommPort: StateFlow<Int> = _rfcommPort.asStateFlow()

    fun setRfcommPort(port: Int) {
        _rfcommPort.value = port.coerceIn(1, 30)
    }

    private val prefs = context.getSharedPreferences("printer_settings", Context.MODE_PRIVATE)

    /** Print darkness chosen by the user; the real value depends on [printerClass]. Survives app restarts. */
    private val _densityLevel = MutableStateFlow(loadDensityLevel())
    val densityLevel: StateFlow<DensityLevel> = _densityLevel.asStateFlow()

    private fun loadDensityLevel(): DensityLevel {
        val savedName = prefs.getString(PREF_DENSITY_LEVEL, null)
            // older versions stored the position in the enum: LIGHT, MEDIUM, DARK
            ?: DensityLevel.values().getOrNull(prefs.getInt(PREF_DENSITY_LEVEL_LEGACY, -1))?.name
        return DensityLevel.values().firstOrNull { it.name == savedName } ?: DensityLevel.MEDIUM
    }

    fun setDensityLevel(level: DensityLevel) {
        _densityLevel.value = level
        prefs.edit().putString(PREF_DENSITY_LEVEL, level.name).apply()
    }

    /** Cap for the share of black dots per row (see [InkSaving]); survives app restarts. */
    private val _inkSaving = MutableStateFlow(
        InkSaving.values().firstOrNull { it.name == prefs.getString(PREF_INK_SAVING, InkSaving.OFF.name) } ?: InkSaving.OFF
    )
    val inkSaving: StateFlow<InkSaving> = _inkSaving.asStateFlow()

    fun setInkSaving(level: InkSaving) {
        _inkSaving.value = level
        prefs.edit().putString(PREF_INK_SAVING, level.name).apply()
    }

    /** Firmware class detected during the handshake (decides the density preset), or null before that. */
    private val _printerClass = MutableStateFlow<PrinterClass?>(null)
    val printerClass: StateFlow<PrinterClass?> = _printerClass.asStateFlow()

    /** Battery voltage in millivolts from the last status reply, or null if the printer did not report it. */
    private val _batteryMillivolts = MutableStateFlow<Int?>(null)
    val batteryMillivolts: StateFlow<Int?> = _batteryMillivolts.asStateFlow()

    private var isReceiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val current = _discoveredDevices.value.toMutableList()
                        if (current.none { it.address == device.address }) {
                            current.add(device)
                            _discoveredDevices.value = current
                            Log.i(TAG, "Discovered device: ${safeName(device)} (${device.address})")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                    Log.i(TAG, "Device discovery finished")
                }
            }
        }
    }

    init {
        if (hasConnectPermission()) {
            refreshPairedDevices()
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** BluetoothDevice.getName() throws SecurityException without BLUETOOTH_CONNECT on Android 12+. */
    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        return name ?: device.address ?: "Принтер"
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!hasConnectPermission()) return
        try {
            val devices = adapter.bondedDevices?.toList() ?: emptyList()
            _pairedDevices.value = devices
            Log.i(TAG, "Paired devices found: ${devices.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching bonded devices: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!hasConnectPermission()) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            _discoveredDevices.value = emptyList()

            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(discoveryReceiver, filter)
                isReceiverRegistered = true
            }

            val started = adapter.startDiscovery()
            _isScanning.value = started
            if (started) {
                Log.i(TAG, "Started Bluetooth discovery")
            } else {
                Log.w(TAG, "Bluetooth discovery did not start")
            }
        } catch (e: SecurityException) {
            _isScanning.value = false
            Log.e(TAG, "Discovery not permitted: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "stopDiscovery not permitted: ${e.message}")
        }
        _isScanning.value = false
    }

    // ================================================================================================
    // Connecting
    // ================================================================================================

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, rfcommPort: Int = _rfcommPort.value) {
        // Ignore repeated taps while a connection attempt or a print is in progress.
        val current = _connectionState.value
        if (current is ConnectionState.Connecting || current is ConnectionState.Printing) {
            Log.w(TAG, "connectToDevice ignored: busy ($current)")
            return
        }
        if (!hasConnectPermission()) {
            _connectionState.value = ConnectionState.Error("Нет разрешения BLUETOOTH_CONNECT")
            return
        }

        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("На устройстве нет Bluetooth")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth выключен. Включите его и подключитесь снова.")
            return
        }

        val deviceName = safeName(device)
        _connectionState.value = ConnectionState.Connecting(deviceName)
        Log.i(TAG, "Connecting to $deviceName (${device.address}) on RFCOMM port $rfcommPort...")

        connectJob = scope.launch(Dispatchers.IO) {
            // Cancel discovery before connecting to free up the Bluetooth radio
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (_: Exception) {
            }

            closeSocket()
            _printerWidth.value = PrinterProtocol.PRINTER_WIDTH
            _printerWarning.value = null
            _printerClass.value = null
            _batteryMillivolts.value = null
            lastErrorCode = 0

            val socket = openSocket(device, rfcommPort)
            if (socket == null) {
                Log.e(TAG, "All connection strategies failed for $deviceName")
                _connectionState.value =
                    ConnectionState.Error("Не удалось подключиться к $deviceName. Проверьте сопряжение и питание.")
                return@launch
            }

            // The user may have pressed "Disconnect" while connect() was blocking.
            if (!isActive) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                return@launch
            }

            try {
                val input = socket.inputStream
                val output = socket.outputStream
                activeSocket = socket
                inputStream = input
                outputStream = output

                // From now on the reader coroutine is the only one reading the socket.
                startReader(socket, input)

                Log.i(TAG, "Socket connected. Performing YHK handshake (matching cat-printer.py)...")
                performHandshake()

                if (activeSocket === socket) {
                    _connectionState.value = ConnectionState.Connected(deviceName, device.address)
                    Log.i(TAG, "Ready for printing: $deviceName (${device.address})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up connection: ${e.message}", e)
                closeSocket()
                _connectionState.value = ConnectionState.Error("Ошибка подключения: ${e.message}")
            }
        }
    }

    /**
     * Tries the connection strategies one after another. A socket that failed to connect is ALWAYS closed,
     * otherwise it leaks and can make the following attempts fail.
     */
    private fun openSocket(device: BluetoothDevice, rfcommPort: Int): BluetoothSocket? {
        // Strategy 1: fixed RFCOMM channel via reflection (channel 2 is what YHK firmware uses)
        tryConnect("Strategy 1 (RFCOMM channel $rfcommPort via reflection)") {
            val createMethod = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            createMethod.invoke(device, rfcommPort) as BluetoothSocket
        }?.let { return it }

        // Strategy 2: standard SPP UUID (service discovery, like the original WalkPrint app)
        tryConnect("Strategy 2 (SPP UUID)") {
            device.createRfcommSocketToServiceRecord(PrinterProtocol.SPP_UUID)
        }?.let { return it }

        // Strategy 3: insecure SPP
        return tryConnect("Strategy 3 (insecure SPP)") {
            device.createInsecureRfcommSocketToServiceRecord(PrinterProtocol.SPP_UUID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(label: String, create: () -> BluetoothSocket): BluetoothSocket? {
        var socket: BluetoothSocket? = null
        return try {
            Log.i(TAG, "$label: connecting...")
            val created = create()
            socket = created
            created.connect()
            Log.i(TAG, "$label: connected successfully")
            created
        } catch (e: Exception) {
            Log.w(TAG, "$label failed: ${e.cause?.message ?: e.message}")
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    // ================================================================================================
    // Reading from the printer
    // ================================================================================================

    /**
     * Continuously reads everything the printer sends (like the original app does). This is what makes
     * a lost connection detectable and lets us react to "out of paper" / "overheated" reports.
     */
    private fun startReader(socket: BluetoothSocket, input: InputStream) {
        readerJob?.cancel()
        while (rxChannel.tryReceive().isSuccess) {
            // drop stale bytes of a previous connection
        }

        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(256)
            var tail = ByteArray(0)
            try {
                while (isActive) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val chunk = buffer.copyOf(count)

                    // "err:" may be split between two reads, so keep the last bytes of the previous chunk.
                    val window = tail + chunk
                    handleUnsolicited(window)
                    tail = window.copyOfRange(maxOf(0, window.size - 4), window.size)

                    rxChannel.trySend(chunk)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Reader stopped: ${e.message}")
            }

            // closeSocket() clears activeSocket first, so an intentional disconnect does not end up here.
            if (activeSocket === socket) {
                Log.w(TAG, "Connection to printer lost")
                handleLinkLost("Соединение с принтером потеряно (принтер мог уснуть). Подключитесь заново.")
            }
        }
    }

    private fun handleUnsolicited(window: ByteArray) {
        val code = PrinterReplies.findErrorCode(window) ?: return
        Log.i(TAG, "Printer reported status code 0x${Integer.toHexString(code)}")
        lastErrorCode = code
        _printerWarning.value = PrinterReplies.describeError(code)
    }

    /**
     * Sends [command] and collects the reply until [expectedLength] bytes arrived or [timeoutMs] passed.
     * Returns whatever was received (possibly nothing).
     */
    private suspend fun query(command: ByteArray, expectedLength: Int, timeoutMs: Long): ByteArray {
        val out = outputStream ?: return ByteArray(0)
        while (rxChannel.tryReceive().isSuccess) {
            // drop anything that arrived earlier
        }
        withContext(Dispatchers.IO) {
            out.write(command)
            out.flush()
        }
        val received = ByteArrayOutputStream()
        withTimeoutOrNull(timeoutMs) {
            while (received.size() < expectedLength) {
                received.write(rxChannel.receive())
            }
        }
        return received.toByteArray()
    }

    /**
     * Same sequence as cat-printer.py:
     * 1. getPrinterStatus: 1E 47 03 -> 38 bytes (contains battery voltage and print width)
     * 2. getPrinterSerialNumber: 1D 67 39 -> 21 bytes
     * 3. getPrinterProductInfo: 1D 67 69 -> 16 bytes
     * The replies are logged so that unknown firmware variants can be analysed from logcat.
     */
    private suspend fun performHandshake() {
        try {
            val status = query(PrinterProtocol.CMD_GET_STATUS, 38, HANDSHAKE_REPLY_TIMEOUT_MS)
            Log.i(TAG, "Handshake 1/3: status ${status.size} B: ${status.toHex()} | ${status.toAscii()}")
            val width = PrinterReplies.parseWidth(status)
            if (width != null) {
                _printerWidth.value = width
                Log.i(TAG, "Printer reports width $width dots")
            } else {
                Log.i(TAG, "Printer width not found in status reply, using ${PrinterProtocol.PRINTER_WIDTH}")
            }
            delay(300)

            val serial = query(PrinterProtocol.CMD_GET_SERIAL, 21, HANDSHAKE_REPLY_TIMEOUT_MS)
            Log.i(TAG, "Handshake 2/3: serial ${serial.size} B: ${serial.toAscii()}")
            delay(300)

            val product = query(PrinterProtocol.CMD_GET_PRODUCT_INFO, 16, HANDSHAKE_REPLY_TIMEOUT_MS)
            Log.i(TAG, "Handshake 3/3: product info ${product.size} B: ${product.toAscii()}")
            delay(300)

            _batteryMillivolts.value = PrinterReplies.parseBatteryMillivolts(status)
            val detected = PrinterReplies.detectClass(status, serial + product)
            _printerClass.value = detected
            Log.i(TAG, "Battery: ${_batteryMillivolts.value} mV; printer class: ${detected.name}")

            Log.i(TAG, "Handshake finished")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A write failure here means the link is already dead - report it instead of pretending success.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Handshake warning (non-fatal): ${e.message}")
        }
    }

    // ================================================================================================
    // Printing
    // ================================================================================================

    /**
     * Prints a processed image following the cat-printer.py sequence:
     * ESC @ -> density -> raster (GS v 0) -> 4x LF.
     *
     * The work runs in [NonCancellable]: if the screen that started the print is left half-way
     * (tab switch), the data stream is still finished, so the printer is never left with a partial
     * command and the state never gets stuck on "Printing".
     */
    suspend fun printImage(processed: ProcessedImageResult): Result<Unit> {
        if (!printLock.tryLock()) {
            return Result.failure(IOException("Принтер занят предыдущей операцией"))
        }
        try {
            return withContext(Dispatchers.IO + NonCancellable) { doPrintImage(processed) }
        } finally {
            printLock.unlock()
            releaseIfRequested()
        }
    }

    private suspend fun doPrintImage(processed: ProcessedImageResult): Result<Unit> {
        Log.i(TAG, "=== printImage requested ===")
        Log.i(
            TAG,
            "Image info: width=${processed.widthBytes * 8} dots, height=${processed.height} dots, rasterBytes=${processed.rasterBytes.size}"
        )

        val out = outputStream
        val socket = activeSocket
        val state = _connectionState.value
        val connected = state as? ConnectionState.Connected
        val devName = connected?.deviceName ?: "Принтер"
        val devAddr = connected?.deviceAddress ?: ""

        if (out == null || socket == null || connected == null) {
            Log.e(TAG, "printImage FAILED: not connected")
            return Result.failure(IOException("Принтер не подключён"))
        }
        if (processed.height > PrinterProtocol.MAX_RASTER_HEIGHT) {
            return Result.failure(
                IOException("Слишком длинное изображение: ${processed.height} строк (максимум ${PrinterProtocol.MAX_RASTER_HEIGHT})")
            )
        }
        if (processed.widthBytes * 8 != _printerWidth.value) {
            Log.w(TAG, "Image width ${processed.widthBytes * 8} differs from printer width ${_printerWidth.value}")
        }

        try {
            // Step 0: ask the printer whether it is ready (same query the original app sends before printing).
            _connectionState.value = ConnectionState.Printing("Проверка принтера…", 0.05f)
            // Fresh battery reading: big black areas draw a lot of current and fail on a weak battery.
            val freshStatus = query(PrinterProtocol.CMD_GET_STATUS, 38, PAPER_QUERY_TIMEOUT_MS)
            PrinterReplies.parseBatteryMillivolts(freshStatus)?.let { millivolts ->
                _batteryMillivolts.value = millivolts
                Log.i(TAG, "Battery before print: $millivolts mV")
            }

            lastErrorCode = 0
            _printerWarning.value = null
            val paperReply = query(PrinterProtocol.CMD_GET_PAPER, 1, PAPER_QUERY_TIMEOUT_MS)
            Log.i(TAG, "Paper query reply: ${paperReply.size} B: ${paperReply.toHex()}")
            val preCheck = lastErrorCode
            if (preCheck and PrinterReplies.BLOCKING_MASK != 0) {
                val text = PrinterReplies.describeError(preCheck) ?: "Принтер не готов"
                Log.w(TAG, "printImage aborted: $text")
                restoreConnected(socket, devName, devAddr)
                return Result.failure(IOException(text))
            }

            // Step 1: Initialize (ESC @)
            _connectionState.value = ConnectionState.Printing("Инициализация…", 0.10f)
            Log.i(TAG, "Step 1: Sending CMD_INIT (1B 40)...")
            out.write(PrinterProtocol.CMD_INIT)
            out.flush()
            delay(500)

            // Step 2: density (YHK specific: 1D 49 F0 xx)
            _connectionState.value = ConnectionState.Printing("Подготовка печати…", 0.25f)
            val activeClass = _printerClass.value ?: PrinterClass.PRIVATE_NEW
            val density = activeClass.densityFor(_densityLevel.value)
            activeClass.speedCommand?.let { speedCommand ->
                Log.i(TAG, "Step 2a: Sending speed command for ${activeClass.name}...")
                out.write(speedCommand)
                out.flush()
                delay(100)
            }
            Log.i(TAG, "Step 2: Sending density $density (${_densityLevel.value.name}, ${activeClass.name})...")
            out.write(PrinterProtocol.createDensityCommand(density))
            out.flush()
            delay(500)

            // Step 3: raster packet, written in 500-byte chunks like the original app does
            _connectionState.value = ConnectionState.Printing("Передача данных…", 0.50f)
            val inkSaving = _inkSaving.value
            val raster = if (inkSaving == InkSaving.OFF) {
                processed.rasterBytes
            } else {
                Log.i(TAG, "Limiting black dots per row to ${inkSaving.title}")
                RasterTools.limitBlackPerRow(processed.rasterBytes, processed.widthBytes, processed.height, inkSaving.maxFraction)
            }
            val packet = PrinterProtocol.createRasterImagePacket(
                processed.widthBytes,
                processed.height,
                raster
            )
            Log.i(TAG, "Step 3: Sending raster packet: total size = ${packet.size} bytes (${processed.height} lines)...")
            writeChunked(out, packet)
            delay(500)

            // Step 4: end print sequence (feed lines)
            _connectionState.value = ConnectionState.Printing("Протяжка ленты…", 0.90f)
            Log.i(TAG, "Step 4: Sending CMD_END_PRINT (0A 0A 0A 0A)...")
            out.write(PrinterProtocol.CMD_END_PRINT)
            out.flush()
            delay(500)

            // The printer reports "no paper" / "overheated" asynchronously - do not claim success in that case.
            val finalCode = lastErrorCode
            if (finalCode and PrinterReplies.BLOCKING_MASK != 0) {
                val text = PrinterReplies.describeError(finalCode) ?: "Принтер сообщил об ошибке"
                Log.w(TAG, "printImage finished with printer error: $text")
                restoreConnected(socket, devName, devAddr)
                return Result.failure(IOException(text))
            }

            // The user may have interrupted the print by disconnecting while we were only waiting
            if (activeSocket !== socket) {
                throw IOException("Печать прервана")
            }

            Log.i(TAG, "=== printImage SUCCESS ===")
            restoreConnected(socket, devName, devAddr)
            return Result.success(Unit)
        } catch (e: IOException) {
            if (activeSocket === socket) {
                // Broken pipe & co: the link is gone, a later retry on the same socket cannot work.
                Log.e(TAG, "printImage IO error: ${e.message}", e)
                handleLinkLost("Ошибка печати: ${e.message}. Подключитесь к принтеру заново.")
            } else {
                // We closed the socket ourselves (the user pressed "Interrupt"): the state is already correct
                Log.i(TAG, "Print interrupted: connection was closed on request")
            }
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "printImage EXCEPTION: ${e.message}", e)
            restoreConnected(socket, devName, devAddr)
            return Result.failure(e)
        }
    }

    /**
     * Returns to "Connected" after a print - but only if the very same socket is still alive.
     * If the link was lost meanwhile, the "Error" state set by [handleLinkLost] must stay.
     */
    private fun restoreConnected(socket: BluetoothSocket?, name: String, address: String) {
        if (socket != null && activeSocket === socket) {
            _connectionState.value = ConnectionState.Connected(name, address)
        }
    }

    /** Writes [data] in [PrinterProtocol.WRITE_CHUNK_SIZE]-byte pieces and flushes once at the end. */
    private fun writeChunked(out: OutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val length = minOf(PrinterProtocol.WRITE_CHUNK_SIZE, data.size - offset)
            out.write(data, offset, length)
            offset += length
        }
        out.flush()
    }

    /**
     * Feeds paper by sending ESC @ and LF bytes.
     */
    suspend fun feedPaper(lines: Int = 4): Result<Unit> {
        if (!printLock.tryLock()) {
            return Result.failure(IOException("Принтер занят предыдущей операцией"))
        }
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                val out = outputStream
                if (out == null || activeSocket == null) {
                    Log.e(TAG, "feedPaper failed: not connected")
                    return@withContext Result.failure(IOException("Не подключён"))
                }
                try {
                    Log.i(TAG, "feedPaper: feeding $lines lines...")
                    out.write(PrinterProtocol.CMD_INIT)
                    out.flush()
                    delay(200)
                    out.write(PrinterProtocol.createFeedCommand(lines))
                    out.flush()
                    delay(200)
                    Log.i(TAG, "feedPaper: success!")
                    Result.success(Unit)
                } catch (e: IOException) {
                    Log.e(TAG, "feedPaper IO error: ${e.message}", e)
                    handleLinkLost("Ошибка связи: ${e.message}. Подключитесь к принтеру заново.")
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "feedPaper error: ${e.message}", e)
                    Result.failure(e)
                }
            }
        } finally {
            printLock.unlock()
            releaseIfRequested()
        }
    }

    // ================================================================================================
    // Disconnecting
    // ================================================================================================

    fun disconnect() {
        Log.i(TAG, "Disconnecting printer...")
        connectJob?.cancel()
        closeSocket()
        _printerWarning.value = null
        _printerClass.value = null
        _batteryMillivolts.value = null
        _printerWidth.value = PrinterProtocol.PRINTER_WIDTH
        _connectionState.value = ConnectionState.Disconnected
    }

    /** The link died by itself (printer powered off / went to sleep / out of range). */
    private fun handleLinkLost(message: String) {
        closeSocket()
        _connectionState.value = ConnectionState.Error(message)
    }

    private fun closeSocket() {
        // Clear the references FIRST: the reader coroutine uses `activeSocket === socket`
        // to tell an intentional close from a lost connection.
        val socket = activeSocket
        val out = outputStream
        val input = inputStream
        activeSocket = null
        outputStream = null
        inputStream = null

        readerJob?.cancel()
        readerJob = null

        try {
            out?.close()
        } catch (_: Exception) {
        }
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Called when the app is really being closed (not on rotation): drops the connection and
     * stops discovery. If a print is running, the connection is dropped right after it - cutting
     * the data stream would leave the printer with a half-sent command.
     * The manager can be used again afterwards.
     */
    fun release() {
        stopDiscovery()
        if (_connectionState.value is ConnectionState.Printing) {
            releaseWhenIdle = true
            return
        }
        shutdown()
    }

    /** A new Activity appeared while a delayed release was pending: the connection stays. */
    fun cancelPendingRelease() {
        releaseWhenIdle = false
    }

    private fun releaseIfRequested() {
        if (releaseWhenIdle) {
            releaseWhenIdle = false
            shutdown()
        }
    }

    private fun shutdown() {
        disconnect()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {
            }
            isReceiverRegistered = false
        }
    }

    // --- small helpers --------------------------------------------------------------------------

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun ByteArray.toAscii(): String =
        String(this, Charsets.ISO_8859_1).map { if (it.code in 32..126) it else '.' }.joinToString("")
}
