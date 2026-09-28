package com.sih.faceattendance.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Initialized to 0.0, 0.0 (Acquiring live hardware GPS - NO hardcoded coordinates)
    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(0.0, 0.0))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    private val _accuracyMeters = MutableStateFlow<Float>(0.0f)
    val accuracyMeters: StateFlow<Float> = _accuracyMeters

    private val _isGpsActive = MutableStateFlow<Boolean>(false)
    val isGpsActive: StateFlow<Boolean> = _isGpsActive

    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.latitude != 0.0 && loc.longitude != 0.0) {
                _currentLocation.value = Pair(loc.latitude, loc.longitude)
                _accuracyMeters.value = loc.accuracy
                _isGpsActive.value = true
                Log.d("LocationHelper", "Real GPS received: ${loc.latitude}, ${loc.longitude} (acc: ${loc.accuracy}m)")
            }
        }

        override fun onLocationAvailability(avail: LocationAvailability) {
            _isGpsActive.value = avail.isLocationAvailable
        }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationServiceEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
               lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w("LocationHelper", "Cannot start location updates: permission missing")
            return
        }
        if (isTracking) return
        isTracking = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

            // Also fetch immediate last known or current location
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && loc.latitude != 0.0 && _currentLocation.value.first == 0.0) {
                    _currentLocation.value = Pair(loc.latitude, loc.longitude)
                    _accuracyMeters.value = loc.accuracy
                    _isGpsActive.value = true
                }
            }
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                if (loc != null && loc.latitude != 0.0) {
                    _currentLocation.value = Pair(loc.latitude, loc.longitude)
                    _accuracyMeters.value = loc.accuracy
                    _isGpsActive.value = true
                }
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error requesting location updates: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        if (isTracking) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: Exception) {}
            isTracking = false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun updateLocation(): Pair<Double, Double> {
        if (!hasLocationPermission()) return _currentLocation.value
        startLocationUpdates()
        return _currentLocation.value
    }

    fun setSimulatedLocation(lat: Double, lon: Double) {
        _currentLocation.value = Pair(lat, lon)
    }
}
