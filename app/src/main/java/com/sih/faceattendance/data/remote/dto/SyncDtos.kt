package com.sih.faceattendance.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AttendanceItemDto(
    @SerializedName("recordId") val recordId: String,
    @SerializedName("studentId") val studentId: String,
    @SerializedName("studentName") val studentName: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("sessionTitle") val sessionTitle: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("similarityScore") val similarityScore: Float,
    @SerializedName("livenessScore") val livenessScore: Float,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("isLocationValid") val isLocationValid: Boolean
)

data class SyncRequestDto(
    @SerializedName("deviceId") val deviceId: String = "ANDROID-OFFLINE-01",
    @SerializedName("records") val records: List<AttendanceItemDto>
)

data class SyncResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("syncedCount") val syncedCount: Int,
    @SerializedName("duplicatesIgnored") val duplicatesIgnored: Int,
    @SerializedName("totalServerRecords") val totalServerRecords: Int,
    @SerializedName("syncedRecordIds") val syncedRecordIds: List<String>,
    @SerializedName("serverTime") val serverTime: String
)

data class HealthResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("service") val service: String,
    @SerializedName("offlineSupport") val offlineSupport: Boolean
)
