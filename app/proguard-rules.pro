# Kate JNI bridge
-keep class com.kate.assistant.bridge.** { *; }
-keepclassmembers class com.kate.assistant.bridge.KateBridge {
    public void onNativeEvent(java.lang.String, java.lang.String);
}
-keepclasseswithmembernames class * { native <methods>; }

# Room
-keep class com.kate.assistant.data.db.** { *; }

# VOSK
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA — ignore missing AWT classes (not needed on Android)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.Native$AWT
-dontwarn com.sun.jna.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
-dontwarn kotlinx.**
