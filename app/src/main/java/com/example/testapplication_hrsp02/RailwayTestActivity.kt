// Railway Connectivity Test App
package com.example.testapplication_hrsp02

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class RailwayTestActivity : ComponentActivity() {

    private val TAG = "RailwayConnTest"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 15000
        }
    }

    // Railway backend URL
    private val API_URL = "https://tele-oximeter-backend-development.up.railway.app"

    private var statusMessage by mutableStateOf("Ready to test")
    private var sessionKey by mutableStateOf("")
    private var testResults = mutableStateListOf<TestResult>()
    private var isTesting by mutableStateOf(false)

    data class TestResult(
        val endpoint: String,
        val status: String,
        val responseCode: Int?,
        val responseTime: Long,
        val timestamp: String,
        val details: String,
        val success: Boolean
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusMessage = "Internet permission granted"
        } else {
            statusMessage = "Internet permission required for connectivity tests"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RailwayTestScreen()
                }
            }
        }

        // Request internet permission (though it's usually granted by default)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            permissionLauncher.launch(Manifest.permission.INTERNET)
        }
    }

    @Composable
    fun RailwayTestScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Railway Backend Test",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Display Session Key if available
            if (sessionKey.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE3F2FD)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Session Key:",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            text = sessionKey,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFF0D47A1),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { createNewSession() },
                    enabled = !isTesting
                ) {
                    Text("Create Session")
                }

                Button(
                    onClick = { testBackendConnection() },
                    enabled = !isTesting
                ) {
                    Text("Test Connection")
                }

                Button(
                    onClick = { 
                        testResults.clear()
                        sessionKey = ""
                    },
                    enabled = !isTesting && (testResults.isNotEmpty() || sessionKey.isNotEmpty())
                ) {
                    Text("Clear")
                }
            }

            if (isTesting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )
            }

            Text(
                text = "Test Results (${testResults.size}):",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn {
                items(testResults) { result ->
                    TestResultCard(result)
                }
            }
        }
    }

    @Composable
    fun TestResultCard(result: TestResult) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (result.success) 
                    Color(0xFFE8F5E9) 
                else 
                    Color(0xFFFFEBEE)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = result.endpoint,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (result.success) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Status: ${result.status}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                result.responseCode?.let {
                    Text(
                        text = "HTTP Code: $it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                Text(
                    text = "Response Time: ${result.responseTime}ms",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "Time: ${result.timestamp}",
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (result.details.isNotEmpty()) {
                    Text(
                        text = "Details: ${result.details}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    private fun createNewSession() {
        isTesting = true
        statusMessage = "Creating new session..."
        
        ioScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                
                Log.d(TAG, "Requesting new session from: $API_URL/session/new")
                
                val response: HttpResponse = httpClient.post("$API_URL/session/new")
                
                val responseTime = System.currentTimeMillis() - startTime
                val statusCode = response.status.value
                val responseBody = response.bodyAsText()
                
                Log.d(TAG, "Response: $statusCode - $responseBody")
                
                if (response.status.isSuccess()) {
                    // Parse JSON response - simple manual parsing
                    val key = try {
                        // Extract session_key from JSON like {"session_key":"ABC123"}
                        val regex = """"session_key"\s*:\s*"([^"]+)"""".toRegex()
                        regex.find(responseBody)?.groupValues?.get(1) ?: ""
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse session key: ${e.message}")
                        ""
                    }
                    
                    withContext(Dispatchers.Main) {
                        sessionKey = key
                        statusMessage = if (key.isNotEmpty()) {
                            "Session created successfully!"
                        } else {
                            "Session created but key not found in response"
                        }
                        
                        testResults.add(0, TestResult(
                            endpoint = "/session/new",
                            status = "✓ Session Created",
                            responseCode = statusCode,
                            responseTime = responseTime,
                            timestamp = timestamp,
                            details = if (key.isNotEmpty()) "Session Key: $key" else responseBody,
                            success = true
                        ))
                        
                        isTesting = false
                    }
                    
                    Log.d(TAG, "Created new session: $key")
                } else {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Failed to create session: $statusCode"
                        
                        testResults.add(0, TestResult(
                            endpoint = "/session/new",
                            status = "✗ Failed",
                            responseCode = statusCode,
                            responseTime = responseTime,
                            timestamp = timestamp,
                            details = responseBody,
                            success = false
                        ))
                        
                        isTesting = false
                    }
                }
                
            } catch (e: Exception) {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                
                withContext(Dispatchers.Main) {
                    statusMessage = "Error creating session: ${e.message}"
                    
                    testResults.add(0, TestResult(
                        endpoint = "/session/new",
                        status = "✗ Error",
                        responseCode = null,
                        responseTime = 0,
                        timestamp = timestamp,
                        details = e.message ?: "Unknown error",
                        success = false
                    ))
                    
                    isTesting = false
                }
                
                Log.e(TAG, "Error creating session: ${e.message}", e)
            }
        }
    }

    private fun testBackendConnection() {
        isTesting = true
        statusMessage = "Testing backend connectivity..."
        
        ioScope.launch {
            // Test base URL
            testEndpoint(API_URL, "Base URL")
            delay(500)
            
            // Test session endpoint (GET)
            testEndpoint("$API_URL/session/new", "Session Endpoint")
            
            withContext(Dispatchers.Main) {
                isTesting = false
                val successful = testResults.count { it.success }
                val total = testResults.size
                statusMessage = "Testing complete: $successful/$total tests passed"
            }
        }
    }

    private suspend fun testEndpoint(url: String, label: String) {
        val startTime = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        try {
            Log.d(TAG, "Testing endpoint: $url")
            
            val response: HttpResponse = httpClient.get(url)
            
            val responseTime = System.currentTimeMillis() - startTime
            val statusCode = response.status.value
            val statusDescription = response.status.description
            
            val success = response.status.isSuccess()
            val status = if (success) "✓ Connected" else "✗ Failed"
            
            val result = TestResult(
                endpoint = label,
                status = status,
                responseCode = statusCode,
                responseTime = responseTime,
                timestamp = timestamp,
                details = statusDescription,
                success = success
            )
            
            withContext(Dispatchers.Main) {
                testResults.add(0, result)
            }
            
            Log.d(TAG, "Test result: $url - $statusCode ($responseTime ms)")
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            
            val result = TestResult(
                endpoint = label,
                status = "✗ Error",
                responseCode = null,
                responseTime = responseTime,
                timestamp = timestamp,
                details = e.message ?: "Unknown error",
                success = false
            )
            
            withContext(Dispatchers.Main) {
                testResults.add(0, result)
            }
            
            Log.e(TAG, "Test failed: $url - ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        httpClient.close()
    }
}
