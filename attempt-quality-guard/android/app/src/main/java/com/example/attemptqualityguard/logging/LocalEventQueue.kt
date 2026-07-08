package com.example.attemptqualityguard.logging

import android.content.Context
import org.json.JSONArray

/**
 * Very small persistent retry queue for payloads that failed to reach the Apps
 * Script Web App (no network, script down, etc). Backed by SharedPreferences,
 * which is plenty for a hackathon MVP's expected volume.
 */
class LocalEventQueue(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(payloadJson: String) {
        val current = readArray()
        current.put(payloadJson)
        prefs.edit().putString(KEY_QUEUE, current.toString()).apply()
    }

    @Synchronized
    fun peekAll(): List<String> {
        val array = readArray()
        return (0 until array.length()).map { array.getString(it) }
    }

    @Synchronized
    fun removeFirst(count: Int) {
        val array = readArray()
        if (count <= 0 || array.length() == 0) return
        val remaining = JSONArray()
        for (i in count until array.length()) {
            remaining.put(array.getString(i))
        }
        prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
    }

    @Synchronized
    fun size(): Int = readArray().length()

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    private fun readArray(): JSONArray {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    companion object {
        private const val PREFS_NAME = "attempt_quality_guard_pending_events"
        private const val KEY_QUEUE = "queue"
    }
}
