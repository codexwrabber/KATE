package com.kate.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus
import com.kate.assistant.ui.theme.*

@Composable
fun HomeScreen() {
    var statusText by remember { mutableStateOf("Listening...") }
    var lastHeard by remember { mutableStateOf("") }

    // Observe Kate events
    LaunchedEffect(Unit) {
        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.WakeWordDetected ->
                    statusText = "Wake word detected!"
                is KateEvent.IntentEvent ->
                    statusText = "Intent: ${event.intent} — ${event.entity}"
                is KateEvent.Error ->
                    statusText = "Error: ${event.message}"
                else -> Unit
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.12f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KateDark),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale)
                .background(KateCyan.copy(alpha = 0.08f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(KateCyan.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("K", color = KateCyan, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "K.A.T.E",
                color = KateCyan,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                color = KateText.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            if (lastHeard.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Heard: \"$lastHeard\"",
                    color = KateCyan.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
