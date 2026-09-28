package com.sih.faceattendance.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LocationHelper — Dual-provider GPS with full offline support.
 *
 * Uses two parallel location providers so GPS always works, internet or not:
 *
 * Provider 1: FusedLocationProviderClient (Google Play Services)
 *   - Fast when internet is available (uses A-GPS network assist for faster fix)
 *   - May be slow or stale when internet is off
 *
 * Provider 2: Android LocationManager → GPS_PROVIDER (raw satellite)
 *   - Works 100% offline, no internet needed at all
 *   - Slower cold start (~30s) but gives accurate fix from satellites alone
 *
 * Winner: whichever provider gives the most recent, most accurate fix wins.
 * This means turning off WiFi/mobile data will NOT break geofencing.
 *
 * IMPORTANT: GPS does NOT require internet. Only A-GPS (Assisted GPS) uses the
 * internet to download almanac data faster. Raw GPS satellites always work offline.
 * Android's GPS_PROVIDER uses raw satellites. NETWORK_PROVIDER uses WiFi/cell
 * for location — that's what breaks when internet is off.
 */
class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Initialized to 0.0, 0.0 — means "not acquired yet"
    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(0.0, 0.0))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    private val _accuracyMeters = MutableStateFlow<Float>(0.0f)
    val accuracyMeters: StateFlow<Float> = _accuracyMeters

    private val _isGpsActive = MutableStateFlow<Boolean>(false)
    val isGpsActive: StateFlow<Boolean> = _isGpsActive

    // Track age of last accepted fix so we prefer fresher readings
    private var lastFixTimeMs = 0L

    private var isTracking = false

    // ── Fused (Google Play Services) callback ────────────────────────────────
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            acceptLocationIfBetter(loc, source = "Fused")
        }

        override fun onLocationAvailability(avail: LocationAvailability) {
            // Don't set _isGpsActive = false here — the raw GPS provider may still be active
            if (avail.isLocationAvailable) {
                _isGpsActive.value = true
            }
        }
    }

    // ── Raw GPS_PROVIDER (satellite, fully offline) ──────────────────────────
    private val rawGpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            acceptLocationIfBetter(loc, source = "GPS_SATELLITE")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            _isGpsActive.value = true
        }
        override fun onProviderDisabled(provider: String) {
            // Only mark GPS inactive if BOTH providers are disabled
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                _isGpsActive.value = false
            }
        }
    }

    /**
     * Accepts a new location fix only if it is better than the currently stored fix.
     * "Better" means: more recent OR significantly more accurate.
     */
    private fun acceptLocationIfBetter(loc: Location, source: String) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return

        val ageMs = System.currentTimeMillis() - lastFixTimeMs
        val currentAccuracy = _accuracyMeters.value

        val isFresher = ageMs > 10_000L         // Current fix is >10 seconds old
        val isMoreAccurate = loc.accuracy < (currentAccuracy - 10f) // >10m more accurate
        val isFirstFix = _currentLocation.value.first == 0.0

        if (isFirstFix || isFresher || isMoreAccurate) {
            _currentLocation.value = Pair(loc.latitude, loc.longitude)
            _accuracyMeters.value = loc.accuracy
            _isGpsActive.value = true
            lastFixTimeMs = System.currentTimeMillis()
            Log.d("LocationHelper", "[$source] Fix accepted: ${loc.latitude}, ${loc.longitude} acc=${loc.accuracy}m")
        }
    }

    /**
     * Returns true if the hardware GPS chip is enabled (satellite GPS).
     * Does NOT check for NETWORK_PROVIDER — network location is separate from GPS
     * and requires internet/WiFi. Satellite GPS works fully offline.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if GPS hardware is switched on in device settings.
     * Only checks GPS_PROVIDER (satellite). NETWORK_PROVIDER is irrelevant for geofencing
     * — it uses WiFi/cell towers and breaks when internet is off.
     */
    fun isLocationServiceEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        // Check satellite GPS first. If GPS is off, fall back to checking network provider.
        // But we never REQUIRE network provider — it is unreliable without internet.
        return lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w("LocationHelper", "Cannot start location updates: permission missing")
            return
        }
        if (isTracking) return
        isTracking = true

        // ── Provider 1: Fused (fast, uses network assist when online) ──────
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)  // Don't wait — take first available fix
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())

            // Seed with last known location immediately (may be seconds or minutes old)
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _currentLocation.value.first == 0.0) {
                    acceptLocationIfBetter(loc, "Fused-LastKnown")
                }
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Fused provider error: ${e.message}")
        }

        // ── Provider 2: Raw satellite GPS (works 100% offline) ─────────────
        // This is the critical fallback. When internet is off, the Fused provider
        // may become slow or stale. Raw GPS_PROVIDER always works from satellites alone.
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,       // minimum 1.5 seconds between updates
                    0.5f,        // minimum 0.5 meters movement
                    rawGpsListener,
                    Looper.getMainLooper()
                )
                Log.d("LocationHelper", "Raw GPS_PROVIDER started (offline-capable)")

                // Seed with last known GPS fix if we have no location yet
                val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastGps != null && _currentLocation.value.first == 0.0) {
                    acceptLocationIfBetter(lastGps, "GPS-LastKnown")
                }
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Raw GPS provider error: ${e.message}")
        }

        // ── Provider 3: Network provider (fast but only when online) ────────
        // Register as third fallback — gives a coarse location quickly while
        // GPS satellite fix is being acquired. Silently ignored if offline.
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    5f,
                    rawGpsListener,
                    Looper.getMainLooper()
                )
                Log.d("LocationHelper", "NETWORK_PROVIDER started (online assist)")
            }
        } catch (e: Exception) {
            Log.d("LocationHelper", "NETWORK_PROVIDER not available (offline): ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        if (isTracking) {
            try {
                fusedLocationClient.removeLocationUpdates(fusedCallback)
            } catch (_: Exception) {}
            try {
                locationManager.removeUpdates(rawGpsListener)
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
