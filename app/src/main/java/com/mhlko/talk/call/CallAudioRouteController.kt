package com.mhlko.talk.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Keeps communication audio routing provider-neutral.
 *
 * Agora and Tencent otherwise apply different defaults when Bluetooth devices
 * appear or disappear. LiveKit keeps its own proven AudioSwitch path, so this
 * controller is used by the non-LiveKit native adapters only.
 */
internal class CallAudioRouteController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var started = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyPreferredRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyPreferredRoute()
    }

    fun start() {
        if (started) {
            applyPreferredRoute()
            return
        }
        started = true
        previousMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        applyPreferredRoute()
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = previousSpeakerphone
        }
        audioManager.mode = previousMode
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun applyPreferredRoute() {
        if (!started) return
        val bluetoothAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferred = audioManager.availableCommunicationDevices
                .filterNot { !bluetoothAllowed && it.isBluetoothRoute() }
                .minByOrNull { it.routePriority() }
                ?: return
            audioManager.setCommunicationDevice(preferred)
            return
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = bluetoothAllowed && outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        val hasWired = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        when {
            hasBluetooth -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            hasWired -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            else -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
        }
    }
}

private fun AudioDeviceInfo.isBluetoothRoute() =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

private fun AudioDeviceInfo.routePriority() = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
    AudioDeviceInfo.TYPE_BLE_HEADSET -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0 else 8
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET -> 1
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 3
    else -> 7
}
