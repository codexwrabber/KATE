package com.kate.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kate.assistant.services.KateService
import com.kate.assistant.ui.screens.HomeScreen
import com.kate.assistant.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    private val REQUEST_MIC = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkMicPermission()

        setContent {
            KateTheme {
                HomeScreen()
            }
        }
    }

    // 🔴 STEP 1 — Check & request mic permission
    private fun checkMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MIC
            )
        } else {
            startKateService()
        }
    }

    // 🔴 STEP 2 — Handle permission result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_MIC) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("Kate", "✅ Mic permission granted")
                startKateService()
            } else {
                Log.e("Kate", "❌ Mic permission denied")
            }
        }
    }

    // 🔴 STEP 3 — Start service ONLY after permission
    private fun startKateService() {
        val serviceIntent = Intent(this, KateService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
