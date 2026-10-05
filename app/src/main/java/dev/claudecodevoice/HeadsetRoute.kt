package dev.claudecodevoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/**
 * Prefers the microphone of a connected headset.
 *
 * A wired or USB headset only needs to be chosen as preferred input. A Bluetooth headset only provides its microphone
 * in the "call" mode (voice link), so that mode is switched on while listening; playback then also has to use the
 * voice-call path, otherwise it may end up on the phone's loudspeaker. Without a headset nothing changes.
 */
object HeadsetRoute {
    /** True while a Bluetooth headset is used in call mode. */
    @Volatile var callMode = false
        private set

    /** Preferred microphone, or null for the system default. */
    @Volatile var input: AudioDeviceInfo? = null
        private set

    /** Audio usage for all playback (speech, thinking sound). */
    val playbackUsage get() = if (callMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA

    /** Recording source: the call source routes to the Bluetooth microphone and brings echo cancellation. */
    val recordSource get() = if (callMode) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION

    private val wiredTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    private val bluetoothTypes = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Switches to the headset microphone if one is connected; returns its name, or null if there is none. */
    @Synchronized
    fun enable(context: Context): String? {
        disable(context)
        val am = context.getSystemService(AudioManager::class.java)
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        inputs.firstOrNull { it.type in wiredTypes }?.let { wired ->
            input = wired
            return wired.productName.toString()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.availableCommunicationDevices.firstOrNull { it.type in bluetoothTypes } ?: return null
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!am.setCommunicationDevice(device)) {
                am.mode = AudioManager.MODE_NORMAL
                return null
            }
            callMode = true
            input = inputs.firstOrNull { it.type == device.type }
            return device.productName.toString()
        }
        val sco = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return null
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.startBluetoothSco()
        @Suppress("DEPRECATION")
        am.isBluetoothScoOn = true
        callMode = true
        input = sco
        return sco.productName.toString()
    }

    /** Back to the normal audio routing. */
    @Synchronized
    fun disable(context: Context) {
        input = null
        if (!callMode) return
        val am = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
        callMode = false
    }
}
