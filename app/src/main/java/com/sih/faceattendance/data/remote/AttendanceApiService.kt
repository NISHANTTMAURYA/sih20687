package com.sih.faceattendance.data.remote

import com.sih.faceattendance.data.remote.dto.HealthResponseDto
import com.sih.faceattendance.data.remote.dto.SyncRequestDto
import com.sih.faceattendance.data.remote.dto.SyncResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AttendanceApiService {

    @GET("health")
    suspend fun getHealth(): Response<HealthResponseDto>

    @POST("attendance/sync")
    suspend fun syncAttendance(@Body request: SyncRequestDto): Response<SyncResponseDto>
}
