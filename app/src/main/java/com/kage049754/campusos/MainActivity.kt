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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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

data class SubjectNote(val id: Long, val title: String, val body: String, val updatedAt: Long, val favorite: Boolean = false, val order: Long = 0L)

private fun readableContentColor(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.62f) Color.Black else Color.White
}

fun nextRecordId(store: LocalStore, key: String, offset: Int = 0): Long {
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
    private val appContext = context.applicationContext
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
        CampusReminders.reschedule(appContext)
        CampusWidgets.updateAll(appContext)
    }
    fun get(key: String) = read(key)
    fun put(key: String, list: List<Record>) = save(key, list)
    fun delete(key: String, id: Long) = save(key, read(key).filterNot { it.id == id })
    fun pin() = prefs.getString("pin", "") ?: ""
    fun setPin(v: String) { prefs.edit().putString("pin", v).apply(); revision++ }
    fun theme() = prefs.getString("theme", "system") ?: "system"
    fun homeLayoutOrder(): List<String> = (prefs.getString("home_layout_order", "") ?: "").split(",").filter { it.isNotBlank() }
    fun setHomeLayoutOrder(order: List<String>) { prefs.edit().putString("home_layout_order", order.joinToString(",")).apply(); revision++ }
    fun homeHiddenTiles(): Set<String> = (prefs.getString("home_hidden_tiles", "") ?: "").split(",").filter { it.isNotBlank() }.toSet()
    fun setHomeHiddenTiles(hidden: Set<String>) { prefs.edit().putString("home_hidden_tiles", hidden.joinToString(",")).apply(); revision++ }
    fun resetHomeLayout() { prefs.edit().remove("home_layout_order").remove("home_hidden_tiles").apply(); revision++ }
    fun setTheme(v: String) { prefs.edit().putString("theme", v).apply(); revision++ }
    fun lockEnabled() = prefs.getBoolean("lock", false)
    fun setLockEnabled(v: Boolean) { prefs.edit().putBoolean("lock", v).apply(); revision++ }
    fun profileName() = prefs.getString("profile_name", "") ?: ""
    fun profileStudentId() = prefs.getString("profile_student_id", "") ?: ""
    fun profileSection() = prefs.getString("profile_section", "") ?: ""
    fun profilePhotoPath() = prefs.getString("profile_photo_path", "") ?: ""
    fun subjectNotes(subjectId: Long): List<SubjectNote> = runCatching {
        val raw = prefs.getString("subject_notes_$subjectId", "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                SubjectNote(o.optLong("id"), o.optString("title"), o.optString("body"), o.optLong("updatedAt"), o.optBoolean("favorite", false), o.optLong("order", 0L))
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrElse { emptyList() }

    fun saveSubjectNotes(subjectId: Long, notes: List<SubjectNote>) {
        val a = JSONArray()
        notes.forEach { n ->
            a.put(JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("body", n.body)
                put("updatedAt", n.updatedAt)
                put("favorite", n.favorite)
                put("order", n.order)
            })
        }
        prefs.edit().putString("subject_notes_$subjectId", a.toString()).apply()
        revision++
    }
    fun setProfile(name: String, studentId: String, section: String, photoPath: String) { prefs.edit().putString("profile_name", name).putString("profile_student_id", studentId).putString("profile_section", section).putString("profile_photo_path", photoPath).apply(); revision++; CampusWidgets.updateAll(appContext) }
    fun scheduleDayHighlight() = prefs.getLong("schedule_day_highlight", 0xFF1976D2L)
    fun setScheduleDayHighlight(v: Long) { prefs.edit().putLong("schedule_day_highlight", v) .apply(); revision++ }
    fun scheduleTimeHighlight() = prefs.getLong("schedule_time_highlight", 0xFF43A047L)
    fun setScheduleTimeHighlight(v: Long) { prefs.edit().putLong("schedule_time_highlight", v) .apply(); revision++ }
    fun scheduleTableBackground() = prefs.getLong("schedule_table_background", 0x00000000L)
    fun setScheduleTableBackground(v: Long) { prefs.edit().putLong("schedule_table_background", v) .apply(); revision++ }
    fun scheduleTableBorder() = prefs.getLong("schedule_table_border", 0xFF808080L)
    fun setScheduleTableBorder(v: Long) { prefs.edit().putLong("schedule_table_border", v) .apply(); revision++ }
    fun scheduleTableHorizontalScroll() = prefs.getBoolean("schedule_table_horizontal_scroll", false)
    fun setScheduleTableHorizontalScroll(v: Boolean) { prefs.edit().putBoolean("schedule_table_horizontal_scroll", v).apply(); revision++ }
    fun scheduleTableVerticalScroll() = prefs.getBoolean("schedule_table_vertical_scroll", false)
    fun setScheduleTableVerticalScroll(v: Boolean) { prefs.edit().putBoolean("schedule_table_vertical_scroll", v).apply(); revision++ }
    fun scheduleTableFontSize() = prefs.getFloat("schedule_table_font_size", 11f)
    fun setScheduleTableFontSize(v: Float) { prefs.edit().putFloat("schedule_table_font_size", v.coerceIn(8f, 18f)).apply(); revision++ }
    fun scheduleTableDayWidth() = prefs.getFloat("schedule_table_day_width", 0f)
    fun setScheduleTableDayWidth(v: Float) { prefs.edit().putFloat("schedule_table_day_width", v.coerceIn(0f, 180f)).apply(); revision++ }
    fun scheduleTableRowHeight() = prefs.getFloat("schedule_table_row_height", 0f)
    fun setScheduleTableRowHeight(v: Float) { prefs.edit().putFloat("schedule_table_row_height", v.coerceIn(0f, 120f)).apply(); revision++ }
    fun scheduleDays() = (prefs.getString("schedule_days", "Monday,Tuesday,Wednesday,Thursday,Friday,Saturday") ?: "Monday,Tuesday,Wednesday,Thursday,Friday,Saturday").split(",").filter { it.isNotBlank() }
    fun scheduleDaysConfigured() = prefs.getBoolean("schedule_days_configured", false)
    fun setScheduleDays(v: List<String>) { prefs.edit().putString("schedule_days", v.joinToString(",")).putBoolean("schedule_days_configured", true).apply(); revision++; CampusReminders.reschedule(appContext); CampusWidgets.updateAll(appContext) }
    fun scheduleStartHour() = prefs.getInt("schedule_start_hour", 7)
    fun scheduleEndHour() = prefs.getInt("schedule_end_hour", 19)
    fun setScheduleHours(start: Int, end: Int) { prefs.edit().putInt("schedule_start_hour", start).putInt("schedule_end_hour", end).apply(); revision++; CampusReminders.reschedule(appContext); CampusWidgets.updateAll(appContext) }
    fun backupJson(selected: Set<String> = setOf("homepage","schedule","tasks","academics")): String {
        val root = JSONObject()
        if ("schedule" in selected) root.put("schedule", prefs.getString("schedule", "[]"))
        if ("tasks" in selected) root.put("tasks", prefs.getString("tasks", "[]"))
        if ("academics" in selected) {
            root.put("subjects", prefs.getString("subjects", "[]"))
            val notes = JSONObject()
            prefs.all.filterKeys { it.startsWith("subject_notes_") }.forEach { (k,v) -> if (v is String) notes.put(k.removePrefix("subject_notes_"), v) }
            root.put("subjectNotes", notes)
            val files = JSONArray(); val dir = File(appContext.filesDir, "subject_files")
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                files.put(JSONObject().apply { put("path", f.relativeTo(dir).path); put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) })
            }
            root.put("subjectFiles", files)
        }
        if ("homepage" in selected) {
            root.put("theme", theme()); root.put("lock", lockEnabled())
            root.put("profileName", profileName()); root.put("profileStudentId", profileStudentId()); root.put("profileSection", profileSection())
        }
        return root.toString(2)
    }
    fun restoreJson(json: String, selected: Set<String> = setOf("homepage","schedule","tasks","academics")) {
        val root = JSONObject(json); val e = prefs.edit()
        if ("schedule" in selected && root.has("schedule")) e.putString("schedule", root.getString("schedule"))
        if ("tasks" in selected && root.has("tasks")) e.putString("tasks", root.getString("tasks"))
        if ("academics" in selected) {
            if (root.has("subjects")) e.putString("subjects", root.getString("subjects"))
            root.optJSONObject("subjectNotes")?.let { notes -> notes.keys().forEach { id -> e.putString("subject_notes_$id", notes.getString(id)) } }
        }
        if ("homepage" in selected) {
            if (root.has("theme")) e.putString("theme", root.getString("theme"))
            if (root.has("lock")) e.putBoolean("lock", root.getBoolean("lock"))
            if (root.has("profileName")) e.putString("profile_name", root.getString("profileName"))
            if (root.has("profileStudentId")) e.putString("profile_student_id", root.getString("profileStudentId"))
            if (root.has("profileSection")) e.putString("profile_section", root.getString("profileSection"))
        }
        if ("academics" in selected && root.has("subjectFiles")) {
            val dir = File(appContext.filesDir, "subject_files"); dir.mkdirs(); val files = root.optJSONArray("subjectFiles") ?: JSONArray()
            for (i in 0 until files.length()) runCatching { val o=files.getJSONObject(i); val target=File(dir,o.getString("path")); target.parentFile?.mkdirs(); target.writeBytes(Base64.decode(o.getString("data"),Base64.DEFAULT)) }
        }
        e.apply(); revision++; CampusReminders.reschedule(appContext); CampusWidgets.updateAll(appContext)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CampusOSApp(this) }
        CampusReminders.reschedule(this)
        CampusWidgets.updateAll(this)
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
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val screen = Screen.valueOf(screenName)
    var search by rememberSaveable { mutableStateOf("") }
    var showHomeAdd by remember { mutableStateOf(false) }
    var showHomeColors by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showScheduleSettings by remember { mutableStateOf(false) }
    var showScheduleTableSettings by remember { mutableStateOf(false) }
    var showScheduleManager by remember { mutableStateOf(false) }
    var showScheduleDetails by remember { mutableStateOf(false) }
    var settingsModule by remember { mutableStateOf<String?>(null) }
    var settingsParent by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleFullscreen by rememberSaveable { mutableStateOf(false) }
    var subjectPageId by rememberSaveable { mutableLongStateOf(0L) }
    var subjectPageMode by rememberSaveable { mutableIntStateOf(0) }
    var subjectOpenedFile by rememberSaveable { mutableStateOf("") }

    BackHandler {
        when {
            subjectPageId != 0L && subjectOpenedFile.isNotBlank() -> subjectOpenedFile = ""
            subjectPageId != 0L -> { subjectPageId = 0L; subjectOpenedFile = "" }
            showScheduleDetails -> showScheduleDetails = false
            showScheduleTableSettings -> {
                showScheduleTableSettings = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showScheduleManager -> {
                showScheduleManager = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showScheduleSettings -> {
                showScheduleSettings = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showProfile -> {
                showProfile = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showHomeColors -> {
                showHomeColors = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showHomeAdd -> {
                showHomeAdd = false
                settingsModule = settingsParent
                settingsParent = null
            }
            showHomeSettings -> showHomeSettings = false
            settingsModule != null -> {
                settingsModule = null
                settingsParent = null
                showHomeSettings = true
            }
            scheduleFullscreen -> scheduleFullscreen = false
            screen != Screen.HOME -> screenName = Screen.HOME.name
        }
    }

    if (locked) { LockScreen(store) { locked = false }; return }

    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        if (subjectPageId != 0L) {
            val subject = store.get("subjects").firstOrNull { it.id == subjectPageId }
            if (subject == null) {
                subjectPageId = 0L
                subjectOpenedFile = ""
            } else if (subjectOpenedFile.isNotBlank()) {
                val file = File(subjectFolder(activity, subject.id), subjectOpenedFile)
                if (file.exists()) InAppFileViewerPage(file) { subjectOpenedFile = "" }
                else subjectOpenedFile = ""
            } else if (subjectPageMode == 0) {
                SubjectNotepadPage(subject, store) { subjectPageId = 0L }
            } else {
                SubjectLectureFilesPage(subject, { subjectOpenedFile = it }) { subjectPageId = 0L }
            }
        } else {
        Scaffold(
            topBar = {
                if (!scheduleFullscreen) {
                    TopAppBar(
                        title = { Text("CampusOS", fontWeight = FontWeight.Bold) },
                        actions = {
                            if (screen == Screen.SCHEDULE) {
                                IconButton(onClick = { showScheduleDetails = true }) {
                                    Icon(Icons.Default.Info, "Subject details")
                                }
                            }
                            IconButton(onClick = { showHomeSettings = true }) {
                                Icon(Icons.Default.Settings, "CampusOS settings")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!scheduleFullscreen) {
                    NavigationBar {
                        listOf(
                            Screen.HOME,
                            Screen.SCHEDULE,
                            Screen.TASKS,
                            Screen.ACADEMICS
                        ).forEach {
                            NavigationBarItem(
                                selected = screen == it,
                                onClick = { screenName = it.name },
                                icon = { Icon(iconFor(it), it.label) },
                                label = { Text(it.label) }
                            )
                        }
                    }
                }
            },
            floatingActionButton = { }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(store) { screenName = it.name }
                    Screen.SCHEDULE -> ScheduleScreen(store, search, scheduleFullscreen, { scheduleFullscreen = it }, { showScheduleDetails = true }) { search = "" }
                    Screen.TASKS -> TasksScreen(store, search, { search = "" }, { id -> subjectPageId = id; subjectPageMode = 0 })
                    Screen.ACADEMICS -> AcademicsScreen(store, search, { search = "" }, { subjectPageId = it.id; subjectPageMode = 0 }, { subjectPageId = it.id; subjectPageMode = 1 })
                    Screen.FILES -> FilesScreen()
                    Screen.SETTINGS -> SettingsScreen(
                        store, theme,
                        { theme = it; store.setTheme(it) },
                        { locked = true },
                        { showScheduleSettings = true },
                        { showScheduleManager = true }
                    )
                }
                if (!scheduleFullscreen && screen != Screen.HOME && screen != Screen.SETTINGS && screen != Screen.FILES && screen != Screen.TASKS) {
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
                HomeSettingsDialog(onModule = { settingsModule = it; settingsParent = null; showHomeSettings = false }, onAppearance = { showHomeColors = true; settingsParent = null; showHomeSettings = false }, done = { showHomeSettings = false })
            }
            if (showHomeAdd) ScheduleDialog(store) { showHomeAdd = false }
            if (showHomeColors) HomeAppearanceDialog(store, theme, { theme = it; store.setTheme(it) }) { showHomeColors = false }
            if (showProfile) ProfileDialog(store) { showProfile = false }
            if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
            if (showScheduleTableSettings) ScheduleTableSettingsDialog(store) { showScheduleTableSettings = false }
            if (showScheduleManager) ScheduleManagerDialog(store) { showScheduleManager = false }
            settingsModule?.let { module -> ModuleSettingsDialog(module, { settingsModule = null; settingsParent = null; showHomeSettings = true }, { settingsParent = module; settingsModule = null; showProfile = true }, { settingsParent = module; settingsModule = null; showScheduleManager = true }, { settingsParent = module; settingsModule = null; showScheduleSettings = true }, { settingsParent = module; settingsModule = null; showScheduleTableSettings = true }, { settingsParent = module; settingsModule = null; showHomeAdd = true }, { settingsModule = null; settingsParent = null; screenName = Screen.TASKS.name }, { settingsModule = null; settingsParent = null; screenName = Screen.ACADEMICS.name }) }
            if (showScheduleDetails) SubjectDetailsDialog(store.get("schedule"), { showScheduleDetails = false })
        }
        }
    }
}private fun iconFor(s: Screen) = when(s) {
    Screen.HOME -> Icons.Default.Home
    Screen.SCHEDULE -> Icons.Default.CalendarMonth
    Screen.TASKS -> Icons.Default.CheckCircle
    Screen.ACADEMICS -> Icons.Default.School
    Screen.FILES -> Icons.Default.Folder
    Screen.SETTINGS -> Icons.Default.Settings
}

@Composable
fun HomeSettingsDialog(onModule:(String)->Unit,onAppearance:()->Unit,done:()->Unit) {
    AlertDialog(onDismissRequest=done,title={Text("CampusOS Settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Choose a module",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text("Open the dedicated settings for each part of CampusOS.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Homepage" to Icons.Default.Home,"Schedule" to Icons.Default.CalendarMonth,"Tasks" to Icons.Default.CheckCircle,"Academics" to Icons.Default.School).forEach{(name,icon)->OutlinedButton({onModule(name)},Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(8.dp));Text(name)}}
        HorizontalDivider()
        OutlinedButton(onAppearance,Modifier.fillMaxWidth()){Icon(Icons.Default.Palette,null);Spacer(Modifier.width(8.dp));Text("Appearance & Design")}
    }},confirmButton={TextButton(done){Text("Close")}})
}
@Composable
fun ModuleSettingsDialog(module:String,close:()->Unit,profile:()->Unit,scheduleManager:()->Unit,scheduleSettings:()->Unit,tableSettings:()->Unit,addClass:()->Unit,openTasks:()->Unit,openAcademics:()->Unit) {
    var showTaskSettings by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=close,title={Text("$module Settings")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        when(module){
            "Homepage"->{Text("Homepage controls",fontWeight=FontWeight.Bold);OutlinedButton(profile,Modifier.fillMaxWidth()){Icon(Icons.Default.Person,null);Spacer(Modifier.width(8.dp));Text("Profile & homepage information")}}
            "Schedule"->{Text("Schedule controls",fontWeight=FontWeight.Bold);OutlinedButton(addClass,Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Add Class")};OutlinedButton(scheduleManager,Modifier.fillMaxWidth()){Icon(Icons.Default.EditCalendar,null);Spacer(Modifier.width(8.dp));Text("Edit / Delete Classes")};OutlinedButton(scheduleSettings,Modifier.fillMaxWidth()){Icon(Icons.Default.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text("Class Schedule Settings")};OutlinedButton(tableSettings,Modifier.fillMaxWidth()){Icon(Icons.Default.TableView,null);Spacer(Modifier.width(8.dp));Text("Schedule Table Settings")}}
            "Tasks"->{Text("Task controls",fontWeight=FontWeight.Bold);Text("Simple calendar and to-do tasks stored offline.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openTasks,Modifier.fillMaxWidth()){Icon(Icons.Default.CheckCircle,null);Spacer(Modifier.width(8.dp));Text("Open Tasks")};OutlinedButton({ showTaskSettings = true },Modifier.fillMaxWidth()){Icon(Icons.Default.Settings,null);Spacer(Modifier.width(8.dp));Text("Task Settings")}}
            "Academics"->{Text("Academics controls",fontWeight=FontWeight.Bold);Text("Subjects, Notepad, and Lecture Files are stored offline. Use the Academics screen to manage them.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openAcademics,Modifier.fillMaxWidth()){Icon(Icons.Default.School,null);Spacer(Modifier.width(8.dp));Text("Open Academics")}}
        }
    }},confirmButton={TextButton(close){Text("Close")}})
    if (showTaskSettings) TaskSettingsDialog(LocalStore(androidx.compose.ui.platform.LocalContext.current)) { showTaskSettings = false }
}
@Composable
fun ScheduleTableSettingsDialog(store: LocalStore, done: () -> Unit) {
    var horizontal by remember { mutableStateOf(store.scheduleTableHorizontalScroll()) }
    var vertical by remember { mutableStateOf(store.scheduleTableVerticalScroll()) }
    var fontSize by remember { mutableFloatStateOf(store.scheduleTableFontSize()) }
    var dayWidth by remember { mutableFloatStateOf(store.scheduleTableDayWidth()) }
    var rowHeight by remember { mutableFloatStateOf(store.scheduleTableRowHeight()) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Schedule Table Settings") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Default layout stays unchanged until you customize it.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Horizontal scrolling"); Text("Swipe left/right when the table is wider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = horizontal, onCheckedChange = { horizontal = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Vertical scrolling"); Text("Scroll the timetable up/down.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = vertical, onCheckedChange = { vertical = it })
                }
                Text("Text size: ${fontSize.toInt()} sp")
                Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 8f..18f, steps = 9)
                Text("Day column width: ${if (dayWidth == 0f) "Automatic" else "%.0f dp".format(dayWidth)}")
                Slider(value = if (dayWidth == 0f) 86f else dayWidth, onValueChange = { dayWidth = it }, valueRange = 60f..180f, steps = 11)
                TextButton(onClick = { dayWidth = 0f }) { Text("Use automatic width") }
                Text("Row height: ${if (rowHeight == 0f) "Automatic" else "%.0f dp".format(rowHeight)}")
                if (rowHeight > 0f) {
                    Slider(value = rowHeight, onValueChange = { rowHeight = it }, valueRange = 30f..120f, steps = 8)
                }
                TextButton(onClick = { rowHeight = 0f }) { Text("Use automatic height") }
            }
        },
        confirmButton = { Button(onClick = {
            store.setScheduleTableHorizontalScroll(horizontal)
            store.setScheduleTableVerticalScroll(vertical)
            store.setScheduleTableFontSize(fontSize)
            store.setScheduleTableDayWidth(dayWidth)
            store.setScheduleTableRowHeight(rowHeight)
            done()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@Composable
fun ProfileDialog(store: LocalStore, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(store.profileName()) }
    var studentId by remember { mutableStateOf(store.profileStudentId()) }
    var section by remember { mutableStateOf(store.profileSection()) }
    var photoPath by remember { mutableStateOf(store.profilePhotoPath()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val target = File(context.filesDir, "profile_photo")
        runCatching { context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }; photoPath = target.absolutePath }
    }
    val bitmap = remember(photoPath) { if (photoPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(photoPath) }.getOrNull() else null }
    AlertDialog(onDismissRequest = done, title = { Text("Profile") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), "Profile photo", Modifier.size(96.dp), contentScale = ContentScale.Crop)
            else Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, Modifier.size(48.dp)) }
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Upload profile photo") }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            OutlinedTextField(studentId, { studentId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Student ID") })
            OutlinedTextField(section, { section = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Section") })
        }
    }, confirmButton = { Button(onClick = { store.setProfile(name.trim(), studentId.trim(), section.trim(), photoPath); done() }) { Text("Save") } }, dismissButton = { TextButton(done) { Text("Cancel") } })
}

@Composable
fun HomeScreen(store: LocalStore, go: (Screen) -> Unit) {
    val revision = store.revision
    val tasks = remember(revision) { store.get("tasks") }
    val schedule = remember(revision) { store.get("schedule") }
    val subjects = remember(revision) { store.get("subjects") }
    val profileName = store.profileName().ifBlank { "Student" }
    val studentId = store.profileStudentId()
    val section = store.profileSection()
    val photoPath = store.profilePhotoPath()
    val date = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
    val todayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    val todaySchedule = remember(schedule, todayName) { mergeTodayClasses(schedule.filter { it.day.equals(todayName, true) }) }
    val pendingTasks = remember(tasks) { tasks.filter { !it.done }.sortedWith(compareBy({ it.dueDate }, { it.dueTime })).take(5) }
    val pinnedTasks = remember(tasks) { tasks.filter { !it.done && taskPinned(it) }.sortedWith(compareBy({ it.dueDate }, { it.dueTime })).take(5) }
    val photo = remember(photoPath, revision) { if (photoPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(photoPath) }.getOrNull() else null }
    val defaultOrder = listOf("profile", "stats", "classes", "pinned", "tasks", "quick")
    val savedOrder = store.homeLayoutOrder()
    var tileOrder by remember(revision) { mutableStateOf((savedOrder + defaultOrder).distinct().filter { it in defaultOrder }) }
    var editMode by remember { mutableStateOf(false) }
    var showHomeTileSettings by remember { mutableStateOf(false) }
    var hiddenTiles by remember(revision) { mutableStateOf(store.homeHiddenTiles()) }
    val listState = rememberLazyListState()
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val visibleOrder = tileOrder.filter { it !in hiddenTiles }

    fun moveTile(key: String, targetKey: String) {
        val from = tileOrder.indexOf(key)
        val to = tileOrder.indexOf(targetKey)
        if (from >= 0 && to >= 0 && from != to) {
            tileOrder = tileOrder.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("CampusOS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (editMode) Text("Drag tiles to arrange your Home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                editMode = !editMode
                if (editMode) tileOrder = (store.homeLayoutOrder() + defaultOrder).distinct().filter { it in defaultOrder }
                else {
                    store.setHomeLayoutOrder(tileOrder)
                    store.setHomeHiddenTiles(hiddenTiles)
                }
            }) {
                Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, if (editMode) "Done" else "Customize Home")
            }
            if (!editMode) {
                IconButton(onClick = { showHomeTileSettings = true }) {
                    Icon(Icons.Default.ViewModule, "Home tiles")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items = visibleOrder, key = { tile -> tile }) { tileKey ->
                val isDragged = draggedKey == tileKey
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; alpha = if (isDragged) 0.82f else 1f }
                        .then(
                            if (editMode) Modifier.pointerInput(tileKey, tileOrder) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggedKey = tileKey; dragOffset = 0f },
                                    onDragCancel = { draggedKey = null; dragOffset = 0f },
                                    onDragEnd = {
                                        draggedKey = null
                                        dragOffset = 0f
                                        store.setHomeLayoutOrder(tileOrder)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val visible: List<androidx.compose.foundation.lazy.LazyListItemInfo> = listState.layoutInfo.visibleItemsInfo
                                        val firstIndex = listState.firstVisibleItemIndex
                                        val draggedIndex = tileOrder.indexOf(tileKey)
                                        val draggedPosition = draggedIndex - firstIndex
                                        val draggedInfo = visible.getOrNull(draggedPosition)
                                        val center = draggedInfo?.let { it.offset + it.size / 2 } ?: 0
                                        val pointerCenter = center.toFloat() + dragOffset
                                        var targetPosition = -1
                                        var targetDistance = Float.MAX_VALUE
                                        for (position in visible.indices) {
                                            val itemIndex = firstIndex + position
                                            if (itemIndex == draggedIndex) continue
                                            val item = visible[position]
                                            val distance = kotlin.math.abs((item.offset + item.size / 2).toFloat() - pointerCenter)
                                            if (distance < targetDistance) {
                                                targetDistance = distance
                                                targetPosition = position
                                            }
                                        }
                                        if (targetPosition >= 0) {
                                            val targetIndex = firstIndex + targetPosition
                                            val targetKey = tileOrder.getOrNull(targetIndex)
                                            val targetItem = visible[targetPosition]
                                            if (targetKey != null && targetDistance < targetItem.size / 2) {
                                                moveTile(tileKey, targetKey)
                                                dragOffset = 0f
                                            }
                                        }
                                    }
                                )
                            } else Modifier
                        )
                ) {
                    when (tileKey) {
                        "profile" -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (photo != null) Image(photo.asImageBitmap(), "Profile photo", Modifier.size(64.dp), contentScale = ContentScale.Crop)
                                else Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)), contentAlignment = Alignment.Center) { Text(profileName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Welcome back", style = MaterialTheme.typography.labelLarge)
                                    Text(profileName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    val details = listOf(studentId, section).filter { it.isNotBlank() }.joinToString(" • ")
                                    if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodyMedium)
                                    Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        "stats" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Classes", schedule.size.toString(), Modifier.weight(1f))
                            StatCard("Subjects", subjects.size.toString(), Modifier.weight(1f))
                            StatCard("Tasks", tasks.count { !it.done }.toString(), Modifier.weight(1f))
                        }
                        "classes" -> HomeClassesTile(todaySchedule)
                        "pinned" -> HomePinnedTile(pinnedTasks)
                        "tasks" -> HomeTasksTile(pendingTasks)
                        "quick" -> Column(Modifier.fillMaxWidth()) {
                            SectionTitle("Quick access")
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction("Subjects", Icons.Default.School) { go(Screen.ACADEMICS) }
                                SmallAction("Files", Icons.Default.Folder) { go(Screen.FILES) }
                                SmallAction("Tasks", Icons.Default.CheckCircle) { go(Screen.TASKS) }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun HomeTileSettingsDialog(
    defaultOrder: List<String>,
    hiddenTiles: Set<String>,
    onHiddenChanged: (Set<String>) -> Unit,
    onReset: () -> Unit,
    done: () -> Unit
) {
    val labels = mapOf(
        "profile" to "Profile / Welcome",
        "stats" to "Statistics",
        "classes" to "Today's Classes",
        "pinned" to "Pinned Tasks",
        "tasks" to "Tasks to Do",
        "quick" to "Quick Access"
    )
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Home Tiles") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Choose which tiles appear on your Home screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                defaultOrder.forEach { key ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(labels[key] ?: key, Modifier.weight(1f))
                        Switch(
                            checked = key !in hiddenTiles,
                            onCheckedChange = { shown ->
                                val next = hiddenTiles.toMutableSet()
                                if (shown) next.remove(key) else next.add(key)
                                onHiddenChanged(next)
                            }
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.RestartAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Home Layout")
                }
            }
        },
        confirmButton = { TextButton(onClick = done) { Text("Done") } }
    )
}
@Composable
fun HomeTodayClassCard(r: Record) {
    val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = readableContentColor(bg))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (r.startTime.isNotBlank() && r.endTime.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${r.startTime}-${r.endTime}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (r.subtitle.isNotBlank()) Text(r.subtitle)
            if (r.day.isNotBlank()) Text(r.day, style = MaterialTheme.typography.labelMedium)
            if (r.room.isNotBlank()) Text("Room: ${r.room}")
            if (r.professor.isNotBlank()) Text("Professor: ${r.professor}")
            if (r.classType.isNotBlank()) Text("Option: ${r.classType}")
        }
    }
}

@Composable
fun ScheduleDaySetupDialog(store: LocalStore, done: () -> Unit) {
    val allDays=listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"); var chosen by remember{mutableStateOf(emptySet<String>())}
    AlertDialog(onDismissRequest={},title={Text("Set up your class days")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("Choose the days you normally have classes. Only these days will appear in your schedule.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp));allDays.forEach{day->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(chosen.contains(day),{chosen=if(day in chosen)chosen-day else chosen+day});Text(day)}}
    }},confirmButton={Button(enabled=chosen.isNotEmpty(),onClick={store.setScheduleDays(allDays.filter{it in chosen});done()}){Text("Continue")}})
}
@Composable
fun ScheduleSettingsDialog(store:LocalStore,done:()->Unit){
    val allDays=listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday");var chosen by remember{mutableStateOf(store.scheduleDays().toSet())};var start by remember{mutableIntStateOf(store.scheduleStartHour())};var end by remember{mutableIntStateOf(store.scheduleEndHour())}
    AlertDialog(onDismissRequest=done,title={Text("Class Schedule Settings")},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Show only the days you have class",fontWeight=FontWeight.Bold);allDays.forEach{day->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(chosen.contains(day),{chosen=if(day in chosen)chosen-day else chosen+day});Text(day)}}
        Text("Time range",fontWeight=FontWeight.Bold);Text("%02d:00 – %02d:00".format(start,end),style=MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){OutlinedButton({if(start>0)start--}){Text("Start −")};OutlinedButton({if(start<end-1)start++}){Text("Start +")};OutlinedButton({if(end<24)end++}){Text("End +")};OutlinedButton({if(end>start+1)end--}){Text("End −")}}
    }},confirmButton={Button({if(chosen.isNotEmpty()){store.setScheduleDays(allDays.filter{it in chosen});store.setScheduleHours(start,end)};done()}){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})}
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
fun ScheduleScreen(store: LocalStore, query: String, fullscreen: Boolean, setFullscreen: (Boolean) -> Unit, openDetails: () -> Unit, clear: () -> Unit) {
    val revision = store.revision
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showDaySetup by remember { mutableStateOf(!store.scheduleDaysConfigured()) }
    var showDetails by remember { mutableStateOf(false) }
    var showScheduleSettings by remember { mutableStateOf(false) }
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
                (it.title + " " + it.subtitle + " " + it.extra + " " + it.day + " " + it.room + " " + it.professor).contains(query, true)
        }
    }

    val scheduleDays = store.scheduleDays()
    val startHour = store.scheduleStartHour().coerceIn(0, 23)
    val endHour = store.scheduleEndHour().coerceIn(startHour, 23)
    val hours = if (endHour > startHour) (startHour until endHour).toList() else listOf(startHour)
    val calendar = remember(nowTick) { Calendar.getInstance() }
    val today = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val dayHighlight = Color(store.scheduleDayHighlight())
    val timeHighlight = Color(store.scheduleTimeHighlight())
    val tableBgValue = store.scheduleTableBackground()
    val tableBg = if (tableBgValue == 0L) Color.Transparent else Color(tableBgValue)
    val tableBorder = Color(store.scheduleTableBorder())
    val horizontalScrollEnabled = remember(revision) { store.scheduleTableHorizontalScroll() }
    val verticalScrollEnabled = remember(revision) { store.scheduleTableVerticalScroll() }
    val tableFontSize = remember(revision) { store.scheduleTableFontSize() }
    val customDayWidth = remember(revision) { store.scheduleTableDayWidth() }
    val customRowHeight = remember(revision) { store.scheduleTableRowHeight() }
    var zoom by remember(revision) { mutableFloatStateOf(1f) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)
        ) {
            val baseDayWidth = if (customDayWidth > 0f) customDayWidth.dp else (maxWidth - 56.dp).coerceAtLeast(0.dp) / scheduleDays.size.coerceAtLeast(1)
            val dayWidth = baseDayWidth * zoom
            val headerHeight = 34.dp
            val footerHeight = 0.dp
            val baseRowHeight = if (customRowHeight > 0f) customRowHeight.dp else ((maxHeight - headerHeight - footerHeight) / hours.size.coerceAtLeast(1)).coerceAtLeast(30.dp)
            val rowHeight = baseRowHeight * zoom

            Column(Modifier.fillMaxSize()
                .then(if (horizontalScrollEnabled) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .then(if (verticalScrollEnabled) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                if (horizontalScrollEnabled || verticalScrollEnabled) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Zoom ${zoom.toInt()}x", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }) { Text("−") }
                        TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
                        TextButton(onClick = { zoom = 1f }) { Text("Reset") }
                    }
                }
                Row(Modifier.height(headerHeight)) {
                    Box(Modifier.width(56.dp).fillMaxHeight().background(tableBg).border(1.dp, tableBorder), contentAlignment = Alignment.Center) {
                        Text("Time", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                    }
                    scheduleDays.forEach { d ->
                        val isToday = d.equals(today, true)
                        Box(
                            Modifier.width(dayWidth).fillMaxHeight()
                                .background(if (isToday) dayHighlight.copy(alpha = 0.16f) else tableBg)
                                .border(if (isToday) 2.dp else 1.dp, if (isToday) dayHighlight else tableBorder),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (isToday) Box(Modifier.size(6.dp).background(dayHighlight, RoundedCornerShape(50)))
                                Text(d.take(3), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                            }
                        }
                    }
                }

                hours.forEach { h ->
                    val isCurrentHour = h == currentHour
                    Row(Modifier.height(rowHeight)) {
                        Box(
                            Modifier.width(56.dp).fillMaxHeight().background(tableBg).border(1.dp, tableBorder)
                                .then(if (isCurrentHour) Modifier.border(2.dp, timeHighlight) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isCurrentHour) Box(Modifier.size(6.dp).background(timeHighlight, RoundedCornerShape(50)))
                                Text(formatHourRange(h, h + 1), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                            }
                        }

                        scheduleDays.forEach { day ->
                            val classes = all.filter { it.day.equals(day, true) && it.startTime.toHourOrNull() == h }
                            Box(
                                Modifier.width(dayWidth).fillMaxHeight()
                                    .background(if (day.equals(today, true) && isCurrentHour) timeHighlight.copy(alpha = 0.10f) else tableBg)
                                    .border(if (day.equals(today, true) && isCurrentHour) 2.dp else 1.dp, if (day.equals(today, true) && isCurrentHour) timeHighlight else tableBorder)
                                    .padding(1.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    classes.groupBy { it.title.trim().uppercase(Locale.getDefault()) }
                                        .values.take(2).forEach { subjectClasses ->
                                        val r = subjectClasses.first()
                                        val types = subjectClasses.map { if (it.classType.equals("Lecture", true)) "Lec" else "Lab" }.distinct().joinToString(" + ")
                                        val rooms = subjectClasses.map { it.room.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
                                        val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
                                        Card(
                                            Modifier.fillMaxWidth().weight(1f, fill = false),
                                            colors = CardDefaults.cardColors(containerColor = bg, contentColor = readableContentColor(bg)),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                                                Text("${r.title} - ${types.ifBlank { "Class" }}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                
                                                if (rooms.isNotBlank()) Text(rooms, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 3, softWrap = true, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.height(footerHeight)) {
                    Box(Modifier.width(56.dp).fillMaxHeight().background(tableBg).border(1.dp, tableBorder), contentAlignment = Alignment.Center) {
                        Text(formatHourRange(endHour, (endHour + 1).coerceAtMost(24)), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    scheduleDays.forEach { Box(Modifier.width(dayWidth).fillMaxHeight().background(tableBg).border(1.dp, tableBorder)) }
                }
            }
        }
    }

    if (showAdd) ScheduleDialog(store) { showAdd = false; clear(); refresh++ }
    if (showDetails) SubjectDetailsDialog(all, { showDetails = false })
    if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
    if (showDaySetup) ScheduleDaySetupDialog(store) { showDaySetup = false }
}

@Composable
fun SubjectDetailsDialog(all: List<Record>, done: () -> Unit) {
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Subject details") },
        text = {
            if (all.isEmpty()) {
                EmptyCard("No classes in the current schedule.")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(all, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(width = 4.dp, height = 48.dp).background(
                                        if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp)
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(r.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        if (r.room.isNotBlank()) Text("  •  " + r.room, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (r.subtitle.isNotBlank()) Text(r.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    if (r.professor.isNotBlank()) Text(r.professor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    Text(r.day + " • " + r.startTime + "-" + r.endTime + " • " + r.classType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(done) { Text("Close") } }
    )
}

@Composable
fun ScheduleManagerDialog(store: LocalStore, done: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Record?>(null) }
    val records = remember(refresh, store.revision) {
        store.get("schedule").sortedWith(compareBy<Record>({ it.day }, { it.startTime }, { it.title.lowercase() }, { it.classType }))
    }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Edit / Delete Classes") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fix a wrong type, room, time, day, professor, or subject without rebuilding the whole schedule.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (records.isEmpty()) EmptyCard("No schedule entries yet.")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(records, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(r.title + " • " + r.classType, fontWeight = FontWeight.SemiBold) },
                                supportingContent = {
                                    Text(buildString {
                                        append(r.day); append(" • "); append(r.startTime)
                                        if (r.endTime.isNotBlank()) append("–").append(r.endTime)
                                        if (r.room.isNotBlank()) append(" • ").append(r.room)
                                        if (r.professor.isNotBlank()) append(" • ").append(r.professor)
                                    }, maxLines = 2)
                                },
                                leadingContent = { Icon(Icons.Default.EventNote, null) },
                                trailingContent = {
                                    Row {
                                        IconButton({ editing = r }) { Icon(Icons.Default.Edit, "Edit class") }
                                        IconButton({
                                            deleteScheduleAndSync(store, r)
                                            refresh++
                                        }) { Icon(Icons.Default.Delete, "Delete class") }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(done) { Text("Close") } }
    )
    editing?.let { record ->
        EditScheduleRecordDialog(record, store, { editing = null; refresh++ }, { editing = null })
    }
}

@Composable
fun EditScheduleRecordDialog(record: Record, store: LocalStore, done: () -> Unit, cancel: () -> Unit) {
    var subject by remember(record.id) { mutableStateOf(record.title) }
    var fullName by remember(record.id) { mutableStateOf(record.subtitle) }
    var day by remember(record.id) { mutableStateOf(record.day) }
    var startTime by remember(record.id) { mutableStateOf(record.startTime) }
    var endTime by remember(record.id) { mutableStateOf(record.endTime) }
    var room by remember(record.id) { mutableStateOf(record.room) }
    var professor by remember(record.id) { mutableStateOf(record.professor) }
    var notes by remember(record.id) { mutableStateOf(record.extra) }
    var type by remember(record.id) { mutableStateOf(record.classType.ifBlank { "Lecture" }) }
    val days = store.scheduleDays().ifEmpty { listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday") }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Edit class") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject code") }, singleLine = true)
                OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Whole subject name") })
                Text("Class type", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Lecture","Lab").forEach { option -> FilterChip(type == option, { type = option }, label = { Text(option) }) }
                }
                Text("Day", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { option -> FilterChip(day.equals(option,true), { day = option }, label = { Text(option.take(3)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startTime, { startTime = it }, Modifier.weight(1f), label = { Text("Start (HH:mm)") }, singleLine = true)
                    OutlinedTextField(endTime, { endTime = it }, Modifier.weight(1f), label = { Text("End (HH:mm)") }, singleLine = true)
                }
                OutlinedTextField(room, { room = it }, Modifier.fillMaxWidth(), label = { Text("Room") }, singleLine = true)
                OutlinedTextField(professor, { professor = it }, Modifier.fillMaxWidth(), label = { Text("Professor") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            }
        },
        confirmButton = {
            Button({
                if (subject.isNotBlank() && day.isNotBlank() && startTime.isNotBlank() && endTime.isNotBlank()) {
                    val updated = record.copy(title = subject.trim(), subtitle = fullName.trim(), extra = notes.trim(),
                        day = day, startTime = startTime.trim(), endTime = endTime.trim(),
                        room = room.trim(), professor = professor.trim(), classType = type)
                    store.put("schedule", store.get("schedule").map { if (it.id == record.id) updated else it })
                    syncSubjectFromClass(store, updated)
                    done()
                }
            }) { Text("Save changes") }
        },
        dismissButton = { TextButton(cancel) { Text("Cancel") } }
    )
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

fun String.toHourOrNull(): Int? = substringBefore(":").toIntOrNull()
private fun formatHourRange(start: Int, end: Int) = "%02d:00-%02d:00".format(start, end)
private fun mergeTodayClasses(records: List<Record>): List<Record> {
    // Same subject + consecutive time on the same day = one class.
    // Any vacant/gap period starts a separate class, even for the same subject.
    val sorted = records.sortedBy { it.startTime.toHourOrNull() ?: 99 }
    val out = mutableListOf<Record>()
    for (r in sorted) {
        val previous = out.lastOrNull()
        val previousEnd = previous?.endTime?.toHourOrNull()
        val start = r.startTime.toHourOrNull()
        val sameSubject = previous?.title?.trim()?.equals(r.title.trim(), ignoreCase = true) == true
        val consecutive = previousEnd != null && start != null && previousEnd == start
        if (previous != null && sameSubject && consecutive) {
            out[out.lastIndex] = previous.copy(endTime = r.endTime)
        } else {
            out += r
        }
    }
    return out
}

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
    val initialHour = initial.substringBefore(":").toIntOrNull()?.coerceIn(7, 19) ?: 7
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
                            maxValue = 19
                            value = hour
                            wrapSelectorWheel = false
                            displayedValues = (7..19).map { "%02d:00".format(it) }.toTypedArray()
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
    var lectureRoom by remember { mutableStateOf("") }
    var labRoom by remember { mutableStateOf("") }
    var sameRoomForLectureLab by remember { mutableStateOf(true) }
    var professor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var classType by remember { mutableStateOf("Lecture") }
    var color by remember { mutableLongStateOf(0xFFE3F2FD) }
    var selectedSlots by remember { mutableStateOf(setOf<String>()) }
    val colors = listOf(0xFFE3F2FDL,0xFFE8F5E9L,0xFFFFF3E0L,0xFFF3E5F5L,0xFFFFEBEEL,0xFFE0F7FAL)
    val startHour = store.scheduleStartHour().coerceIn(0,23)
    val endHour = store.scheduleEndHour().coerceIn(startHour,23)
    val hours = (startHour..endHour).toList()
    val weekDays = store.scheduleDays()
    val existingSchedule = store.get("schedule")
    AlertDialog(onDismissRequest=done,title={Text("Add class")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(subject,{subject=it},Modifier.fillMaxWidth(),label={Text("Subject code")},placeholder={Text("e.g. DCIT 25")},singleLine=true)
            OutlinedTextField(fullName,{fullName=it},Modifier.fillMaxWidth(),label={Text("Whole subject name")})
            Text("Type for the next slots",fontWeight=FontWeight.SemiBold)
            Text("Changing Lecture/Lab keeps previous selections. The same subject can have multiple days, times, and both Lecture and Lab.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Lecture","Lab").forEach{option->FilterChip(selected=classType==option,onClick={classType=option},label={Text(option)})}}
            Text("Pick class time(s) and day(s)",fontWeight=FontWeight.SemiBold)
            Text("Existing schedules are shown in each cell so you can avoid duplicate or incorrect entries. Different subjects can still share the same time.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=2.dp)){Column{
                Row{
                    Box(Modifier.width(48.dp).height(32.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Text("Time",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}
                    weekDays.forEach{d->Box(Modifier.width(86.dp).height(32.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Text(d.take(3),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}}
                }
                hours.forEach{h->Row{
                    Box(Modifier.width(48.dp).height(52.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Text("%02d:00".format(h),style=MaterialTheme.typography.labelSmall)}
                    weekDays.forEach{d->
                        val currentKey="$d|$h|$classType"
                        val selected=currentKey in selectedSlots
                        val lectureSelected = "$d|$h|Lecture" in selectedSlots
                        val labSelected = "$d|$h|Lab" in selectedSlots
                        val anySelected = lectureSelected || labSelected
                        val cellRecords=existingSchedule.filter{it.day.equals(d,true)&&it.startTime.toHourOrNull()==h}
                        val sameSubjectAlreadyThere=cellRecords.any{subject.isNotBlank()&&it.title.trim().equals(subject.trim(),true)&&it.classType.equals(classType,true)}
                        val occupantText=cellRecords.groupBy{it.title.trim().uppercase(Locale.getDefault())}.values.take(2).joinToString(" • "){group->
                            val first=group.first()
                            val types=group.map{it.classType}.distinct().joinToString("+")
                            val rooms=group.map{it.room.trim()}.filter{it.isNotBlank()}.distinct().joinToString("/")
                            buildString{append(first.title);append(" ");append(types);if(rooms.isNotBlank())append(" • ").append(rooms)}
                        }
                        Box(Modifier.width(86.dp).height(52.dp)
                            .border(2.dp,if(selected)MaterialTheme.colorScheme.primary else if(cellRecords.isNotEmpty())MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)
                            .background(if(selected)MaterialTheme.colorScheme.primaryContainer else if(cellRecords.isNotEmpty())MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                            .clickable{selectedSlots=if(selected)selectedSlots-currentKey else selectedSlots+currentKey},
                            contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                if(cellRecords.isNotEmpty()) Text(occupantText,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                else Text("Empty",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(selected) Text(if(sameSubjectAlreadyThere)"Already added" else "Selected $classType",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1)
                                else if(anySelected) Text(listOfNotNull(if(lectureSelected) "Lecture" else null, if(labSelected) "Lab" else null).joinToString(" + ") + " selected",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }}
            }}
            Text(if(selectedSlots.isEmpty())"No time selected" else selectedSlots.size.toString()+" slot(s) selected",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            Text("Rooms", fontWeight=FontWeight.SemiBold)
            Text("Lecture and Lab can share one room. If they use different rooms, turn this off and enter each room separately.", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Same room for Lecture + Lab")
                Switch(checked=sameRoomForLectureLab, onCheckedChange={ sameRoomForLectureLab=it })
            }
            if (sameRoomForLectureLab) {
                OutlinedTextField(room,{room=it;lectureRoom=it;labRoom=it},Modifier.fillMaxWidth(),label={Text("Room number")},singleLine=true)
            } else {
                OutlinedTextField(lectureRoom,{lectureRoom=it},Modifier.fillMaxWidth(),label={Text("Lecture room")},singleLine=true)
                OutlinedTextField(labRoom,{labRoom=it},Modifier.fillMaxWidth(),label={Text("Lab room")},singleLine=true)
            }
            OutlinedTextField(professor,{professor=it},Modifier.fillMaxWidth(),label={Text("Professor")})
            OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")})
            Text("Class color",fontWeight=FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){colors.forEach{c->Box(Modifier.size(40.dp).border(3.dp,if(color==c)MaterialTheme.colorScheme.onSurface else Color.Transparent,RoundedCornerShape(50)).padding(4.dp).background(Color(c),RoundedCornerShape(50)).clickable{color=c},contentAlignment=Alignment.Center){if(color==c)Text("✓",color=readableContentColor(Color(c)),fontWeight=FontWeight.Bold)}}}
        }
    },confirmButton={Button({
        if(subject.isNotBlank()&&selectedSlots.isNotEmpty()){
            if (sameRoomForLectureLab) {
                room = lectureRoom.ifBlank { labRoom }
                lectureRoom = room
                labRoom = room
            }
            val normalizedSubject=subject.trim()
            val existing=store.get("schedule")
            val newKeys=selectedSlots.map { key ->
                val p=key.split("|")
                Triple(p.getOrNull(0)?:"",p.getOrNull(1)?.toIntOrNull()?:7,p.getOrNull(2)?:classType)
            }.filterNot{(day,h,type)->
                existing.any{it.day.equals(day,true)&&it.startTime.toHourOrNull()==h&&it.classType.equals(type,true)&&it.title.trim().equals(normalizedSubject,true)}
            }
            if(newKeys.isNotEmpty()){
                val idBase=maxOf(System.currentTimeMillis(),(existing.maxOfOrNull{it.id}?:0L)+1L)
                val selected=newKeys.mapIndexed{index,slot->
                    val (day,h,type)=slot
                    Record(id=idBase+index,title=normalizedSubject,subtitle=fullName.trim(),extra=notes.trim(),day=day,startTime="%02d:00".format(h),endTime="%02d:00".format(h+1),room=(if (type.equals("Lecture", true)) lectureRoom else labRoom).trim().ifBlank { room.trim() },professor=professor.trim(),color=color,classType=type)
                }
                store.put("schedule",existing+selected)
                selected.forEach{syncSubjectFromClass(store,it)}
            }
        }
        done()
    }){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})
}
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
        Text(SimpleDateFormat("MMMM yyyy",Locale.getDefault()).format(first.time),Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            
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
fun AcademicsScreen(
    store: LocalStore,
    query: String,
    clear: () -> Unit,
    openNotepad: (Record) -> Unit,
    openLectureFiles: (Record) -> Unit
) {
    val revision = store.revision
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val labels = listOf("Subjects", "Reviewers")
    val keys = listOf("subjects", "reviewers")
    val list = remember(refresh, revision, query, tab) {
        store.get(keys[tab]).filter {
            query.isBlank() || query == "__ADD__" || (it.title + " " + it.subtitle + " " + it.extra).contains(query, true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Academics", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            labels.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (list.isEmpty()) {
            EmptyCard("No " + labels[tab].lowercase() + " yet. Add classes from Home Settings or use your existing data.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, fontWeight = FontWeight.SemiBold)
                                if (r.subtitle.isNotBlank()) Text(r.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            TextButton(onClick = { openNotepad(r) }) {
                                Icon(Icons.Default.StickyNote2, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Notepad")
                            }
                            TextButton(onClick = { openLectureFiles(r) }) {
                                Icon(Icons.Default.Folder, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Lecture Files")
                            }
                        }
                    }
                }
            }
        }
    }
    if (query == "__ADD__") AddRecordDialog(labels[tab], keys[tab], store) { clear(); refresh++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectNotepadPage(subject: Record, store: LocalStore, done: () -> Unit) {
    val revision = store.revision
    var notes by remember(subject.id, revision) { mutableStateOf(store.subjectNotes(subject.id)) }
    var noteQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var noteSort by rememberSaveable(subject.id) { mutableStateOf("Modified") }
    var editingNoteId by rememberSaveable(subject.id) { mutableStateOf<Long?>(null) }
    var noteTitle by rememberSaveable(subject.id) { mutableStateOf("") }
    var noteBody by rememberSaveable(subject.id) { mutableStateOf("") }

    fun beginNewNote() {
        editingNoteId = -1L
        noteTitle = ""
        noteBody = ""
    }
    fun editNote(note: SubjectNote) {
        editingNoteId = note.id
        noteTitle = note.title
        noteBody = note.body
    }
    fun saveNote() {
        if (noteTitle.isBlank() && noteBody.isBlank()) return
        val now = System.currentTimeMillis()
        val old = if (editingNoteId != null && editingNoteId != -1L) notes.firstOrNull { it.id == editingNoteId } else null
        val note = SubjectNote(
            id = old?.id ?: maxOf(now, (notes.maxOfOrNull { it.id } ?: 0L) + 1L),
            title = noteTitle.trim().ifBlank { "Untitled note" },
            body = noteBody,
            updatedAt = now,
            favorite = old?.favorite ?: false,
            order = old?.order ?: ((notes.maxOfOrNull { it.order } ?: 0L) + 1L)
        )
        val updated = if (old == null) notes + note else notes.map { if (it.id == old.id) note else it }
        store.saveSubjectNotes(subject.id, updated)
        notes = store.subjectNotes(subject.id)
        editingNoteId = null
    }
    fun deleteNote(note: SubjectNote) {
        store.saveSubjectNotes(subject.id, notes.filterNot { it.id == note.id })
        notes = store.subjectNotes(subject.id)
    }

    LaunchedEffect(subject.id) {
        if (notes.isEmpty()) {
            runCatching {
                val o = JSONObject(subject.extra)
                val title = o.optString("title")
                val body = o.optString("body")
                if (title.isNotBlank() || body.isNotBlank()) {
                    val legacy = SubjectNote(maxOf(System.currentTimeMillis(), 1L), title.ifBlank { "Untitled note" }, body, System.currentTimeMillis())
                    store.saveSubjectNotes(subject.id, listOf(legacy))
                    notes = store.subjectNotes(subject.id)
                }
            }
        }
    }

    val filtered = notes.filter { noteQuery.isBlank() || (it.title + " " + it.body).contains(noteQuery, true) }
    val noteList = filtered.sortedWith(
        compareByDescending<SubjectNote> { it.favorite }.thenBy {
            when (noteSort) {
                "Created" -> -it.id
                "Alphabetical" -> it.title.lowercase()
                "Manual" -> it.order
                else -> -it.updatedAt
            }
        }
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        TopAppBar(
            title = {
                Column {
                    Text("Notepad", fontWeight = FontWeight.Bold)
                    Text(subject.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = {
                IconButton(onClick = done) { Icon(Icons.Default.ArrowBack, "Back to Academics") }
            },
            actions = {
                if (editingNoteId == null) IconButton(onClick = { beginNewNote() }) { Icon(Icons.Default.NoteAdd, "New note") }
            }
        )
        if (editingNoteId == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = noteQuery,
                    onValueChange = { noteQuery = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search notes") }
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort:", fontWeight = FontWeight.SemiBold)
                    listOf("Modified", "Created", "Alphabetical", "Manual").forEach { option ->
                        FilterChip(selected = noteSort == option, onClick = { noteSort = option }, label = { Text(option) }, modifier = Modifier.padding(end=6.dp))
                    }
                }
                if (noteList.isEmpty()) {
                    EmptyCard("No notes yet. Tap + to create a note for " + subject.title + ".")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(noteList, key = { it.id }) { note ->
                            Card(onClick = { editNote(note) }, Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.StickyNote2, null)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        if (note.body.isNotBlank()) Text(note.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(note.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        store.saveSubjectNotes(subject.id, notes.map { if (it.id == note.id) it.copy(favorite = !it.favorite) else it })
                                        notes = store.subjectNotes(subject.id)
                                    }) { Icon(if (note.favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
                                    IconButton(onClick = { deleteNote(note) }) { Icon(Icons.Default.Delete, "Delete note") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { editingNoteId = null }) { Icon(Icons.Default.ArrowBack, "Back to notes") }
                    Text(if (editingNoteId == -1L) "New note" else "Edit note", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(noteTitle, { noteTitle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(noteBody, { noteBody = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("Note") }, placeholder = { Text("Write your notes here...") }, textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingNoteId = null }, Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { saveNote() }, Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectLectureFilesPage(subject: Record, openFile: (String) -> Unit, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by remember(subject.id) { mutableStateOf(subjectFiles(context, subject.id)) }
    var fileSort by rememberSaveable(subject.id) { mutableStateOf("Newest") }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        copyUriToSubject(context, uri, subject.id)?.let { files = subjectFiles(context, subject.id) }
    }
    val fileList = files.sortedWith(
        compareByDescending<File> { File(context.filesDir, "subject_favorite_" + subject.id + "_" + it.name).exists() }.thenBy {
            when (fileSort) {
                "Alphabetical" -> it.name.lowercase()
                "Oldest" -> it.lastModified()
                "Manual" -> it.name.lowercase()
                else -> -it.lastModified()
            }
        }
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        TopAppBar(
            title = {
                Column {
                    Text("Lecture Files", fontWeight = FontWeight.Bold)
                    Text(subject.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = done) { Icon(Icons.Default.ArrowBack, "Back to Academics") } },
            actions = { IconButton(onClick = { upload.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Add lecture file") } }
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort:", fontWeight = FontWeight.SemiBold)
                listOf("Newest", "Oldest", "Alphabetical", "Manual").forEach { option ->
                    FilterChip(selected = fileSort == option, onClick = { fileSort = option }, label = { Text(option) }, modifier = Modifier.padding(end=6.dp))
                }
            }
            if (fileList.isEmpty()) {
                EmptyCard("No lecture files yet. Add a PDF, PowerPoint, Word file, image, or other lecture file.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(fileList, key = { it.name }) { file ->
                        val marker = File(context.filesDir, "subject_favorite_" + subject.id + "_" + file.name)
                        Card(onClick = { openFile(file.name) }, Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(file.name, maxLines = 2) },
                                supportingContent = { Text("Lecture " + (fileList.indexOf(file) + 1) + " • " + formatSize(file.length())) },
                                leadingContent = {
                                    Icon(when (fileExtension(file)) {
                                        "pdf" -> Icons.Default.PictureAsPdf
                                        "ppt", "pptx" -> Icons.Default.Slideshow
                                        "doc", "docx" -> Icons.Default.Description
                                        else -> Icons.Default.InsertDriveFile
                                    }, null)
                                },
                                trailingContent = {
                                    IconButton(onClick = {
                                        if (marker.exists()) marker.delete() else marker.createNewFile()
                                        files = subjectFiles(context, subject.id)
                                    }) { Icon(if (marker.exists()) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
                                    IconButton(onClick = {
                                        file.delete()
                                        marker.delete()
                                        files = subjectFiles(context, subject.id)
                                    }) { Icon(Icons.Default.Delete, "Delete lecture file") }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppFileViewerPage(file: File, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? Activity
    var page by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    var slide by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    var fullscreen by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var landscape by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    val ext = fileExtension(file)
    DisposableEffect(file.absolutePath) {
        onDispose {
            activity?.window?.decorView?.systemUiVisibility = 0
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    fun toggleFullscreen() {
        fullscreen = !fullscreen
        activity?.window?.decorView?.systemUiVisibility = if (fullscreen)
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else 0
    }
    fun toggleOrientation() {
        landscape = !landscape
        activity?.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            TopAppBar(
                title = { Column {
                    Text(file.name, maxLines = 2, fontWeight = FontWeight.Bold)
                    Text(when (ext) { "pdf" -> "PDF • Swipe left/right to change page"; "pptx","ppt" -> "Slides • Swipe left/right to change"; "docx" -> "Word • A4 reading layout"; else -> "Reading view" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }},
                navigationIcon = { IconButton(onClick = done) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { toggleOrientation() }) { Icon(if (landscape) Icons.Default.ScreenLockPortrait else Icons.Default.ScreenLockLandscape, "Portrait or landscape") }
                    IconButton(onClick = { toggleFullscreen() }) { Icon(Icons.Default.Fullscreen, "Full screen") }
                }
            )
        }
        when {
            ext == "pdf" -> {
                val pageCount = remember(file) { runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { d -> PdfRenderer(d).use { it.pageCount } } }.getOrDefault(0) }
                if (pageCount > 0) {
                    val safePage = page.coerceIn(0, pageCount - 1)
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f).pointerInput(page, pageCount) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragTotal += amount }, onDragEnd = {
                                if (dragTotal < -70f && page < pageCount - 1) page++ else if (dragTotal > 70f && page > 0) page--
                                dragTotal = 0f
                            }, onDragCancel = { dragTotal = 0f })
                        }, contentAlignment = Alignment.Center) {
                            renderPdfPage(file, safePage)?.let { bitmap -> Image(bitmap.asImageBitmap(), file.name, Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 6.dp), contentScale = ContentScale.Fit) } ?: EmptyCard("Unable to render this PDF.")
                        }
                        Surface(tonalElevation = 3.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Page ${safePage + 1} / $pageCount", fontWeight = FontWeight.SemiBold)
                                Row { IconButton({ if (page > 0) page-- }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }; IconButton({ if (page < pageCount - 1) page++ }, enabled = page < pageCount - 1) { Icon(Icons.Default.ChevronRight, "Next page") }; if (fullscreen) IconButton({ toggleFullscreen() }) { Icon(Icons.Default.FullscreenExit, "Exit full screen") } }
                            }
                        }
                    }
                } else EmptyCard("Unable to open this PDF.")
            }
            ext == "pptx" || ext == "ppt" -> {
                val slides = remember(file) { readOfficeSlides(file) }
                if (slides.isNotEmpty()) {
                    val safeSlide = slide.coerceIn(0, slides.lastIndex)
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f).padding(if (fullscreen) 0.dp else 10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(if (fullscreen) 0.dp else 16.dp)).pointerInput(slide, slides.size) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragTotal += amount }, onDragEnd = {
                                if (dragTotal < -70f && slide < slides.lastIndex) slide++ else if (dragTotal > 70f && slide > 0) slide--
                                dragTotal = 0f
                            }, onDragCancel = { dragTotal = 0f })
                        }, contentAlignment = Alignment.Center) { Text(slides[safeSlide].ifBlank { "Blank slide" }, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
                        Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Slide ${safeSlide + 1} / ${slides.size}", fontWeight = FontWeight.SemiBold); Row { IconButton({ if (slide > 0) slide-- }, enabled = slide > 0) { Icon(Icons.Default.ChevronLeft, "Previous slide") }; IconButton({ if (slide < slides.lastIndex) slide++ }, enabled = slide < slides.lastIndex) { Icon(Icons.Default.ChevronRight, "Next slide") } } } }
                    }
                } else EmptyCard("Unable to read this PowerPoint offline.")
            }
            ext == "docx" -> {
                val text = remember(file) { readOfficeText(file) ?: "No readable text was found in this Word document." }
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 16.dp)) {
                    item { Card(Modifier.fillMaxWidth().widthIn(max = 794.dp), shape = RoundedCornerShape(6.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) { Column(Modifier.padding(horizontal = 36.dp, vertical = 42.dp)) { Text("A4 PRINT LAYOUT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp) } } }
                }
            }
            else -> {
                val text = remember(file) { readDisplayText(file) }
                LazyColumn(Modifier.fillMaxSize().padding(20.dp)) { item { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp) } } }
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
fun ModuleBackupDialog(title:String, selected:Set<String>, onSelected:(Set<String>)->Unit, done:()->Unit) {
    val modules=listOf("homepage" to "Homepage","schedule" to "Schedule","tasks" to "Tasks","academics" to "Academics / Lessons")
    AlertDialog(onDismissRequest=done,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("Check each module you want to transfer.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        modules.forEach{(id,label)->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{onSelected(if(id in selected)selected-id else selected+id)});Text(label)}}
    }},confirmButton={Button(enabled=selected.isNotEmpty(),onClick=done){Text("Continue")}},dismissButton={TextButton(done){Text("Cancel")}})
}

@Composable
fun SettingsScreen(store: LocalStore, theme: String, setTheme: (String) -> Unit, lock: () -> Unit, openScheduleSettings: () -> Unit, openScheduleManager: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf(store.pin()) }
    var lockOn by remember { mutableStateOf(store.lockEnabled()) }
    var showPin by remember { mutableStateOf(false) }
    var showTableSettings by remember { mutableStateOf(false) }
    var showHomeTiles by remember { mutableStateOf(false) }
    var homeHiddenTiles by remember { mutableStateOf(store.homeHiddenTiles()) }
    var notificationsOn by remember { mutableStateOf(context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).getBoolean("enabled", false) && CampusReminders.notificationsEnabled(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            notificationsOn = true
            context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            CampusReminders.reschedule(context)
        }
    }
    var backupMode by remember { mutableStateOf(false) }; var restoreMode by remember { mutableStateOf(false) }
    var selectedModules by remember { mutableStateOf(setOf("schedule","academics")) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri ?: return@rememberLauncherForActivityResult; context.contentResolver.openOutputStream(uri)?.use { it.write(store.backupJson(selectedModules).toByteArray()) } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri ?: return@rememberLauncherForActivityResult; runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.restoreJson(it.readText(), selectedModules) } } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notifications", fontWeight = FontWeight.Bold)
                    Text("Get reminders before your next class and upcoming task deadlines.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (notificationsOn && CampusReminders.notificationsEnabled(context)) "Enabled" else "Off")
                        Switch(
                            checked = notificationsOn,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    notificationsOn = false
                                    context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                                    CampusReminders.reschedule(context)
                                } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notificationsOn = true
                                    context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
                                    CampusReminders.reschedule(context)
                                }
                            }
                        )
                    }
                }
            }
        }

        item { Text("Schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Class schedule", fontWeight = FontWeight.Bold)
                    Text("Edit entries, correct Lecture/Lab type, delete classes, and configure the timetable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = openScheduleManager, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.EditCalendar, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Edit / Delete Classes")
                    }
                    OutlinedButton(onClick = openScheduleSettings, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarMonth, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Class Schedule Settings")
                    }
                    OutlinedButton(onClick = { showTableSettings = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.TableView, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Schedule Table Settings")
                    }
                }
            }
        }

        item { Text("Home", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Homepage", fontWeight = FontWeight.Bold)
                    Text("Customize which Home tiles are visible and restore the default Home layout.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showHomeTiles = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.ViewModule, null); Spacer(Modifier.width(8.dp)); Text("Customize Home Tiles") }
                    Text("Use the CampusOS settings button from the top bar for profile and appearance options.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Academics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Subjects & lecture files", fontWeight = FontWeight.Bold)
                    Text("Manage Notepad and Lecture Files from the Academics screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Tasks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Assignments & To-do", fontWeight = FontWeight.Bold)
                    Text("Manage tasks and deadlines from the Tasks screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("system", "light", "dark").forEach { mode ->
                            FilterChip(theme == mode, { setTheme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("App lock") },
                    supportingContent = { Text(if (pin.isBlank()) "Set a PIN first" else "Require PIN when opening CampusOS") },
                    trailingContent = {
                        Switch(
                            checked = lockOn && pin.isNotBlank(),
                            onCheckedChange = {
                                lockOn = it
                                store.setLockEnabled(it)
                                if (it) lock()
                            }
                        )
                    }
                )
                TextButton({ showPin = true }, Modifier.padding(start = 12.dp)) {
                    Text(if (pin.isBlank()) "Set PIN" else "Change PIN")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Backup & restore", fontWeight = FontWeight.Bold)
                    Text("Export local data to JSON or restore it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button({ backupMode=true }) { Text("Backup") }
                        OutlinedButton({ restoreMode=true }) { Text("Restore") }
                    }
                }
            }
        }

        item { Text("CampusOS 1.0.0 • Offline-first", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Transfer tip: select Schedule to share your timetable, or Academics / Lessons to share subjects, notes, and lecture files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (backupMode) ModuleBackupDialog("Choose modules to backup", selectedModules, { selectedModules=it }) { backupMode=false; if(selectedModules.isNotEmpty()) backup.launch("CampusOS-selected-backup.json") }
    if (restoreMode) ModuleBackupDialog("Choose modules to restore", selectedModules, { selectedModules=it }) { restoreMode=false; if(selectedModules.isNotEmpty()) restore.launch(arrayOf("application/json","text/plain")) }
    if (showTableSettings) {
        ScheduleTableSettingsDialog(store) { showTableSettings = false }
    }
    if (showHomeTiles) {
        HomeTileSettingsDialog(
            defaultOrder = listOf("profile", "stats", "classes", "pinned", "tasks", "quick"),
            hiddenTiles = homeHiddenTiles,
            onHiddenChanged = { homeHiddenTiles = it; store.setHomeHiddenTiles(it) },
            onReset = { store.resetHomeLayout(); homeHiddenTiles = emptySet(); showHomeTiles = false },
            done = { showHomeTiles = false }
        )
    }

    if (showPin) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPin = false },
            title = { Text("Set 4–8 digit PIN") },
            text = { OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") }) },
            confirmButton = {
                Button({ if (newPin.length in 4..8) { pin = newPin; store.setPin(newPin); showPin = false } }) { Text("Save") }
            },
            dismissButton = { TextButton({ showPin = false }) { Text("Cancel") } }
        )
    }
}


@Composable
fun LockScreen(store: LocalStore, unlock: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Icon(Icons.Default.Lock, null, Modifier.size(64.dp))
    Spacer(Modifier.height(18.dp))
    Text("CampusOS is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Enter your PIN to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
    OutlinedTextField(entered, { entered = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") })
    if (error) Text("Incorrect PIN", color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
    Button({ if (entered == store.pin()) unlock() else error = true }) { Text("Unlock") }
    }
}

@Composable fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun SmallAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(4.dp)); Text(text) }
}@Composable
fun HomeClassesTile(todaySchedule: List<Record>) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val currentClass = todaySchedule.firstOrNull { r ->
        val start = r.startTime.toMinutesOrNull()
        val end = r.endTime.toMinutesOrNull()
        start != null && end != null && currentMinutes >= start && currentMinutes < end
    }
    val nextClass = todaySchedule
        .mapNotNull { r -> r.startTime.toMinutesOrNull()?.let { it to r } }
        .filter { it.first > currentMinutes }
        .minByOrNull { it.first }
        ?.second
    fun minutesUntil(time: String): Int? = time.toMinutesOrNull()?.let { (it - currentMinutes).coerceAtLeast(0) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Today's classes")
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(todaySchedule.size.toString(), Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        currentClass?.let { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayCircle, "Current class")
                        Spacer(Modifier.width(8.dp))
                        Text("CURRENT CLASS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Ends at ${r.endTime} • ${r.endTime.toMinutesOrNull()?.let { (it - currentMinutes).coerceAtLeast(0) } ?: 0} min remaining", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        } ?: nextClass?.let { r ->
            val mins = minutesUntil(r.startTime) ?: 0
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, "Next class")
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NEXT CLASS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Next class in ${mins} min • ${r.startTime}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (todaySchedule.isEmpty()) EmptyCard("No classes scheduled for today.")
        else for (r in todaySchedule.take(5)) { HomeTodayClassCard(r); Spacer(Modifier.height(8.dp)) }
    }
}
private fun String.toMinutesOrNull(): Int? {
    val parts = trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
@Composable
fun HomePinnedTile(pinnedTasks: List<Record>) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Pinned tasks")
        Spacer(Modifier.height(8.dp))
        if (pinnedTasks.isEmpty()) EmptyCard("No pinned tasks. Long-press a task to pin it.")
        else for (r in pinnedTasks) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                ListItem(headlineContent = { Text(r.title, fontWeight = FontWeight.SemiBold) }, supportingContent = { if (r.dueDate.isNotBlank()) Text("Due " + r.dueDate + " " + r.dueTime) }, leadingContent = { Icon(Icons.Default.PushPin, "Pinned") })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
@Composable
fun HomeTasksTile(pendingTasks: List<Record>) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Tasks to do")
        Spacer(Modifier.height(8.dp))
        if (pendingTasks.isEmpty()) EmptyCard("You're all caught up.")
        else for (r in pendingTasks) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                ListItem(headlineContent = { Text(r.title, fontWeight = FontWeight.SemiBold) }, supportingContent = { Column { if (r.subtitle.isNotBlank()) Text(r.subtitle, maxLines = 2); if (r.dueDate.isNotBlank()) Text("Due ${r.dueDate} ${r.dueTime}") } }, leadingContent = { Icon(Icons.Default.CheckCircleOutline, null) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

