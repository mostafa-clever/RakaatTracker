package com.ahmed.rakaattracker.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ahmed.rakaattracker.prayer.PrayerTimeCalculator
import com.ahmed.rakaattracker.service.PrayerTrackerService
import java.util.Calendar

class PrayerWindowReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PRAYER_WINDOW_START = "com.ahmed.rakaattracker.PRAYER_WINDOW_START"

        fun scheduleNextPrayerAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = Calendar.getInstance()
            val prayers = PrayerTimeCalculator.calculateToday(date = now)

            prayers.forEachIndexed { index, prayer ->
                val target = prayer.toCalendar(now)
                if (target.timeInMillis > System.currentTimeMillis()) {
                    val intent = Intent(context, PrayerWindowReceiver::class.java).apply {
                        action = ACTION_PRAYER_WINDOW_START
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        2000 + index,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            target.timeInMillis,
                            pi
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            target.timeInMillis,
                            pi
                        )
                    }
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_PRAYER_WINDOW_START && !PrayerTrackerService.isPrayerActive) {
            val svcIntent = Intent(context, PrayerTrackerService::class.java).apply {
                action = PrayerTrackerService.ACTION_START_STANDBY
            }
            context.startForegroundService(svcIntent)
        }
    }
}
