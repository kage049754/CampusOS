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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
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
    val room: String = "", val professor: String = "", val color: Long = 0L, val classType: String = "Lecture", val subjectId: Long = 0L, val dueDate: String = "", val dueTime: String = ""
)
data class FileRecord(val name: String, val size: Long, val file: File? = null)

private fun readableContentColor(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.62f) Color.Black else Color.White
}

private fun nextRecordId(store: LocalStore, key: String, offset: Int = 0): Long {
    val existingMax = store.get(key).maxOfOrNull { it.id } ?: 0L
    return maxOf(System.currentTimeMillis(), existingMax + 1L) + offset
}

private fun subjectFolder(context: Context, subjectId: Long): File =
    File(context.filesDir, "subject_files/$subjectId").apply { mkdirs() }

private fun subjectFiles(context: Context, subjectId: Long): List<File> =
    subjectFolder(context, subjectId).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

private fun safeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "lesson_file" }

private fun copyUriToSubject(context: Context, uri: Uri, subjectId: Long): File? {
    val base = safeFileName(queryName(context, uri) ?: "lesson_file")
    var target = File(subjectFolder(context, subjectId), base)
    var n = 2
    while (target.exists()) {
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        target = File(subjectFolder(context, subjectId), "$stem ($n)$ext")
        n++
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    }.getOrNull()
}

private fun fileExtension(file: File) = file.extension.lowercase(Locale.getDefault())

private fun readOfficeText(file: File): String? = runCatching {
    ZipFile(file).use { zip ->
        when (fileExtension(file)) {
            "docx" -> zip.getEntry("word/document.xml")?.let { entry ->
                zip.getInputStream(entry).use { stream ->
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement.textContent
                }
            }
            "pptx" -> zip.entries().asSequence()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .joinToString("\n\n") { entry ->
                    zip.getInputStream(entry).use { stream ->
                        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement.textContent
                    }
                }
            "xlsx" -> zip.entries().asSequence()
                .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .joinToString("\n") { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
            else -> null
        }
    }
}.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

private fun readOfficeSlides(file: File): List<String> = runCatching {
    ZipFile(file).use { zip ->
        if (fileExtension(file) != "pptx") return@use emptyList<String>()
        zip.entries().asSequence()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
            .map { entry ->
                zip.getInputStream(entry).use { stream ->
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement.textContent.trim()
                }
            }.toList()
    }
}.getOrDefault(emptyList())

private fun readDisplayText(file: File): String = when (fileExtension(file)) {
    "docx", "pptx", "xlsx" -> readOfficeText(file) ?: "No readable text was found in this Office file."
    "txt", "csv", "log", "json", "xml", "kt", "java", "md" ->
        runCatching { file.readText() }.getOrElse { "Unable to read this text file." }
    else -> "This file type does not have an offline text preview in CampusOS."
}

private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? = runCatching {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfRenderer(descriptor).use { renderer ->
        renderer.openPage(pageIndex).use { page ->
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }
}.getOrNull()

class LocalStore(context: Context) {
    var revision by mutableIntStateOf(0)
        private set
    private val prefs = context.getSharedPreferences("campusos", Context.MODE_PRIVATE)
    private fun read(key: String): MutableList<Record> = runCatching {
        val out = mutableListOf<Record>()
        val raw = prefs.getString(key, "[]") ?: "[]"
        val a = JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val storedId = o.optLong("id", 0L)
            val safeId = if (storedId > 0L && out.none { it.id == storedId }) storedId
                else maxOf(System.currentTimeMillis(), (out.maxOfOrNull { it.id } ?: 0L) + 1L)
            out += Record(safeId, o.optString("title"), o.optString("subtitle"),
                o.optString("extra"), o.optDouble("value", 0.0), o.optBoolean("done", false),
                o.optString("day"), o.optString("startTime"), o.optString("endTime"), o.optString("room"), o.optString("professor"), o.optLong("color", 0L), o.optString("classType", "Lecture"), o.optLong("subjectId", 0L), o.optString("dueDate"), o.optString("dueTime"))
        }
        out
    }.getOrElse { mutableListOf() }
    private fun save(key: String, list: List<Record>) {
        val a = JSONArray()
        list.forEach { r -> a.put(JSONObject().apply {
            put("id", r.id); put("title", r.title); put("subtitle", r.subtitle)
            put("extra", r.extra); put("value", r.value); put("done", r.done)
            put("day", r.day); put("startTime", r.startTime); put("endTime", r.endTime); put("room", r.room); put("professor", r.professor); put("color", r.color); put("classType", r.classType); put("subjectId", r.subjectId); put("dueDate", r.dueDate); put("dueTime", r.dueTime)
        }) }
        prefs.edit().putString(key, a.toString()).apply()
        revision++
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
    fun scheduleTableBackground() = prefs.getLong("schedule_table_background", 0x00000000L)
    fun setScheduleTableBackground(v: Long) = prefs.edit().putLong("schedule_table_background", v).apply()
    fun scheduleTableBorder() = prefs.getLong("schedule_table_border", 0xFF808080L)
    fun setScheduleTableBorder(v: Long) = prefs.edit().putLong("schedule_table_border", v).apply()
    fun scheduleDays() = (prefs.getString("schedule_days", "Monday,Tuesday,Wednesday,Thursday,Friday,Saturday") ?: "Monday,Tuesday,Wednesday,Thursday,Friday,Saturday").split(",").filter { it.isNotBlank() }
    fun setScheduleDays(v: List<String>) = prefs.edit().putString("schedule_days", v.joinToString(",")).apply()
    fun scheduleStartHour() = prefs.getInt("schedule_start_hour", 7)
    fun scheduleEndHour() = prefs.getInt("schedule_end_hour", 19)
    fun setScheduleHours(start: Int, end: Int) = prefs.edit().putInt("schedule_start_hour", start).putInt("schedule_end_hour", end).apply()
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
        revision++
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
    FILES("Files"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusOSApp(activity: Activity) {
    val store = remember { LocalStore(activity) }
    var theme by remember { mutableStateOf(store.theme()) }
    var locked by remember { mutableStateOf(store.lockEnabled() && store.pin().isNotBlank()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var search by remember { mutableStateOf("") }
    var showHomeAdd by remember { mutableStateOf(false) }
    var showHomeColors by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    var showScheduleSettings by remember { mutableStateOf(false) }

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
                    actions = {
                        IconButton(onClick = { showHomeSettings = true }) {
                            Icon(Icons.Default.Settings, "CampusOS settings")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Screen.HOME,
                        Screen.SCHEDULE,
                        Screen.TASKS,
                        Screen.ACADEMICS
                    ).forEach {
                        NavigationBarItem(
                            selected = screen == it,
                            onClick = { screen = it },
                            icon = { Icon(iconFor(it), it.label) },
                            label = { Text(it.label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (screen == Screen.TASKS || screen == Screen.ACADEMICS) {
                    FloatingActionButton(onClick = { search = "__ADD__" }) {
                        Icon(Icons.Default.Add, "Add")
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(store) { screen = it }
                    Screen.SCHEDULE -> ScheduleScreen(store, search) { search = "" }
                    Screen.TASKS -> CrudScreen("Assignments & To-do", "tasks", store, search) { search = "" }
                    Screen.ACADEMICS -> AcademicsScreen(store, search) { search = "" }
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
                            label = { Text("Search " + screen.label) },
                            leadingIcon = { Icon(Icons.Default.Search, null) }
                        )
                    }
                }
            }
            if (showHomeSettings) {
                HomeSettingsDialog(
                    store = store,
                    theme = theme,
                    setTheme = { theme = it; store.setTheme(it) },
                    onAddClass = { showHomeAdd = true; showHomeSettings = false },
                    onScheduleSettings = { showScheduleSettings = true; showHomeSettings = false },
                    onAppearance = { showHomeColors = true; showHomeSettings = false },
                    done = { showHomeSettings = false }
                )
            }
            if (showHomeAdd) ScheduleDialog(store) { showHomeAdd = false }
            if (showHomeColors) HomeAppearanceDialog(store, theme, { theme = it; store.setTheme(it) }) { showHomeColors = false }
            if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
        }
    }
}

private fun iconFor(s: Screen) = when(s) {
    Screen.HOME -> Icons.Default.Home
    Screen.SCHEDULE -> Icons.Default.CalendarMonth
    Screen.TASKS -> Icons.Default.CheckCircle
    Screen.ACADEMICS -> Icons.Default.School
    Screen.FILES -> Icons.Default.Folder
    Screen.SETTINGS -> Icons.Default.Settings
}

@Composable
fun HomeSettingsDialog(store: LocalStore, theme: String, setTheme: (String) -> Unit, onAddClass: () -> Unit, onScheduleSettings: () -> Unit, onAppearance: () -> Unit, done: () -> Unit) {
    AlertDialog(onDismissRequest = done, title = { Text("CampusOS Settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAddClass, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Class") }
            OutlinedButton(onClick = onScheduleSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("Class Schedule Settings") }
            OutlinedButton(onClick = onAppearance, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Palette, null); Spacer(Modifier.width(8.dp)); Text("Colors & Appearance") }
            OutlinedButton(onClick = { setTheme(if (theme == "dark") "light" else "dark") }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (theme == "dark") Icons.Default.LightMode else Icons.Default.DarkMode, null); Spacer(Modifier.width(8.dp)); Text(if (theme == "dark") "Switch to Light Mode" else "Switch to Dark Mode")
            }
        }
    }, confirmButton = { TextButton(done) { Text("Close") } })
}

@Composable
fun HomeScreen(store: LocalStore, go: (Screen) -> Unit) {
    val revision = store.revision
    var tick by remember { mutableIntStateOf(0) }
    val tasks = remember(tick, revision) { store.get("tasks") }
    val schedule = remember(tick, revision) { store.get("schedule") }
    val date = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
    val todayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    val todaySchedule = remember(schedule, todayName) {
        schedule
            .filter { it.day.equals(todayName, ignoreCase = true) }
            .sortedBy { it.startTime }
    }
    val pendingTasks = remember(tasks) {
        tasks.filter { !it.done }
            .sortedWith(compareBy({ it.dueDate }, { it.dueTime }))
            .take(5)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Good day 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Classes", schedule.size.toString(), Modifier.weight(1f))
            StatCard("Tasks", tasks.count { !it.done }.toString(), Modifier.weight(1f))
        }}
        item { SectionTitle("Today • $todayName") }
        if (todaySchedule.isEmpty()) {
            item { EmptyCard("No classes scheduled for today.") }
        } else {
            items(todaySchedule.take(5), key = { it.id }) { r ->
                HomeTodayClassCard(r)
            }
        }
        item { SectionTitle("Tasks to do") }
        if (pendingTasks.isEmpty()) {
            item { EmptyCard("No unfinished tasks.") }
        } else {
            items(pendingTasks, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.title, fontWeight = FontWeight.Bold)
                        if (r.subtitle.isNotBlank()) Text(r.subtitle, maxLines = 2)
                        if (r.dueDate.isNotBlank()) {
                            Text("Due: ${r.dueDate} ${r.dueTime}", style = MaterialTheme.typography.labelMedium)
                        }
                        if (r.subjectId != 0L) {
                            store.get("subjects").firstOrNull { it.id == r.subjectId }?.let { sub ->
                                Text("Subject: ${sub.title}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Quick access") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmallAction("Subjects", Icons.Default.School) { go(Screen.ACADEMICS) }
            SmallAction("Files", Icons.Default.Folder) { go(Screen.FILES) }
            SmallAction("Tasks", Icons.Default.CheckCircle) { go(Screen.TASKS) }
        }}
    }
}

@Composable
fun HomeTodayClassCard(r: Record) {
    val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = readableContentColor(bg))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (r.subtitle.isNotBlank()) Text(r.subtitle)
            if (r.day.isNotBlank()) Text("${r.day} • ${r.startTime}-${r.endTime}", style = MaterialTheme.typography.labelMedium)
            if (r.room.isNotBlank()) Text("Room: ${r.room}")
            if (r.professor.isNotBlank()) Text("Professor: ${r.professor}")
            if (r.classType.isNotBlank()) Text("Option: ${r.classType}")
        }
    }
}

@Composable
fun ScheduleSettingsDialog(store:LocalStore,done:()->Unit){
    val allDays=listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday");var chosen by remember{mutableStateOf(store.scheduleDays().toSet())};var start by remember{mutableIntStateOf(store.scheduleStartHour())};var end by remember{mutableIntStateOf(store.scheduleEndHour())}
    AlertDialog(onDismissRequest=done,title={Text("Class Schedule Settings")},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Show only the days you have class",fontWeight=FontWeight.Bold);allDays.forEach{day->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(chosen.contains(day),{chosen=if(chosen.contains(day))chosen-day else chosen+day});Text(day)}}
        Text("Time range",fontWeight=FontWeight.Bold);Text("%02d:00 – %02d:00".format(start,end),style=MaterialTheme.typography.titleMedium);Text("24-hour range. Default is 07:00–19:00.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){OutlinedButton({if(start>0)start--}){Text("Start −")};OutlinedButton({if(start<end-1)start++}){Text("Start +")};OutlinedButton({if(end<24)end++}){Text("End +")};OutlinedButton({if(end>start+1)end--}){Text("End −")}}
    }},confirmButton={Button({if(chosen.isNotEmpty()){store.setScheduleDays(allDays.filter{it in chosen});store.setScheduleHours(start,end)};done()}){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})
}
@Composable
fun HomeAppearanceDialog(store: LocalStore, theme: String, setTheme: (String) -> Unit, done: () -> Unit) {
    var tableBg by remember { mutableLongStateOf(store.scheduleTableBackground()) }
    var border by remember { mutableLongStateOf(store.scheduleTableBorder()) }
    val colors = listOf(0xFF000000L,0xFFFFFFFFL,0xFF263238L,0xFF37474FL,0xFFECEFF1L,0xFFF5F5F5L,0xFF1976D2L,0xFF7B1FA2L,0xFFC62828L,0xFF00897BL)
    AlertDialog(
        onDismissRequest=done,
        title={Text("CampusOS appearance")},
        text={
            Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Theme",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    listOf("system","light","dark").forEach { mode ->
                        FilterChip(theme==mode,{setTheme(mode)},label={Text(mode.replaceFirstChar{it.uppercase()})})
                    }
                }
                Text("Schedule table background",fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    colors.forEach { c -> ColorChoiceCircle(c,tableBg==c){tableBg=c} }
                }
                Text("Schedule table border",fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    colors.forEach { c -> ColorChoiceCircle(c,border==c){border=c} }
                }
            }
        },
        confirmButton={
            Button({
                store.setScheduleTableBackground(tableBg)
                store.setScheduleTableBorder(border)
                done()
            }){Text("Save")}
        },
        dismissButton={TextButton(done){Text("Cancel")}}
    )
}

@Composable
fun ColorChoiceCircle(value: Long, selected: Boolean, click: () -> Unit) {
    Box(
        Modifier.size(40.dp).border(3.dp,if(selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,RoundedCornerShape(50))
            .padding(4.dp).background(Color(value),RoundedCornerShape(50)).clickable(onClick=click),
        contentAlignment=Alignment.Center
    ) { if(selected) Text("✓",color=readableContentColor(Color(value)),fontWeight=FontWeight.Bold) }
}

@Composable
fun ScheduleScreen(store: LocalStore, query: String, clear: () -> Unit) {
    val revision = store.revision
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

    val all = remember(refresh, revision, query) {
        store.get("schedule").filter {
            query.isBlank() || query == "__ADD__" ||
                (it.title + " " + it.subtitle + " " + it.extra + " " + it.day + " " + it.room + " " + it.professor)
                    .contains(query, true)
        }
    }

    // Show only configured days/hours and highlight the current day, current hour, and matching class.
    val scheduleDays = store.scheduleDays()
    val startHour = store.scheduleStartHour().coerceIn(0, 23)
    val endHour = store.scheduleEndHour().coerceIn(startHour + 1, 24)
    val hours = (startHour until endHour).toList()
    val calendar = remember(nowTick) { Calendar.getInstance() }
    val today = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val dayHighlight = Color(store.scheduleDayHighlight())
    val timeHighlight = Color(store.scheduleTimeHighlight())
    val tableBgValue = store.scheduleTableBackground()
    val tableBg = if (tableBgValue == 0L) Color.Transparent else Color(tableBgValue)
    val tableBorder = Color(store.scheduleTableBorder())

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Class Schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${scheduleDays.joinToString(" • ")} • %02d:00–%02d:00".format(startHour, endHour),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton({ showColors = true }) {
                Icon(Icons.Default.Palette, "Schedule colors")
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=4.dp)) {
            val dayWidth=(maxWidth-56.dp).coerceAtLeast(0.dp)/scheduleDays.size.coerceAtLeast(1)

            Column(
                Modifier.fillMaxWidth().heightIn(max=430.dp).verticalScroll(rememberScrollState())
            ) {
                Row {
                    Box(
                        Modifier.width(56.dp).height(42.dp).background(tableBg).border(1.dp, tableBorder),
                        contentAlignment = Alignment.Center
                    ) { Text("Time", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }

                    scheduleDays.forEach { d ->
                        val isToday = d.equals(today, true)
                        Box(
                            Modifier.width(dayWidth).height(42.dp)
                                .background(if (isToday) dayHighlight.copy(alpha = 0.16f) else tableBg)
                                .border(if (isToday) 2.dp else 1.dp, if (isToday) dayHighlight else tableBorder),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                                if(isToday) Box(Modifier.size(7.dp).background(dayHighlight,RoundedCornerShape(50)))
                                Text(d.take(3), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                hours.forEach { h ->
                    val isCurrentHour = h == currentHour
                    Row {
                        Box(
                            Modifier.width(56.dp).height(62.dp).background(tableBg).border(1.dp, tableBorder)
                                .then(if (isCurrentHour) Modifier.border(2.dp, timeHighlight) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                                if(isCurrentHour) Box(Modifier.size(7.dp).background(timeHighlight,RoundedCornerShape(50)))
                                Text("%02d:00".format(h),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
                            }
                        }

                        scheduleDays.forEach { day ->
                            val classes = all.filter {
                                it.day.equals(day, true) && it.startTime.toHourOrNull() == h
                            }
                            Box(
                                Modifier.width(dayWidth).height(62.dp)
                                .background(if (day.equals(today, true) && isCurrentHour) timeHighlight.copy(alpha = 0.10f) else tableBg)
                                .border(
                                    if (day.equals(today, true) && isCurrentHour) 2.dp else 1.dp,
                                    if (day.equals(today, true) && isCurrentHour) timeHighlight else tableBorder
                                )
                                .padding(2.dp)
                            ) {
                                classes.firstOrNull()?.let { r ->
                                    val isCurrentClass = day.equals(today, true) && isCurrentHour
                                    val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
                                    Card(
                                        Modifier.fillMaxSize()
                                            .then(if (isCurrentClass) Modifier.border(3.dp, dayHighlight, RoundedCornerShape(10.dp)) else Modifier),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isCurrentClass) bg.copy(alpha = 0.92f) else bg,
                                            contentColor = readableContentColor(bg)
                                        )
                                    ) {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                r.title,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 2
                                            )
                                            if (r.room.isNotBlank()) {
                                                Text(
                                                    r.room,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                            if (r.classType.isNotBlank()) {
                                                Text(
                                                    r.classType,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Explicit bottom boundary makes the 19:00 end of the timetable clear.
                Row {
                    Box(
                        Modifier.width(56.dp).height(28.dp).background(tableBg).border(1.dp, tableBorder),
                        contentAlignment = Alignment.Center
                    ) { Text("19:00", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    scheduleDays.forEach {
                        Box(Modifier.width(dayWidth).height(28.dp).background(tableBg).border(1.dp, tableBorder))
                    }
                }
            }
        }

        Text(
            "Subject details",
            Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(all, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(width = 4.dp, height = 48.dp)
                                .background(
                                    if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                if (r.room.isNotBlank()) {
                                    Text(
                                        "  •  ${r.room}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (r.subtitle.isNotBlank()) {
                                Text(
                                    r.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                            }
                            if (r.professor.isNotBlank()) {
                                Text(
                                    r.professor,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Text(
                                "${r.day} • ${r.startTime}-${r.endTime}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton({ deleteScheduleAndSync(store, r); refresh++ }) {
                            Icon(Icons.Default.Delete, "Delete")
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
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c -> ColorChoiceCircle(c, dayColor == c) { dayColor = c } }
                }
                Text("Current time row", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c -> ColorChoiceCircle(c, timeColor == c) { timeColor = c } }
                }
                Text("The selected color is shown directly on the schedule.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
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

private fun deleteScheduleAndSync(store: LocalStore, classRecord: Record) {
    store.delete("schedule", classRecord.id)
    val remaining = store.get("schedule").any { it.title.equals(classRecord.title, true) }
    if (!remaining) {
        store.put("subjects", store.get("subjects").filterNot { it.title.equals(classRecord.title, true) })
    } else {
        val latest = store.get("schedule").last { it.title.equals(classRecord.title, true) }
        syncSubjectFromClass(store, latest)
    }
}

private fun syncSubjectFromClass(store: LocalStore, classRecord: Record) {
    val code = classRecord.title.trim()
    if (code.isBlank()) return
    val subjects = store.get("subjects")
    val existing = subjects.firstOrNull { it.title.equals(code, true) }
    val synced = if (existing == null) {
        Record(title=code, subtitle=classRecord.subtitle, extra=classRecord.extra,
            professor=classRecord.professor, room=classRecord.room, classType=classRecord.classType)
    } else {
        existing.copy(
            subtitle=if (classRecord.subtitle.isNotBlank()) classRecord.subtitle else existing.subtitle,
            professor=if (classRecord.professor.isNotBlank()) classRecord.professor else existing.professor,
            room=if (classRecord.room.isNotBlank()) classRecord.room else existing.room,
            classType=classRecord.classType
        )
    }
    store.put("subjects", if (existing == null) subjects + synced
        else subjects.map { if (it.id == existing.id) synced else it })
}

@Composable
fun TimeWheelDialog(
    title: String,
    initial: String,
    done: (String) -> Unit,
    cancel: () -> Unit
) {
    val initialHour = initial.substringBefore(":").toIntOrNull()?.coerceIn(7, 18) ?: 7
    var hour by remember { mutableIntStateOf(initialHour) }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "%02d:00".format(hour),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Choose a 1-hour class slot",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AndroidView(
                    factory = { context ->
                        android.widget.NumberPicker(context).apply {
                            minValue = 7
                            maxValue = 18
                            value = hour
                            wrapSelectorWheel = false
                            displayedValues = (7..18).map { "%02d:00".format(it) }.toTypedArray()
                            setOnValueChangedListener { _, _, newValue -> hour = newValue }
                        }
                    },
                    update = { picker ->
                        if (picker.value != hour) picker.value = hour
                    },
                    modifier = Modifier.width(150.dp).height(190.dp)
                )
                Text(
                    "%02d:00 – %02d:00".format(hour, hour + 1),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            Button(onClick = { done("%02d:00".format(hour)) }) {
                Text("Set time")
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

@Composable
fun ScheduleDialog(store: LocalStore, done: () -> Unit) {
    var subject by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var professor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var classType by remember { mutableStateOf("Lecture") }
    var color by remember { mutableLongStateOf(0xFFE3F2FD) }
    var selectedSlots by remember { mutableStateOf(setOf<String>()) }
    val colors = listOf(0xFFE3F2FDL,0xFFE8F5E9L,0xFFFFF3E0L,0xFFF3E5F5L,0xFFFFEBEEL,0xFFE0F7FAL)
    val hours = (7..18).toList()

    AlertDialog(
        onDismissRequest = done,
        title = { Text("Add class") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject,{subject=it},Modifier.fillMaxWidth(),label={Text("Subject code")},placeholder={Text("e.g. DCIT 25")})
                OutlinedTextField(fullName,{fullName=it},Modifier.fillMaxWidth(),label={Text("Whole subject name")})
                Text("Option",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Lecture","Lab").forEach { option -> FilterChip(selected=classType==option,onClick={classType=option},label={Text(option)}) }
                }
                Text("Pick class time(s) and day(s)",fontWeight=FontWeight.SemiBold)
                Text("Tap cells to select. One subject can have multiple days and multiple 1-hour slots.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=2.dp)) {
                    Column {
                        Row {
                            Box(Modifier.width(52.dp).height(34.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center) { Text("Time",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold) }
                            listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").forEach { d -> Box(Modifier.width(76.dp).height(34.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center) { Text(d.take(3),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold) } }
                        }
                        hours.forEach { h ->
                            Row {
                                Box(Modifier.width(52.dp).height(48.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center) { Text("%02d:00".format(h),style=MaterialTheme.typography.labelSmall) }
                                listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").forEach { d ->
                                    val key = "$d|$h"
                                    val selected = key in selectedSlots
                                    Box(Modifier.width(76.dp).height(48.dp).border(2.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline).background(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).clickable { selectedSlots = if(selected) selectedSlots - key else selectedSlots + key },contentAlignment=Alignment.Center) {
                                        if(selected) {
                                            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(classType,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1); Text("%02d–%02d".format(h,h+1),style=MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                        Row {
                            Box(Modifier.width(52.dp).height(26.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center) { Text("19:00",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold) }
                            listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").forEach { Box(Modifier.width(76.dp).height(26.dp).border(1.dp,MaterialTheme.colorScheme.outline)) }
                        }
                    }
                }
                Text(if(selectedSlots.isEmpty()) "No time selected" else "${selectedSlots.size} slot(s) selected",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text("Room number")})
                OutlinedTextField(professor,{professor=it},Modifier.fillMaxWidth(),label={Text("Professor")})
                OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")})
                Text("Class color",fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c -> Box(Modifier.size(40.dp).border(3.dp,if(color==c) MaterialTheme.colorScheme.onSurface else Color.Transparent,RoundedCornerShape(50)).padding(4.dp).background(Color(c),RoundedCornerShape(50)).clickable { color=c },contentAlignment=Alignment.Center) { if(color==c) Text("✓",color=readableContentColor(Color(c)),fontWeight=FontWeight.Bold) } }
                }
            }
        },
        confirmButton={ Button({
            if(subject.isNotBlank() && selectedSlots.isNotEmpty()) {
                val existingSchedule = store.get("schedule")
                val idBase = maxOf(System.currentTimeMillis(), (existingSchedule.maxOfOrNull { it.id } ?: 0L) + 1L)
                val selected = selectedSlots.mapIndexedNotNull { index, key ->
                    val parts=key.split("|")
                    if(parts.size!=2) null else {
                        val h=parts[1].toIntOrNull() ?: return@mapIndexedNotNull null
                        Record(id=idBase + index, title=subject.trim(),subtitle=fullName.trim(),extra=notes.trim(),day=parts[0],startTime="%02d:00".format(h),endTime="%02d:00".format(h+1),room=room.trim(),professor=professor.trim(),color=color,classType=classType)
                    }
                }
                store.put("schedule",existingSchedule+selected)
                selected.firstOrNull()?.let { syncSubjectFromClass(store,it) }
            }
            done()
        }) { Text("Save") } },
        dismissButton={ TextButton(done) { Text("Cancel") } }
    )
}
private val days = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")

@Composable
fun TaskCalendar(selectedDate:String,onSelect:(String)->Unit){
    val sdf=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault())
    val selectedCal=Calendar.getInstance().apply { runCatching { time=sdf.parse(selectedDate) ?: time } }
    var monthOffset by remember(selectedDate) {
        mutableIntStateOf(
            ((Calendar.getInstance().get(Calendar.YEAR)-selectedCal.get(Calendar.YEAR))*12 +
                Calendar.getInstance().get(Calendar.MONTH)-selectedCal.get(Calendar.MONTH))
        )
    }
    val shown=Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH,1); add(Calendar.MONTH,-monthOffset) }
    val first=shown.clone() as Calendar
    val offset=(first.get(Calendar.DAY_OF_WEEK)-Calendar.MONDAY+7)%7
    val max=first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val todayKey=sdf.format(Calendar.getInstance().time)
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal > 80f) monthOffset++
                        else if (dragTotal < -80f) monthOffset--
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                )
            },
        verticalArrangement=Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            IconButton({monthOffset++}){Icon(Icons.Default.ChevronLeft,"Previous month")}
            Text(SimpleDateFormat("MMMM yyyy",Locale.getDefault()).format(first.time),Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            IconButton({monthOffset--}){Icon(Icons.Default.ChevronRight,"Next month")}
        }
        Row(Modifier.fillMaxWidth()){
            listOf("M","T","W","T","F","S","S").forEach{Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelSmall)}
        }
        for(row in 0..5) Row(Modifier.fillMaxWidth()){
            for(col in 0..6){
                val n=row*7+col-offset+1
                if(n in 1..max){
                    val d=(first.clone() as Calendar).apply{set(Calendar.DAY_OF_MONTH,n)}
                    val k=sdf.format(d.time);val selected=k==selectedDate;val today=k==todayKey
                    Box(Modifier.weight(1f).padding(2.dp).height(40.dp)
                        .background(if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,RoundedCornerShape(8.dp))
                        .then(if(today)Modifier.border(2.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(8.dp))else Modifier)
                        .clickable{onSelect(k)},contentAlignment=Alignment.Center
                    ){Text(n.toString(),fontWeight=if(selected||today)FontWeight.Bold else FontWeight.Normal)}
                }else Box(Modifier.weight(1f).height(44.dp))
            }
        }
    }
}
@Composable
fun CrudScreen(title:String,key:String,store:LocalStore,query:String,clear:()->Unit){
    val revision = store.revision
    var refresh by remember{mutableIntStateOf(0)};var showAdd by remember{mutableStateOf(false)};var selectedDate by remember{mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))}
    LaunchedEffect(query){if(query=="__ADD__")showAdd=true}
    val list=remember(refresh,revision,query){store.get(key).filter{query.isBlank()||query=="__ADD__"||(it.title+" "+it.subtitle+" "+it.extra).contains(query,true)}.sortedWith(compareBy<Record>({it.done},{it.dueDate},{it.dueTime}))}
    Column(Modifier.fillMaxSize()){
        if(key=="tasks")Card(Modifier.fillMaxWidth().padding(12.dp)){Column(Modifier.padding(12.dp)){TaskCalendar(selectedDate){selectedDate=it};Text("Selected: $selectedDate",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Text(title,Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        if(list.isEmpty())EmptyCard("No tasks yet. Tap + to add a task.") else LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(list,key={it.id}){r->RecordCard(r,key,store){refresh++}}}
    }
    if(showAdd)AddRecordDialog(title,key,store){showAdd=false;clear();refresh++}
}
@Composable
fun AcademicsScreen(store: LocalStore, query: String, clear: () -> Unit) {
    val revision = store.revision
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedSubject by remember { mutableStateOf<Record?>(null) }
    val labels = listOf("Subjects","Reviewers")
    val keys = listOf("subjects","reviewers")
    val list = remember(refresh, revision, query, tab) { store.get(keys[tab]).filter {
        query.isBlank() || query == "__ADD__" || (it.title+" "+it.subtitle+" "+it.extra).contains(query, true)
    }}
    Column(Modifier.fillMaxSize()) {
        Text("Academics", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            labels.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (list.isEmpty()) EmptyCard("No "+labels[tab].lowercase()+" yet. Add classes from Home Settings or use your existing data.")
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedNote = remember(subject) {
        runCatching {
            val o = JSONObject(subject.extra)
            Pair(o.optString("title"), o.optString("body"))
        }.getOrElse { Pair("", subject.extra) }
    }
    var noteTitle by remember { mutableStateOf(savedNote.first) }
    var noteBody by remember { mutableStateOf(savedNote.second) }
    var files by remember { mutableStateOf(subjectFiles(context, subject.id)) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var showNoteEditor by remember { mutableStateOf(false) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        copyUriToSubject(context, uri, subject.id)?.let { files = subjectFiles(context, subject.id) }
    }
    fun saveNote() {
        val noteJson = JSONObject().apply {
            put("title", noteTitle.trim())
            put("body", noteBody)
        }.toString()
        store.put("subjects", store.get("subjects").map {
            if (it.id == subject.id) it.copy(extra = noteJson) else it
        })
    }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(subject.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (subject.subtitle.isNotBlank()) Text(subject.subtitle, fontWeight = FontWeight.SemiBold)
                if (subject.professor.isNotBlank()) Text("Professor: " + subject.professor, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lessons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(onClick = { showNoteEditor = true }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.Note, null)
                            Spacer(Modifier.height(8.dp))
                            Text("Note", fontWeight = FontWeight.Bold)
                            Text(if (noteTitle.isBlank()) "Create new note" else noteTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    Card(onClick = { upload.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.InsertDriveFile, null)
                            Spacer(Modifier.height(8.dp))
                            Text("File", fontWeight = FontWeight.Bold)
                            Text("${files.size} stored", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (files.isEmpty()) {
                    EmptyCard("No lesson files yet. Tap File to upload a PDF, PowerPoint, Word document, or other file.")
                } else {
                    files.forEach { file ->
                        Card(onClick = { viewingFile = file }, modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(file.name, maxLines = 2) },
                                supportingContent = { Text(formatSize(file.length())) },
                                leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                                trailingContent = {
                                    IconButton({
                                        file.delete()
                                        files = subjectFiles(context, subject.id)
                                    }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(done) { Text("Close") } }
    )
    if (showNoteEditor) {
        AlertDialog(
            onDismissRequest = { showNoteEditor = false },
            title = { Text("Create / edit note") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(noteTitle, { noteTitle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Note title") }, placeholder = { Text("e.g. Introduction to DCIT 25") })
                    OutlinedTextField(noteBody, { noteBody = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp), label = { Text("Description / note") }, placeholder = { Text("Type your lesson note or description...") })
                }
            },
            confirmButton = { Button({ saveNote(); showNoteEditor = false }) { Text("Save note") } },
            dismissButton = { TextButton({ showNoteEditor = false }) { Text("Cancel") } }
        )
    }
    viewingFile?.let { file -> InAppFileViewerDialog(file) { viewingFile = null } }
}

@Composable
fun InAppFileViewerDialog(file: File, done: () -> Unit) {
    var page by remember(file) { mutableIntStateOf(0) }
    var slide by remember(file) { mutableIntStateOf(0) }
    var fullScreen by remember(file) { mutableStateOf(false) }
    val ext = fileExtension(file)
    Dialog(onDismissRequest = done) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(if (fullScreen) 1f else 0.92f), shape = if (fullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Bold)
                    IconButton({ fullScreen = !fullScreen }) {
                        Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Toggle full screen")
                    }
                    IconButton(done) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                when {
                    ext == "pdf" -> {
                        val pageCount = remember(file) {
                            runCatching {
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                                    PdfRenderer(descriptor).use { it.pageCount }
                                }
                            }.getOrDefault(0)
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (pageCount > 0) {
                                Box(Modifier.fillMaxWidth().weight(1f).padding(6.dp), contentAlignment = Alignment.Center) {
                                    renderPdfPage(file, page.coerceIn(0, pageCount - 1))?.let { bitmap ->
                                        Image(bitmap.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                    } ?: EmptyCard("Unable to render this PDF.")
                                }
                                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Page ${page + 1} of $pageCount")
                                    Row {
                                        TextButton({ if (page > 0) page-- }, enabled = page > 0) { Text("Previous") }
                                        TextButton({ if (page < pageCount - 1) page++ }, enabled = page < pageCount - 1) { Text("Next") }
                                    }
                                }
                            } else EmptyCard("Unable to open this PDF.")
                        }
                    }
                    ext == "pptx" || ext == "ppt" -> {
                        val slides = remember(file) { readOfficeSlides(file) }
                        Column(Modifier.fillMaxSize()) {
                            if (slides.isNotEmpty()) {
                                Box(Modifier.fillMaxWidth().weight(1f).padding(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Text(slides[slide.coerceIn(0, slides.lastIndex)].ifBlank { "Blank slide" }, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                                }
                                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Slide ${slide + 1} of ${slides.size}")
                                    Row {
                                        TextButton({ if (slide > 0) slide-- }, enabled = slide > 0) { Text("Previous") }
                                        TextButton({ if (slide < slides.lastIndex) slide++ }, enabled = slide < slides.lastIndex) { Text("Next") }
                                    }
                                }
                            } else EmptyCard("Unable to read this PowerPoint offline.")
                        }
                    }
                    ext == "docx" -> {
                        val text = remember(file) { readOfficeText(file) ?: "No readable text was found in this Word document." }
                        LazyColumn(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            item {
                                Card(Modifier.fillMaxWidth().widthIn(max = 794.dp), shape = RoundedCornerShape(0.dp)) {
                                    Column(Modifier.padding(36.dp)) {
                                        Text("A4 Print Layout", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(10.dp))
                                        Text(text, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val text = remember(file) { readDisplayText(file) }
                        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) { item { Text(text, style = MaterialTheme.typography.bodyLarge) } }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordCard(r: Record, key: String, store: LocalStore, refresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, fontWeight = FontWeight.SemiBold)
                if (r.subtitle.isNotBlank()) Text(r.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.extra.isNotBlank()) Text(r.extra, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (key == "tasks" && r.dueDate.isNotBlank()) Text("Due: ${r.dueDate} ${r.dueTime}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (key == "tasks" && r.subjectId != 0L) store.get("subjects").firstOrNull { it.id == r.subjectId }?.let { Text("Subject: ${it.title}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
fun AddRecordDialog(label:String,key:String,store:LocalStore,done:()->Unit){
    var subjectId by remember{mutableLongStateOf(0L)};var title by remember{mutableStateOf("")};var subtitle by remember{mutableStateOf("")};var extra by remember{mutableStateOf("")};var value by remember{mutableStateOf("")}
    var dueDate by remember{mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))}
    var showDueDatePicker by remember{mutableStateOf(false)}
    val subjects=store.get("subjects");val isTask=key=="tasks"
    AlertDialog(onDismissRequest=done,title={Text("Add $label")},text={Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(isTask){Text("Subject",fontWeight=FontWeight.Bold);if(subjects.isEmpty())Text("Add a class first so this task can be linked to a subject.",color=MaterialTheme.colorScheme.error)
            else Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){subjects.forEach{s->FilterChip(subjectId==s.id,{subjectId=s.id},label={Text(s.title)})}}
            OutlinedButton(onClick={showDueDatePicker=true},modifier=Modifier.fillMaxWidth()){
                Icon(Icons.Default.Event,null);Spacer(Modifier.width(8.dp));Text("Due date: $dueDate")
            }}
        OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("Title")});OutlinedTextField(subtitle,{subtitle=it},Modifier.fillMaxWidth(),label={Text("Description")})
        if(key=="tasks"||key=="reviewers")OutlinedTextField(extra,{extra=it},Modifier.fillMaxWidth(),label={Text("Notes")})
        if(key=="grades"||key=="expenses")OutlinedTextField(value,{value=it},Modifier.fillMaxWidth(),label={Text(if(key=="grades")"Grade" else "Amount")})
    }},confirmButton={Button({if(title.isNotBlank()&&(!isTask||subjectId!=0L))store.put(key,store.get(key)+Record(title=title.trim(),subtitle=subtitle.trim(),extra=extra.trim(),value=value.toDoubleOrNull()?:0.0,subjectId=subjectId,dueDate=if(isTask)dueDate else "",dueTime=""));done()}){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})
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
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
                .replace("/", "_")
                .replace("\\", "_")
        }
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