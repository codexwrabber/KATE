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
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kate.assistant.data.preferences.KatePreferences
import com.kate.assistant.services.KateService
import com.kate.assistant.ui.screens.*
import com.kate.assistant.ui.theme.KateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: KatePreferences

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { launchKate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KatePreferences(this)

        // Ensure device ID exists from first boot
        lifecycleScope.launch { prefs.ensureDeviceId() }

        setContent {
            KateTheme {
                KateNavGraph(prefs = prefs, onReadyToLaunch = {
                    startKateService()
                    requestBatteryOptimizationExemption()
                    Handler(Looper.getMainLooper()).postDelayed({ checkAccessibility() }, 6000)
                })
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        ).also { list ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                list.add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) launchKate()
        else permLauncher.launch(needed.toTypedArray())
    }

    private fun launchKate() { /* permissions satisfied — NavGraph handles routing */ }

    private fun startKateService() {
        val intent = Intent(this, KateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else startService(intent)
    }

    @Suppress("DEPRECATION")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkAccessibility() {
        if (!isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val target = "$packageName/com.kate.assistant.services.KateAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(target, ignoreCase = true)) return true
            }
            false
        } catch (_: Exception) { false }
    }
}

// ── Navigation graph ──────────────────────────────────────────────────────────

@Composable
fun KateNavGraph(prefs: KatePreferences, onReadyToLaunch: () -> Unit) {

    val navController  = rememberNavController()
    var splashDone     by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf<Boolean?>(null) }
    var privacyDone    by remember { mutableStateOf<Boolean?>(null) }
    var userName       by remember { mutableStateOf("") }
    var isSubscribed   by remember { mutableStateOf(false) }
    var dailyUsed      by remember { mutableStateOf(0) }

    // Load persisted state
    val onboardingFlow by prefs.onboardingComplete.collectAsState(initial = null)
    val privacyFlow    by prefs.privacyAccepted.collectAsState(initial = null)
    val nameFlow       by prefs.userName.collectAsState(initial = "")
    val subFlow        by prefs.isSubscribed.collectAsState(initial = false)
    val usageFlow      by prefs.dailyRequestCount.collectAsState(initial = 0)

    LaunchedEffect(onboardingFlow, privacyFlow, nameFlow, subFlow, usageFlow) {
        if (onboardingFlow == null || privacyFlow == null) return@LaunchedEffect
        onboardingDone = onboardingFlow
        privacyDone    = privacyFlow
        userName       = nameFlow
        isSubscribed   = subFlow
        dailyUsed      = usageFlow
    }

    // Determine start destination once we have data
    val startDestination = remember(splashDone, onboardingDone, privacyDone) {
        when {
            !splashDone                -> "splash"
            onboardingDone == false    -> "onboarding"
            privacyDone == false       -> "privacy"
            else                       -> "home"
        }
    }

    NavHost(navController = navController, startDestination = "splash") {

        composable("splash") {
            SplashScreen(onFinished = {
                splashDone = true
                val next = when {
                    onboardingDone == false -> "onboarding"
                    privacyDone == false    -> "privacy"
                    else                    -> { onReadyToLaunch(); "home" }
                }
                navController.navigate(next) { popUpTo("splash") { inclusive = true } }
            })
        }

        composable("onboarding") {
            val scope = rememberCoroutineScope()
            OnboardingScreen(onNameEntered = { name ->
                userName = name
                scope.launch {
                    prefs.setUserName(name)
                    prefs.setOnboardingComplete(true)
                }
                navController.navigate("privacy") {
                    popUpTo("onboarding") { inclusive = true }
                }
            })
        }

        composable("privacy") {
            val scope = rememberCoroutineScope()
            PrivacyScreen(userName = userName.ifBlank { "there" }, onAccepted = {
                scope.launch { prefs.setPrivacyAccepted(true) }
                onReadyToLaunch()
                navController.navigate("home") { popUpTo("privacy") { inclusive = true } }
            })
        }

        composable("home") {
            HomeScreen(
                userName     = userName,
                isSubscribed = isSubscribed,
                onOpenSub    = { navController.navigate("subscription") }
            )
        }

        composable("subscription") {
            SubscriptionScreen(
                isSubscribed = isSubscribed,
                dailyUsed    = dailyUsed,
                onUpgrade    = { /* Phase 3: launch Play Billing flow */ },
                onBack       = { navController.popBackStack() }
            )
        }
    }
}
