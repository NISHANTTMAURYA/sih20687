package com.sih.faceattendance.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    // Default to the host Wi-Fi LAN IP so physical devices connect immediately
    private var currentBaseUrl = "http://192.168.1.39:8000/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private var retrofit: Retrofit = buildRetrofit(currentBaseUrl)

    var apiService: AttendanceApiService = retrofit.create(AttendanceApiService::class.java)
        private set

    fun updateBaseUrl(newUrl: String) {
        val formatted = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        currentBaseUrl = formatted
        retrofit = buildRetrofit(formatted)
        apiService = retrofit.create(AttendanceApiService::class.java)
    }

    fun getBaseUrl(): String = currentBaseUrl

    private fun buildRetrofit(url: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
