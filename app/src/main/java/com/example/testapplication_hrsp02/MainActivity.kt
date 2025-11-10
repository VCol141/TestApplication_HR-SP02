
// Revised MainActivity.kt that creates sessions directly in Supabase (no external /sessions endpoint).
// Adjusts id types to Long, uses PostgREST select() to return inserted row.
package com.example.testapplication_hrsp02

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.testapplication_hrsp02.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.math.min

@Serializable
data class SessionResponse(
    val id: Long,
    val session_key: String
)

@Serializable
data class HealthData(
    val session_id: Long,
    val timestamp: Long,
    val pulse: Int,
    val spo2: Int
)

object SupabaseProvider {
    private const val URL = BuildConfig.SUPABASE_URL
    private const val KEY = BuildConfig.SUPABASE_ANON_KEY

    val client = createSupabaseClient(
        supabaseUrl = URL,
        supabaseKey = KEY
    ) {
        install(Postgrest)
        install(Auth)
    }
}

class MainActivity : ComponentActivity() {

    private val deviceName = "BLT_M70C"
    private val knownServiceUuid: java.util.UUID? = null
    private val knownNotifyCharUuid: java.util.UUID? = null

    private val WRITE_INTERVAL_SEC = 5
    private val BLE_WINDOW_MS = 25_000L
    private val TAG = "BLE_HR_SPO2"
    
    // Railway backend URL
    private val API_URL = "https://tele-oximeter-backend-development.up.railway.app"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 15000
        }
    }

    private var status by mutableStateOf("Status: Idle")
    private var hrState by mutableStateOf("--")
    private var spo2State by mutableStateOf("--")
    private var isScanningBle by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var isStreaming by mutableStateOf(false)
    private var scanEvents by mutableStateOf(0)
    private var sessionKey by mutableStateOf("")
    private val discoveredDevices = mutableStateListOf<DiscoveredDevice>()
    private val seenKeys = Collections.synchronizedSet(mutableSetOf<String>())

    data class DiscoveredDevice(val address: String?, val name: String?, val rssi: Int?)

    private val buffer = Collections.synchronizedList(mutableListOf<Triple<Long, Int, Int>>())
    private var writerJob: Job? = null
    private var bleJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var uploaderJob: Job? = null
    private val uploadChan = Channel<Pair<Int, Int>>(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val descriptorQueue: ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>> = ArrayDeque()
    private val subscribedCharUuids = Collections.synchronizedSet(mutableSetOf<java.util.UUID>())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            updateStatus("Required permissions denied")
        } else {
            updateStatus("Permissions granted - Ready to connect")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }

        requestAllBtPerms()
    }
    
    @Composable
    fun MainScreen() {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Oximeter Data Streaming",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(status)
            Spacer(Modifier.height(16.dp))

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Bluetooth Status: ${if (isConnected) "✓ Connected" else "✗ Disconnected"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Text("HR: $hrState  |  SpO₂: $spo2State%")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Session Key Display
            if (sessionKey.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE3F2FD)
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Session Key:",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            text = sessionKey,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFF0D47A1)
                        )
                        if (isStreaming) {
                            Text(
                                text = "🔴 Streaming data to Railway + Supabase",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFC62828),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Main Control Buttons
            Button(
                onClick = { connectOximeter() },
                enabled = !isConnected && !isScanningBle,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = if (isScanningBle) "Connecting..." else "1. Connect Oximeter",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { startStreaming() },
                enabled = isConnected && !isStreaming,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = if (isStreaming) "Streaming Active" else "2. Start Streaming Data",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { stopStreaming() },
                enabled = isStreaming,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = "Stop Streaming",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    private fun requestAllBtPerms() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permLauncher.launch(perms)
    }
    
    private fun connectOximeter() {
        if (!ensureEnvReady()) return
        updateStatus("Searching for oximeter...")
        startBleScanWindow()
    }
    
    private fun startStreaming() {
        if (!isConnected) {
            updateStatus("Please connect oximeter first")
            return
        }
        
        isStreaming = true
        updateStatus("Creating Railway session...")
        
        ioScope.launch {
            // Create session on Railway
            val railwaySessionKey = createRailwaySession()
            
            if (railwaySessionKey != null) {
                // Create session in Supabase
                val supabaseSession = createSessionInSupabase(railwaySessionKey)
                
                if (supabaseSession != null) {
                    currentSession = supabaseSession
                    withContext(Dispatchers.Main) {
                        sessionKey = railwaySessionKey
                        updateStatus("Streaming active - Session: $railwaySessionKey")
                    }
                    
                    startCsvWriterIfNeeded()
                    startUploader()
                } else {
                    withContext(Dispatchers.Main) {
                        isStreaming = false
                        updateStatus("Failed to create Supabase session")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    isStreaming = false
                    updateStatus("Failed to create Railway session")
                }
            }
        }
    }
    
    private fun stopStreaming() {
        isStreaming = false
        sessionKey = ""
        currentSession = null
        writerJob?.cancel()
        uploaderJob?.cancel()
        updateStatus("Streaming stopped")
    }
    
    private suspend fun createRailwaySession(): String? {
        return try {
            Log.d(TAG, "Requesting new session from: $API_URL/session/new")
            
            val response: HttpResponse = httpClient.post("$API_URL/session/new")
            val responseBody = response.bodyAsText()
            
            Log.d(TAG, "Railway Response: ${response.status.value} - $responseBody")
            
            if (response.status.value in 200..299) {
                // Parse JSON response - extract session_key
                val regex = """"session_key"\s*:\s*"([^"]+)"""".toRegex()
                val key = regex.find(responseBody)?.groupValues?.get(1)
                
                if (key != null) {
                    Log.d(TAG, "Railway session created: $key")
                    key
                } else {
                    Log.e(TAG, "Could not parse session_key from response")
                    null
                }
            } else {
                Log.e(TAG, "Railway session creation failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Railway session: ${e.message}", e)
            null
        }
    }
    
    private suspend fun createSessionInSupabase(railwayKey: String): SessionResponse? {
        return try {
            val res: PostgrestResult = SupabaseProvider.client
                .from("sessions")
                .insert(mapOf("session_key" to railwayKey)) { select() }
            res.decodeSingle<SessionResponse>()
        } catch (e: Exception) {
            Log.e(TAG, "Supabase session insert failed: ${e.message}", e)
            null
        }
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

    private fun refreshBle() {
        stopBleScan()
        safeCloseGatt()
        isConnected = false
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

    @SuppressLint("MissingPermission")
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

    @SuppressLint("MissingPermission")
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

        @SuppressLint("MissingPermission")
        private fun handleOne(result: ScanResult) {
            val dev = result.device ?: return
            val addr = runCatching { dev.address }.getOrNull()
            val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull()
            val rssi = result.rssi
            val key = "${addr ?: name}:$rssi"
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

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                updateStatus("GATT error: $statusCode"); safeCloseGatt(); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    updateStatus("Connected — requesting MTU & high priority")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.requestMtu(247)) {
                        updateStatus("requestMtu failed; discovering services")
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
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
                val cccd = ch.getDescriptor(java.util.UUID.fromString(CCC_DESCRIPTOR_UUID))
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

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val next = descriptorQueue.removeFirstOrNull()
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

    private fun handleNotify(ch: BluetoothGattCharacteristic, bytes: ByteArray) {
        val n = min(bytes.size, 32)
        val hex = (0 until n).joinToString(" ") { i -> String.format("%02X", bytes[i]) }
        Log.d(TAG, "NOTIFY ${ch.uuid} len=${bytes.size} data=$hex")

        val parsed = parseFrame(bytes) ?: return
        val (hr, spo2) = parsed
        hrState = hr.toString()
        spo2State = spo2.toString()

        val now = System.currentTimeMillis() / 1000L
        buffer.add(Triple(now, hr, spo2))

        if (currentSession != null) {
            Log.d(TAG, "Enqueueing data: HR=$hr, SpO2=$spo2")
            uploadChan.trySend(hr to spo2)
        } else {
            Log.w(TAG, "No active session, skipping upload of HR=$hr, SpO2=$spo2")
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

        try {
            FileWriter(csvFile(), true).use { w ->
                snapshot.forEach { (ts, hr, spo2) -> w.appendLine("$ts,$hr,$spo2") }
            }
            Log.d(TAG, "CSV appended ${snapshot.size} rows.")
        } catch (e: IOException) { Log.e(TAG, "CSV write failed", e) }

        val sid = currentSession?.id ?: return
        ioScope.launch {
            val rows = snapshot.map { (ts, hr, sp) ->
                HealthData(session_id = sid, timestamp = ts, pulse = hr, spo2 = sp)
            }
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

                val sid = currentSession?.id
                if (item != null && sid != null) {
                    val (pulse, spo2) = item
                    batch += HealthData(
                        session_id = sid,
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

    private suspend fun insertManyHealth(rows: List<HealthData>) {
        if (rows.isEmpty()) return
        try {
            Log.d(TAG, "Inserting ${rows.size} health records. First: ${rows.firstOrNull()}")
            SupabaseProvider.client
                .from("health_data")
                .insert(rows)
            Log.d(TAG, "Successfully inserted ${rows.size} health records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert health data: ${e.message}", e)
            throw e
        }
    }

    private fun safeCloseGatt() { runCatching { gatt?.close() }; gatt = null }
    private fun updateStatus(msg: String) { Log.d(TAG, msg); runOnUiThread { status = msg } }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        safeCloseGatt()
        writerJob?.cancel()
        bleJob?.cancel()
        uploaderJob?.cancel()
        ioScope.cancel()
        httpClient.close()
    }

    companion object {
        private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private var currentSession: SessionResponse? = null
    }
}
