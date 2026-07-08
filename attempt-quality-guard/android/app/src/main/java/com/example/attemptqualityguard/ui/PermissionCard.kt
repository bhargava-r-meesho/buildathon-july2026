package com.example.attemptqualityguard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shows exactly which permissions are granted/denied and, in plain language,
 * which signal becomes unavailable when a permission is missing.
 */
@Composable
fun PermissionCard(
    callPhoneGranted: Boolean,
    readPhoneStateGranted: Boolean,
    readPhoneNumbersGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Permissions", style = MaterialTheme.typography.titleMedium)

            PermissionRow(
                label = "Call Phone",
                granted = callPhoneGranted,
                consequenceIfDenied = "Without it, tapping Call Customer only opens the dialer " +
                    "(ACTION_DIAL) instead of placing the call directly, and the attempt can " +
                    "never be VERIFIED.",
            )
            PermissionRow(
                label = "Read Phone State",
                granted = readPhoneStateGranted,
                consequenceIfDenied = "Without it, we cannot detect that the phone entered a " +
                    "call state, so that signal cannot be verified.",
            )
            PermissionRow(
                label = "Read Phone Numbers",
                granted = readPhoneNumbersGranted,
                consequenceIfDenied = "Without it, the attempt can't be attributed to this " +
                    "device's own number (fe_phone_number will be blank).",
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, consequenceIfDenied: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "$label: ${if (granted) "Granted" else "Not granted"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!granted) {
            Text(text = consequenceIfDenied, style = MaterialTheme.typography.bodySmall)
        }
    }
}
