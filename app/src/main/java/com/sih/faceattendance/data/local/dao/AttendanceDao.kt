package com.sih.faceattendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getAllRecordsFlow(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE syncStatus = :status ORDER BY timestamp ASC")
    fun getRecordsByStatusFlow(status: SyncStatus): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE syncStatus = :status ORDER BY timestamp ASC")
    suspend fun getRecordsByStatus(status: SyncStatus): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE studentId = :studentId AND sessionId = :sessionId LIMIT 1")
    suspend fun getRecordForStudentInSession(studentId: String, sessionId: String): AttendanceRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: AttendanceRecordEntity)

    @Update
    suspend fun updateRecord(record: AttendanceRecordEntity)

    @Query("UPDATE attendance_records SET syncStatus = :newStatus, syncedAt = :syncedAt WHERE recordId IN (:recordIds)")
    suspend fun markRecordsSynced(recordIds: List<String>, newStatus: SyncStatus = SyncStatus.SYNCED, syncedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM attendance_records WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getRecordsForSessionFlow(sessionId: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT COUNT(*) FROM attendance_records WHERE syncStatus = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM attendance_records WHERE syncStatus = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM attendance_records")
    fun getTotalCountFlow(): Flow<Int>

    @Query("DELETE FROM attendance_records")
    suspend fun clearAllRecords()
}
