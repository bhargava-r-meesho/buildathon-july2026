package com.example.attemptqualityguard.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Best-effort read of this device's own SIM number, used to attribute an attempt
 * to the FE placing it. Many carriers/SIMs never populate this - a null/blank
 * result is expected and handled gracefully, not treated as an error.
 */
object FePhoneNumber {

    fun read(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return try {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            telephonyManager.line1Number?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: SecurityException) {
            null
        }
    }
}
