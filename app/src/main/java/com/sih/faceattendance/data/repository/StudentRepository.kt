package com.sih.faceattendance.data.repository

import android.content.Context
import android.util.Base64
import com.sih.faceattendance.data.local.dao.StudentDao
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.data.remote.AttendanceApiService
import com.sih.faceattendance.data.remote.dto.StudentSyncItemDto
import com.sih.faceattendance.data.remote.dto.StudentSyncRequestDto
import com.sih.faceattendance.ml.CosineSimilarity
import kotlinx.coroutines.flow.Flow
import java.io.File

sealed class StudentSyncResult {
    data class Success(val pushedCount: Int, val pulledCount: Int, val totalCount: Int) : StudentSyncResult()
    data class Error(val message: String) : StudentSyncResult()
}


class StudentRepository(
    private val studentDao: StudentDao
) {
    val allStudentsFlow: Flow<List<StudentEntity>> = studentDao.getAllStudentsFlow()

    suspend fun getAllStudents(): List<StudentEntity> = studentDao.getAllStudents()

    suspend fun getStudentById(id: String): StudentEntity? = studentDao.getStudentById(id)

    /**
     * Checks if a given face embedding matches an already registered student in the database.
     * Prevents duplicate face registrations under different IDs or names.
     */
    suspend fun findDuplicateFace(newEmbedding: FloatArray, threshold: Float = 0.55f): Pair<StudentEntity, Float>? {
        val allStudents = studentDao.getAllStudents()
        var highestMatch: StudentEntity? = null
        var maxSimilarity = -1.0f

        for (student in allStudents) {
            val similarity = CosineSimilarity.compute(newEmbedding, student.faceEmbedding)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                highestMatch = student
            }
        }

        return if (highestMatch != null && maxSimilarity >= threshold) {
            Pair(highestMatch, maxSimilarity)
        } else {
            null
        }
    }

    suspend fun enrollStudent(
        studentId: String,
        name: String,
        rollNumber: String,
        course: String,
        enrolledSessionIds: List<String>,
        faceEmbedding: FloatArray,
        photoUri: String? = null
    ): StudentEntity {
        val student = StudentEntity(
            studentId = studentId,
            name = name,
            rollNumber = rollNumber,
            course = course,
            enrolledSessionIds = enrolledSessionIds,
            faceEmbedding = faceEmbedding,
            photoUri = photoUri,
            createdAt = System.currentTimeMillis()
        )
        studentDao.insertStudent(student)
        return student
    }

    suspend fun updateStudentSessions(studentId: String, newSessionIds: List<String>, newCourse: String? = null): StudentEntity? {
        val student = studentDao.getStudentById(studentId) ?: return null
        val updated = student.copy(
            enrolledSessionIds = (student.enrolledSessionIds + newSessionIds).distinct(),
            course = if (newCourse != null) "${student.course}, $newCourse" else student.course
        )
        studentDao.insertStudent(updated)
        return updated
    }

    suspend fun deleteStudent(
        studentId: String,
        apiService: AttendanceApiService? = null,
        context: Context? = null
    ) {
        studentDao.deleteStudent(studentId)

        // Record in pending deletions so sync doesn't resurrect it
        if (context != null) {
            val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
            val currentPending = prefs.getStringSet("pending_deletions", emptySet())?.toMutableSet() ?: mutableSetOf()
            currentPending.add(studentId)
            prefs.edit().putStringSet("pending_deletions", currentPending).commit()
        }

        // Attempt direct delete on server if network is available
        if (apiService != null) {
            try {
                val res = apiService.deleteStudent(studentId)
                if (res.isSuccessful && context != null) {
                    val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
                    val currentPending = prefs.getStringSet("pending_deletions", emptySet())?.toMutableSet() ?: mutableSetOf()
                    currentPending.remove(studentId)
                    prefs.edit().putStringSet("pending_deletions", currentPending).commit()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteAllStudents(
        allIds: List<String> = emptyList(),
        apiService: AttendanceApiService? = null,
        context: Context? = null
    ) {
        studentDao.deleteAllStudents()

        if (context != null && allIds.isNotEmpty()) {
            val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
            val currentPending = prefs.getStringSet("pending_deletions", emptySet())?.toMutableSet() ?: mutableSetOf()
            currentPending.addAll(allIds)
            prefs.edit().putStringSet("pending_deletions", currentPending).commit()
        }

        if (apiService != null) {
            for (id in allIds) {
                try {
                    apiService.deleteStudent(id)
                } catch (_: Exception) {}
            }
            if (context != null) {
                val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("pending_deletions").commit()
            }
        }
    }

    fun clearPendingDeletions(context: Context) {
        val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("pending_deletions").commit()
    }

    suspend fun getCount(): Int = studentDao.getStudentCount()

    suspend fun syncWithServer(apiService: AttendanceApiService, context: Context): StudentSyncResult {
        return try {
            val prefs = context.getSharedPreferences("student_sync_prefs", Context.MODE_PRIVATE)
            val pendingDeletions = prefs.getStringSet("pending_deletions", emptySet())?.toSet() ?: emptySet()

            val localStudents = studentDao.getAllStudents().filter { !pendingDeletions.contains(it.studentId) }
            val dtos = localStudents.map { s ->
                var b64Photo: String? = null
                if (!s.photoUri.isNullOrEmpty() && !s.photoUri.startsWith("students/")) {
                    try {
                        val file = File(s.photoUri)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            b64Photo = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    } catch (_: Exception) {}
                }
                StudentSyncItemDto(
                    studentId = s.studentId,
                    name = s.name,
                    rollNumber = s.rollNumber,
                    course = s.course,
                    enrolledSessionIds = s.enrolledSessionIds,
                    faceEmbedding = s.faceEmbedding.toList(),
                    photoBase64 = b64Photo
                )
            }

            val request = StudentSyncRequestDto(
                deviceId = "ANDROID-OFFLINE-01",
                students = dtos,
                deletedStudentIds = pendingDeletions.toList()
            )

            val response = apiService.syncStudents(request)
            if (!response.isSuccessful || response.body() == null) {
                return StudentSyncResult.Error("Server returned code ${response.code()}: ${response.message()}")
            }

            val body = response.body()!!

            // Clear acknowledged pending deletions
            if (pendingDeletions.isNotEmpty()) {
                val updatedPending = prefs.getStringSet("pending_deletions", emptySet())?.toMutableSet() ?: mutableSetOf()
                updatedPending.removeAll(pendingDeletions)
                prefs.edit().putStringSet("pending_deletions", updatedPending).commit()
            }

            // Remove any students locally that were deleted on the server or pending delete
            val allDeletedIds = (body.deletedStudentIds + pendingDeletions).toSet()
            for (delId in allDeletedIds) {
                studentDao.deleteStudent(delId)
            }

            var pulledCount = 0
            val existingMap = studentDao.getAllStudents().associateBy { it.studentId }

            for (srv in body.serverStudents) {
                if (allDeletedIds.contains(srv.studentId)) {
                    continue
                }

                val existing = existingMap[srv.studentId]
                if (existing == null) {
                    var localPhotoPath: String? = null
                    if (!srv.photoBase64.isNullOrEmpty()) {
                        try {
                            val dir = File(context.filesDir, "enrolled_students")
                            if (!dir.exists()) dir.mkdirs()
                            val pFile = File(dir, "${srv.studentId}.jpg")
                            val bytes = Base64.decode(srv.photoBase64, Base64.DEFAULT)
                            pFile.writeBytes(bytes)
                            localPhotoPath = pFile.absolutePath
                        } catch (_: Exception) {}
                    }
                    if (localPhotoPath == null) {
                        localPhotoPath = "students/${srv.studentId}.jpg"
                    }

                    val newEntity = StudentEntity(
                        studentId = srv.studentId,
                        name = srv.name,
                        rollNumber = srv.rollNumber,
                        course = srv.course,
                        enrolledSessionIds = srv.enrolledSessionIds,
                        faceEmbedding = srv.faceEmbedding.toFloatArray(),
                        photoUri = localPhotoPath,
                        createdAt = System.currentTimeMillis()
                    )
                    studentDao.insertStudent(newEntity)
                    pulledCount++
                } else {
                    var updated = existing
                    var changed = false
                    val combinedSessions = (existing.enrolledSessionIds + srv.enrolledSessionIds).distinct()
                    if (combinedSessions.size != existing.enrolledSessionIds.size) {
                        updated = updated.copy(enrolledSessionIds = combinedSessions)
                        changed = true
                    }
                    if (srv.faceEmbedding.isNotEmpty() && !srv.faceEmbedding.toFloatArray().contentEquals(existing.faceEmbedding)) {
                        updated = updated.copy(faceEmbedding = srv.faceEmbedding.toFloatArray())
                        changed = true
                    }
                    if (!srv.photoBase64.isNullOrEmpty()) {
                        try {
                            val dir = File(context.filesDir, "enrolled_students")
                            if (!dir.exists()) dir.mkdirs()
                            val pFile = File(dir, "${srv.studentId}.jpg")
                            val bytes = Base64.decode(srv.photoBase64, Base64.DEFAULT)
                            pFile.writeBytes(bytes)
                            if (updated.photoUri != pFile.absolutePath) {
                                updated = updated.copy(photoUri = pFile.absolutePath)
                                changed = true
                            }
                        } catch (_: Exception) {}
                    }
                    if (srv.name.isNotBlank() && srv.name != existing.name) {
                        updated = updated.copy(name = srv.name)
                        changed = true
                    }
                    if (srv.rollNumber.isNotBlank() && srv.rollNumber != existing.rollNumber) {
                        updated = updated.copy(rollNumber = srv.rollNumber)
                        changed = true
                    }
                    if (srv.course.isNotBlank() && srv.course != existing.course) {
                        updated = updated.copy(course = srv.course)
                        changed = true
                    }
                    if (changed) {
                        studentDao.insertStudent(updated)
                        pulledCount++
                    }
                }
            }

            try {
                val verResp = apiService.getRosterVersion()
                if (verResp.isSuccessful && verResp.body() != null) {
                    prefs.edit().putLong("last_roster_version", verResp.body()!!.rosterVersion).apply()
                }
            } catch (_: Exception) {}

            StudentSyncResult.Success(
                pushedCount = body.syncedFromApp,
                pulledCount = pulledCount,
                totalCount = studentDao.getStudentCount()
            )
        } catch (e: Exception) {
            StudentSyncResult.Error("Network error: ${e.localizedMessage ?: e.message}")
        }
    }
}
