package com.kate.assistant.features.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

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
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pending
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pending
                )
            }

            Log.d("ReminderScheduler", "Scheduled: $task in ${delayMs / 1000}s")

        } catch (e: Exception) {
            Log.e("ReminderScheduler", "Schedule failed: ${e.message}")
        }
    }
}
