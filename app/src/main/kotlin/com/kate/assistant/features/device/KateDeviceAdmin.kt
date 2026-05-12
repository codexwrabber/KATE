package com.kate.assistant.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class KateDeviceAdmin : DeviceAdminReceiver() {
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Kate Device Admin enabled", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Kate Device Admin disabled", Toast.LENGTH_SHORT).show()
    }
}
