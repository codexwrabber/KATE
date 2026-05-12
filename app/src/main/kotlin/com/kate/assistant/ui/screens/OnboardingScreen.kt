package com.kate.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kate.assistant.ui.theme.*
import kotlinx.coroutines.delay

/**
 * First-launch name capture screen.
 * Shown once — after this, the name is stored and Kate addresses the user personally.
 */
@Composable
fun OnboardingScreen(onNameEntered: (String) -> Unit) {

    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(700))
        delay(300)
        focusRequester.requestFocus()
    }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.length < 2) { nameError = true; return }
        keyboard?.hide()
        onNameEntered(trimmed)
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(KateDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text("👋", fontSize = 48.sp)

            Spacer(Modifier.height(16.dp))

            Text(
                text       = "Hi, I'm Kate.",
                color      = KateCyan,
                fontSize   = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text      = "Your personal AI assistant, built by\nDewrabber's Tech Institute.",
                color     = KateText.copy(alpha = 0.65f),
                fontSize  = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(36.dp))

            Text(
                text      = "What should I call you?",
                color     = KateText,
                fontSize  = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value         = name,
                onValueChange = { name = it; nameError = false },
                modifier      = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder   = { Text("Your name", color = KateText.copy(alpha = 0.35f)) },
                isError       = nameError,
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = KateCyan,
                    unfocusedBorderColor = KateText.copy(alpha = 0.25f),
                    focusedTextColor     = KateText,
                    unfocusedTextColor   = KateText,
                    cursorColor          = KateCyan
                ),
                shape = RoundedCornerShape(12.dp)
            )

            if (nameError) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text     = "Please enter at least 2 characters",
                    color    = Color(0xFFFF5252),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick  = { submit() },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = KateCyan)
            ) {
                Text(
                    text       = "Let's go",
                    color      = KateDark,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    modifier   = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
