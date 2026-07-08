package com.example.attemptqualityguard.model

/** Names of the discrete events written to the Raw_Events sheet tab. */
object EventName {
    const val CALL_CTA_CLICKED = "CALL_CTA_CLICKED"
    const val DIRECT_CALL_INTENT_FIRED = "DIRECT_CALL_INTENT_FIRED"
    const val DIRECT_CALL_INTENT_FAILED = "DIRECT_CALL_INTENT_FAILED"
    const val CALL_STATE_OFFHOOK = "CALL_STATE_OFFHOOK"
    const val CALL_STATE_IDLE = "CALL_STATE_IDLE"
    const val CALL_DURATION_COMPUTED = "CALL_DURATION_COMPUTED"
    const val VALIDATION_RUN = "VALIDATION_RUN"
}

/** One row appended to the Raw_Events sheet tab. */
data class RawEvent(
    val deviceTs: Long,
    val appInstallId: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val callAttemptId: String,
    val eventName: String,
    val fePhoneNumber: String? = null,
    val phoneState: String? = null,
    val phoneNumberMasked: String? = null,
    val phoneNumberHash: String? = null,
    val callStateDurationSec: Long? = null,
    val permissionCallPhone: Boolean = false,
    val permissionReadPhoneState: Boolean = false,
    val metadataJson: String = "{}",
)

/** The single summary row appended to the Attempt_Summary sheet tab per attempt. */
data class AttemptSummary(
    val validationTsDevice: Long,
    val appInstallId: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val callAttemptId: String,
    val fePhoneNumber: String?,
    val phoneNumberMasked: String?,
    val phoneNumberHash: String?,
    val callInitiatedFromApp: Boolean,
    val phoneEnteredCallState: Boolean,
    val callStateStartedAt: Long?,
    val callStateEndedAt: Long?,
    val callStateDurationSec: Long?,
    val callStateLasted15Sec: Boolean,
    val callHappenedWithinLast10Min: Boolean,
    val finalDecision: String,
    val missingSignals: List<String>,
    val metadataJson: String = "{}",
)

enum class SignalStatus { WAITING, PASS, FAIL, UNKNOWN }

/** Everything the UI needs to render the live signal cards for one call attempt. */
data class AttemptUiState(
    val phoneNumberInput: String = "",

    val callPhonePermissionGranted: Boolean = false,
    val readPhoneStatePermissionGranted: Boolean = false,
    val readPhoneNumbersPermissionGranted: Boolean = false,

    val callAttemptId: String? = null,
    val fePhoneNumber: String? = null,
    val phoneNumberMasked: String? = null,
    val phoneNumberHash: String? = null,

    val callInitiatedFromApp: Boolean = false,
    val fallbackMode: Boolean = false,
    val callInitiatedAtMs: Long? = null,

    val phoneEnteredCallState: Boolean = false,
    val callStateStartedAtMs: Long? = null,
    val callStateEndedAtMs: Long? = null,
    val callStateDurationSec: Long? = null,
    val callStateLasted15Sec: Boolean? = null,

    val callHappenedWithinLast10Min: Boolean? = null,

    val finalDecision: String? = null,
    val missingSignals: List<String> = emptyList(),

    val statusMessage: String? = null,
)
