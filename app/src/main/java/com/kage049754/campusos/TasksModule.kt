package com.kage049754.campusos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private const val TASK_PREFS = "campusos_tasks"

data class TaskSub(val id: Long, val title: String, val done: Boolean)
data class TaskMeta(
    val type: String = "Task",
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

fun taskMeta(r: Record): TaskMeta {
    return runCatching {
        val o = JSONObject(r.extra)
        val reminders = mutableListOf<Long>()
        val a = o.optJSONArray("reminders")
        if (a != null) for (i in 0 until a.length()) reminders += a.optLong(i)
        TaskMeta(
            type = o.optString("type", "Task"),
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
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Record?>(null) }
    val allTasks = remember(revision) { store.get("tasks") }
    val selectedTasks = allTasks.filter { it.dueDate == selectedDate }.sortedBy { taskDue(it) }
    val monthCalendar = remember(shownMonth) { parseDate(shownMonth + "-01") }
    val todayKey = taskDateFormat().format(Date())

    fun moveMonth(delta: Int) {
        val c = monthCalendar.clone() as Calendar
        c.add(Calendar.MONTH, delta)
        shownMonth = SimpleDateFormat("yyyy-MM").format(c.time)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Default.Add, "Add Task")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tasks", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selectedDate = todayKey
                    shownMonth = SimpleDateFormat("yyyy-MM").format(Date())
                }) { Text("Today") }
                IconButton(onClick = { moveMonth(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
                IconButton(onClick = { moveMonth(1) }) { Icon(Icons.Default.ChevronRight, "Next month") }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(monthTitle(monthCalendar), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${allTasks.count { it.dueDate == selectedDate }} tasks",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CalendarMonthGrid(
                month = monthCalendar,
                selectedDate = selectedDate,
                todayDate = todayKey,
                tasks = allTasks,
                onSelectDate = { key ->
                    selectedDate = key
                    shownMonth = SimpleDateFormat("yyyy-MM").format(parseDate(key).time)
                }
            )

            HorizontalDivider()

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (selectedDate == todayKey) "Today" else dateLabel(selectedDate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${selectedTasks.size} task" + if (selectedTasks.size == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { editing = null; showEditor = true }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add task")
                }
            }

            if (selectedTasks.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.EventNote, null, Modifier.size(52.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No tasks for this date", fontWeight = FontWeight.SemiBold)
                    Text("Tap a date or + to add a task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            "${selectedTasks.count { it.done }} / ${selectedTasks.size} completed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(selectedTasks, key = { it.id }) { task ->
                        SimpleTaskCard(task, store, openSubject, { editing = task; showEditor = true }, {
                            store.put("tasks", store.get("tasks").filterNot { it.id == task.id })
                        })
                    }
                }
            }
        }
    }

    if (showEditor) {
        SimpleTaskEditor(store, editing, selectedDate) {
            showEditor = false
            editing = null
        }
    }
}

@Composable
private fun CalendarMonthGrid(
    month: Calendar,
    selectedDate: String,
    todayDate: String,
    tasks: List<Record>,
    onSelectDate: (String) -> Unit
) {
    val first = month.clone() as Calendar
    val firstDay = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val rows = (firstDay + daysInMonth + 6) / 7

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
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
                        Box(Modifier.weight(1f).height(58.dp))
                    } else {
                        val c = month.clone() as Calendar
                        c.set(Calendar.DAY_OF_MONTH, dayNumber)
                        val key = dateKey(c)
                        val dayTasks = tasks.filter { it.dueDate == key }
                        val selected = key == selectedDate
                        val today = key == todayDate
                        Box(
                            Modifier.weight(1f).height(58.dp).padding(2.dp).clickable { onSelectDate(key) },
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        today -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ) {
                                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
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
                                                    RoundedCornerShape(50)
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { checked ->
                    store.put("tasks", store.get("tasks").map { if (it.id == task.id) it.copy(done = checked) else it })
                }
            )
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    var description by remember { mutableStateOf(existing?.subtitle ?: "") }
    var date by remember { mutableStateOf(existing?.dueDate?.takeIf { it.isNotBlank() } ?: selectedDate) }
    var dueTime by remember { mutableStateOf(existing?.dueTime?.takeIf { it.isNotBlank() } ?: "23:59") }
    var subjectId by remember { mutableLongStateOf(existing?.subjectId ?: 0L) }
    val subjects = store.get("subjects")

    AlertDialog(
        onDismissRequest = done,
        title = { Text(if (existing == null) "Add Task" else "Edit Task") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Task / To-do") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                Text("Subject", fontWeight = FontWeight.SemiBold)
                if (subjects.isEmpty()) {
                    Text("No subjects yet. You can still save this task without a subject.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(subjectId == 0L, { subjectId = 0L }, label = { Text("None") })
                        subjects.forEach { subject ->
                            FilterChip(subjectId == subject.id, { subjectId = subject.id }, label = { Text(subject.title) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val c = Calendar.getInstance().apply { time = taskDateFormat().parse(date) ?: Date() }
                        DatePickerDialog(context, { _, y, m, d -> date = "%04d-%02d-%02d".format(y, m + 1, d) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }, Modifier.weight(1f)) { Text("Date") }
                    Button(onClick = {
                        val c = Calendar.getInstance()
                        c.set(Calendar.HOUR_OF_DAY, dueTime.substringBefore(":").toIntOrNull() ?: 23)
                        c.set(Calendar.MINUTE, dueTime.substringAfter(":").toIntOrNull() ?: 59)
                        TimePickerDialog(context, { _, h, m -> dueTime = "%02d:%02d".format(h, m) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                    }, Modifier.weight(1f)) { Text(dueTime) }
                }
                Text("Due: ${dateLabel(date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val list = store.get("tasks")
                    val record = existing?.copy(title = title.trim(), subtitle = description.trim(), subjectId = subjectId, dueDate = date, dueTime = dueTime)
                        ?: Record(id = nextRecordId(store, "tasks"), title = title.trim(), subtitle = description.trim(), subjectId = subjectId, dueDate = date, dueTime = dueTime)
                    store.put("tasks", if (existing == null) list + record else list.map { if (it.id == existing.id) record else it })
                    done()
                }
            }) { Text("Save Task") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@Composable
fun TaskSettingsDialog(store: LocalStore, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCompleted by remember { mutableStateOf(prefs(context).getBoolean("show_completed", true)) }
    var defaultReminder by remember { mutableStateOf(prefs(context).getBoolean("default_reminder", false)) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Tasks Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calendar & To-do", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show completed tasks", Modifier.weight(1f))
                    Switch(checked = showCompleted, onCheckedChange = { showCompleted = it; prefs(context).edit().putBoolean("show_completed", it).apply() })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Default reminder", Modifier.weight(1f))
                    Switch(checked = defaultReminder, onCheckedChange = { defaultReminder = it; prefs(context).edit().putBoolean("default_reminder", it).apply() })
                }
                Text("Tasks are stored offline on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = done) { Text("Done") } }
    )
}
