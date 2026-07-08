package com.example.attemptqualityguard.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.evaluation.AttemptEvaluator
import com.example.attemptqualityguard.logging.SheetLogger
import com.example.attemptqualityguard.model.AttemptSummary
import com.example.attemptqualityguard.model.AttemptUiState
import com.example.attemptqualityguard.model.EventName
import com.example.attemptqualityguard.model.RawEvent
import com.example.attemptqualityguard.telephony.CallStateTracker
import com.example.attemptqualityguard.util.AppInstallId
import com.example.attemptqualityguard.util.FePhoneNumber
import com.example.attemptqualityguard.util.PhoneMasking
import com.example.attemptqualityguard.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "AttemptQualityGuard"

class AttemptViewModel(application: Application) : AndroidViewModel(application) {

    private val callStateTracker = CallStateTracker(application)
    private val sheetLogger = SheetLogger(application)
    private val appInstallId = AppInstallId.get(application)

    private val _uiState = MutableStateFlow(AttemptUiState())
    val uiState: StateFlow<AttemptUiState> = _uiState.asStateFlow()

    init {
        syncPendingLogs()
    }

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
                runValidation()
            }
        }

        override fun onTrackingTimedOut() {
            viewModelScope.launch { runValidation() }
        }
    }

    fun onPhoneNumberChange(value: String) {
        _uiState.update { it.copy(phoneNumberInput = value) }
    }

    fun onPermissionsUpdated(callPhone: Boolean, readPhoneState: Boolean, readPhoneNumbers: Boolean) {
        _uiState.update {
            it.copy(
                callPhonePermissionGranted = callPhone,
                readPhoneStatePermissionGranted = readPhoneState,
                readPhoneNumbersPermissionGranted = readPhoneNumbers,
            )
        }
    }

    /** Called on every screen resume so a working connection picks up anything still queued. */
    fun syncPendingLogs() {
        viewModelScope.launch {
            val result = sheetLogger.syncPending()
            if (result.succeeded > 0 || result.failed > 0) {
                Log.d(TAG, "Sync: ${result.succeeded} sent, ${result.failed} still pending. ${result.lastFailureDetail.orEmpty()}")
            }
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

        // Everything below this point is local (UUID, hashing, a TelephonyManager property
        // read) - no network I/O - so the call intent fires within milliseconds of the tap.
        // Logging and validation are dispatched as separate background coroutines further
        // down specifically so a slow/blocked Sheet upload can never delay placing the call.
        val attemptId = UUID.randomUUID().toString()
        val masked = PhoneMasking.mask(phoneNumber)
        val hash = PhoneMasking.sha256Hash(phoneNumber)
        val fePhoneNumber = FePhoneNumber.read(getApplication())

        _uiState.update {
            AttemptUiState(
                phoneNumberInput = it.phoneNumberInput,
                callPhonePermissionGranted = it.callPhonePermissionGranted,
                readPhoneStatePermissionGranted = it.readPhoneStatePermissionGranted,
                readPhoneNumbersPermissionGranted = it.readPhoneNumbersPermissionGranted,
                callAttemptId = attemptId,
                fePhoneNumber = fePhoneNumber,
                phoneNumberMasked = masked,
                phoneNumberHash = hash,
            )
        }

        viewModelScope.launch { logEvent(EventName.CALL_CTA_CLICKED) }

        val initiatedAt = TimeUtils.nowMs()
        if (_uiState.value.callPhonePermissionGranted) {
            val fired = launchDirectCall(phoneNumber)
            if (fired) {
                _uiState.update {
                    it.copy(callInitiatedFromApp = true, fallbackMode = false, callInitiatedAtMs = initiatedAt)
                }
                viewModelScope.launch { logEvent(EventName.DIRECT_CALL_INTENT_FIRED) }
                if (_uiState.value.readPhoneStatePermissionGranted) {
                    callStateTracker.startTracking(callStateListener)
                }
            } else {
                viewModelScope.launch { logEvent(EventName.DIRECT_CALL_INTENT_FAILED) }
                _uiState.update { it.copy(statusMessage = "Could not start the call.") }
                viewModelScope.launch { runValidation() }
            }
        } else {
            val dialed = launchDialFallback(phoneNumber)
            _uiState.update { it.copy(callInitiatedFromApp = false, fallbackMode = true) }
            if (dialed) {
                viewModelScope.launch {
                    logEvent(EventName.DIRECT_CALL_INTENT_FAILED, metadataJson = """{"reason":"CALL_PHONE_denied_used_ACTION_DIAL"}""")
                }
                _uiState.update {
                    it.copy(statusMessage = "CALL_PHONE was denied: opened the dialer only. This attempt cannot be VERIFIED.")
                }
            } else {
                _uiState.update { it.copy(statusMessage = "Could not open the dialer either.") }
            }
            viewModelScope.launch { runValidation() }
        }
    }

    private suspend fun runValidation() {
        val now = TimeUtils.nowMs()
        val state = _uiState.value
        if (state.callAttemptId == null) return

        val result = AttemptEvaluator.evaluate(state, now)

        _uiState.update {
            it.copy(
                callHappenedWithinLast10Min = result.callHappenedWithinLast10Min,
                finalDecision = result.finalDecision,
                missingSignals = result.missingSignals,
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
            fePhoneNumber = summaryState.fePhoneNumber,
            phoneNumberMasked = summaryState.phoneNumberMasked,
            phoneNumberHash = summaryState.phoneNumberHash,
            callInitiatedFromApp = result.callInitiatedFromApp,
            phoneEnteredCallState = result.phoneEnteredCallState,
            callStateStartedAt = summaryState.callStateStartedAtMs,
            callStateEndedAt = summaryState.callStateEndedAtMs,
            callStateDurationSec = summaryState.callStateDurationSec,
            callStateLasted15Sec = result.callStateLasted15Sec,
            callHappenedWithinLast10Min = result.callHappenedWithinLast10Min,
            finalDecision = result.finalDecision,
            missingSignals = result.missingSignals,
        )

        val outcome = sheetLogger.logAttemptSummary(summary)
        Log.d(TAG, "Attempt_Summary ${outcome.detail}: ${result.finalDecision}")
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
            fePhoneNumber = s.fePhoneNumber,
            phoneState = phoneState,
            phoneNumberMasked = s.phoneNumberMasked,
            phoneNumberHash = s.phoneNumberHash,
            callStateDurationSec = callStateDurationSec,
            permissionCallPhone = s.callPhonePermissionGranted,
            permissionReadPhoneState = s.readPhoneStatePermissionGranted,
            metadataJson = metadataJson,
        )
        val outcome = sheetLogger.logRawEvent(event)
        Log.d(TAG, "$eventName ${outcome.detail}")
    }

    override fun onCleared() {
        super.onCleared()
        callStateTracker.stopTracking()
    }
}
