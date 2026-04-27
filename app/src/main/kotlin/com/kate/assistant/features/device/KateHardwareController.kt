package com.kate.assistant.features.device

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*

class KateHardwareController(private val context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val audioManager  = context.getSystemService(AudioManager::class.java)
    private val vibrator      = context.getSystemService(Vibrator::class.java)
    private val notifManager  = context.getSystemService(NotificationManager::class.java)
    private val cameraId      = runCatching { cameraManager.cameraIdList[0] }.getOrNull()

    fun torch(on: Boolean) {
        cameraId?.let { runCatching { cameraManager.setTorchMode(it, on) } }
    }
    fun torchOn()    = torch(true)
    fun torchOff()   = torch(false)

    fun volumeUp()   = audioManager.adjustVolume(AudioManager.ADJUST_RAISE,  AudioManager.FLAG_SHOW_UI)
    fun volumeDown() = audioManager.adjustVolume(AudioManager.ADJUST_LOWER,  AudioManager.FLAG_SHOW_UI)
    fun muteAll()    = audioManager.adjustVolume(AudioManager.ADJUST_MUTE,   AudioManager.FLAG_SHOW_UI)
    fun unmuteAll()  = audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)

    fun kateHaptic() = vibrator.vibrate(
        VibrationEffect.createWaveform(longArrayOf(0L, 80L, 60L, 80L), -1)
    )

    fun setDND(on: Boolean) {
        if (notifManager.isNotificationPolicyAccessGranted) {
            notifManager.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_NONE
                else    NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }
}
