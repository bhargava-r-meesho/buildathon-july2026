package com.example.attemptqualityguard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.evaluation.AttemptEvaluator
import com.example.attemptqualityguard.model.AttemptUiState
import com.example.attemptqualityguard.model.SignalStatus

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_PHONE_NUMBERS,
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
            readPhoneNumbers = hasPermission(Manifest.permission.READ_PHONE_NUMBERS),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions() }

    // Reflect real OS permission state as soon as the screen first composes.
    LaunchedEffect(Unit) { refreshPermissions() }

    // Also re-check on every resume: a permission granted via Settings (or after
    // reinstalling) while this screen was already alive would otherwise never be
    // picked up, silently sending Call Customer down the ACTION_DIAL fallback path.
    // Resuming is also when we opportunistically retry anything still queued.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResume = rememberUpdatedState {
        refreshPermissions()
        viewModel.syncPendingLogs()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentOnResume.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    val buildLabel = remember {
        try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            "v${packageInfo.versionName} (build $versionCode)"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown build"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Attempt Quality Guard",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
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
            readPhoneNumbersGranted = state.readPhoneNumbersPermissionGranted,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        OutlinedTextField(
            value = state.phoneNumberInput,
            onValueChange = viewModel::onPhoneNumberChange,
            label = { Text("Customer phone number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

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

        val anyPermissionMissing = !state.callPhonePermissionGranted ||
            !state.readPhoneStatePermissionGranted ||
            !state.readPhoneNumbersPermissionGranted
        if (anyPermissionMissing) {
            TextButton(onClick = { openAppSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Nothing happens when you tap Request Permissions? Android only shows that " +
                        "popup a couple of times, then blocks it silently. Tap here to grant " +
                        "permissions from Settings instead.",
                )
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

        Text(
            text = buildLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 24.dp),
        )
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
        modifier = spacing,
    )

    val finalStatus = when (state.finalDecision) {
        AttemptEvaluator.VERIFIED -> SignalStatus.PASS
        AttemptEvaluator.NOT_VERIFIED -> SignalStatus.FAIL
        else -> SignalStatus.WAITING
    }
    SignalCard(
        label = "6. Final decision",
        status = finalStatus,
        detail = state.finalDecision?.let {
            if (state.missingSignals.isEmpty()) it else "$it — missing: ${state.missingSignals.joinToString(", ")}"
        },
        modifier = spacing,
    )
}
