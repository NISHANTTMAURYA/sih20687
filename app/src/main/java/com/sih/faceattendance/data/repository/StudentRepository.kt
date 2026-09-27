package com.sih.faceattendance.data.repository

import com.sih.faceattendance.data.local.dao.StudentDao
import com.sih.faceattendance.data.local.entities.StudentEntity
import kotlinx.coroutines.flow.Flow

class StudentRepository(
    private val studentDao: StudentDao
) {
    val allStudentsFlow: Flow<List<StudentEntity>> = studentDao.getAllStudentsFlow()

    suspend fun getAllStudents(): List<StudentEntity> = studentDao.getAllStudents()

    suspend fun getStudentById(id: String): StudentEntity? = studentDao.getStudentById(id)

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

    suspend fun deleteStudent(studentId: String) {
        studentDao.deleteStudent(studentId)
    }

    suspend fun getCount(): Int = studentDao.getStudentCount()
}
