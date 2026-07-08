package com.example.attemptqualityguard.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.evaluation.AttemptEvaluator
import com.example.attemptqualityguard.location.LocationTracker
import com.example.attemptqualityguard.logging.SheetLogger
import com.example.attemptqualityguard.model.AttemptSummary
import com.example.attemptqualityguard.model.AttemptUiState
import com.example.attemptqualityguard.model.EventName
import com.example.attemptqualityguard.model.RawEvent
import com.example.attemptqualityguard.telephony.CallStateTracker
import com.example.attemptqualityguard.util.AppInstallId
import com.example.attemptqualityguard.util.PhoneMasking
import com.example.attemptqualityguard.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AttemptViewModel(application: Application) : AndroidViewModel(application) {

    private val locationTracker = LocationTracker(application)
    private val callStateTracker = CallStateTracker(application)
    private val sheetLogger = SheetLogger(application)
    private val appInstallId = AppInstallId.get(application)

    private val _uiState = MutableStateFlow(AttemptUiState(pendingSyncCount = sheetLogger.pendingCount))
    val uiState: StateFlow<AttemptUiState> = _uiState.asStateFlow()

    private val callStateListener = object : CallStateTracker.Listener {
        override fun onCallStateOffhook() {
            viewModelScope.launch {
                val startedAt = TimeUtils.nowMs()
                _uiState.update { it.copy(phoneEnteredCallState = true, callStateStartedAtMs = startedAt) }
                logEvent(EventName.CALL_STATE_OFFHOOK, phoneState = "OFFHOOK")
            }
        }

        override fun onCallStateIdleAfterOffhook() {
            viewModelScope.launch {
                val endedAt = TimeUtils.nowMs()
                val startedAt = _uiState.value.callStateStartedAtMs
                val durationSec = if (startedAt != null) TimeUtils.secondsBetween(startedAt, endedAt) else null
                val lasted15 = durationSec != null && durationSec >= AppConfig.MIN_CALL_STATE_DURATION_SECONDS

                _uiState.update {
                    it.copy(
                        callStateEndedAtMs = endedAt,
                        callStateDurationSec = durationSec,
                        callStateLasted15Sec = lasted15,
                    )
                }
                logEvent(EventName.CALL_STATE_IDLE, phoneState = "IDLE")
                logEvent(EventName.CALL_DURATION_COMPUTED, callStateDurationSec = durationSec)
            }
        }

        override fun onTrackingTimedOut() {
            appendEventLog("Call-state tracking timed out with no IDLE transition observed.")
        }
    }

    fun onPhoneNumberChange(value: String) {
        _uiState.update { it.copy(phoneNumberInput = value) }
    }

    fun onToggleUseCurrentLocationAsCustomer(value: Boolean) {
        _uiState.update { it.copy(useCurrentLocationAsCustomer = value) }
    }

    fun onManualCustomerLatChange(value: String) {
        _uiState.update { it.copy(manualCustomerLat = value) }
    }

    fun onManualCustomerLngChange(value: String) {
        _uiState.update { it.copy(manualCustomerLng = value) }
    }

    fun onPermissionsUpdated(callPhone: Boolean, readPhoneState: Boolean, location: Boolean) {
        _uiState.update {
            it.copy(
                callPhonePermissionGranted = callPhone,
                readPhoneStatePermissionGranted = readPhoneState,
                locationPermissionGranted = location,
            )
        }
    }

    /**
     * Orchestrates one call attempt. [launchDirectCall] and [launchDialFallback] are provided by
     * the Activity/Composable because firing an Intent needs an Activity Context; this ViewModel
     * only decides *which* one to use and records the outcome.
     */
    fun onCallCustomerTapped(
        launchDirectCall: (String) -> Boolean,
        launchDialFallback: (String) -> Boolean,
    ) {
        val phoneNumber = _uiState.value.phoneNumberInput
        if (!PhoneMasking.isPlausiblePhoneNumber(phoneNumber)) {
            _uiState.update { it.copy(statusMessage = "Enter a valid phone number first.") }
            return
        }

        viewModelScope.launch {
            val attemptId = UUID.randomUUID().toString()
            val masked = PhoneMasking.mask(phoneNumber)
            val hash = PhoneMasking.sha256Hash(phoneNumber)

            _uiState.update {
                AttemptUiState(
                    phoneNumberInput = it.phoneNumberInput,
                    useCurrentLocationAsCustomer = it.useCurrentLocationAsCustomer,
                    manualCustomerLat = it.manualCustomerLat,
                    manualCustomerLng = it.manualCustomerLng,
                    callPhonePermissionGranted = it.callPhonePermissionGranted,
                    readPhoneStatePermissionGranted = it.readPhoneStatePermissionGranted,
                    locationPermissionGranted = it.locationPermissionGranted,
                    callAttemptId = attemptId,
                    phoneNumberMasked = masked,
                    phoneNumberHash = hash,
                    eventLog = it.eventLog,
                    pendingSyncCount = it.pendingSyncCount,
                )
            }

            logEvent(EventName.CALL_CTA_CLICKED)

            val captured = locationTracker.captureCurrentLocation()
            if (captured != null) {
                val (customerLat, customerLng) = resolveCustomerLocation(
                    useCurrentAsCustomer = _uiState.value.useCurrentLocationAsCustomer,
                    feLat = captured.lat,
                    feLng = captured.lng,
                    manualLatText = _uiState.value.manualCustomerLat,
                    manualLngText = _uiState.value.manualCustomerLng,
                )
                _uiState.update {
                    it.copy(
                        feLat = captured.lat,
                        feLng = captured.lng,
                        locationAccuracyM = captured.accuracyMeters,
                        locationCaptured = true,
                        customerLat = customerLat,
                        customerLng = customerLng,
                    )
                }
                logEvent(EventName.LOCATION_CAPTURED_BEFORE_CALL)
            } else {
                appendEventLog("Could not capture location before the call (permission denied or no fix).")
            }

            val initiatedAt = TimeUtils.nowMs()
            if (_uiState.value.callPhonePermissionGranted) {
                val fired = launchDirectCall(phoneNumber)
                if (fired) {
                    _uiState.update {
                        it.copy(callInitiatedFromApp = true, fallbackMode = false, callInitiatedAtMs = initiatedAt)
                    }
                    logEvent(EventName.DIRECT_CALL_INTENT_FIRED)
                    if (_uiState.value.readPhoneStatePermissionGranted) {
                        callStateTracker.startTracking(callStateListener)
                    } else {
                        appendEventLog("READ_PHONE_STATE not granted: cannot observe call state.")
                    }
                } else {
                    logEvent(EventName.DIRECT_CALL_INTENT_FAILED)
                    _uiState.update { it.copy(statusMessage = "Could not start the call.") }
                }
            } else {
                val dialed = launchDialFallback(phoneNumber)
                _uiState.update { it.copy(callInitiatedFromApp = false, fallbackMode = true) }
                if (dialed) {
                    logEvent(EventName.DIRECT_CALL_INTENT_FAILED, metadataJson = """{"reason":"CALL_PHONE_denied_used_ACTION_DIAL"}""")
                    _uiState.update {
                        it.copy(statusMessage = "CALL_PHONE was denied: opened the dialer only. This attempt cannot be VERIFIED.")
                    }
                } else {
                    _uiState.update { it.copy(statusMessage = "Could not open the dialer either.") }
                }
            }
        }
    }

    fun validateAttempt() {
        viewModelScope.launch {
            val now = TimeUtils.nowMs()
            val state = _uiState.value
            if (state.callAttemptId == null) {
                _uiState.update { it.copy(statusMessage = "Place a call attempt first.") }
                return@launch
            }

            val result = AttemptEvaluator.evaluate(state, now)

            _uiState.update {
                it.copy(
                    callHappenedWithinLast10Min = result.callHappenedWithinLast10Min,
                    distanceToCustomerM = result.distanceToCustomerM,
                    feNearCustomerLocation = result.feNearCustomerLocation,
                    finalDecision = result.finalDecision,
                    missingSignals = result.missingSignals,
                    statusMessage = null,
                )
            }

            logEvent(
                EventName.VALIDATION_RUN,
                metadataJson = """{"final_decision":"${result.finalDecision}"}""",
            )

            val summaryState = _uiState.value
            val summary = AttemptSummary(
                validationTsDevice = now,
                appInstallId = appInstallId,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                appVersion = AppConfig.APP_VERSION,
                callAttemptId = summaryState.callAttemptId.orEmpty(),
                phoneNumberMasked = summaryState.phoneNumberMasked,
                phoneNumberHash = summaryState.phoneNumberHash,
                callInitiatedFromApp = result.callInitiatedFromApp,
                phoneEnteredCallState = result.phoneEnteredCallState,
                callStateStartedAt = summaryState.callStateStartedAtMs,
                callStateEndedAt = summaryState.callStateEndedAtMs,
                callStateDurationSec = summaryState.callStateDurationSec,
                callStateLasted15Sec = result.callStateLasted15Sec,
                callHappenedWithinLast10Min = result.callHappenedWithinLast10Min,
                feLat = summaryState.feLat,
                feLng = summaryState.feLng,
                locationAccuracyM = summaryState.locationAccuracyM,
                customerLat = summaryState.customerLat,
                customerLng = summaryState.customerLng,
                distanceToCustomerM = result.distanceToCustomerM,
                feNearCustomerLocation = result.feNearCustomerLocation,
                finalDecision = result.finalDecision,
                missingSignals = result.missingSignals,
            )

            val outcome = sheetLogger.logAttemptSummary(summary)
            appendEventLog("Attempt_Summary ${outcome.detail}: ${result.finalDecision}")
            _uiState.update { it.copy(pendingSyncCount = sheetLogger.pendingCount) }
        }
    }

    fun syncPendingLogs() {
        viewModelScope.launch {
            val result = sheetLogger.syncPending()
            _uiState.update { it.copy(pendingSyncCount = sheetLogger.pendingCount) }
            val suffix = result.lastFailureDetail?.let { " — last failure: $it" }.orEmpty()
            appendEventLog("Sync complete: ${result.succeeded} sent, ${result.failed} still pending.$suffix")
        }
    }

    private fun resolveCustomerLocation(
        useCurrentAsCustomer: Boolean,
        feLat: Double,
        feLng: Double,
        manualLatText: String,
        manualLngText: String,
    ): Pair<Double?, Double?> {
        if (useCurrentAsCustomer) return feLat to feLng
        val lat = manualLatText.toDoubleOrNull()
        val lng = manualLngText.toDoubleOrNull()
        return lat to lng
    }

    private suspend fun logEvent(
        eventName: String,
        phoneState: String? = null,
        callStateDurationSec: Long? = null,
        metadataJson: String = "{}",
    ) {
        val s = _uiState.value
        val event = RawEvent(
            deviceTs = TimeUtils.nowMs(),
            appInstallId = appInstallId,
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            appVersion = AppConfig.APP_VERSION,
            callAttemptId = s.callAttemptId.orEmpty(),
            eventName = eventName,
            phoneState = phoneState,
            phoneNumberMasked = s.phoneNumberMasked,
            phoneNumberHash = s.phoneNumberHash,
            lat = s.feLat,
            lng = s.feLng,
            locationAccuracyM = s.locationAccuracyM,
            customerLat = s.customerLat,
            customerLng = s.customerLng,
            distanceToCustomerM = s.distanceToCustomerM,
            callStateDurationSec = callStateDurationSec,
            permissionCallPhone = s.callPhonePermissionGranted,
            permissionReadPhoneState = s.readPhoneStatePermissionGranted,
            permissionLocation = s.locationPermissionGranted,
            metadataJson = metadataJson,
        )
        val outcome = sheetLogger.logRawEvent(event)
        appendEventLog("$eventName ${outcome.detail}")
        _uiState.update { it.copy(pendingSyncCount = sheetLogger.pendingCount) }
    }

    private fun appendEventLog(message: String) {
        _uiState.update {
            val updated = (it.eventLog + message).takeLast(30)
            it.copy(eventLog = updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        callStateTracker.stopTracking()
    }
}
