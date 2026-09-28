package com.sih.faceattendance.data.repository

import com.sih.faceattendance.data.local.dao.SessionDao
import com.sih.faceattendance.data.local.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val sessionDao: SessionDao
) {
    val activeSessionsFlow: Flow<List<SessionEntity>> = sessionDao.getActiveSessionsFlow()

    suspend fun getSessionById(sessionId: String): SessionEntity? =
        sessionDao.getSessionById(sessionId)

    suspend fun getAllSessions(): List<SessionEntity> =
        sessionDao.getAllSessions()

    suspend fun insertSession(session: SessionEntity) {
        sessionDao.insertSession(session)
    }

    suspend fun updateSessionLocation(sessionId: String, latitude: Double, longitude: Double, centerName: String? = null): SessionEntity? {
        val session = sessionDao.getSessionById(sessionId) ?: return null
        val updated = session.copy(
            centerLatitude = latitude,
            centerLongitude = longitude,
            centerName = centerName ?: session.centerName
        )
        sessionDao.insertSession(updated)
        return updated
    }

    suspend fun syncSessionsFromServer(apiService: com.sih.faceattendance.data.remote.AttendanceApiService): Result<Int> {
        return try {
            val response = apiService.getSessions()
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                for (dto in list) {
                    val existing = sessionDao.getSessionById(dto.sessionId)
                    val entity = SessionEntity(
                        sessionId = dto.sessionId,
                        title = dto.title,
                        batchCode = dto.batchCode,
                        startTime = dto.startTime,
                        endTime = dto.endTime,
                        centerName = dto.centerName,
                        centerLatitude = dto.centerLatitude,
                        centerLongitude = dto.centerLongitude,
                        allowedRadiusMeters = dto.allowedRadiusMeters,
                        isActive = dto.isActive
                    )
                    sessionDao.insertSession(entity)
                }
                Result.success(list.size)
            } else {
                Result.failure(Exception("Failed to fetch sessions: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
