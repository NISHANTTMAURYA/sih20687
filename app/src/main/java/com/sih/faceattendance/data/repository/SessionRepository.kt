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
}
