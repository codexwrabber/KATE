package com.kate.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kate.assistant.services.KateService
import com.kate.assistant.ui.screens.HomeScreen
import com.kate.assistant.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startKateService()
        checkAccessibility()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KateTheme { HomeScreen() } }

        val needed = mutableListOf<String>()
        val perms  = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        ).also { list ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                (list as MutableList).add(Manifest.permission.POST_NOTIFICATIONS)
        }

        perms.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm)
                != PackageManager.PERMISSION_GRANTED) needed.add(perm)
        }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else { startKateService(); checkAccessibility() }
    }

    private fun startKateService() {
        val intent = Intent(this, KateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else startService(intent)
    }

    private fun checkAccessibility() {
        // Delay so Kate speaks first before opening settings
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAccessibilityEnabled()) {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, 4000)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/com.kate.assistant.services.KateAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            TextUtils.SimpleStringSplitter(':').also { it.setString(enabled) }
                .asSequence().any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) { false }
    }
}
