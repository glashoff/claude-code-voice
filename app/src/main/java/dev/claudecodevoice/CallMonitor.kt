package dev.claudecodevoice

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Reports when a phone call rings or is active. Needs READ_PHONE_STATE; without it nothing is reported. */
class CallMonitor(private val context: Context, private val onCall: () -> Unit) {
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var callback: Any? = null

    private fun handle(state: Int) {
        if (state != TelephonyManager.CALL_STATE_IDLE) onCall()
    }

    fun start() {
        if (callback != null) return
        val tm = telephony ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handle(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val cb = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(cb, PhoneStateListener.LISTEN_CALL_STATE)
                callback = cb
            }
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val tm = telephony ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.unregisterTelephonyCallback(cb as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }
}
