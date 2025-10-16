package com.example.testapplication_hrsp02.data

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val session_key: String
)

@Serializable
data class SessionResponse(
    val id: Long,
    val session_key: String
)

@Serializable
data class HealthData(
    val session_id: Long,
    val timestamp: Long,
    val spo2: Int,
    val pulse: Int
)