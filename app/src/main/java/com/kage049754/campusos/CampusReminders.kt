package com.kage049754.campusos

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CampusReminders {
    private const val CHANNEL_ID = "campusos_reminders"
    private const val PREFS = "campusos_reminders"
    private const val KEY_ALARM_IDS = "alarm_ids"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CampusOS reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Upcoming classes and assignment deadlines"
                }
            )
        }
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun reschedule(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        cancelExisting(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || !notificationsEnabled(app)) return

        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val store = LocalStore(app)
        val ids = mutableSetOf<Int>()
        val now = System.currentTimeMillis()

        store.get("schedule").forEach { record ->
            classOccurrences(record, now, 14).forEach { startMillis ->
                listOf(30L to "Class starts in 30 minutes", 10L to "Class starts in 10 minutes").forEach { pair ->
                    val trigger = startMillis - pair.first * 60_000L
                    if (trigger > now + 5_000L) {
                        val id = alarmId(record.id, startMillis, pair.first)
                        scheduleAlarm(app, alarmManager, id, trigger, "class", record.title, pair.second + " • " + record.startTime + "-" + record.endTime, record.day)
                        ids += id
                    }
                }
            }
        }

        store.get("tasks").filter { !it.done && it.dueDate.isNotBlank() }.forEach { task ->
            val due = taskDueMillis(task)
            if (due > now) {
                val m = taskMeta(task)
                val offsets = if (task.extra.contains("\"_task\"")) m.reminders else listOf(86_400_000L, 3_600_000L)
                offsets.distinct().forEach { offset ->
                    val trigger = due - offset
                    if (trigger > now + 5_000L) {
                        val label = when (offset) {
                            86_400_000L -> "1 day before"
                            43_200_000L -> "12 hours before"
                            7_200_000L -> "2 hours before"
                            1_800_000L -> "30 minutes before"
                            else -> "Reminder"
                        }
                        val id = alarmId(task.id, due, offset)
                        scheduleAlarm(app, alarmManager, id, trigger, "task", task.title, label + " • due " + task.dueDate + " " + task.dueTime, task.subtitle)
                        ids += id
                    }
                }
            }
        }

        prefs.edit().putStringSet(KEY_ALARM_IDS, ids.map(Int::toString).toSet()).apply()
        CampusWidgets.updateAll(app)
    }

    private fun scheduleAlarm(context: Context, alarmManager: AlarmManager, id: Int, trigger: Long, kind: String, title: String, text: String, extra: String) {
        val intent = Intent(context, CampusReminderReceiver::class.java).apply {
            putExtra("kind", kind); putExtra("title", title); putExtra("text", text); putExtra("extra", extra); putExtra("id", id)
        }
        val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun cancelExisting(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_ALARM_IDS, emptySet()).orEmpty().forEach { raw ->
            raw.toIntOrNull()?.let { id ->
                val pending = PendingIntent.getBroadcast(context, id, Intent(context, CampusReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) alarmManager.cancel(pending)
            }
        }
        prefs.edit().remove(KEY_ALARM_IDS).apply()
    }

    private fun alarmId(recordId: Long, whenMillis: Long, offset: Long): Int =
        (recordId xor whenMillis xor offset).hashCode()

    private fun classOccurrences(record: Record, from: Long, days: Int): List<Long> {
        if (record.day.isBlank() || record.startTime.isBlank()) return emptyList()
        val wanted = dayNumber(record.day)
        val hour = record.startTime.toHourOrNull() ?: return emptyList()
        val minute = record.startTime.substringAfter(':', "0").toIntOrNull()?.coerceIn(0, 59) ?: 0
        val out = mutableListOf<Long>()
        val base = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        for (offset in 0..days) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) == wanted) {
                c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
                if (c.timeInMillis > from) out += c.timeInMillis
            }
        }
        return out
    }

    private fun taskDueMillis(record: Record): Long {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(record.dueDate) ?: return 0L
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, record.dueTime.substringBefore(':').toIntOrNull() ?: 9)
            set(Calendar.MINUTE, record.dueTime.substringAfter(':').toIntOrNull() ?: 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun dayNumber(day: String): Int = when (day.lowercase(Locale.getDefault())) {
        "sunday" -> Calendar.SUNDAY; "monday" -> Calendar.MONDAY; "tuesday" -> Calendar.TUESDAY
        "wednesday" -> Calendar.WEDNESDAY; "thursday" -> Calendar.THURSDAY; "friday" -> Calendar.FRIDAY
        "saturday" -> Calendar.SATURDAY; else -> -1
    }
}

class CampusReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        CampusReminders.ensureChannel(app)
        if (!CampusReminders.notificationsEnabled(app)) return
        val title = intent.getStringExtra("title") ?: "CampusOS reminder"
        val text = intent.getStringExtra("text") ?: ""
        val extra = intent.getStringExtra("extra") ?: ""
        val kind = intent.getStringExtra("kind") ?: "reminder"
        val notificationId = intent.getIntExtra("id", title.hashCode())
        val launch = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, "campusos_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (kind == "class") "CampusOS • Next Class" else "CampusOS • To-do Deadline")
            .setContentText(title + " — " + text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title + "\n" + text + "\n" + extra))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(launch).build()
        NotificationManagerCompat.from(app).notify(notificationId, notification)
        CampusReminders.reschedule(app)
    }
}

class CampusBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            CampusReminders.reschedule(context)
            CampusWidgets.updateAll(context)
        }
    }
}

object CampusWidgets {
    private const val LIVE_WIDGET_REQUEST = 78001

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = android.appwidget.AppWidgetManager.getInstance(app)
        updateProvider(app, manager, CampusTodayWidgetProvider::class.java)
        updateProvider(app, manager, CampusNextClassWidgetProvider::class.java)
        updateProvider(app, manager, CampusTaskWidgetProvider::class.java)
        updateProvider(app, manager, CampusScheduleWidgetProvider::class.java)
        ensureLiveUpdates(app, manager)
    }

    private fun updateProvider(context: Context, manager: android.appwidget.AppWidgetManager, provider: Class<*>) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        when (provider) {
            CampusTodayWidgetProvider::class.java -> ids.forEach { CampusTodayWidgetProvider.update(context, manager, it) }
            CampusNextClassWidgetProvider::class.java -> ids.forEach { CampusNextClassWidgetProvider.update(context, manager, it) }
            CampusTaskWidgetProvider::class.java -> ids.forEach { CampusTaskWidgetProvider.update(context, manager, it) }
            CampusScheduleWidgetProvider::class.java -> ids.forEach { CampusScheduleWidgetProvider.update(context, manager, it) }
        }
    }

    private fun ensureLiveUpdates(context: Context, manager: android.appwidget.AppWidgetManager) {
        val hasWidgets = listOf(
            CampusTodayWidgetProvider::class.java,
            CampusNextClassWidgetProvider::class.java,
            CampusTaskWidgetProvider::class.java,
            CampusScheduleWidgetProvider::class.java
        ).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, CampusWidgetTickReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            LIVE_WIDGET_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (hasWidgets) {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                60_000L,
                pending
            )
        } else {
            alarmManager.cancel(pending)
        }
    }
}

class CampusWidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CampusWidgets.updateAll(context.applicationContext)
    }
}

private fun appOpenPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(context, 700, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

class CampusTodayWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun update(context: Context, manager: android.appwidget.AppWidgetManager, id: Int) {
            val v = android.widget.RemoteViews(context.packageName, R.layout.widget_today)
            val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
            v.setTextViewText(R.id.widget_title, "CampusOS")
            v.setTextViewText(R.id.widget_main, SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date()))
            v.setTextViewText(R.id.widget_secondary, "Today • " + day)
            v.setOnClickPendingIntent(R.id.widget_root, appOpenPendingIntent(context)); manager.updateAppWidget(id, v)
        }
    }
}

class CampusNextClassWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun update(context: Context, manager: android.appwidget.AppWidgetManager, id: Int) {
            val v = android.widget.RemoteViews(context.packageName, R.layout.widget_next_class)
            val state = nextWidgetClass(LocalStore(context))
            if (state == null) {
                v.setTextViewText(R.id.widget_title, "Next Class")
                v.setTextViewText(R.id.widget_main, "No upcoming class")
                v.setTextViewText(R.id.widget_secondary, "")
                v.setTextViewText(R.id.widget_status, "")
                v.setProgressBar(R.id.widget_progress, 100, 0, false)
            } else {
                val r = state.record
                val now = System.currentTimeMillis()
                val start = state.startMillis
                val end = state.endMillis
                val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(start))
                val duration = (end - start).coerceAtLeast(1L)
                val progress = if (state.current) (((now - start).toDouble() / duration.toDouble()) * 100.0).toInt().coerceIn(0, 100) else 0
                val status = if (state.current) {
                    "Started " + widgetCountdown((now - start) / 60_000L) + " ago • " +
                        widgetCountdown((end - now) / 60_000L) + " remaining"
                } else {
                    "Starts in " + widgetCountdown((start - now) / 60_000L)
                }
                v.setTextViewText(R.id.widget_title, if (state.current) "Current Class" else "Next Class")
                v.setTextViewText(R.id.widget_main, r.title)
                v.setTextViewText(
                    R.id.widget_secondary,
                    day + " • " + r.startTime + "–" + r.endTime +
                        if (r.room.isNotBlank()) " • Room " + r.room else ""
                )
                v.setTextViewText(R.id.widget_status, status)
                v.setProgressBar(R.id.widget_progress, 100, progress, false)
            }
            v.setOnClickPendingIntent(R.id.widget_root, appOpenPendingIntent(context))
            manager.updateAppWidget(id, v)
        }
    }
}

class CampusScheduleWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun update(context: Context, manager: android.appwidget.AppWidgetManager, id: Int) {
            val v = android.widget.RemoteViews(context.packageName, R.layout.widget_schedule)
            val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
            val rows = mergeAdjacentWidgetClasses(
                LocalStore(context).get("schedule")
                    .filter { it.day.equals(day, true) }
            )
                .take(5)
                .joinToString("\n") { it.startTime + "–" + it.endTime + "  " + it.title + " (" + it.classType + ")" }
            v.setTextViewText(R.id.widget_title, "Today's Class Schedule")
            v.setTextViewText(R.id.widget_main, if (rows.isBlank()) "No classes today" else rows)
            v.setTextViewText(R.id.widget_secondary, day)
            v.setOnClickPendingIntent(R.id.widget_root, appOpenPendingIntent(context))
            manager.updateAppWidget(id, v)
        }
    }
}

class CampusTaskWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun update(context: Context, manager: android.appwidget.AppWidgetManager, id: Int) {
            val v = android.widget.RemoteViews(context.packageName, R.layout.widget_task)
            val text = nextTaskText(LocalStore(context))
            v.setTextViewText(R.id.widget_title, "To-do / Deadline")
            v.setTextViewText(R.id.widget_main, text)
            v.setTextViewText(R.id.widget_secondary, "Tap to open Tasks")
            v.setOnClickPendingIntent(R.id.widget_root, appOpenPendingIntent(context)); manager.updateAppWidget(id, v)
        }
    }
}

private fun widgetMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun mergeAdjacentWidgetClasses(records: List<Record>): List<Record> {
    val sorted = records.sortedWith(
        compareBy<Record>(
            { widgetMinutes(it.startTime) ?: Int.MAX_VALUE },
            { widgetMinutes(it.endTime) ?: Int.MAX_VALUE }
        )
    )
    val out = mutableListOf<Record>()
    for (r in sorted) {
        val previous = out.lastOrNull()
        val previousEnd = previous?.let { widgetMinutes(it.endTime) }
        val start = widgetMinutes(r.startTime)
        val sameSubject = previous?.title?.trim()?.equals(r.title.trim(), true) == true
        val sameType = previous?.classType?.trim()?.equals(r.classType.trim(), true) == true
        if (previous != null && previousEnd != null && start != null && previousEnd == start && sameSubject && sameType) {
            out[out.lastIndex] = previous.copy(
                endTime = r.endTime,
                room = previous.room.ifBlank { r.room },
                professor = previous.professor.ifBlank { r.professor }
            )
        } else {
            out += r
        }
    }
    return out
}

private data class WidgetClassState(
    val record: Record,
    val startMillis: Long,
    val endMillis: Long,
    val current: Boolean
)

private fun nextWidgetClass(store: LocalStore): WidgetClassState? {
    val now = Calendar.getInstance()
    val records = mergeAdjacentWidgetClasses(store.get("schedule"))
    val current = records.mapNotNull { r ->
        val start = widgetOccurrenceMillis(r, now, false) ?: return@mapNotNull null
        val end = widgetEndOccurrenceMillis(r, start) ?: return@mapNotNull null
        if (start <= now.timeInMillis && now.timeInMillis < end) WidgetClassState(r, start, end, true) else null
    }.minByOrNull { it.endMillis }

    if (current != null) return current

    return records.mapNotNull { r ->
        val start = widgetOccurrenceMillis(r, now, true) ?: return@mapNotNull null
        val end = widgetEndOccurrenceMillis(r, start) ?: return@mapNotNull null
        WidgetClassState(r, start, end, false)
    }.minByOrNull { it.startMillis }
}

private fun widgetOccurrenceMillis(record: Record, now: Calendar, futureOnly: Boolean): Long? {
    val wanted = when (record.day.lowercase(Locale.getDefault())) {
        "sunday" -> Calendar.SUNDAY
        "monday" -> Calendar.MONDAY
        "tuesday" -> Calendar.TUESDAY
        "wednesday" -> Calendar.WEDNESDAY
        "thursday" -> Calendar.THURSDAY
        "friday" -> Calendar.FRIDAY
        "saturday" -> Calendar.SATURDAY
        else -> return null
    }
    val startMinute = widgetMinutes(record.startTime) ?: return null
    for (offset in 0..7) {
        val c = now.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, offset)
        if (c.get(Calendar.DAY_OF_WEEK) != wanted) continue
        c.set(Calendar.HOUR_OF_DAY, startMinute / 60)
        c.set(Calendar.MINUTE, startMinute % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (futureOnly && c.timeInMillis <= now.timeInMillis) continue
        return c.timeInMillis
    }
    return null
}

private fun widgetEndOccurrenceMillis(record: Record, startMillis: Long): Long? {
    val endMinute = widgetMinutes(record.endTime) ?: return null
    return Calendar.getInstance().apply {
        timeInMillis = startMillis
        set(Calendar.HOUR_OF_DAY, endMinute / 60)
        set(Calendar.MINUTE, endMinute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun widgetCountdown(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return if (m < 60) "$m min" else {
        val h = m / 60
        val rem = m % 60
        if (rem == 0L) "$h hr" else "$h hr $rem min"
    }
}

private fun nextClassText(store: LocalStore): String {
    val state = nextWidgetClass(store) ?: return "No upcoming class"
    return state.record.title
}

private fun nextTaskText(store: LocalStore): String {
    val task = store.get("tasks").filter { !it.done && it.dueDate.isNotBlank() }.minByOrNull { it.dueDate + it.dueTime }
    return task?.let { it.title + " • Due " + it.dueDate + if (it.dueTime.isNotBlank()) " " + it.dueTime else "" } ?: "No pending deadline"
}
