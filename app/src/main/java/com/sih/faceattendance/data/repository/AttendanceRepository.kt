package com.sih.faceattendance.data.repository

import com.sih.faceattendance.data.local.dao.AttendanceDao
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SyncStatus
import com.sih.faceattendance.data.remote.AttendanceApiService
import com.sih.faceattendance.data.remote.dto.AttendanceItemDto
import com.sih.faceattendance.data.remote.dto.SyncRequestDto
import kotlinx.coroutines.flow.Flow

sealed class SyncResult {
    data class Success(val syncedCount: Int, val duplicatesIgnored: Int, val totalServerRecords: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
    object NoPendingRecords : SyncResult()
}

class AttendanceRepository(
    private val attendanceDao: AttendanceDao,
    private val apiService: AttendanceApiService
) {
    val allRecordsFlow: Flow<List<AttendanceRecordEntity>> = attendanceDao.getAllRecordsFlow()
    val pendingRecordsFlow: Flow<List<AttendanceRecordEntity>> = attendanceDao.getRecordsByStatusFlow(SyncStatus.PENDING)
    val pendingCountFlow: Flow<Int> = attendanceDao.getPendingCountFlow()
    val totalCountFlow: Flow<Int> = attendanceDao.getTotalCountFlow()

    fun getRecordsForSessionFlow(sessionId: String): Flow<List<AttendanceRecordEntity>> =
        attendanceDao.getRecordsForSessionFlow(sessionId)

    suspend fun getPendingCount(): Int = attendanceDao.getPendingCount()

    suspend fun recordAttendance(
        studentId: String,
        studentName: String,
        sessionId: String,
        sessionTitle: String,
        similarityScore: Float,
        livenessScore: Float,
        latitude: Double,
        longitude: Double,
        isLocationValid: Boolean,
        capturedFaceBase64: String? = null
    ): AttendanceRecordEntity {
        val record = AttendanceRecordEntity(
            studentId = studentId,
            studentName = studentName,
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            similarityScore = similarityScore,
            livenessScore = livenessScore,
            latitude = latitude,
            longitude = longitude,
            isLocationValid = isLocationValid,
            syncStatus = SyncStatus.PENDING,
            capturedFaceBase64 = capturedFaceBase64
        )
        attendanceDao.insertRecord(record)
        return record
    }

    suspend fun isAlreadyMarked(studentId: String, sessionId: String): Boolean {
        return attendanceDao.getRecordForStudentInSession(studentId, sessionId) != null
    }

    suspend fun syncPendingRecords(): SyncResult {
        val pending = attendanceDao.getRecordsByStatus(SyncStatus.PENDING)
        if (pending.isEmpty()) {
            return SyncResult.NoPendingRecords
        }

        val dtoList = pending.map {
            AttendanceItemDto(
                recordId = it.recordId,
                studentId = it.studentId,
                studentName = it.studentName,
                sessionId = it.sessionId,
                sessionTitle = it.sessionTitle,
                timestamp = it.timestamp,
                similarityScore = it.similarityScore,
                livenessScore = it.livenessScore,
                latitude = it.latitude,
                longitude = it.longitude,
                isLocationValid = it.isLocationValid,
                capturedFaceBase64 = it.capturedFaceBase64
            )
        }

        return try {
            val response = apiService.syncAttendance(SyncRequestDto(records = dtoList))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                // Mark locally as synced
                attendanceDao.markRecordsSynced(body.syncedRecordIds, SyncStatus.SYNCED)
                SyncResult.Success(
                    syncedCount = body.syncedCount,
                    duplicatesIgnored = body.duplicatesIgnored,
                    totalServerRecords = body.totalServerRecords
                )
            } else {
                SyncResult.Error("Server returned code ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            SyncResult.Error(e.localizedMessage ?: "Network connection failed. Device remains offline.")
        }
    }

    suspend fun clearHistory() {
        attendanceDao.clearAllRecords()
    }
}
