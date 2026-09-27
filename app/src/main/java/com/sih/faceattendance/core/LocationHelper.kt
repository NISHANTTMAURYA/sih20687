package com.sih.faceattendance.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Default mock training center GPS: 19.0760° N, 72.8777° E (NCCT Training Centre)
    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(19.0760, 72.8777))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    @SuppressLint("MissingPermission")
    suspend fun updateLocation(): Pair<Double, Double> {
        return suspendCancellableCoroutine { cont ->
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            _currentLocation.value = Pair(location.latitude, location.longitude)
                        }
                        cont.resume(_currentLocation.value)
                    }
                    .addOnFailureListener {
                        cont.resume(_currentLocation.value)
                    }
            } catch (_: Exception) {
                cont.resume(_currentLocation.value)
            }
        }
    }

    fun setSimulatedLocation(lat: Double, lon: Double) {
        _currentLocation.value = Pair(lat, lon)
    }
}
