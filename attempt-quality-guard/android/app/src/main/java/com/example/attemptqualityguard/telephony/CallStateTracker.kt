package com.example.attemptqualityguard.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Watches ONLY for the call state transition caused by the call this app just placed.
 *
 * Design constraints (see README "What this proves / does not prove"):
 *  - Never touches READ_CALL_LOG or call history.
 *  - The listener is registered right before we fire the ACTION_CALL intent and is
 *    unregistered as soon as we observe IDLE-after-OFFHOOK, or after [timeoutMs] elapses
 *    with no such transition (e.g. call never connects to the telephony stack, or the
 *    user backs out of the dialer). We are not "always listening" in the background.
 *
 * Dual-SIM handling: this app never chooses which SIM places the call - that's entirely
 * up to Android (either its own SIM-picker dialog, or the user's configured default). On
 * API 30+ we register a listener on every active SIM subscription so whichever one the
 * system actually uses still gets observed; on older API levels there is no per-subscription
 * TelephonyManager API, so we fall back to the single default-SIM listener as before.
 */
class CallStateTracker(private val context: Context) {

    interface Listener {
        fun onCallStateOffhook()
        fun onCallStateIdleAfterOffhook()
        fun onTrackingTimedOut()
    }

    private class Registration(
        val telephonyManager: TelephonyManager,
        val telephonyCallback: TelephonyCallback? = null,
        val legacyListener: PhoneStateListener? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    private val registrations = mutableListOf<Registration>()
    private var timeoutRunnable: Runnable? = null

    private var sawOffhook = false
    private var activeTelephonyManager: TelephonyManager? = null
    private var listener: Listener? = null
    var isTracking: Boolean = false
        private set

    fun hasReadPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Begin watching call state for the attempt about to be placed. Call this immediately
     * before firing [android.content.Intent.ACTION_CALL].
     */
    fun startTracking(listener: Listener, timeoutMs: Long = TimeUnit.MINUTES.toMillis(5)) {
        if (isTracking) return
        if (!hasReadPhoneStatePermission()) return

        this.listener = listener
        this.sawOffhook = false
        this.activeTelephonyManager = null
        this.isTracking = true

        val defaultManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val perSimManagers = perSubscriptionTelephonyManagers(defaultManager)
        val managersToWatch = perSimManagers.ifEmpty { listOf(defaultManager) }

        managersToWatch.forEach { manager -> registrations += registerOn(manager) }

        val timeout = Runnable {
            if (isTracking) {
                val cb = this.listener
                stopTracking()
                cb?.onTrackingTimedOut()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
    }

    /**
     * One TelephonyManager per active SIM subscription (API 30+ only - createForSubscriptionId
     * doesn't exist below that). Returns an empty list on older API levels, if there's only one
     * subscription anyway, or if the subscription list can't be read for any reason - the
     * caller falls back to the single default-SIM manager in every one of those cases.
     */
    private fun perSubscriptionTelephonyManagers(defaultManager: TelephonyManager): List<TelephonyManager> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                ?: return emptyList()
            activeSubscriptions.mapNotNull { info ->
                try {
                    defaultManager.createForSubscriptionId(info.subscriptionId)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun registerOn(telephonyManager: TelephonyManager): Registration {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(telephonyManager, state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            Registration(telephonyManager, telephonyCallback = callback)
        } else {
            @Suppress("DEPRECATION")
            val phoneStateListener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(telephonyManager, state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Registration(telephonyManager, legacyListener = phoneStateListener)
        }
    }

    /**
     * [source] identifies which SIM's TelephonyManager reported this state. Once one SIM
     * reports OFFHOOK it "wins" the attempt; state changes from every other SIM (e.g. an
     * unused SIM's initial IDLE delivered right as it's registered) are ignored from then on,
     * so an unrelated SIM can never be mistaken for the end of the call that's actually happening.
     */
    private fun handleState(source: TelephonyManager, state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!sawOffhook) {
                    sawOffhook = true
                    activeTelephonyManager = source
                    listener?.onCallStateOffhook()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (sawOffhook && source === activeTelephonyManager) {
                    val cb = listener
                    stopTracking()
                    cb?.onCallStateIdleAfterOffhook()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // Not relevant for an outgoing app-initiated call; ignored.
            }
        }
    }

    /** Unregister every registered listener. Safe to call multiple times. */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        registrations.forEach { registration ->
            registration.telephonyCallback?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    registration.telephonyManager.unregisterTelephonyCallback(it)
                }
            }
            registration.legacyListener?.let {
                @Suppress("DEPRECATION")
                registration.telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        registrations.clear()
        activeTelephonyManager = null

        listener = null
    }
}
