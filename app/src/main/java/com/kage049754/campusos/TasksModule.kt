package com.kage049754.campusos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TASK_PREFS = "campusos_tasks"

fun taskDue(r: Record): Long {
    if (r.dueDate.isBlank()) return Long.MAX_VALUE
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(r.dueDate) }.getOrNull() ?: return Long.MAX_VALUE
    val h = r.dueTime.substringBefore(":").toIntOrNull() ?: 23
    val m = r.dueTime.substringAfter(":").toIntOrNull() ?: 59
    return Calendar.getInstance().apply {
        time = d
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
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
private fun dateLabel(value: String): String {
    val d = runCatching { taskDateFormat().parse(value) }.getOrNull() ?: Date()
    return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(d)
}
private fun dateTitle(c: Calendar): String =
    if (dateKey(c) == dateKey(Calendar.getInstance())) "Today" else SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(c.time)

private fun prefs(context: Context) = context.getSharedPreferences(TASK_PREFS, 0)

@Composable
fun TasksScreen(store: LocalStore, query: String, clear: () -> Unit, openSubject: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision = store.revision
    var selectedDate by rememberSaveable { mutableStateOf(taskDateFormat().format(Date())) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Record?>(null) }

    val selectedCalendar = remember(selectedDate) {
        Calendar.getInstance().apply { time = taskDateFormat().parse(selectedDate) ?: Date() }
    }
    val allTasks = remember(revision) { store.get("tasks") }
    val tasks = allTasks.filter { it.dueDate == selectedDate }.sortedBy { taskDue(it) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Default.Add, "Add Task")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Tasks", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Simple calendar & to-do", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    selectedCalendar.add(Calendar.DAY_OF_YEAR, -1)
                    selectedDate = dateKey(selectedCalendar)
                }) { Icon(Icons.Default.ArrowBack, "Previous day") }

                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dateTitle(selectedCalendar), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(dateLabel(selectedDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                IconButton(onClick = {
                    selectedCalendar.add(Calendar.DAY_OF_YEAR, 1)
                    selectedDate = dateKey(selectedCalendar)
                }) { Icon(Icons.Default.ArrowForward, "Next day") }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val start = selectedCalendar.clone() as Calendar
                start.add(Calendar.DAY_OF_YEAR, -3)
                repeat(7) {
                    val day = start.clone() as Calendar
                    day.add(Calendar.DAY_OF_YEAR, it)
                    val key = dateKey(day)
                    val count = allTasks.count { task -> task.dueDate == key }
                    FilterChip(
                        selected = key == selectedDate,
                        onClick = { selectedDate = key },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(SimpleDateFormat("EEE", Locale.getDefault()).format(day.time))
                                Text(day.get(Calendar.DAY_OF_MONTH).toString())
                                if (count > 0) Text(count.toString() + " task" + if (count == 1) "" else "s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }

            HorizontalDivider()

            if (tasks.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No tasks for this day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Tap + to add a task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            tasks.count { it.done }.toString() + " / " + tasks.size + " completed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(tasks, key = { it.id }) { task ->
                        SimpleTaskCard(
                            task, store,
                            onEdit = { editing = task; showEditor = true },
                            onDelete = { store.put("tasks", store.get("tasks").filterNot { it.id == task.id }) }
                        )
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
private fun SimpleTaskCard(task: Record, store: LocalStore, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { checked ->
                    store.put("tasks", store.get("tasks").map { if (it.id == task.id) it.copy(done = checked) else it })
                }
            )
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

    AlertDialog(
        onDismissRequest = done,
        title = { Text(if (existing == null) "Add Task" else "Edit Task") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Task / To-do") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
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
                Text("Due: " + dateLabel(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val list = store.get("tasks")
                    val record = existing?.copy(title = title.trim(), subtitle = description.trim(), dueDate = date, dueTime = dueTime)
                        ?: Record(id = nextRecordId(store, "tasks"), title = title.trim(), subtitle = description.trim(), dueDate = date, dueTime = dueTime)
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
                Text("Simple Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
