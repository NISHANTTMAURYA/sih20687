package com.sih.faceattendance.data.repository

import com.sih.faceattendance.data.local.dao.StudentDao
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.ml.CosineSimilarity
import kotlinx.coroutines.flow.Flow

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
    suspend fun findDuplicateFace(newEmbedding: FloatArray, threshold: Float = 0.72f): Pair<StudentEntity, Float>? {
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

    suspend fun deleteStudent(studentId: String) {
        studentDao.deleteStudent(studentId)
    }

    suspend fun deleteAllStudents() {
        studentDao.deleteAllStudents()
    }

    suspend fun getCount(): Int = studentDao.getStudentCount()
}
