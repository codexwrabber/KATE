package com.kate.assistant.features.device

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kate.assistant.core.KateDeviceAdmin

class KateHardwareController(private val context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, KateDeviceAdmin::class.java)

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
                false
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
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

    fun muteAll() {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        audioManager.setStreamVolume(
            AudioManager.STREAM_NOTIFICATION,
            0,
            AudioManager.FLAG_SHOW_UI
        )
        audioManager.setStreamVolume(
            AudioManager.STREAM_RING,
            0,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun unmuteAll() {
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

    // ─────────────────────────────
    // DO NOT DISTURB (DND) MODE
    // ─────────────────────────────
    fun setDND(enabled: Boolean): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                if (enabled) {
                    if (notificationManager.isNotificationPolicyAccessGranted) {
                        notificationManager.setInterruptionFilter(
                            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        )
                        true
                    } else {
                        false
                    }
                } else {
                    if (notificationManager.isNotificationPolicyAccessGranted) {
                        notificationManager.setInterruptionFilter(
                            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                        )
                        true
                    } else false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        } else {
            false
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
    // LOCK SCREEN
    // Requires Device Admin to be active
    // ─────────────────────────────
    fun lockScreen(): Boolean {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                true
            } else {
                false
            }
        } catch (e: Exception) { 
            false 
        }
    }

    fun isDeviceAdminActive(): Boolean {
        return try {
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    // ─────────────────────────────
    // WIFI TOGGLE
    // API 28 and below: direct WifiManager toggle
    // API 29+: open system WiFi quick panel (can't toggle directly — Android restriction)
    // ─────────────────────────────
    fun setWifi(enable: Boolean): WifiResult {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wm.isWifiEnabled = enable
                WifiResult.TOGGLED
            } else {
                WifiResult.NEEDS_PANEL
            }
        } catch (e: Exception) { WifiResult.FAILED }
    }

    enum class WifiResult { TOGGLED, NEEDS_PANEL, FAILED }

    // ─────────────────────────────
    // BLUETOOTH TOGGLE
    // API 32 and below: direct BluetoothAdapter toggle (deprecated in 33 but still works)
    // API 33+: opens system settings — no direct toggle allowed for third-party apps
    // ─────────────────────────────
    @Suppress("DEPRECATION", "MissingPermission")
    fun setBluetooth(enable: Boolean): BluetoothResult {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return BluetoothResult.FAILED
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                if (enable) adapter.enable() else adapter.disable()
                BluetoothResult.TOGGLED
            } else {
                BluetoothResult.NEEDS_SETTINGS
            }
        } catch (e: Exception) { BluetoothResult.FAILED }
    }

    enum class BluetoothResult { TOGGLED, NEEDS_SETTINGS, FAILED }
}
