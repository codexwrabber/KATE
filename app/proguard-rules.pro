-keep class com.kate.assistant.bridge.** { *; }
-keepclassmembers class com.kate.assistant.bridge.KateBridge {
    public void onNativeEvent(java.lang.String, java.lang.String);
}
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.kate.assistant.data.db.** { *; }
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
