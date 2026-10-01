package io.github.rastr1sr.radiomonash

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun decodeReminder(value: String): Pair<Long, String>? {
    val start = value.substringBefore('|').toLongOrNull() ?: return null
    return start to value.substringAfter('|')
}

internal class Reminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val followPrefs = context.getSharedPreferences("follows", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val remindedIds = MutableStateFlow(prefs.all.keys.toSet())
    private val followedTitles = MutableStateFlow(followPrefs.all.keys.toSet())
    val reminded: StateFlow<Set<String>> = remindedIds
    val followed: StateFlow<Set<String>> = followedTitles

    fun toggleReminder(show: Show) {
        if (show.id in remindedIds.value) {
            forget(show.id)
        } else {
            remind(show.id, show.start.toEpochMilli(), show.title)
        }
    }

    fun toggleFollow(show: Show, shows: List<Show>) {
        if (show.title in followedTitles.value) {
            followPrefs.edit { remove(show.title) }
            shows.filter { it.title == show.title }.forEach { forget(it.id) }
        } else {
            followPrefs.edit { putBoolean(show.title, true) }
        }
        followedTitles.value = followPrefs.all.keys.toSet()
        remindFollowed(shows)
    }

    fun remindFollowed(shows: List<Show>) {
        val now = System.currentTimeMillis()
        shows.filter {
            it.title in followedTitles.value && it.id !in remindedIds.value &&
                it.start.toEpochMilli() > now
        }.forEach { remind(it.id, it.start.toEpochMilli(), it.title) }
    }

    fun rearm() {
        prefs.all.forEach { (id, value) ->
            val (start, title) = decodeReminder(value.toString()) ?: return@forEach
            if (start > System.currentTimeMillis()) arm(id, start, title) else forget(id)
        }
    }

    fun fired(id: String) {
        prefs.edit { remove(id) }
        remindedIds.value = prefs.all.keys.toSet()
    }

    private fun remind(id: String, start: Long, title: String) {
        prefs.edit { putString(id, "$start|$title") }
        arm(id, start, title)
        remindedIds.value = prefs.all.keys.toSet()
    }

    private fun forget(id: String) {
        prefs.edit { remove(id) }
        alarms.cancel(alarm(id, ""))
        remindedIds.value = prefs.all.keys.toSet()
    }

    private fun arm(id: String, start: Long, title: String) {
        alarms.setWindow(AlarmManager.RTC_WAKEUP, start, 5 * MINUTE_MS, alarm(id, title))
    }

    private fun alarm(id: String, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", id)
            .putExtra("title", title)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = context.radio().reminders
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            reminders.rearm()
            return
        }
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title").orEmpty()
        reminders.fired(id)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = context.getString(R.string.reminder_channel)
        manager.createNotificationChannel(
            NotificationChannel("shows", channel, NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, "shows")
            .setSmallIcon(R.drawable.ic_bell_on)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.starting_now))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id.hashCode(), notification)
    }
}
