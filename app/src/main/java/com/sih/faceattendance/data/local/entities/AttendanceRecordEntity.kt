package com.sih.faceattendance.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED
}

@Entity(tableName = "attendance_records")
data class AttendanceRecordEntity(
    @PrimaryKey
    val recordId: String = UUID.randomUUID().toString(),
    val studentId: String,
    val studentName: String,
    val sessionId: String,
    val sessionTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
    val similarityScore: Float,
    val livenessScore: Float,
    val latitude: Double,
    val longitude: Double,
    val isLocationValid: Boolean,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncedAt: Long? = null
)
