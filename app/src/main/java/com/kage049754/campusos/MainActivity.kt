package com.kage049754.campusos

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

data class Record(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val subtitle: String = "",
    val extra: String = "",
    val value: Double = 0.0,
    val done: Boolean = false,
    val day: String = "", val startTime: String = "", val endTime: String = "",
    val room: String = "", val professor: String = "", val color: Long = 0L
)
data class FileRecord(val name: String, val size: Long)

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("campusos", Context.MODE_PRIVATE)
    private fun read(key: String): MutableList<Record> {
        val out = mutableListOf<Record>()
        val a = JSONArray(prefs.getString(key, "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += Record(o.getLong("id"), o.getString("title"), o.optString("subtitle"),
                o.optString("extra"), o.optDouble("value", 0.0), o.optBoolean("done", false),
                o.optString("day"), o.optString("startTime"), o.optString("endTime"), o.optString("room"), o.optString("professor"), o.optLong("color", 0L))
        }
        return out
    }
    private fun save(key: String, list: List<Record>) {
        val a = JSONArray()
        list.forEach { r -> a.put(JSONObject().apply {
            put("id", r.id); put("title", r.title); put("subtitle", r.subtitle)
            put("extra", r.extra); put("value", r.value); put("done", r.done)
            put("day", r.day); put("startTime", r.startTime); put("endTime", r.endTime); put("room", r.room); put("professor", r.professor); put("color", r.color)
        }) }
        prefs.edit().putString(key, a.toString()).apply()
    }
    fun get(key: String) = read(key)
    fun put(key: String, list: List<Record>) = save(key, list)
    fun delete(key: String, id: Long) = save(key, read(key).filterNot { it.id == id })
    fun pin() = prefs.getString("pin", "") ?: ""
    fun setPin(v: String) = prefs.edit().putString("pin", v).apply()
    fun theme() = prefs.getString("theme", "system") ?: "system"
    fun setTheme(v: String) = prefs.edit().putString("theme", v).apply()
    fun lockEnabled() = prefs.getBoolean("lock", false)
    fun setLockEnabled(v: Boolean) = prefs.edit().putBoolean("lock", v).apply()
    fun scheduleDayHighlight() = prefs.getLong("schedule_day_highlight", 0xFF1976D2L)
    fun setScheduleDayHighlight(v: Long) = prefs.edit().putLong("schedule_day_highlight", v).apply()
    fun scheduleTimeHighlight() = prefs.getLong("schedule_time_highlight", 0xFF43A047L)
    fun setScheduleTimeHighlight(v: Long) = prefs.edit().putLong("schedule_time_highlight", v).apply()
    fun backupJson(): String {
        val root = JSONObject()
        listOf("subjects","schedule","tasks","reviewers","grades","attendance","expenses").forEach {
            root.put(it, prefs.getString(it, "[]"))
        }
        root.put("theme", theme()); root.put("lock", lockEnabled()); return root.toString(2)
    }
    fun restoreJson(json: String) {
        val root = JSONObject(json); val e = prefs.edit()
        listOf("subjects","schedule","tasks","reviewers","grades","attendance","expenses").forEach {
            if (root.has(it)) e.putString(it, root.getString(it))
        }
        if (root.has("theme")) e.putString("theme", root.getString("theme"))
        if (root.has("lock")) e.putBoolean("lock", root.getBoolean("lock"))
        e.apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CampusOSApp(this) }
    }
}

enum class Screen(val label: String) {
    HOME("Home"), SCHEDULE("Schedule"), TASKS("Tasks"), ACADEMICS("Academics"),
    FINANCE("Finance"), FILES("Files"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusOSApp(activity: Activity) {
    val store = remember { LocalStore(activity) }
    var theme by remember { mutableStateOf(store.theme()) }
    var locked by remember { mutableStateOf(store.lockEnabled() && store.pin().isNotBlank()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var search by remember { mutableStateOf("") }

    if (locked) { LockScreen(store) { locked = false }; return }

    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("CampusOS", fontWeight = FontWeight.Bold) },
                    actions = { IconButton({ screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") } }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(Screen.HOME, Screen.SCHEDULE, Screen.TASKS, Screen.ACADEMICS, Screen.FINANCE).forEach {
                        NavigationBarItem(screen == it, { screen = it }, icon = { Icon(iconFor(it), it.label) }, label = { Text(it.label) })
                    }
                }
            },
            floatingActionButton = {
                if (screen in listOf(Screen.SCHEDULE, Screen.TASKS, Screen.ACADEMICS, Screen.FINANCE))
                    FloatingActionButton({ search = "__ADD__" }) { Icon(Icons.Default.Add, "Add") }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(store) { screen = it }
                    Screen.SCHEDULE -> ScheduleScreen(store, search) { search = "" }
                    Screen.TASKS -> CrudScreen("Assignments & To-do", "tasks", store, search) { search = "" }
                    Screen.ACADEMICS -> AcademicsScreen(store, search) { search = "" }
                    Screen.FINANCE -> CrudScreen("Allowance & Expenses", "expenses", store, search) { search = "" }
                    Screen.FILES -> FilesScreen()
                    Screen.SETTINGS -> SettingsScreen(store, theme, { theme = it; store.setTheme(it) }) { locked = true }
                }
                if (screen != Screen.HOME && screen != Screen.SETTINGS && screen != Screen.FILES) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedTextField(
                            value = search.takeUnless { it == "__ADD__" } ?: "",
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search "+screen.label) },
                            leadingIcon = { Icon(Icons.Default.Search, null) }
                        )
                    }
                }
            }
        }
    }
}

private fun iconFor(s: Screen) = when(s) {
    Screen.HOME -> Icons.Default.Home
    Screen.SCHEDULE -> Icons.Default.CalendarMonth
    Screen.TASKS -> Icons.Default.CheckCircle
    Screen.ACADEMICS -> Icons.Default.School
    Screen.FINANCE -> Icons.Default.AccountBalanceWallet
    Screen.FILES -> Icons.Default.Folder
    Screen.SETTINGS -> Icons.Default.Settings
}

@Composable
fun HomeScreen(store: LocalStore, go: (Screen) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val tasks = remember(tick) { store.get("tasks") }
    val schedule = remember(tick) { store.get("schedule") }
    val grades = remember(tick) { store.get("grades") }
    val expenses = remember(tick) { store.get("expenses") }
    val attendance = remember(tick) { store.get("attendance") }
    val gpa = if (grades.isEmpty()) 0.0 else grades.sumOf { it.value } / grades.size
    val spent = expenses.sumOf { it.value }
    val date = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Good day 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Classes", schedule.size.toString(), Modifier.weight(1f))
            StatCard("Tasks", tasks.count { !it.done }.toString(), Modifier.weight(1f))
            StatCard("GPA", "%.2f".format(gpa), Modifier.weight(1f))
        }}
        item { SectionTitle("Today") }
        if (schedule.isEmpty()) item { EmptyCard("No classes yet. Add your schedule.") }
        items(schedule.take(5), key = { it.id }) { RecordCard(it, "schedule", store) { tick++ } }
        item { SectionTitle("Academic snapshot") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Attendance", attendance.count { it.done }.toString(), Modifier.weight(1f))
            StatCard("Spent", "₱%.0f".format(spent), Modifier.weight(1f))
        }}
        item { SectionTitle("Quick access") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmallAction("Subjects", Icons.Default.School) { go(Screen.ACADEMICS) }
            SmallAction("Files", Icons.Default.Folder) { go(Screen.FILES) }
            SmallAction("Tasks", Icons.Default.CheckCircle) { go(Screen.TASKS) }
        }}
    }
}

@Composable
fun ScheduleScreen(store: LocalStore, query: String, clear: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(30000)
        }
    }
    LaunchedEffect(query) { if (query == "__ADD__") showAdd = true }
    val all = remember(refresh, query) { store.get("schedule").filter {
        query.isBlank() || query == "__ADD__" || (it.title+" "+it.subtitle+" "+it.extra+" "+it.day+" "+it.room+" "+it.professor).contains(query, true)
    }}
    val activeDays = days.filter { d -> all.any { it.day.equals(d, true) } }
    val hours = if (all.isEmpty()) (7..18).toList() else {
        val starts = all.mapNotNull { it.startTime.toHourOrNull() }
        val ends = all.mapNotNull { it.endTime.toHourOrNull() }
        val min = (starts.minOrNull() ?: 7).coerceAtLeast(0)
        val max = (ends.maxOrNull() ?: 18).coerceAtMost(23)
        (min until max.coerceAtLeast(min + 1)).toList()
    }
    val calendar = remember(nowTick) { Calendar.getInstance() }
    val today = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val dayHighlight = Color(store.scheduleDayHighlight())
    val timeHighlight = Color(store.scheduleTimeHighlight())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Class Schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Today: ${today} • ${String.format(Locale.getDefault(), "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ showColors = true }) { Icon(Icons.Default.Palette, "Schedule highlight colors") }
        }
        if (activeDays.isEmpty()) EmptyCard("No classes yet. Tap + to build your Monday–Saturday schedule.")
        else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                Column {
                    Row {
                        Box(Modifier.width(72.dp).height(48.dp).then(
                            if (today in activeDays) Modifier.border(3.dp, dayHighlight) else Modifier
                        ), contentAlignment = Alignment.Center) { Text("Time", fontWeight = FontWeight.Bold) }
                        activeDays.forEach { d ->
                            val isToday = d.equals(today, true)
                            Box(Modifier.width(118.dp).height(48.dp).then(
                                if (isToday) Modifier.border(3.dp, dayHighlight) else Modifier
                            ), contentAlignment = Alignment.Center) { Text(d.take(3), fontWeight = FontWeight.Bold) }
                        }
                    }
                    hours.forEach { h ->
                        val isCurrentHour = h == currentHour
                        Row {
                            Box(Modifier.width(72.dp).height(74.dp).then(
                                if (isCurrentHour) Modifier.border(3.dp, timeHighlight) else Modifier
                            ), contentAlignment = Alignment.TopCenter) {
                                Text(String.format(Locale.getDefault(), "%02d:00", h), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            activeDays.forEach { day ->
                                val classes = all.filter { it.day.equals(day, true) && it.startTime.toHourOrNull() == h }
                                Box(Modifier.width(118.dp).height(74.dp).padding(2.dp)) {
                                    classes.firstOrNull()?.let { r ->
                                        val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
                                        Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = bg)) {
                                            Column(Modifier.padding(7.dp)) {
                                                Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                                if (r.room.isNotBlank()) Text(r.room, maxLines = 1)
                                                if (r.endTime.isNotBlank()) Text("${r.startTime}-${r.endTime}", style = MaterialTheme.typography.labelSmall)
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
        Text("Subject details", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(all, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (r.subtitle.isNotBlank()) Text(r.subtitle)
                        if (r.professor.isNotBlank()) Text("Professor: ${r.professor}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.day.isNotBlank()) Text("${r.day} • ${r.startTime}-${r.endTime}${if (r.room.isNotBlank()) " • ${r.room}" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.extra.isNotBlank()) Text(r.extra, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton({ store.delete("schedule", r.id); refresh++ }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) ScheduleDialog(store) { showAdd = false; clear(); refresh++ }
    if (showColors) ScheduleHighlightColorDialog(store) { showColors = false }
}

@Composable
fun ScheduleHighlightColorDialog(store: LocalStore, done: () -> Unit) {
    var dayColor by remember { mutableLongStateOf(store.scheduleDayHighlight()) }
    var timeColor by remember { mutableLongStateOf(store.scheduleTimeHighlight()) }
    val colors = listOf(0xFF1976D2L,0xFF7B1FA2L,0xFFC62828L,0xFF00897BL,0xFFF9A825L,0xFF5D4037L)
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Schedule highlight colors") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Today / day header", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    colors.forEach { c -> FilterChip(dayColor == c, { dayColor = c }, label = { Text("●") }) }
                }
                Text("Current time row", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    colors.forEach { c -> FilterChip(timeColor == c, { timeColor = c }, label = { Text("●") }) }
                }
            }
        },
        confirmButton = {
            Button({
                store.setScheduleDayHighlight(dayColor)
                store.setScheduleTimeHighlight(timeColor)
                done()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(done) { Text("Cancel") } }
    )
}

private fun String.toHourOrNull(): Int? = substringBefore(":").toIntOrNull()

private fun syncSubjectFromClass(store: LocalStore, classRecord: Record) {
    val code = classRecord.title.trim()
    if (code.isBlank()) return
    val subjects = store.get("subjects")
    val existing = subjects.firstOrNull { it.title.equals(code, true) }
    val synced = if (existing == null) {
        Record(title=code, subtitle=classRecord.subtitle, extra=classRecord.extra,
            professor=classRecord.professor, room=classRecord.room)
    } else {
        existing.copy(
            subtitle=if (classRecord.subtitle.isNotBlank()) classRecord.subtitle else existing.subtitle,
            professor=if (classRecord.professor.isNotBlank()) classRecord.professor else existing.professor,
            room=if (classRecord.room.isNotBlank()) classRecord.room else existing.room
        )
    }
    store.put("subjects", if (existing == null) subjects + synced
        else subjects.map { if (it.id == existing.id) synced else it })
}

@Composable
fun ScheduleDialog(store: LocalStore, done: () -> Unit) {
    var subject by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("Monday") }
    var start by remember { mutableStateOf("07:00") }
    var end by remember { mutableStateOf("08:00") }
    var room by remember { mutableStateOf("") }
    var professor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var color by remember { mutableLongStateOf(0xFFE3F2FD) }
    val colors = listOf(0xFFE3F2FDL,0xFFE8F5E9L,0xFFFFF3E0L,0xFFF3E5F5L,0xFFFFEBEEL,0xFFE0F7FAL)
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Add class") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                item { OutlinedTextField(subject, { subject=it }, Modifier.fillMaxWidth(), label={Text("Subject code")}, placeholder={Text("e.g. DCIT 25")}) }
                item { OutlinedTextField(fullName, { fullName=it }, Modifier.fillMaxWidth(), label={Text("Whole subject name")}) }
                item { Text("Day", fontWeight=FontWeight.SemiBold) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    days.forEach { d -> FilterChip(day==d,{day=d},label={Text(d.take(3))}) }
                }}
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start,{start=it},Modifier.weight(1f),label={Text("Start")})
                    OutlinedTextField(end,{end=it},Modifier.weight(1f),label={Text("End")})
                }}
                item { OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text("Room number")}) }
                item { OutlinedTextField(professor,{professor=it},Modifier.fillMaxWidth(),label={Text("Professor")}) }
                item { OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")}) }
                item { Text("Class color", fontWeight=FontWeight.SemiBold) }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c -> FilterChip(color==c,{color=c},label={Text("●")}) }
                }}
            }
        },
        confirmButton = { Button({
            if(subject.isNotBlank() && start.toHourOrNull()!=null && end.toHourOrNull()!=null) {
                val classRecord = Record(title=subject.trim(), subtitle=fullName.trim(), extra=notes.trim(),
                    day=day, startTime=start, endTime=end, room=room.trim(), professor=professor.trim(), color=color)
                store.put("schedule", store.get("schedule") + classRecord)
                syncSubjectFromClass(store, classRecord)
            }
            done()
        }) { Text("Save") } },
        dismissButton = { TextButton(done) { Text("Cancel") } }
    )
}

private val days = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")

@Composable
fun CrudScreen(title: String, key: String, store: LocalStore, query: String, clear: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(query) { if (query == "__ADD__") showAdd = true }
    val list = remember(refresh, query) { store.get(key).filter {
        query.isBlank() || query == "__ADD__" || (it.title+" "+it.subtitle+" "+it.extra).contains(query, true)
    }}
    Column(Modifier.fillMaxSize()) {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (list.isEmpty()) EmptyCard("Nothing here yet. Tap + to add your first item.")
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { r -> RecordCard(r, key, store) { refresh++ } }
        }
    }
    if (showAdd) AddRecordDialog(title, key, store) { showAdd = false; clear(); refresh++ }
}

@Composable
fun AcademicsScreen(store: LocalStore, query: String, clear: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedSubject by remember { mutableStateOf<Record?>(null) }
    val labels = listOf("Subjects","Reviewers")
    val keys = listOf("subjects","reviewers")
    val list = remember(refresh, query, tab) { store.get(keys[tab]).filter {
        query.isBlank() || query == "__ADD__" || (it.title+" "+it.subtitle+" "+it.extra).contains(query, true)
    }}
    Column(Modifier.fillMaxSize()) {
        Text("Academics", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            labels.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (list.isEmpty()) EmptyCard("No "+labels[tab].lowercase()+" yet. Use + to add.")
        else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { r ->
                Card(onClick = { if (tab == 0) selectedSubject = r }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold)
                        if (r.subtitle.isNotBlank()) Text(r.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.extra.isNotBlank()) Text(r.extra, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
    }
    if (query == "__ADD__") AddRecordDialog(labels[tab], keys[tab], store) { clear(); refresh++ }
    selectedSubject?.let { subject ->
        SubjectNotepadDialog(subject, store) { selectedSubject = null; refresh++ }
    }
}

@Composable
fun SubjectNotepadDialog(subject: Record, store: LocalStore, done: () -> Unit) {
    var note by remember { mutableStateOf(subject.extra) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(subject.title + " Notepad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subject.subtitle.isNotBlank()) Text(subject.subtitle)
                if (subject.professor.isNotBlank()) Text("Professor: " + subject.professor,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value=note,
                    onValueChange={note=it},
                    modifier=Modifier.fillMaxWidth().heightIn(min=240.dp),
                    label={Text("Notes")},
                    placeholder={Text("Write lessons, reminders, reviewer notes, or anything for this subject...")}
                )
            }
        },
        confirmButton = {
            Button({
                store.put("subjects", store.get("subjects").map {
                    if (it.id == subject.id) it.copy(extra=note) else it
                })
                done()
            }) { Text("Save notes") }
        },
        dismissButton = { TextButton(done) { Text("Close") } }
    )
}

@Composable
fun RecordCard(r: Record, key: String, store: LocalStore, refresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, fontWeight = FontWeight.SemiBold)
                if (r.subtitle.isNotBlank()) Text(r.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.extra.isNotBlank()) Text(r.extra, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (key == "grades") Text("Grade: %.2f".format(r.value))
                if (key == "expenses") Text("₱%.2f".format(r.value), fontWeight = FontWeight.Bold)
            }
            if (key == "tasks" || key == "attendance") Checkbox(r.done, {
                store.put(key, store.get(key).map { if (it.id == r.id) it.copy(done = !it.done) else it }); refresh()
            })
            IconButton({ store.delete(key, r.id); refresh() }) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

@Composable
fun AddRecordDialog(label: String, key: String, store: LocalStore, done: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = done) {
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add $label", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(if (key == "schedule") "Subject / class" else if (key == "expenses") "Expense" else "Name / title") })
                OutlinedTextField(subtitle, { subtitle = it }, Modifier.fillMaxWidth(), label = { Text(when(key) {
                    "schedule" -> "Time • room"; "tasks" -> "Due date / details"; "subjects" -> "Teacher / section"
                    "grades" -> "Subject / units"; "attendance" -> "Subject / date"; "reviewers" -> "Topic / notes"; else -> "Details"
                }) })
                if (key in listOf("schedule","tasks","attendance","reviewers"))
                    OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), label = { Text("Extra notes") })
                if (key == "grades" || key == "expenses")
                    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(if (key == "grades") "Grade" else "Amount") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(done) { Text("Cancel") }
                    Button({
                        if (title.isNotBlank()) store.put(key, store.get(key) + Record(
                            title = title.trim(), subtitle = subtitle.trim(), extra = extra.trim(), value = value.toDoubleOrNull() ?: 0.0
                        ))
                        done()
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun FilesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by remember { mutableStateOf(listFiles(context)) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "document"
        val target = File(context.filesDir, name)
        runCatching { context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } }
        files = listFiles(context)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("School Files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Stored only on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button({ open.launch(arrayOf("*/*")) }) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Import") }
        }
        Spacer(Modifier.height(12.dp))
        if (files.isEmpty()) EmptyCard("Import PDFs, documents, images, or other school files.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.name }) { f ->
                Card(Modifier.fillMaxWidth()) { ListItem(
                    headlineContent = { Text(f.name) }, supportingContent = { Text(formatSize(f.size)) },
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                    trailingContent = { IconButton({ File(context.filesDir, f.name).delete(); files = listFiles(context) }) { Icon(Icons.Default.Delete, "Delete") } }
                )}
            }
        }
    }
}
private fun listFiles(context: Context) = context.filesDir.listFiles()?.filter { it.isFile }
    ?.map { FileRecord(it.name, it.length()) }?.sortedBy { it.name.lowercase() } ?: emptyList()
private fun queryName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) return it.getString(0).replace(Regex("[/\\]"), "_")
    }
    return uri.lastPathSegment?.substringAfterLast("/")
}
private fun formatSize(size: Long) = when {
    size < 1024 -> "$size B"
    size < 1024*1024 -> "%.1f KB".format(size/1024.0)
    else -> "%.1f MB".format(size/1024.0/1024.0)
}

@Composable
fun SettingsScreen(store: LocalStore, theme: String, setTheme: (String) -> Unit, lock: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf(store.pin()) }
    var lockOn by remember { mutableStateOf(store.lockEnabled()) }
    var showPin by remember { mutableStateOf(false) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(store.backupJson().toByteArray()) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.restoreJson(it.readText()) } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("system","light","dark").forEach { mode ->
                        FilterChip(theme == mode, { setTheme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        }}
        item { Card(Modifier.fillMaxWidth()) {
            ListItem(headlineContent = { Text("App lock") },
                supportingContent = { Text(if (pin.isBlank()) "Set a PIN first" else "Require PIN when opening CampusOS") },
                trailingContent = { Switch(lockOn && pin.isNotBlank(), {
                    lockOn = it; store.setLockEnabled(it); if (it) lock()
                }) })
            TextButton({ showPin = true }, Modifier.padding(start = 12.dp)) { Text(if (pin.isBlank()) "Set PIN" else "Change PIN") }
        }}
        item { Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Backup & restore", fontWeight = FontWeight.Bold)
                Text("Export local data to JSON or restore it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Button({ backup.launch("CampusOS-backup.json") }) { Text("Backup") }
                    OutlinedButton({ restore.launch(arrayOf("application/json","text/plain")) }) { Text("Restore") }
                }
            }
        }}
        item { Text("CampusOS 1.0.0 • Offline-first", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (showPin) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showPin = false }, title = { Text("Set 4–8 digit PIN") },
            text = { OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") }) },
            confirmButton = { Button({ if (newPin.length in 4..8) { pin = newPin; store.setPin(newPin); showPin = false } }) { Text("Save") } },
            dismissButton = { TextButton({ showPin = false }) { Text("Cancel") } })
    }
}

@Composable
fun LockScreen(store: LocalStore, unlock: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(64.dp))
        Spacer(Modifier.height(18.dp)); Text("CampusOS is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter your PIN to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(entered, { entered = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") })
        if (error) Text("Incorrect PIN", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp)); Button({ if (entered == store.pin()) unlock() else error = true }) { Text("Unlock") }
    }
}
@Composable fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun SmallAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(4.dp)); Text(text) }
}
