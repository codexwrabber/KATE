package com.kate.assistant.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.kate.assistant.services.KateService
import com.kate.assistant.ui.screens.HomeScreen
import com.kate.assistant.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    // ✅ Modern permission launcher
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d("Kate", "✅ Mic permission granted")
                startKateService()
            } else {
                Log.e("Kate", "❌ Mic permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Request mic permission
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            KateTheme {
                HomeScreen()
            }
        }
    }

    private fun startKateService() {
        val intent = Intent(this, KateService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
