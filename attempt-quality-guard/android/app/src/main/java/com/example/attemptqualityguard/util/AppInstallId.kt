package com.example.attemptqualityguard.util

import android.content.Context
import java.util.UUID

/** Stable per-install identifier, generated once and persisted in SharedPreferences. */
object AppInstallId {

    private const val PREFS_NAME = "attempt_quality_guard_identity"
    private const val KEY_INSTALL_ID = "app_install_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }
}
