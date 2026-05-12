package com.kate.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kate.assistant.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(900))
        delay(1800)
        alpha.animateTo(0f, tween(500))
        onFinished()
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(KateDark)
            .alpha(alpha.value),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // Logo placeholder — swap with your actual logo drawable when ready
            Text(
                text       = "K",
                color      = KateCyan,
                fontSize   = 96.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text       = "K.A.T.E",
                color      = KateCyan,
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text      = "Kernel Autonomous Task Engine",
                color     = KateText.copy(alpha = 0.5f),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            Text(
                text      = "by Dewrabber's Tech Institute",
                color     = KateText.copy(alpha = 0.35f),
                fontSize  = 10.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text      = "D . T . I",
                color     = KateCyan.copy(alpha = 0.4f),
                fontSize  = 10.sp,
                letterSpacing = 4.sp
            )
        }
    }
}
