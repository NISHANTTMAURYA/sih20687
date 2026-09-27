package com.sih.faceattendance.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "training_sessions")
data class SessionEntity(
    @PrimaryKey
    val sessionId: String, // e.g. "DL-01"
    val title: String, // e.g. "Digital Literacy"
    val batchCode: String,
    val startTime: String, // "09:00 AM"
    val endTime: String,   // "11:00 AM"
    val centerName: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val allowedRadiusMeters: Float = 100.0f,
    val isActive: Boolean = true
)
