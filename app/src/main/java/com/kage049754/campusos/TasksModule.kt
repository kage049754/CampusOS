package com.kage049754.campusos

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private const val TASK_PREFS = "campusos_tasks"

data class TaskSub(val id: Long, val title: String, val done: Boolean)
data class TaskMeta(
    val type: String = "Note",
    val priority: String = "Medium",
    val status: String = "Not started",
    val reminders: List<Long> = emptyList(),
    val attachments: List<String> = emptyList(),
    val link: String = "",
    val location: String = "",
    val notes: String = "",
    val pinned: Boolean = false,
    val semester: String = "2026–2027 1st Semester",
    val subtasks: List<TaskSub> = emptyList()
)

fun taskPinned(r: Record): Boolean = runCatching { JSONObject(r.extra).optBoolean("pinned", false) }.getOrDefault(false)

fun setTaskPinned(store: LocalStore, taskId: Long, pinned: Boolean) { store.put("tasks", store.get("tasks").map { r -> if (r.id != taskId) r else { val o = runCatching { JSONObject(r.extra) }.getOrElse { JSONObject() }; o.put("pinned", pinned); r.copy(extra = o.toString()) } }) }

fun taskMeta(r: Record): TaskMeta {
    return runCatching {
        val o = JSONObject(r.extra)
        val reminders = mutableListOf<Long>()
        val a = o.optJSONArray("reminders")
        if (a != null) for (i in 0 until a.length()) reminders += a.optLong(i)
        TaskMeta(
            type = o.optString("type", "Note"),
            priority = o.optString("priority", "Medium"),
            status = if (r.done) "Completed" else o.optString("status", "Not started"),
            reminders = reminders
        )
    }.getOrElse { TaskMeta(status = if (r.done) "Completed" else "Not started") }
}

fun taskDue(r: Record): Long {
    if (r.dueDate.isBlank()) return Long.MAX_VALUE
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(r.dueDate) }.getOrNull() ?: return Long.MAX_VALUE
    val h = r.dueTime.substringBefore(":").toIntOrNull() ?: 23
    val m = r.dueTime.substringAfter(":").toIntOrNull() ?: 59
    return Calendar.getInstance().apply {
        time = d
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

fun taskOverdue(r: Record): Boolean = !r.done && taskDue(r) < System.currentTimeMillis()
fun taskToday(r: Record): Boolean = r.dueDate == taskDateFormat().format(Date())
fun taskTomorrow(r: Record): Boolean {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, 1)
    return r.dueDate == taskDateFormat().format(c.time)
}
fun taskLabel(r: Record): String {
    if (r.dueDate.isBlank()) return "No due date"
    val label = if (taskToday(r)) "Today" else if (taskTomorrow(r)) "Tomorrow" else r.dueDate
    return label + if (r.dueTime.isNotBlank()) " • " + r.dueTime else ""
}

private fun taskDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private fun dateKey(c: Calendar) = taskDateFormat().format(c.time)
private fun parseDate(value: String): Calendar = Calendar.getInstance().apply {
    time = taskDateFormat().parse(value) ?: Date()
}
private fun dateLabel(value: String): String {
    val d = runCatching { taskDateFormat().parse(value) }.getOrNull() ?: Date()
    return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(d)
}
private fun monthTitle(c: Calendar): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
private fun prefs(context: Context) = context.getSharedPreferences(TASK_PREFS, 0)

@Composable
fun TasksScreen(store: LocalStore, query: String, clear: () -> Unit, openSubject: (Long) -> Unit) {
    val revision = store.revision
    var selectedDate by rememberSaveable { mutableStateOf(taskDateFormat().format(Date())) }
    var shownMonth by rememberSaveable { mutableStateOf(SimpleDateFormat("yyyy-MM").format(Date())) }
    var showDateWindow by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Record?>(null) }

    LaunchedEffect(query) {
        if (query == "__ADD__") {
            showDateWindow = true
            editing = null
            clear()
        }
    }

    val allTasks = remember(revision) { store.get("tasks") }
    val monthCalendar = remember(shownMonth) { parseDate(shownMonth + "-01") }
    val todayKey = taskDateFormat().format(Date())

    Column(Modifier.fillMaxSize()) {
        CalendarMonthGrid(
            month = monthCalendar,
            selectedDate = selectedDate,
            todayDate = todayKey,
            tasks = allTasks,
            onSwipeMonth = { delta ->
                val c = monthCalendar.clone() as Calendar
                c.add(Calendar.MONTH, delta)
                shownMonth = SimpleDateFormat("yyyy-MM").format(c.time)
            },
            onSelectDate = { key ->
                selectedDate = key
                shownMonth = SimpleDateFormat("yyyy-MM").format(parseDate(key).time)
                editing = null
                showDateWindow = true
            }
        )
    }

    if (showDateWindow) {
        TaskDateWindow(
            selectedDate = selectedDate,
            store = store,
            openSubject = openSubject,
            editing = editing,
            onEdit = { task -> editing = task },
            onAdd = { },
            onDismiss = {
                showDateWindow = false
                editing = null
            },
            onSaved = { editing = null }
        )
    }
}

@Composable
private fun TaskDateWindow(
    selectedDate: String,
    store: LocalStore,
    openSubject: (Long) -> Unit,
    editing: Record?,
    onEdit: (Record) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val revision = store.revision
    val tasks = remember(selectedDate, revision) {
        store.get("tasks")
            .filter { it.dueDate == selectedDate }
            .sortedWith(compareByDescending<Record> { taskPinned(it) }.thenBy { taskDue(it) })
    }

    if (editing != null || showEditor) {
        SimpleTaskEditor(store = store, existing = editing, selectedDate = selectedDate) {
            showEditor = false
            onSaved()
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateLabel(selectedDate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(tasks.size.toString() + " note" + if (tasks.size == 1) "" else "s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                if (tasks.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.EventNote, null, Modifier.size(44.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No notes on this date", fontWeight = FontWeight.SemiBold)
                        Text("Add something for this date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            SimpleTaskCard(
                                task = task,
                                store = store,
                                openSubject = openSubject,
                                onEdit = { onEdit(task) },
                                onDelete = { store.put("tasks", store.get("tasks").filterNot { it.id == task.id }) }
                            )
                        }
                    }
                }
                Button(onClick = { showEditor = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add note to this date")
                }
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(
    month: Calendar,
    selectedDate: String,
    todayDate: String,
    tasks: List<Record>,
    onSwipeMonth: (Int) -> Unit,
    onSelectDate: (String) -> Unit
) {
    val first = month.clone() as Calendar
    val firstDay = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val rows = (firstDay + daysInMonth + 6) / 7
    var dragTotal by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(month.timeInMillis) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal > 80f) onSwipeMonth(-1)
                        else if (dragTotal < -80f) onSwipeMonth(1)
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                )
            }
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(monthTitle(month), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Swipe to change month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Box(Modifier.weight(1f).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    val dayNumber = index - firstDay + 1
                    if (dayNumber !in 1..daysInMonth) {
                        Box(Modifier.weight(1f).height(64.dp))
                    } else {
                        val c = month.clone() as Calendar
                        c.set(Calendar.DAY_OF_MONTH, dayNumber)
                        val key = dateKey(c)
                        val dayTasks = tasks.filter { it.dueDate == key }
                        val selected = key == selectedDate
                        val today = key == todayDate
                        Box(Modifier.weight(1f).height(64.dp).padding(2.dp).clickable { onSelectDate(key) }, contentAlignment = Alignment.TopCenter) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        today -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ) {
                                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            dayNumber.toString(),
                                            color = when {
                                                selected -> MaterialTheme.colorScheme.onPrimary
                                                today -> MaterialTheme.colorScheme.onPrimaryContainer
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                if (dayTasks.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        dayTasks.take(3).forEach { task ->
                                            Box(
                                                Modifier.size(5.dp).background(
                                                    if (task.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(50.dp)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleTaskCard(
    task: Record,
    store: LocalStore,
    openSubject: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val subject = if (task.subjectId != 0L) store.get("subjects").firstOrNull { it.id == task.subjectId } else null
    Card(Modifier.fillMaxWidth().pointerInput(task.id, taskPinned(task)) { detectTapGestures(onLongPress = { setTaskPinned(store, task.id, !taskPinned(task)) }) }, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { checked ->
                    store.put("tasks", store.get("tasks").map { if (it.id == task.id) it.copy(done = checked) else it })
                }
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { if (taskPinned(task)) { Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }; Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (subject != null) {
                    TextButton(onClick = { openSubject(subject.id) }, contentPadding = PaddingValues(0.dp)) { Text(subject.title) }
                }
                if (task.subtitle.isNotBlank()) Text(task.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.dueTime.isNotBlank()) Text(task.dueTime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

@Composable
private fun SimpleTaskEditor(store: LocalStore, existing: Record?, selectedDate: String, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var date by remember { mutableStateOf(existing?.dueDate?.takeIf { it.isNotBlank() } ?: selectedDate) }
    var dueTime by remember { mutableStateOf(existing?.dueTime?.takeIf { it.isNotBlank() } ?: "23:59") }
    var subjectId by remember { mutableLongStateOf(existing?.subjectId ?: 0L) }
    val subjects = store.get("subjects")

    AlertDialog(
        onDismissRequest = done,
        title = { Text(if (existing == null) "Add Note" else "Edit Note") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Subject", fontWeight = FontWeight.SemiBold)
                if (subjects.isEmpty()) {
                    Text("No subjects available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        subjects.forEach { subject ->
                            FilterChip(
                                selected = subjectId == subject.id,
                                onClick = { subjectId = subject.id },
                                label = { Text(subject.title) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Note") },
                    placeholder = { Text("Type what you want to remember...") },
                    minLines = 4,
                    maxLines = 7
                )

                Text("Time", fontWeight = FontWeight.SemiBold)

                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        showAlarmTimePicker(context, dueTime) { picked -> dueTime = picked }
                    },
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Alarm time", fontWeight = FontWeight.SemiBold)
                            Text(
                                formatAlarmTime(dueTime),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = "Pick time")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Text(
                        "Date: " + dateLabel(date),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val list = store.get("tasks")
                    val record = existing?.copy(
                        title = title.trim(),
                        subtitle = "",
                        subjectId = subjectId,
                        dueDate = date,
                        dueTime = dueTime
                    ) ?: Record(
                        id = nextRecordId(store, "tasks"),
                        title = title.trim(),
                        subtitle = "",
                        subjectId = subjectId,
                        dueDate = date,
                        dueTime = dueTime
                    )
                    store.put("tasks", if (existing == null) list + record else list.map { if (it.id == existing.id) record else it })
                    done()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

private fun formatAlarmTime(value: String): String {
    val h = value.substringBefore(":").toIntOrNull() ?: 23
    val m = value.substringAfter(":").toIntOrNull() ?: 59
    val am = h < 12
    val hour = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "%d:%02d %s".format(hour, m, if (am) "AM" else "PM")
}

private fun showAlarmTimePicker(context: Context, current: String, onPicked: (String) -> Unit) {
    val initial = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, current.substringBefore(":").toIntOrNull()?.coerceIn(0, 23) ?: 23)
        set(Calendar.MINUTE, current.substringAfter(":").toIntOrNull()?.coerceIn(0, 59) ?: 59)
    }
    android.app.TimePickerDialog(
        context,
        { _, h, m -> onPicked("%02d:%02d".format(h, m)) },
        initial.get(Calendar.HOUR_OF_DAY),
        initial.get(Calendar.MINUTE),
        false
    ).show()
}

@Composable
fun TaskSettingsDialog(store: LocalStore, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCompleted by remember { mutableStateOf(prefs(context).getBoolean("show_completed", true)) }
    var defaultReminder by remember { mutableStateOf(prefs(context).getBoolean("default_reminder", false)) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Notes Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calendar & Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show completed notes", Modifier.weight(1f))
                    Switch(checked = showCompleted, onCheckedChange = { showCompleted = it; prefs(context).edit().putBoolean("show_completed", it).apply() })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Default note reminder", Modifier.weight(1f))
                    Switch(checked = defaultReminder, onCheckedChange = { defaultReminder = it; prefs(context).edit().putBoolean("default_reminder", it).apply() })
                }
                Text("Notes are stored offline on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = done) { Text("Done") } }
    )
}
