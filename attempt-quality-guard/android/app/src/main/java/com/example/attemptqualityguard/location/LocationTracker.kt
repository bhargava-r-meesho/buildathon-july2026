package com.example.attemptqualityguard.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class CapturedLocation(val lat: Double, val lng: Double, val accuracyMeters: Float)

/**
 * Thin wrapper around [FusedLocationProviderClient]. We only ever ask for a single
 * fresh fix at the moment of a call attempt or validation - no continuous location
 * updates, no background tracking.
 */
class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun captureCurrentLocation(): CapturedLocation? {
        if (!hasLocationPermission()) return null

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .build()

        val location: Location? = suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

            try {
                client.getCurrentLocation(request, cancellationTokenSource.token)
                    .addOnSuccessListener { loc -> continuation.resume(loc) }
                    .addOnFailureListener { continuation.resume(null) }
            } catch (securityException: SecurityException) {
                continuation.resume(null)
            }
        }

        return location?.let {
            CapturedLocation(lat = it.latitude, lng = it.longitude, accuracyMeters = it.accuracy)
        }
    }
}
