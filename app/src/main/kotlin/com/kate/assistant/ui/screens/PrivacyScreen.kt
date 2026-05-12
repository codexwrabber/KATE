package com.kate.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.kate.assistant.ui.theme.*

/**
 * Privacy policy screen — shown once on first launch after name entry.
 * The user must explicitly accept before Kate activates.
 * This satisfies Google Play Store data safety requirements.
 */
@Composable
fun PrivacyScreen(userName: String, onAccepted: () -> Unit) {

    var accepted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateDark)
            .padding(horizontal = 24.dp)
    ) {

        Spacer(Modifier.height(48.dp))

        Text(
            text       = "Before we begin, $userName",
            color      = KateCyan,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text     = "A quick word about your privacy",
            color    = KateText.copy(alpha = 0.55f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = KateSurface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PolicySection(
                    title = "What Kate collects",
                    body  = "When you are connected to the internet, your voice is transcribed " +
                            "using Deepgram's speech recognition service. The text of your " +
                            "message is then sent to Anthropic's Claude AI to generate a " +
                            "response. When offline, all processing happens entirely on your " +
                            "device using VOSK — no data leaves your phone."
                )
                PolicySection(
                    title = "What Kate stores locally",
                    body  = "Your name, a randomly generated device ID (used for your " +
                            "subscription), your preferences, and a small buffer of recent " +
                            "voice transcripts used to improve offline accuracy over time. " +
                            "This data never leaves your device unless you are online."
                )
                PolicySection(
                    title = "Third-party services",
                    body  = "Online mode uses:\n" +
                            "• Deepgram — voice-to-text (deepgram.com/privacy)\n" +
                            "• Anthropic Claude — AI responses (anthropic.com/privacy)\n" +
                            "• Google Play — subscription billing\n\n" +
                            "We do not sell your data to advertisers. Ever."
                )
                PolicySection(
                    title = "Your subscription",
                    body  = "Kate assigns your device a unique ID so your subscription is " +
                            "tied to this phone. If you install Kate on another device, it " +
                            "receives its own free tier. Subscriptions are managed and billed " +
                            "by Google Play."
                )
                PolicySection(
                    title = "Built by",
                    body  = "Dewrabber's Tech Institute (D.T.I)\n" +
                            "Kate v2.0.0"
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked         = accepted,
                onCheckedChange = { accepted = it },
                colors          = CheckboxDefaults.colors(
                    checkedColor   = KateCyan,
                    checkmarkColor = KateDark
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text     = "I understand and accept the privacy policy",
                color    = KateText,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick  = { if (accepted) onAccepted() },
            enabled  = accepted,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor         = KateCyan,
                disabledContainerColor = KateSurface
            )
        ) {
            Text(
                text       = "Activate Kate",
                color      = if (accepted) KateDark else KateText.copy(alpha = 0.3f),
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp,
                modifier   = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Text(
        text       = title,
        color      = KateCyan,
        fontSize   = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text       = body,
        color      = KateText.copy(alpha = 0.75f),
        fontSize   = 12.sp,
        lineHeight = 18.sp
    )
    Spacer(Modifier.height(18.dp))
}
