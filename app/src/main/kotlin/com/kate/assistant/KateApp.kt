package com.kate.assistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

// @HiltAndroidApp is REQUIRED when the Hilt Gradle plugin is applied.
// Without it, Hilt's generated component code is never wired in and the
// app crashes instantly at launch with:
//   "Hilt components were generated but the application class is not annotated"
// This was the #1 cause of the app closing immediately after launch.
@HiltAndroidApp
class KateApp : Application()
