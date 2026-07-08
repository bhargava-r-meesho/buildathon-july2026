package com.example.attemptqualityguard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.evaluation.AttemptEvaluator
import com.example.attemptqualityguard.model.AttemptUiState
import com.example.attemptqualityguard.model.SignalStatus
import kotlin.math.roundToInt

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
fun AttemptQualityScreen(viewModel: AttemptViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun refreshPermissions() {
        viewModel.onPermissionsUpdated(
            callPhone = hasPermission(Manifest.permission.CALL_PHONE),
            readPhoneState = hasPermission(Manifest.permission.READ_PHONE_STATE),
            location = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions() }

    // Reflect real OS permission state as soon as the screen first composes.
    LaunchedEffect(Unit) { refreshPermissions() }

    var manualLocationExpanded by remember { mutableStateOf(false) }

    fun launchDirectCall(phoneNumber: String): Boolean = try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
        context.startActivity(intent)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun launchDialFallback(phoneNumber: String): Boolean = try {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(text = "Attempt Quality Guard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "Verifies whether a customer call was actually initiated from the app and " +
                "reached phone call state.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        if (!AppConfig.isConfigured()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    text = "Setup needed: paste your Apps Script Web App URL into AppConfig.kt " +
                        "before attempts can reach the Google Sheet. Events will queue locally " +
                        "until then.",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        PermissionCard(
            callPhoneGranted = state.callPhonePermissionGranted,
            readPhoneStateGranted = state.readPhoneStatePermissionGranted,
            locationGranted = state.locationPermissionGranted,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        OutlinedTextField(
            value = state.phoneNumberInput,
            onValueChange = viewModel::onPhoneNumberChange,
            label = { Text("Customer phone number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Use current location as demo customer location",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.useCurrentLocationAsCustomer,
                onCheckedChange = viewModel::onToggleUseCurrentLocationAsCustomer,
            )
        }

        TextButton(onClick = { manualLocationExpanded = !manualLocationExpanded }, modifier = Modifier.wrapContentWidth()) {
            Text(if (manualLocationExpanded) "Hide manual customer location" else "Manual customer location (advanced)")
        }
        if (manualLocationExpanded) {
            OutlinedTextField(
                value = state.manualCustomerLat,
                onValueChange = viewModel::onManualCustomerLatChange,
                label = { Text("Customer latitude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = state.manualCustomerLng,
                onValueChange = viewModel::onManualCustomerLngChange,
                label = { Text("Customer longitude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) }, modifier = Modifier.weight(1f)) {
                Text("Request Permissions")
            }
            Button(
                onClick = { viewModel.onCallCustomerTapped(::launchDirectCall, ::launchDialFallback) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Call Customer")
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::validateAttempt, modifier = Modifier.weight(1f)) {
                Text("Validate Attempt")
            }
            Button(onClick = viewModel::syncPendingLogs, modifier = Modifier.weight(1f)) {
                Text("Sync Pending Logs (${state.pendingSyncCount})")
            }
        }

        state.statusMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(text = "Live Signals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        SignalCards(state)

        Text(text = "Local Event Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (state.eventLog.isEmpty()) {
                    Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.eventLog.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCards(state: AttemptUiState) {
    val spacing = Modifier.padding(bottom = 8.dp)

    SignalCard(
        label = "1. Call initiated from app",
        status = when {
            state.callInitiatedFromApp -> SignalStatus.PASS
            state.fallbackMode -> SignalStatus.FAIL
            else -> SignalStatus.WAITING
        },
        detail = if (state.fallbackMode) "Fallback ACTION_DIAL was used instead." else null,
        modifier = spacing,
    )

    SignalCard(
        label = "2. Phone entered call state",
        status = if (state.callAttemptId == null) SignalStatus.WAITING
        else if (state.phoneEnteredCallState) SignalStatus.PASS else SignalStatus.WAITING,
        modifier = spacing,
    )

    SignalCard(
        label = "3. Call state duration",
        status = if (state.callStateDurationSec == null) SignalStatus.WAITING
        else if (state.callStateLasted15Sec == true) SignalStatus.PASS else SignalStatus.FAIL,
        detail = state.callStateDurationSec?.let { "${it}s" },
        modifier = spacing,
    )

    SignalCard(
        label = "4. Call state lasted at least 15s",
        status = when (state.callStateLasted15Sec) {
            true -> SignalStatus.PASS
            false -> SignalStatus.FAIL
            null -> SignalStatus.WAITING
        },
        modifier = spacing,
    )

    SignalCard(
        label = "5. Call happened within last 10 min",
        status = when (state.callHappenedWithinLast10Min) {
            true -> SignalStatus.PASS
            false -> SignalStatus.FAIL
            null -> SignalStatus.WAITING
        },
        detail = "Evaluated when you tap Validate Attempt.",
        modifier = spacing,
    )

    SignalCard(
        label = "6. Device location captured",
        status = if (state.locationCaptured) SignalStatus.PASS else SignalStatus.WAITING,
        detail = state.locationAccuracyM?.let { "Accuracy: ${it.roundToInt()}m" },
        modifier = spacing,
    )

    SignalCard(
        label = "7. Distance from customer location",
        status = when (state.feNearCustomerLocation) {
            true -> SignalStatus.PASS
            false -> SignalStatus.FAIL
            null -> SignalStatus.WAITING
        },
        detail = state.distanceToCustomerM?.let { "${it.roundToInt()}m" }
            ?: "Evaluated when you tap Validate Attempt.",
        modifier = spacing,
    )

    SignalCard(
        label = "8. FE near customer location",
        status = when (state.feNearCustomerLocation) {
            true -> SignalStatus.PASS
            false -> SignalStatus.FAIL
            null -> SignalStatus.WAITING
        },
        modifier = spacing,
    )

    val finalStatus = when (state.finalDecision) {
        AttemptEvaluator.VERIFIED -> SignalStatus.PASS
        AttemptEvaluator.NOT_VERIFIED -> SignalStatus.FAIL
        else -> SignalStatus.WAITING
    }
    SignalCard(
        label = "9. Final decision",
        status = finalStatus,
        detail = state.finalDecision?.let {
            if (state.missingSignals.isEmpty()) it else "$it — missing: ${state.missingSignals.joinToString(", ")}"
        },
        modifier = spacing,
    )
}
