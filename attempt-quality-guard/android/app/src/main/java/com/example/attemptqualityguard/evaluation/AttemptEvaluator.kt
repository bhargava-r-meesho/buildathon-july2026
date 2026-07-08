package com.example.attemptqualityguard.evaluation

import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.model.AttemptUiState
import com.example.attemptqualityguard.util.TimeUtils

object AttemptEvaluator {

    const val VERIFIED = "VERIFIED"
    const val NOT_VERIFIED = "NOT_VERIFIED"

    data class Result(
        val callInitiatedFromApp: Boolean,
        val phoneEnteredCallState: Boolean,
        val callStateLasted15Sec: Boolean,
        val callHappenedWithinLast10Min: Boolean,
        val finalDecision: String,
        val missingSignals: List<String>,
    )

    /**
     * Pure evaluation function - takes the current signal snapshot and the moment
     * validation is run, and derives the four boolean checks plus the final decision.
     *
     * IMPORTANT: none of these signals prove the customer answered the phone. See
     * README "What this proves / does not prove".
     */
    fun evaluate(state: AttemptUiState, validationAtMs: Long): Result {
        val missing = mutableListOf<String>()

        val callInitiatedFromApp = state.callInitiatedFromApp && !state.fallbackMode
        if (!callInitiatedFromApp) missing += "call_initiated_from_app"

        val phoneEnteredCallState = state.phoneEnteredCallState
        if (!phoneEnteredCallState) missing += "phone_entered_call_state"

        val durationSec = state.callStateDurationSec
        val callStateLasted15Sec = durationSec != null &&
            durationSec >= AppConfig.MIN_CALL_STATE_DURATION_SECONDS
        if (!callStateLasted15Sec) missing += "call_state_lasted_15_sec"

        val referenceCallMoment = state.callStateStartedAtMs ?: state.callInitiatedAtMs
        val callHappenedWithinLast10Min = referenceCallMoment != null &&
            TimeUtils.isWithinLastMinutes(
                eventMs = referenceCallMoment,
                referenceMs = validationAtMs,
                minutes = AppConfig.VALIDATION_WINDOW_MINUTES,
            )
        if (!callHappenedWithinLast10Min) missing += "call_happened_within_last_10_min"

        val finalDecision = if (missing.isEmpty()) VERIFIED else NOT_VERIFIED

        return Result(
            callInitiatedFromApp = callInitiatedFromApp,
            phoneEnteredCallState = phoneEnteredCallState,
            callStateLasted15Sec = callStateLasted15Sec,
            callHappenedWithinLast10Min = callHappenedWithinLast10Min,
            finalDecision = finalDecision,
            missingSignals = missing,
        )
    }
}
