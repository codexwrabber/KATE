package com.kate.assistant.features.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log

class KateDeviceController(private val context: Context) {

    fun openApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("KateDevice", "openApp failed: ${e.message}")
            false
        }
    }

    fun makeCall(number: String) {
        try {
            // FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_CLEAR_TOP prevents
            // Kate's service from pulling focus back and ending the call
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            )
        } catch (e: Exception) {
            Log.e("KateDevice", "Call failed: ${e.message}")
        }
    }

    fun sendSms(number: String, message: String) {
        try {
            SmsManager.getDefault()
                .sendTextMessage(number.trim(), null, message, null, null)
        } catch (e: Exception) {
            Log.e("KateDevice", "SMS failed: ${e.message}")
        }
    }
}
