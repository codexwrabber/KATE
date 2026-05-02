package com.kate.assistant.features.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(task: String, delayMs: Long) {
        try {
            val triggerTime = System.currentTimeMillis() + delayMs
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("task", task)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                task.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            }
            Log.d("ReminderScheduler", "Scheduled: $task in ${delayMs/1000}s")
        } catch (e: Exception) {
            Log.e("ReminderScheduler", "Schedule failed: ${e.message}")
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task    = intent.getStringExtra("task") ?: "Your reminder"
        val channel = "kate_reminders"
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(channel, "Kate Reminders", NotificationManager.IMPORTANCE_HIGH)
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle("Kate Reminder")
            .setContentText(task)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        manager.notify(task.hashCode(), notification)
    }
}
