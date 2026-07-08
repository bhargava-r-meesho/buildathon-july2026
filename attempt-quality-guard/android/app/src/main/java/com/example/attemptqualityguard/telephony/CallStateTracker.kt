package com.example.attemptqualityguard.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
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
 */
class CallStateTracker(private val context: Context) {

    interface Listener {
        fun onCallStateOffhook()
        fun onCallStateIdleAfterOffhook()
        fun onTrackingTimedOut()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    private var telephonyCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var timeoutRunnable: Runnable? = null

    private var sawOffhook = false
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
        this.isTracking = true

        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
        } else {
            @Suppress("DEPRECATION")
            val phoneStateListener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            legacyListener = phoneStateListener
        }

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

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!sawOffhook) {
                    sawOffhook = true
                    listener?.onCallStateOffhook()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (sawOffhook) {
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

    /** Unregister the listener. Safe to call multiple times. */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        telephonyCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        }
        telephonyCallback = null

        legacyListener?.let {
            @Suppress("DEPRECATION")
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        legacyListener = null

        listener = null
    }
}
