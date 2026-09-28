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

private fun prefs(context: Context) =
    context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

internal fun decodeReminder(value: String): Pair<Long, String>? {
    val start = value.substringBefore('|').toLongOrNull() ?: return null
    return start to value.substringAfter('|')
}

fun reminders(context: Context): Set<String> = prefs(context).all.keys

private fun followPrefs(context: Context) =
    context.getSharedPreferences("follows", Context.MODE_PRIVATE)

fun follows(context: Context): Set<String> = followPrefs(context).all.keys

internal fun follow(context: Context, title: String, shows: List<Show>) {
    followPrefs(context).edit { putBoolean(title, true) }
    remindFollowed(context, shows)
}

internal fun unfollow(context: Context, title: String, shows: List<Show>) {
    followPrefs(context).edit { remove(title) }
    shows.filter { it.title == title }.forEach { forget(context, it.id) }
}

internal fun remindFollowed(context: Context, shows: List<Show>) {
    val titles = follows(context)
    val set = reminders(context)
    val now = System.currentTimeMillis()
    shows.filter { it.title in titles && it.id !in set && it.start.toEpochMilli() > now }
        .forEach { remind(context, it.id, it.start.toEpochMilli(), it.title) }
}

fun remind(context: Context, id: String, start: Long, title: String) {
    prefs(context).edit { putString(id, "$start|$title") }
    arm(context, id, start, title)
}

fun forget(context: Context, id: String) {
    prefs(context).edit { remove(id) }
    context.getSystemService(AlarmManager::class.java).cancel(alarm(context, id, ""))
}

private fun arm(context: Context, id: String, start: Long, title: String) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    alarms.setWindow(AlarmManager.RTC_WAKEUP, start, 5 * 60_000L, alarm(context, id, title))
}

private fun alarm(context: Context, id: String, title: String): PendingIntent {
    val intent = Intent(context, ReminderReceiver::class.java)
        .putExtra("id", id)
        .putExtra("title", title)
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            prefs(context).all.forEach { (id, value) ->
                val (start, title) = decodeReminder(value.toString()) ?: return@forEach
                val now = System.currentTimeMillis()
                if (start > now) arm(context, id, start, title) else forget(context, id)
            }
            return
        }
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title").orEmpty()
        prefs(context).edit { remove(id) }
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
