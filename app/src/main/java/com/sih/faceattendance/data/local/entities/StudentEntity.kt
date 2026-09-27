package com.sih.faceattendance.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey
    val studentId: String, // e.g. "NCCT1024"
    val name: String,
    val rollNumber: String,
    val course: String,
    val enrolledSessionIds: List<String>,
    val faceEmbedding: FloatArray, // 128-dimensional L2-normalized embedding
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StudentEntity
        if (studentId != other.studentId) return false
        if (!faceEmbedding.contentEquals(other.faceEmbedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = studentId.hashCode()
        result = 31 * result + faceEmbedding.contentHashCode()
        return result
    }
}
