package com.kate.assistant.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus
import com.kate.assistant.ui.theme.*

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Initializing...") }
    var showAccessibilityGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.Error -> statusText = event.message
                else -> Unit
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue    = 1f,
        targetValue     = 1.12f,
        animationSpec   = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label           = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KateDark),
        contentAlignment = Alignment.Center
    ) {
        // Glow ring
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale)
                .background(KateCyan.copy(alpha = 0.08f), CircleShape)
        )

        // Core orb
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(KateCyan.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("K", color = KateCyan, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }

        // Bottom info
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "K.A.T.E",
                color      = KateCyan,
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                statusText,
                color     = KateText.copy(alpha = 0.6f),
                fontSize  = 13.sp,
                textAlign = TextAlign.Center,
                maxLines  = 2
            )

            Spacer(Modifier.height(16.dp))

            // Accessibility guide card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAccessibilityGuide = !showAccessibilityGuide },
                shape  = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = KateSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚙ Enable Accessibility",
                        color      = KateCyan,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (showAccessibilityGuide) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. Tap below to open Accessibility Settings\n" +
                            "2. Tap \"Kate Assistant\" → you'll see 'Restricted'\n" +
                            "3. Go back → Settings → Apps → Kate\n" +
                            "4. Tap ⋮ menu → \"Allow restricted settings\"\n" +
                            "5. Return to Accessibility → Enable Kate Assistant",
                            color    = KateText.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = KateCyan)
                        ) {
                            Text("Open Accessibility Settings", color = KateDark, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(6.dp))

                        // Shortcut to app info for "Allow restricted settings"
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KateCyan)
                        ) {
                            Text("Open Kate App Info (Allow restricted settings here)")
                        }
                    }
                }
            }
        }
    }
