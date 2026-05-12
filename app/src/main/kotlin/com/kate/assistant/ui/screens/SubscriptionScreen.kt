package com.kate.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kate.assistant.ui.theme.*

/**
 * Subscription management screen.
 * Play Billing purchase flow will be wired in Phase 3.
 * For now this shows the tier comparison and a placeholder CTA.
 */
@Composable
fun SubscriptionScreen(
    isSubscribed: Boolean,
    dailyUsed: Int,
    onUpgrade: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateDark)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(48.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back", color = KateCyan, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text       = "Kate Pro",
            color      = KateCyan,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            text     = "by Dewrabber's Tech Institute",
            color    = KateText.copy(alpha = 0.4f),
            fontSize = 11.sp
        )

        Spacer(Modifier.height(24.dp))

        // Current status badge
        if (isSubscribed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = Color(0xFF003D26))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✓", color = Color(0xFF00E676), fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text       = "Kate Pro — Active",
                        color      = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // Usage bar
            val fraction = (dailyUsed / 20f).coerceIn(0f, 1f)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = KateSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Today's usage", color = KateText.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("$dailyUsed / 20 free", color = KateCyan, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress        = { fraction },
                        modifier        = Modifier.fillMaxWidth(),
                        color           = if (fraction >= 1f) Color(0xFFFF5252) else KateCyan,
                        trackColor      = KateText.copy(alpha = 0.1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Tier comparison
        TierCard(
            title     = "Free",
            price     = "Always free",
            highlight = false,
            features  = listOf(
                "20 online AI requests per day",
                "Unlimited offline voice commands",
                "All local device controls",
                "Reminders & calendar"
            )
        )

        Spacer(Modifier.height(12.dp))

        TierCard(
            title     = "Pro",
            price     = "Billed via Google Play",
            highlight = true,
            features  = listOf(
                "500 online AI requests per day",
                "Claude Sonnet for complex tasks",
                "Priority response speed",
                "Everything in Free",
                "Support D.T.I development ❤"
            )
        )

        Spacer(Modifier.weight(1f))

        if (!isSubscribed) {
            Button(
                onClick  = onUpgrade,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = KateCyan)
            ) {
                Text(
                    text       = "Upgrade to Kate Pro",
                    color      = KateDark,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text      = "Purchases managed by Google Play · Cancel anytime",
                color     = KateText.copy(alpha = 0.3f),
                fontSize  = 10.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TierCard(
    title: String,
    price: String,
    highlight: Boolean,
    features: List<String>
) {
    val borderColor = if (highlight) KateCyan else KateText.copy(alpha = 0.15f)
    val bgColor     = if (highlight) KateSurface else KateDark

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (highlight) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = title,
                    color      = if (highlight) KateCyan else KateText,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (highlight) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape  = RoundedCornerShape(4.dp),
                        color  = KateCyan.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text     = "RECOMMENDED",
                            color    = KateCyan,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(price, color = KateText.copy(alpha = 0.45f), fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            features.forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("•  ", color = KateCyan, fontSize = 12.sp)
                    Text(feature, color = KateText.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
