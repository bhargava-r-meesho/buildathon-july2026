package com.example.attemptqualityguard.logging

import android.content.Context
import com.example.attemptqualityguard.AppConfig
import com.example.attemptqualityguard.model.AttemptSummary
import com.example.attemptqualityguard.model.RawEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SyncResult(val succeeded: Int, val failed: Int, val lastFailureDetail: String? = null)

/** Outcome of a single POST attempt, with a human-readable reason for failures. */
data class PostOutcome(val success: Boolean, val detail: String)

/**
 * Posts attempt events/summaries to the deployed Google Apps Script Web App.
 * Every device installing this APK points at the same Web App URL, so every
 * row lands in the same shared Google Sheet.
 */
class SheetLogger(context: Context) {

    private val appContext = context.applicationContext
    private val localQueue = LocalEventQueue(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val pendingCount: Int get() = localQueue.size()

    suspend fun logRawEvent(event: RawEvent): PostOutcome {
        val payload = buildEnvelope(type = "raw_event", data = rawEventToJson(event))
        return send(payload)
    }

    suspend fun logAttemptSummary(summary: AttemptSummary): PostOutcome {
        val payload = buildEnvelope(type = "attempt_summary", data = attemptSummaryToJson(summary))
        return send(payload)
    }

    /** Retries every locally queued payload. Returns how many succeeded vs still failed. */
    suspend fun syncPending(): SyncResult {
        val pending = localQueue.peekAll()
        if (pending.isEmpty()) return SyncResult(succeeded = 0, failed = 0)

        var succeeded = 0
        var lastFailureDetail: String? = null
        val stillFailing = mutableListOf<String>()
        for (payloadJson in pending) {
            val outcome = postRaw(payloadJson)
            if (outcome.success) {
                succeeded++
            } else {
                stillFailing += payloadJson
                lastFailureDetail = outcome.detail
            }
        }

        localQueue.clear()
        stillFailing.forEach { localQueue.enqueue(it) }

        return SyncResult(succeeded = succeeded, failed = stillFailing.size, lastFailureDetail = lastFailureDetail)
    }

    private suspend fun send(payloadJson: String): PostOutcome {
        val outcome = postRaw(payloadJson)
        if (!outcome.success) localQueue.enqueue(payloadJson)
        return outcome
    }

    private suspend fun postRaw(payloadJson: String): PostOutcome {
        if (!AppConfig.isConfigured()) return PostOutcome(false, "Web App URL not configured")

        return withContext(Dispatchers.IO) {
            try {
                val body = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(AppConfig.APPS_SCRIPT_WEB_APP_URL)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext PostOutcome(
                            false,
                            "HTTP ${response.code} ${response.message}".trim() +
                                if (text.isNotBlank()) " — ${text.take(200)}" else "",
                        )
                    }
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return@withContext PostOutcome(
                            false,
                            "HTTP ${response.code} but non-JSON response — ${text.take(200)}",
                        )
                    }
                    if (json.optBoolean("success", false)) {
                        PostOutcome(true, "sent")
                    } else {
                        PostOutcome(false, "server error: ${json.optString("error", "unknown")}")
                    }
                }
            } catch (e: IOException) {
                PostOutcome(false, "network error: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: Exception) {
                PostOutcome(false, "error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun buildEnvelope(type: String, data: JSONObject): String {
        val envelope = JSONObject()
        envelope.put("secret", AppConfig.SHARED_SECRET)
        envelope.put("type", type)
        envelope.put("data", data)
        return envelope.toString()
    }

    private fun rawEventToJson(event: RawEvent): JSONObject = JSONObject().apply {
        put("device_ts", event.deviceTs)
        put("app_install_id", event.appInstallId)
        put("device_model", event.deviceModel)
        put("android_version", event.androidVersion)
        put("app_version", event.appVersion)
        put("call_attempt_id", event.callAttemptId)
        put("event_name", event.eventName)
        putOpt("fe_phone_number", event.fePhoneNumber)
        putOpt("phone_state", event.phoneState)
        putOpt("phone_number_masked", event.phoneNumberMasked)
        putOpt("phone_number_hash", event.phoneNumberHash)
        putOpt("call_state_duration_sec", event.callStateDurationSec)
        put("permission_call_phone", event.permissionCallPhone)
        put("permission_read_phone_state", event.permissionReadPhoneState)
        put("metadata_json", event.metadataJson)
    }

    private fun attemptSummaryToJson(summary: AttemptSummary): JSONObject = JSONObject().apply {
        put("validation_ts_device", summary.validationTsDevice)
        put("app_install_id", summary.appInstallId)
        put("device_model", summary.deviceModel)
        put("android_version", summary.androidVersion)
        put("app_version", summary.appVersion)
        put("call_attempt_id", summary.callAttemptId)
        putOpt("fe_phone_number", summary.fePhoneNumber)
        putOpt("phone_number_masked", summary.phoneNumberMasked)
        putOpt("phone_number_hash", summary.phoneNumberHash)
        put("call_initiated_from_app", summary.callInitiatedFromApp)
        put("phone_entered_call_state", summary.phoneEnteredCallState)
        putOpt("call_state_started_at", summary.callStateStartedAt)
        putOpt("call_state_ended_at", summary.callStateEndedAt)
        putOpt("call_state_duration_sec", summary.callStateDurationSec)
        put("call_state_lasted_15_sec", summary.callStateLasted15Sec)
        put("call_happened_within_last_10_min", summary.callHappenedWithinLast10Min)
        put("final_decision", summary.finalDecision)
        put("missing_signals", summary.missingSignals.joinToString(","))
        put("metadata_json", summary.metadataJson)
    }
}
