package com.sih.faceattendance.data.remote

import com.sih.faceattendance.data.remote.dto.HealthResponseDto
import com.sih.faceattendance.data.remote.dto.RosterVersionDto
import com.sih.faceattendance.data.remote.dto.SessionDto
import com.sih.faceattendance.data.remote.dto.SyncRequestDto
import com.sih.faceattendance.data.remote.dto.SyncResponseDto
import com.sih.faceattendance.data.remote.dto.StudentSyncItemDto
import com.sih.faceattendance.data.remote.dto.StudentSyncRequestDto
import com.sih.faceattendance.data.remote.dto.StudentSyncResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface AttendanceApiService {

    @GET("health")
    suspend fun getHealth(): Response<HealthResponseDto>

    @GET("sessions")
    suspend fun getSessions(): Response<List<SessionDto>>

    @POST("api/phone-location")
    suspend fun reportPhoneLocation(@Body request: com.sih.faceattendance.data.remote.dto.PhoneLocationReportDto): Response<com.sih.faceattendance.data.remote.dto.PhoneLocationResponseDto>

    @POST("attendance/sync")
    suspend fun syncAttendance(@Body request: SyncRequestDto): Response<SyncResponseDto>

    @GET("api/roster/version")
    suspend fun getRosterVersion(): Response<RosterVersionDto>

    @GET("api/students")
    suspend fun getAllStudents(): Response<List<StudentSyncItemDto>>

    @POST("api/students/sync")
    suspend fun syncStudents(@Body request: StudentSyncRequestDto): Response<StudentSyncResponseDto>

    @DELETE("api/students/{studentId}")
    suspend fun deleteStudent(@Path("studentId") studentId: String): Response<Map<String, Any>>
}

