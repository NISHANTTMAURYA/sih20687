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
    @SerializedName("isLocationValid") val isLocationValid: Boolean,
    @SerializedName("capturedFaceBase64") val capturedFaceBase64: String? = null
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

data class SessionDto(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("title") val title: String,
    @SerializedName("batchCode") val batchCode: String,
    @SerializedName("startTime") val startTime: String,
    @SerializedName("endTime") val endTime: String,
    @SerializedName("centerName") val centerName: String,
    @SerializedName("centerLatitude") val centerLatitude: Double,
    @SerializedName("centerLongitude") val centerLongitude: Double,
    @SerializedName("allowedRadiusMeters") val allowedRadiusMeters: Float = 100.0f,
    @SerializedName("locationAddress") val locationAddress: String? = null,
    @SerializedName("isActive") val isActive: Boolean = true
)

data class PhoneLocationReportDto(
    @SerializedName("deviceId") val deviceId: String = "ANDROID-OFFLINE-01",
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("provider") val provider: String = "gps"
)

data class PhoneLocationResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("address") val address: String? = null,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

data class StudentSyncItemDto(
    @SerializedName("studentId") val studentId: String,
    @SerializedName("name") val name: String,
    @SerializedName("rollNumber") val rollNumber: String,
    @SerializedName("course") val course: String,
    @SerializedName("enrolledSessionIds") val enrolledSessionIds: List<String>,
    @SerializedName("faceEmbedding") val faceEmbedding: List<Float>,
    @SerializedName("photoBase64") val photoBase64: String? = null
)

data class StudentSyncRequestDto(
    @SerializedName("deviceId") val deviceId: String = "ANDROID-OFFLINE-01",
    @SerializedName("students") val students: List<StudentSyncItemDto> = emptyList(),
    @SerializedName("deletedStudentIds") val deletedStudentIds: List<String> = emptyList()
)

data class StudentSyncResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("syncedFromApp") val syncedFromApp: Int,
    @SerializedName("totalServerStudents") val totalServerStudents: Int,
    @SerializedName("serverStudents") val serverStudents: List<StudentSyncItemDto>,
    @SerializedName("deletedStudentIds") val deletedStudentIds: List<String> = emptyList()
)

data class RosterVersionDto(
    @SerializedName("status") val status: String,
    @SerializedName("rosterVersion") val rosterVersion: Long,
    @SerializedName("studentCount") val studentCount: Int,
    @SerializedName("timestamp") val timestamp: Long
)


