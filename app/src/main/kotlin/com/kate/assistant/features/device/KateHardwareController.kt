package com.kate.assistant.features.device

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.content.ContextCompat

class KateHardwareController(private val context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun kateHaptic() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.VIBRATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    60,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }

    // ─────────────────────────────
    // TORCH / FLASHLIGHT CONTROLS
    // ─────────────────────────────
    fun torchOn(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, true)
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext?.contentResolver?.let { resolver ->
                    Settings.System.putInt(
                        resolver,
                        Settings.System.FLASHLIGHT_TOGGLE, 1
                    )
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun torchOff(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, false)
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext?.contentResolver?.let { resolver ->
                    Settings.System.putInt(
                        resolver,
                        Settings.System.FLASHLIGHT_TOGGLE, 0
                    )
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isTorchOn(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                cameraId?.let { cameraManager.getCameraCharacteristics(it) }?.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) ?: false
            } catch (e: Exception) {
                false
            }
        } else false
    }

    // ─────────────────────────────
    // VOLUME CONTROLS
    // ─────────────────────────────
    fun volumeUp() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun setVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = level.coerceIn(0, max)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            target,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun getCurrentVolume(): Int {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun getMaxVolume(): Int {
        return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    fun muteAll() {
        // Mute media volume
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        // Mute alarm volume
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        // Mute notification volume
        audioManager.setStreamVolume(
            AudioManager.STREAM_NOTIFICATION,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        // Mute ring volume
        audioManager.setStreamVolume(
            AudioManager.STREAM_RING,
            0,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun unmuteAll() {
        // Restore to reasonable levels
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (mediaMax * 0.6).toInt(),
            AudioManager.FLAG_SHOW_UI
        )
        
        val alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            (alarmMax * 0.7).toInt(),
            AudioManager.FLAG_SHOW_UI
        )
        
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(
            AudioManager.STREAM_RING,
            (ringMax * 0.5).toInt(),
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun isMuted(): Boolean {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    // ─────────────────────────────
    // DO NOT DISTURB (DND) MODE
    // ─────────────────────────────
    fun setDND(enabled: Boolean): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                if (enabled) {
                    // Check if we have permission to modify DND
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (notificationManager.isNotificationPolicyAccessGranted) {
                            notificationManager.setInterruptionFilter(
                                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            )
                            true
                        } else {
                            // Request permission (caller should handle)
                            false
                        }
                    } else {
                        false
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (notificationManager.isNotificationPolicyAccessGranted) {
                            notificationManager.setInterruptionFilter(
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            )
                            true
                        } else false
                    } else false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        } else {
            // For older Android versions, use deprecated method
            try {
                val settingsUri = if (enabled) {
                    Settings.Global.putString(
                        context.contentResolver,
                        Settings.Global.ZEN_MODE,
                        Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS.toString()
                    )
                } else {
                    Settings.Global.putString(
                        context.contentResolver,
                        Settings.Global.ZEN_MODE,
                        Settings.Global.ZEN_MODE_OFF.toString()
                    )
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun isDNDEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                notificationManager.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
            } catch (e: Exception) {
                false
            }
        } else false
    }

    // ─────────────────────────────
    // BRIGHTNESS CONTROL (BONUS)
    // ─────────────────────────────
    fun setBrightness(level: Int) {
        val brightness = level.coerceIn(0, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
        } else {
            @Suppress("DEPRECATION")
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
        }
    }

    fun getCurrentBrightness(): Int {
        return Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            125
        )
    }

    fun brightnessUp() {
        val current = getCurrentBrightness()
        val newValue = (current + 25).coerceAtMost(255)
        setBrightness(newValue)
    }

    fun brightnessDown() {
        val current = getCurrentBrightness()
        val newValue = (current - 25).coerceAtLeast(0)
        setBrightness(newValue)
    }

    // ─────────────────────────────
    // RINGER MODE
    // ─────────────────────────────
    fun setRingerMode(mode: Int) {
        // mode: AudioManager.RINGER_MODE_NORMAL, RINGER_MODE_SILENT, RINGER_MODE_VIBRATE
        audioManager.ringerMode = mode
    }

    fun getRingerMode(): Int {
        return audioManager.ringerMode
    }

    fun silentMode(enabled: Boolean) {
        if (enabled) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } else {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
    }

    fun vibrateMode(enabled: Boolean) {
        if (enabled) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        }
    }
}
