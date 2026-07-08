package com.example.attemptqualityguard

/**
 * Single place to configure this MVP for a demo/deploy.
 *
 * Paste your deployed Google Apps Script Web App URL below (see README.md).
 * The same URL must be used across every device installing this APK so that
 * all attempts append to the same Google Sheet.
 */
object AppConfig {
    const val APPS_SCRIPT_WEB_APP_URL: String = "https://script.google.com/macros/s/AKfycbzqTyA2YB58pM4RCYWpd93Sle041AObOFta5frLYUZwvWPUXbbbyZjs8kTeDgSi9kQ/exec"
    const val SHARED_SECRET: String = "AQ_TESTING"

    const val MIN_CALL_STATE_DURATION_SECONDS: Int = 15
    const val VALIDATION_WINDOW_MINUTES: Int = 10
    const val NEAR_CUSTOMER_RADIUS_METERS: Double = 150.0

    const val APP_VERSION: String = "1.0"

    private const val PLACEHOLDER_URL: String = "PASTE_WEB_APP_URL_HERE"

    fun isConfigured(): Boolean =
        APPS_SCRIPT_WEB_APP_URL.isNotBlank() && APPS_SCRIPT_WEB_APP_URL != PLACEHOLDER_URL
}
