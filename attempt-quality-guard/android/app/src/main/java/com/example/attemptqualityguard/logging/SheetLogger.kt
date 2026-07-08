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

data class SyncResult(val succeeded: Int, val failed: Int)

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

    suspend fun logRawEvent(event: RawEvent): Boolean {
        val payload = buildEnvelope(type = "raw_event", data = rawEventToJson(event))
        return send(payload)
    }

    suspend fun logAttemptSummary(summary: AttemptSummary): Boolean {
        val payload = buildEnvelope(type = "attempt_summary", data = attemptSummaryToJson(summary))
        return send(payload)
    }

    /** Retries every locally queued payload. Returns how many succeeded vs still failed. */
    suspend fun syncPending(): SyncResult {
        val pending = localQueue.peekAll()
        if (pending.isEmpty()) return SyncResult(succeeded = 0, failed = 0)

        var succeeded = 0
        val stillFailing = mutableListOf<String>()
        for (payloadJson in pending) {
            val ok = postRaw(payloadJson)
            if (ok) succeeded++ else stillFailing += payloadJson
        }

        localQueue.clear()
        stillFailing.forEach { localQueue.enqueue(it) }

        return SyncResult(succeeded = succeeded, failed = stillFailing.size)
    }

    private suspend fun send(payloadJson: String): Boolean {
        val ok = postRaw(payloadJson)
        if (!ok) localQueue.enqueue(payloadJson)
        return ok
    }

    private suspend fun postRaw(payloadJson: String): Boolean {
        if (!AppConfig.isConfigured()) return false

        return withContext(Dispatchers.IO) {
            try {
                val body = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(AppConfig.APPS_SCRIPT_WEB_APP_URL)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val text = response.body?.string().orEmpty()
                    val json = JSONObject(text)
                    json.optBoolean("success", false)
                }
            } catch (e: IOException) {
                false
            } catch (e: Exception) {
                false
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
        putOpt("phone_state", event.phoneState)
        putOpt("phone_number_masked", event.phoneNumberMasked)
        putOpt("phone_number_hash", event.phoneNumberHash)
        putOpt("lat", event.lat)
        putOpt("lng", event.lng)
        putOpt("location_accuracy_m", event.locationAccuracyM?.toDouble())
        putOpt("customer_lat", event.customerLat)
        putOpt("customer_lng", event.customerLng)
        putOpt("distance_to_customer_m", event.distanceToCustomerM)
        putOpt("call_state_duration_sec", event.callStateDurationSec)
        put("permission_call_phone", event.permissionCallPhone)
        put("permission_read_phone_state", event.permissionReadPhoneState)
        put("permission_location", event.permissionLocation)
        put("metadata_json", event.metadataJson)
    }

    private fun attemptSummaryToJson(summary: AttemptSummary): JSONObject = JSONObject().apply {
        put("validation_ts_device", summary.validationTsDevice)
        put("app_install_id", summary.appInstallId)
        put("device_model", summary.deviceModel)
        put("android_version", summary.androidVersion)
        put("app_version", summary.appVersion)
        put("call_attempt_id", summary.callAttemptId)
        putOpt("phone_number_masked", summary.phoneNumberMasked)
        putOpt("phone_number_hash", summary.phoneNumberHash)
        put("call_initiated_from_app", summary.callInitiatedFromApp)
        put("phone_entered_call_state", summary.phoneEnteredCallState)
        putOpt("call_state_started_at", summary.callStateStartedAt)
        putOpt("call_state_ended_at", summary.callStateEndedAt)
        putOpt("call_state_duration_sec", summary.callStateDurationSec)
        put("call_state_lasted_15_sec", summary.callStateLasted15Sec)
        put("call_happened_within_last_10_min", summary.callHappenedWithinLast10Min)
        putOpt("fe_lat", summary.feLat)
        putOpt("fe_lng", summary.feLng)
        putOpt("location_accuracy_m", summary.locationAccuracyM?.toDouble())
        putOpt("customer_lat", summary.customerLat)
        putOpt("customer_lng", summary.customerLng)
        putOpt("distance_to_customer_m", summary.distanceToCustomerM)
        put("fe_near_customer_location", summary.feNearCustomerLocation)
        put("final_decision", summary.finalDecision)
        put("missing_signals", summary.missingSignals.joinToString(","))
        put("metadata_json", summary.metadataJson)
    }
}
