package com.example.attemptqualityguard.util

import java.security.MessageDigest

/**
 * We never send a usable phone number to the Google Sheet: only a masked
 * display value (last 4 digits visible) and a one-way SHA-256 hash, so the
 * same number still dedupes across rows without being reversible from the sheet.
 */
object PhoneMasking {

    private val NON_DIGITS = Regex("[^0-9]")

    fun normalizeDigits(rawPhoneNumber: String): String = rawPhoneNumber.replace(NON_DIGITS, "")

    /** Basic sanity check: enough digits to plausibly be a phone number. */
    fun isPlausiblePhoneNumber(rawPhoneNumber: String): Boolean {
        val digits = normalizeDigits(rawPhoneNumber)
        return digits.length in 7..15
    }

    /** e.g. "9876543210" -> "XXXXXX3210" */
    fun mask(rawPhoneNumber: String): String {
        val digits = normalizeDigits(rawPhoneNumber)
        if (digits.length <= 4) return "X".repeat(digits.length)
        val visible = digits.takeLast(4)
        val hidden = "X".repeat(digits.length - 4)
        return hidden + visible
    }

    fun sha256Hash(rawPhoneNumber: String): String {
        val digits = normalizeDigits(rawPhoneNumber)
        val bytes = MessageDigest.getInstance("SHA-256").digest(digits.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
