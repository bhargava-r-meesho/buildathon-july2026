package com.example.attemptqualityguard.util

import java.util.concurrent.TimeUnit

object TimeUtils {

    fun nowMs(): Long = System.currentTimeMillis()

    fun secondsBetween(startMs: Long, endMs: Long): Long =
        TimeUnit.MILLISECONDS.toSeconds(endMs - startMs).coerceAtLeast(0)

    fun isWithinLastMinutes(eventMs: Long, referenceMs: Long, minutes: Int): Boolean {
        val windowMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        val delta = referenceMs - eventMs
        return delta in 0..windowMs
    }
}
