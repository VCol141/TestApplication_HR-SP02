package com.example.testapplication_hrsp02

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.*
import kotlin.math.min

// ---------- Supabase ----------
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

// Continuous uploader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withTimeoutOrNull

// Initializes Supabase once; reads URL/KEY from build.gradle BuildConfig fields
object SupabaseProvider {
    private const val URL = BuildConfig.SUPABASE_URL
    private const val KEY = BuildConfig.SUPABASE_ANON_KEY

    val client = createSupabaseClient(
        supabaseUrl = URL,
        supabaseKey = KEY // anon key only; never service role in mobile apps
    ) {
        install(Postgrest)
        install(Auth)
    }
}

// Import models
import com.example.testapplication_hrsp02.data.*
import java.util.UUID

// Session management
private var currentSession: SessionResponse? = null

// Create a new session
private suspend fun createSession(): SessionResponse? {
    return try {
        val sessionKey = UUID.randomUUID().toString()
        val session = Session(session_key = sessionKey)
        SupabaseProvider.client
            .from("sessions")
            .insert(session)
            .decodeSingle<SessionResponse>()
    } catch (e: Exception) {
        Log.e("Session", "Failed to create session", e)
        null
    }
}

// Bulk insert helper
private suspend fun insertManyHealth(rows: List<HealthData>) {
    if (rows.isEmpty()) return
    SupabaseProvider.client
        .from("health_data")
        .insert(rows)
}

// ===============================================================

class MainActivity : ComponentActivity() {

    // ====== CONFIG ======
    private val deviceName = "BLT_M70C"               // target BLE device name
    private val knownServiceUuid: UUID? = null        // set once known (optional)
    private val knownNotifyCharUuid: UUID? = null     // set once known (optional)

    private val WRITE_INTERVAL_SEC = 5
    private val BLE_WINDOW_MS = 25_000L
    private val TAG = "BLE_HR_SPO2"

    // ====== BT / BLE ======
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    // ====== UI state ======
    private var status by mutableStateOf("Status: Idle")
    private var hrState by mutableStateOf("--")
    private var spo2State by mutableStateOf("--")
    private var isScanningBle by mutableStateOf(false)
    private var scanEvents by mutableStateOf(0)
    private val discoveredDevices = mutableStateListOf<DiscoveredDevice>()
    private val seenKeys = Collections.synchronizedSet(mutableSetOf<String>())

    data class DiscoveredDevice(val address: String?, val name: String?, val rssi: Int?)

    // ====== CSV ======
    private val buffer = Collections.synchronizedList(mutableListOf<Triple<Long, Int, Int>>())
    private var writerJob: Job? = null
    private var bleJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ====== Continuous uploader ======
    private var uploaderJob: Job? = null
    private val uploadChan = Channel<Pair<Int, Int>>(  // (pulse, spo2)
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ====== Subscription queue (enable notifications on multiple chars safely) ======
    private val descriptorQueue: ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>> = ArrayDeque()
    private val subscribedCharUuids = Collections.synchronizedSet(mutableSetOf<UUID>())

    // ====== Permissions ======
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            updateStatus("Required permissions denied"); return@registerForActivityResult
        }
        startEnvironment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        setContent {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(16.dp)) {
                    Text(status)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { refreshBle() }) { Text("Refresh BLE") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { clearList() }) { Text("Clear List") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { promptEnableBluetooth() }) { Text("Enable Bluetooth") }
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { openLocationSettings() }) { Text("Location Settings") }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("HR: $hrState")
                    Text("SpO₂: $spo2State%")
                    Spacer(Modifier.height(12.dp))
                    Text("Nearby (live):")
                    LazyColumn {
                        items(discoveredDevices) { d ->
                            Text(
                                "• ${d.name ?: "(no name)"} — ${d.address ?: "unknown"} [RSSI ${d.rssi ?: "?"}]",
                                Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Scan events: $scanEvents | Scanning: ${if (isScanningBle) "YES" else "NO"}")
                }
            }
        }

        requestAllBtPerms()
    }

    // ===== Permissions =====
    private fun requestAllBtPerms() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permLauncher.launch(perms)
    }

    private fun hasPerm(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureEnvReady(): Boolean {
        if (!::bluetoothAdapter.isInitialized) { updateStatus("Bluetooth not available"); return false }
        if (!bluetoothAdapter.isEnabled) { updateStatus("Bluetooth is off"); return false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN) || !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                updateStatus("Missing BLUETOOTH permissions"); return false
            }
        } else {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                updateStatus("Location permission required (< Android 12)"); return false
            }
            if (!isLocationEnabled()) {
                updateStatus("Turn ON Location in system settings for BLE scanning"); return false
            }
        }
        return true
    }

    private fun isLocationEnabled(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.getProviders(true).isNotEmpty()
    } catch (_: Exception) { true }

    private fun startEnvironment() {
        if (!ensureEnvReady()) return
        
        ioScope.launch {
            currentSession = createSession()
            if (currentSession == null) {
                updateStatus("Failed to create session")
                return@launch
            }
            startCsvWriterIfNeeded()
            startUploader()
            startBleScanWindow()
        }
    }

    // ===== UI actions =====
    private fun refreshBle() {
        stopBleScan()
        safeCloseGatt()
        hrState = "--"; spo2State = "--"
        scanEvents = 0
        updateStatus("Refreshing BLE…")
        startBleScanWindow()
    }

    private fun clearList() {
        discoveredDevices.clear()
        seenKeys.clear()
        scanEvents = 0
        updateStatus("Cleared list")
    }

    private fun promptEnableBluetooth() {
        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    // ===== BLE scan =====
    private fun startBleScanWindow() {
        if (!ensureEnvReady()) return
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) { isScanningBle = false; updateStatus("BLE scanner unavailable"); return }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    it.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    it.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
            }.build()

        try {
            @Suppress("UNCHECKED_CAST")
            bleScanner?.startScan(null as List<ScanFilter>?, settings, bleCallback)
            isScanningBle = true
            updateStatus("BLE scan…")
        } catch (e: Exception) {
            isScanningBle = false
            updateStatus("Failed to start BLE scan: ${e.message}")
            return
        }

        bleJob?.cancel()
        bleJob = ioScope.launch {
            delay(BLE_WINDOW_MS)
            runOnUiThread { updateStatus("BLE window elapsed"); stopBleScan() }
        }
    }

    private fun stopBleScan() {
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        bleJob?.cancel()
        isScanningBle = false
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleOne(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleOne)
        override fun onScanFailed(errorCode: Int) {
            updateStatus("BLE scan failed: $errorCode")
            stopBleScan()
        }

        private fun handleOne(result: ScanResult) {
            val dev = result.device ?: return
            val addr = runCatching { dev.address }.getOrNull()
            val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull()
            val rssi = result.rssi
            val key = "${addr ?: name}:${rssi}"
            scanEvents++

            if (seenKeys.add(key)) {
                discoveredDevices.add(DiscoveredDevice(addr, name, rssi))
            }

            if (name == deviceName) {
                stopBleScan()
                updateStatus("Connecting to ${addr ?: "device"}…")
                gatt = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        dev.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        dev.connectGatt(this@MainActivity, false, gattCallback)
                    }
                } catch (e: Exception) {
                    updateStatus("connectGatt failed: ${e.message}")
                    null
                }
            }
        }
    }

    // ===== GATT with robust subscription =====
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                updateStatus("GATT error: $statusCode"); safeCloseGatt(); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateStatus("Connected — requesting MTU & high priority")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // MTU handshake, then discover
                    if (!gatt.requestMtu(247)) {
                        updateStatus("requestMtu failed; discovering services")
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateStatus("Disconnected")
                    safeCloseGatt()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            updateStatus("MTU changed: $mtu (status=$status) — discovering services")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateStatus("Service discovery failed: $status"); return
            }

            val sb = StringBuilder("Services/Chars:\n")
            gatt.services.forEach { svc ->
                sb.append("  SVC ${svc.uuid}\n")
                svc.characteristics.forEach { ch ->
                    sb.append("    CHAR ${ch.uuid}  props=${propsFlags(ch.properties)}\n")
                }
            }
            Log.d(TAG, sb.toString())

            val notifyChars = mutableListOf<BluetoothGattCharacteristic>()

            val primaryService = knownServiceUuid?.let { gatt.getService(it) }
            if (primaryService != null) {
                notifyChars += primaryService.characteristics.filter { hasNotifyOrIndicate(it) }
            }
            gatt.services.forEach { svc ->
                svc.characteristics.forEach { ch ->
                    if (hasNotifyOrIndicate(ch) && notifyChars.none { it.uuid == ch.uuid }) {
                        notifyChars += ch
                    }
                }
            }

            if (notifyChars.isEmpty()) {
                updateStatus("No notify/indicate characteristics found"); return
            }

            knownNotifyCharUuid?.let { knownUuid ->
                val idx = notifyChars.indexOfFirst { it.uuid == knownUuid }
                if (idx >= 0) {
                    val chosen = notifyChars.removeAt(idx)
                    notifyChars.add(0, chosen)
                }
            }

            subscribedCharUuids.clear()
            descriptorQueue.clear()
            for (ch in notifyChars) {
                if (!gatt.setCharacteristicNotification(ch, true)) {
                    Log.w(TAG, "setCharacteristicNotification failed for ${ch.uuid}")
                    continue
                }
                val cccd = ch.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID))
                if (cccd == null) {
                    Log.w(TAG, "CCCD not found for ${ch.uuid}")
                    continue
                }
                val supportsNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val value = if (supportsNotify)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                descriptorQueue.add(Pair(cccd, value))
            }

            if (descriptorQueue.isEmpty()) {
                updateStatus("No CCCD descriptors to write"); return
            }

            updateStatus("Subscribing to ${descriptorQueue.size} characteristic(s)…")
            writeNextDescriptor(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val chUuid = descriptor.characteristic.uuid
            if (descriptor.uuid.toString().equals(CCC_DESCRIPTOR_UUID, true)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribedCharUuids.add(chUuid)
                    Log.d(TAG, "Subscribed OK -> $chUuid")
                } else {
                    Log.w(TAG, "CCCD write failed ($status) -> $chUuid")
                }
            }
            writeNextDescriptor(gatt)
        }

        @Deprecated("Still used on many devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(characteristic, value)
        }
    }

    // ===== Helper: write next descriptor in queue =====
    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val next = descriptorQueue.pollFirst()
        if (next == null) {
            updateStatus("Subscribed to ${subscribedCharUuids.size} characteristic(s)")
            return
        }
        val (desc, value) = next
        desc.value = value
        if (!gatt.writeDescriptor(desc)) {
            Log.w(TAG, "writeDescriptor failed immediately -> ${desc.characteristic.uuid}")
            writeNextDescriptor(gatt)
        }
    }

    // ===== Parse + logging =====
    private fun handleNotify(ch: BluetoothGattCharacteristic, bytes: ByteArray) {
        val n = min(bytes.size, 32)
        val hex = (0 until n).joinToString(" ") { i -> String.format("%02X", bytes[i]) }
        Log.d(TAG, "NOTIFY ${ch.uuid} len=${bytes.size} data=$hex")

        val parsed = parseFrame(bytes) ?: return
        val (hr, spo2) = parsed
        hrState = hr.toString()
        spo2State = spo2.toString()

        // Timestamp once
        val now = System.currentTimeMillis() / 1000L

        // Keep CSV buffer
        buffer.add(Triple(now, hr, spo2))

        // Only upload if we have an active session
        if (currentSession != null) {
            // Enqueue for continuous upload (non-blocking)
            uploadChan.trySend(hr to spo2)
        }
    }

    private fun hasNotifyOrIndicate(ch: BluetoothGattCharacteristic): Boolean {
        val p = ch.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun propsFlags(p: Int): String {
        val flags = mutableListOf<String>()
        if ((p and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) flags += "BROADCAST"
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) flags += "READ"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) flags += "WRITE_NR"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) flags += "WRITE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) flags += "NOTIFY"
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) flags += "INDICATE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) flags += "SIGNED"
        if ((p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) flags += "EXT"
        return flags.joinToString("|")
    }

    // ===== Frame parsing (same logic as your Python) =====
    private fun parseFrame(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 19) return null
        val raw = bytes.map { it.toInt() and 0xFF }
        val b15 = raw[15]; val b16 = raw[16]; val b17 = raw[17]; val b18 = raw[18]
        return if (b18 == 0xFF && !(b15 == 0xFF && b16 == 0x7F && b17 == 0xFF)) {
            val spo2 = b16
            val hr = b17
            if (hr in 1..240 && spo2 in 1..100) hr to spo2 else null
        } else null
    }

    // ===== CSV + Supabase (batch on disk, continuous via channel) =====
    private fun csvFile(): File = File(getExternalFilesDir(null), "health_data.csv")

    private fun ensureCsvHeader() {
        val f = csvFile()
        if (f.exists()) return
        try {
            f.parentFile?.mkdirs()
            FileWriter(f, false).use { it.appendLine("timestamp,heart_rate,spo2") }
        } catch (e: IOException) { Log.e(TAG, "CSV header write failed", e) }
    }

    private fun flushBufferToCsv() {
        val snapshot = mutableListOf<Triple<Long, Int, Int>>()
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            snapshot.addAll(buffer); buffer.clear()
        }

        // Write CSV
        try {
            FileWriter(csvFile(), true).use { w ->
                snapshot.forEach { (ts, hr, spo2) -> w.appendLine("$ts,$hr,$spo2") }
            }
            Log.d(TAG, "CSV appended ${snapshot.size} rows.")
        } catch (e: IOException) { Log.e(TAG, "CSV write failed", e) }

        // ALSO send the same batch to Supabase (safety net)
        ioScope.launch {
            val rows = snapshot.map { (ts, hr, sp) -> HealthRow(ts, hr, sp) }
            runCatching { insertManyHealth(rows) }
                .onSuccess { Log.d(TAG, "Supabase: inserted ${rows.size} rows") }
                .onFailure { err -> Log.w(TAG, "Supabase insert failed: ${err.message}", err) }
        }
    }

    private fun startCsvWriterIfNeeded() {
        if (writerJob == null || writerJob?.isCancelled == true) {
            writerJob = ioScope.launch {
                ensureCsvHeader()
                while (isActive) {
                    delay(WRITE_INTERVAL_SEC * 1000L)
                    flushBufferToCsv()
                }
            }
        }
    }

    // ===== Continuous uploader (flush every few seconds or max batch) =====
    private fun startUploader() {
        if (uploaderJob?.isActive == true) return
        uploaderJob = ioScope.launch {
            val batch = mutableListOf<HealthData>()
            val FLUSH_MS = 3000L
            val MAX_BATCH = 50
            var lastFlush = System.currentTimeMillis()

            while (isActive) {
                val remaining = FLUSH_MS - (System.currentTimeMillis() - lastFlush)
                val item = withTimeoutOrNull(if (remaining > 0) remaining else 1L) {
                    uploadChan.receive()
                }
                
                if (item != null && currentSession != null) {
                    val (pulse, spo2) = item
                    batch += HealthData(
                        session_id = currentSession!!.id,
                        timestamp = System.currentTimeMillis() / 1000L,
                        pulse = pulse,
                        spo2 = spo2
                    )
                }

                val timeFlush = System.currentTimeMillis() - lastFlush >= FLUSH_MS
                val sizeFlush = batch.size >= MAX_BATCH

                if ((timeFlush || sizeFlush) && batch.isNotEmpty()) {
                    val toSend = batch.toList()
                    batch.clear()
                    lastFlush = System.currentTimeMillis()
                    runCatching { insertManyHealth(toSend) }
                        .onSuccess { Log.d(TAG, "Supabase: sent ${toSend.size} rows") }
                        .onFailure { e -> Log.w(TAG, "Supabase batch failed: ${e.message}", e) }
                }
            }
        }
    }

    // ===== Utils =====
    private fun safeCloseGatt() { runCatching { gatt?.close() }; gatt = null }
    private fun updateStatus(msg: String) { Log.d(TAG, msg); runOnUiThread { status = msg } }

    override fun onDestroy() {
        super.onDestroy()
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        safeCloseGatt()
        writerJob?.cancel()
        bleJob?.cancel()
        uploaderJob?.cancel()      // <-- stop uploader
        ioScope.cancel()
    }

    companion object {
        private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
