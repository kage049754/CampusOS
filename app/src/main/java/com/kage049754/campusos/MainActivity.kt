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
import androidx.compose.ui.platform.LocalDensity
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
    init {
        if (!prefs.getBoolean("schedule_time_range_extended_v1", false)) {
            val existingEnd = prefs.getInt("schedule_end_hour", 21)
            prefs.edit()
                .putInt("schedule_end_hour", maxOf(existingEnd, 21))
                .putBoolean("schedule_time_range_extended_v1", true)
                .apply()
        }
    }
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
    fun pattern() = prefs.getString("lock_pattern", "") ?: ""
    fun setPattern(v: String) { prefs.edit().putString("lock_pattern", v).apply(); revision++ }
    fun authMethod(): String { val stored = prefs.getString("lock_method", "") ?: ""; if (stored.isNotBlank()) return stored; return if (pin().isNotBlank()) "pin" else "none" }
    fun setAuthMethod(v: String) { prefs.edit().putString("lock_method", v).apply(); revision++ }
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
    fun scheduleEndHour() = prefs.getInt("schedule_end_hour", 21)
    fun setScheduleHours(start: Int, end: Int) { prefs.edit().putInt("schedule_start_hour", start).putInt("schedule_end_hour", end).apply(); revision++; CampusReminders.reschedule(appContext); CampusWidgets.updateAll(appContext) }
    fun backupJson(selected: Set<String> = setOf("homepage","schedule","tasks","academics")): String {
        val root = JSONObject().apply {
            put("format", "CampusOS Backup")
            put("version", 2)
            put("createdAt", System.currentTimeMillis())
            put("modules", JSONArray(selected.toList().sorted()))
        }
        if ("schedule" in selected) {
            root.put("schedule", prefs.getString("schedule", "[]"))
            root.put("scheduleDays", prefs.getString("schedule_days", "Monday,Tuesday,Wednesday,Thursday,Friday,Saturday"))
            root.put("scheduleDaysConfigured", prefs.getBoolean("schedule_days_configured", false))
            root.put("scheduleStartHour", scheduleStartHour())
            root.put("scheduleEndHour", scheduleEndHour())
            root.put("scheduleDayHighlight", scheduleDayHighlight())
            root.put("scheduleTimeHighlight", scheduleTimeHighlight())
            root.put("scheduleTableBackground", scheduleTableBackground())
            root.put("scheduleTableBorder", scheduleTableBorder())
            root.put("scheduleTableHorizontalScroll", scheduleTableHorizontalScroll())
            root.put("scheduleTableVerticalScroll", scheduleTableVerticalScroll())
            root.put("scheduleTableFontSize", scheduleTableFontSize())
            root.put("scheduleTableDayWidth", scheduleTableDayWidth())
            root.put("scheduleTableRowHeight", scheduleTableRowHeight())
        }
        if ("tasks" in selected) root.put("tasks", prefs.getString("tasks", "[]"))
        if ("academics" in selected) {
            root.put("subjects", prefs.getString("subjects", "[]"))
            val notes = JSONObject()
            prefs.all.filterKeys { it.startsWith("subject_notes_") }.forEach { (k,v) -> if (v is String) notes.put(k.removePrefix("subject_notes_"), v) }
            root.put("subjectNotes", notes)
            val files = JSONArray(); val dir = File(appContext.filesDir, "subject_files")
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                files.put(JSONObject().apply { put("path", file.relativeTo(dir).path); put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)) })
            }
            root.put("subjectFiles", files)
        }
        if ("homepage" in selected) {
            root.put("theme", theme()); root.put("lock", lockEnabled()); root.put("lockMethod", authMethod())
            root.put("profileName", profileName()); root.put("profileStudentId", profileStudentId()); root.put("profileSection", profileSection())
            root.put("homeLayoutOrder", prefs.getString("home_layout_order", ""))
            root.put("homeHiddenTiles", prefs.getString("home_hidden_tiles", ""))
        }
        return root.toString(2)
    }

    fun restoreJson(json: String, selected: Set<String> = setOf("homepage","schedule","tasks","academics")) {
        val root = JSONObject(json)
        require(root.optString("format") == "CampusOS Backup") { "This is not a valid CampusOS backup file." }
        val e = prefs.edit()
        if ("schedule" in selected && root.has("schedule")) {
            e.putString("schedule", root.getString("schedule"))
            if (root.has("scheduleDays")) e.putString("schedule_days", root.getString("scheduleDays"))
            if (root.has("scheduleDaysConfigured")) e.putBoolean("schedule_days_configured", root.getBoolean("scheduleDaysConfigured"))
            if (root.has("scheduleStartHour")) e.putInt("schedule_start_hour", root.getInt("scheduleStartHour"))
            if (root.has("scheduleEndHour")) e.putInt("schedule_end_hour", root.getInt("scheduleEndHour"))
            if (root.has("scheduleDayHighlight")) e.putLong("schedule_day_highlight", root.getLong("scheduleDayHighlight"))
            if (root.has("scheduleTimeHighlight")) e.putLong("schedule_time_highlight", root.getLong("scheduleTimeHighlight"))
            if (root.has("scheduleTableBackground")) e.putLong("schedule_table_background", root.getLong("scheduleTableBackground"))
            if (root.has("scheduleTableBorder")) e.putLong("schedule_table_border", root.getLong("scheduleTableBorder"))
            if (root.has("scheduleTableHorizontalScroll")) e.putBoolean("schedule_table_horizontal_scroll", root.getBoolean("scheduleTableHorizontalScroll"))
            if (root.has("scheduleTableVerticalScroll")) e.putBoolean("schedule_table_vertical_scroll", root.getBoolean("scheduleTableVerticalScroll"))
            if (root.has("scheduleTableFontSize")) e.putFloat("schedule_table_font_size", root.getDouble("scheduleTableFontSize").toFloat())
            if (root.has("scheduleTableDayWidth")) e.putFloat("schedule_table_day_width", root.getDouble("scheduleTableDayWidth").toFloat())
            if (root.has("scheduleTableRowHeight")) e.putFloat("schedule_table_row_height", root.getDouble("scheduleTableRowHeight").toFloat())
        }
        if ("tasks" in selected && root.has("tasks")) e.putString("tasks", root.getString("tasks"))
        if ("academics" in selected) {
            if (root.has("subjects")) e.putString("subjects", root.getString("subjects"))
            root.optJSONObject("subjectNotes")?.let { notes -> notes.keys().forEach { id -> e.putString("subject_notes_$id", notes.getString(id)) } }
        }
        if ("homepage" in selected) {
            if (root.has("theme")) e.putString("theme", root.getString("theme"))
            if (root.has("lock")) e.putBoolean("lock", root.getBoolean("lock"))
            if (root.has("lockMethod") && listOf("none","pin","pattern").contains(root.optString("lockMethod"))) e.putString("lock_method", root.getString("lockMethod"))
            if (root.has("profileName")) e.putString("profile_name", root.getString("profileName"))
            if (root.has("profileStudentId")) e.putString("profile_student_id", root.getString("profileStudentId"))
            if (root.has("profileSection")) e.putString("profile_section", root.getString("profileSection"))
            if (root.has("homeLayoutOrder")) e.putString("home_layout_order", root.getString("homeLayoutOrder"))
            if (root.has("homeHiddenTiles")) e.putString("home_hidden_tiles", root.getString("homeHiddenTiles"))
        }
        if ("academics" in selected && root.has("subjectFiles")) {
            val dir = File(appContext.filesDir, "subject_files"); dir.mkdirs()
            val files = root.optJSONArray("subjectFiles") ?: JSONArray()
            for (i in 0 until files.length()) runCatching {
                val o = files.getJSONObject(i)
                val relative = o.getString("path").replace("\\", "/").removePrefix("/").replace("..", "_")
                val target = File(dir, relative)
                target.parentFile?.mkdirs()
                target.writeBytes(Base64.decode(o.getString("data"), Base64.DEFAULT))
            }
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
    HOME("Home"), SCHEDULE("Schedule"), TASKS("Notes"), ACADEMICS("Academics"),
    FILES("Files"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusOSApp(activity: Activity) {
    val store = remember { LocalStore(activity) }
    var theme by remember { mutableStateOf(store.theme()) }
    var locked by remember { mutableStateOf(store.lockEnabled() && store.authMethod() != "none") }
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val screen = Screen.valueOf(screenName)
    var search by rememberSaveable { mutableStateOf("") }
    var showHomeAdd by remember { mutableStateOf(false) }
    var showHomeColors by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    var showAppLock by remember { mutableStateOf(false) }
    var showBackupRecovery by remember { mutableStateOf(false) }
    var homeEditRequest by remember { mutableIntStateOf(0) }
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
            contentWindowInsets = WindowInsets.safeDrawing,
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
            Column(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(padding)
                    .imePadding()
            ) {
                when (screen) {
                    Screen.HOME -> HomeScreen(store, { screenName = it.name }, homeEditRequest)
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
                HomeSettingsDialog(store, onModule = { settingsModule = it; settingsParent = null; showHomeSettings = false }, onAppearance = { showHomeColors = true; settingsParent = null; showHomeSettings = false }, onLockNow = { locked = true }, done = { showHomeSettings = false }, openAppLock = { showHomeSettings = false; showAppLock = true }, openBackupRecovery = { showHomeSettings = false; showBackupRecovery = true })
            }
            if (showHomeAdd) ScheduleDialog(store) { showHomeAdd = false }
            if (showHomeColors) HomeAppearanceDialog(store, theme, { theme = it; store.setTheme(it) }) { showHomeColors = false }
            if (showProfile) ProfileDialog(store) { showProfile = false }
            if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
            if (showScheduleTableSettings) ScheduleTableSettingsDialog(store) { showScheduleTableSettings = false }
            if (showScheduleManager) ScheduleManagerDialog(store) { showScheduleManager = false }
            settingsModule?.let { module -> ModuleSettingsDialog(module, { settingsModule = null; settingsParent = null; showHomeSettings = true }, { settingsParent = module; settingsModule = null; showProfile = true }, { settingsParent = module; settingsModule = null; showScheduleManager = true }, { settingsParent = module; settingsModule = null; showScheduleSettings = true }, { settingsParent = module; settingsModule = null; showScheduleTableSettings = true }, { settingsParent = module; settingsModule = null; showHomeAdd = true }, { settingsModule = null; settingsParent = null; screenName = Screen.TASKS.name }, { settingsModule = null; settingsParent = null; screenName = Screen.ACADEMICS.name }, { settingsModule = null; settingsParent = null; showHomeSettings = false; homeEditRequest++ }) }
            if (showScheduleDetails) SubjectDetailsDialog(store.get("schedule"), { showScheduleDetails = false })
            if (showAppLock) AppLockSettingsDialog(store, { showAppLock = false }, { locked = true })
            if (showBackupRecovery) BackupRecoverySettingsDialog(store, { showBackupRecovery = false })
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
fun HomeSettingsDialog(
    store: LocalStore,
    onModule:(String)->Unit,
    onAppearance:()->Unit,
    onLockNow:()->Unit,
    done:()->Unit,
    openAppLock:()->Unit,
    openBackupRecovery:()->Unit
) {
    AlertDialog(
        onDismissRequest=done,
        title={Text("CampusOS Settings")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Choose a module",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Open the dedicated settings for each part of CampusOS.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("Homepage" to Icons.Default.Home,"Schedule" to Icons.Default.CalendarMonth,"Notes" to Icons.Default.CheckCircle,"Academics" to Icons.Default.School).forEach{(name,icon)->
                    OutlinedButton({onModule(name)},Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(8.dp));Text(name)}
                }
                HorizontalDivider()
                OutlinedButton(onAppearance,Modifier.fillMaxWidth()){Icon(Icons.Default.Palette,null);Spacer(Modifier.width(8.dp));Text("Appearance & Design")}
                OutlinedButton(openAppLock,Modifier.fillMaxWidth()){Icon(Icons.Default.Lock,null);Spacer(Modifier.width(8.dp));Text("App Lock")}
                OutlinedButton(openBackupRecovery,Modifier.fillMaxWidth()){Icon(Icons.Default.Backup,null);Spacer(Modifier.width(8.dp));Text("Backup & Recovery")}
                Text("CampusOS 1.0.0 • Offline-first",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton={TextButton(done){Text("Close")}}
    )
}
@Composable
fun AppLockSettingsDialog(store: LocalStore, done: () -> Unit, lockNow: () -> Unit) {
    var method by remember { mutableStateOf(store.authMethod()) }
    var showPin by remember { mutableStateOf(false) }
    var showPattern by remember { mutableStateOf(false) }
    var enableAfterSetup by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=done,title={Text("App Lock")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Choose how CampusOS is protected when it opens.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("none" to "None","pin" to "PIN","pattern" to "Pattern").forEach { (value,label) ->
            OutlinedButton({
                method=value
                if(value=="none"){store.setAuthMethod("none");store.setLockEnabled(false)}
                else if(value=="pin"){enableAfterSetup=true;showPin=true}
                else {enableAfterSetup=true;showPattern=true}
            },Modifier.fillMaxWidth()){
                Icon(if(value=="pattern") Icons.Default.Grid3x3 else if(value=="pin") Icons.Default.Pin else Icons.Default.LockOpen,null)
                Spacer(Modifier.width(8.dp));Text(if(method==value) "✓ $label" else label)
            }
        }
        Text(when(method){"pin"->"PIN lock is ${if(store.lockEnabled()) "on" else "off"}. Minimum 4 digits; maximum 8.";"pattern"->"Pattern lock is ${if(store.lockEnabled()) "on" else "off"}. Use at least 4 points.";else->"No app lock is enabled."},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(method=="pin") OutlinedButton({showPin=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(8.dp));Text(if(store.pin().isBlank()) "Set PIN" else "Change PIN")}
        if(method=="pattern") OutlinedButton({showPattern=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Grid3x3,null);Spacer(Modifier.width(8.dp));Text(if(store.pattern().isBlank()) "Set Pattern" else "Change Pattern")}
        if(method!="none" && store.authMethod()==method && store.lockEnabled()) OutlinedButton({store.setLockEnabled(false)},Modifier.fillMaxWidth()){Text("Turn off app lock")}
    }},confirmButton={TextButton(done){Text("Done")}})
    if(showPin) PinSetupDialog(store,enableAfterSetup){showPin=false;enableAfterSetup=false}
    if(showPattern) PatternSetupDialog(store,enableAfterSetup){showPattern=false;enableAfterSetup=false}
}

@Composable
fun PinSetupDialog(store: LocalStore, enableAfterSetup:Boolean, done:()->Unit) {
    var current by remember{mutableStateOf("")};var value by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=done,title={Text(if(store.pin().isBlank()) "Set PIN" else "Change PIN")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(store.pin().isNotBlank()) OutlinedTextField(current,{current=it.filter(Char::isDigit).take(8)},label={Text("Current PIN")},singleLine=true)
        OutlinedTextField(value,{value=it.filter(Char::isDigit).take(8)},label={Text("New PIN")},singleLine=true)
        OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(8)},label={Text("Confirm PIN")},singleLine=true)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Text("Minimum 4 digits, maximum 8.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={Button({error=when{store.pin().isNotBlank()&&current!=store.pin()->"Current PIN is incorrect.";value.length !in 4..8->"PIN must be 4–8 digits.";value!=confirm->"PINs do not match.";else->""};if(error.isBlank()){store.setPin(value);store.setAuthMethod("pin");if(enableAfterSetup)store.setLockEnabled(true);done()}}){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})
}

@Composable
fun PatternSetupDialog(store:LocalStore,enableAfterSetup:Boolean,done:()->Unit){
    var current by remember{mutableStateOf("")};var value by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    fun add(target:String,n:Int):String=if(target.split("-").contains(n.toString()))target else if(target.isBlank())n.toString() else "$target-$n"
    AlertDialog(onDismissRequest=done,title={Text(if(store.pattern().isBlank())"Set Pattern" else "Change Pattern")},text={Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(store.pattern().isNotBlank()){Text("Current pattern",style=MaterialTheme.typography.bodySmall);PatternGrid(current.split("-").filter{it.isNotBlank()}.mapNotNull{it.toIntOrNull()},{n->current=add(current,n)},{current=""})}
        Text("New pattern",style=MaterialTheme.typography.bodySmall);PatternGrid(value.split("-").filter{it.isNotBlank()}.mapNotNull{it.toIntOrNull()},{n->value=add(value,n)},{value=""})
        Text("Repeat pattern",style=MaterialTheme.typography.bodySmall);PatternGrid(confirm.split("-").filter{it.isNotBlank()}.mapNotNull{it.toIntOrNull()},{n->confirm=add(confirm,n)},{confirm=""})
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error);Text("Use at least 4 different points.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={Button({val currentOk=store.pattern().isBlank()||current==store.pattern();error=when{!currentOk->"Current pattern is incorrect.";value.split("-").filter{it.isNotBlank()}.size<4->"Pattern must use at least 4 points.";value!=confirm->"Patterns do not match.";else->""};if(error.isBlank()){store.setPattern(value);store.setAuthMethod("pattern");store.setLockEnabled(true);done()}}){Text("Save")}},dismissButton={TextButton(done){Text("Cancel")}})
}

@Composable
fun PatternGrid(selected:List<Int>,onPoint:(Int)->Unit,clear:()->Unit){
    Column(horizontalAlignment=Alignment.CenterHorizontally){for(row in 0..2)Row{for(col in 0..2){val n=row*3+1+col;OutlinedButton({onPoint(n)},Modifier.size(64.dp).padding(4.dp),contentPadding=PaddingValues(0.dp)){Text(if(selected.contains(n))"●" else "○",fontSize=22.sp)}}};TextButton(clear){Text("Clear")}}
}

@Composable
fun BackupRecoverySettingsDialog(store:LocalStore,done:()->Unit){
    var selectedModules by remember{mutableStateOf(setOf("homepage","schedule","tasks","academics"))};var showBackup by remember{mutableStateOf(false)};var showRecover by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};val context=androidx.compose.ui.platform.LocalContext.current
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->uri?:return@rememberLauncherForActivityResult;runCatching{context.contentResolver.openOutputStream(uri)?.use{it.write(store.backupJson(selectedModules).toByteArray())}}.onFailure{error=it.message?:"Unable to create backup file."}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?:return@rememberLauncherForActivityResult;runCatching{context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{store.restoreJson(it.readText(),selectedModules)}}.onFailure{error=it.message?:"Unable to recover backup file."}}
    AlertDialog(onDismissRequest=done,title={Text("Backup & Recovery")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Choose what you want to back up or recover. Your existing module data stays separate.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({showBackup=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Backup,null);Spacer(Modifier.width(8.dp));Text("Backup")}
        OutlinedButton({showRecover=true;error=""},Modifier.fillMaxWidth()){Icon(Icons.Default.Restore,null);Spacer(Modifier.width(8.dp));Text("Recovery")}
        HorizontalDivider();Text("Modules",fontWeight=FontWeight.Bold);Text("Homepage • Schedule • Notes • Academics / Lessons",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("Backups use the CampusOS JSON format. Recovery can restore only the modules you select.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(done){Text("Done")}})
    if(showBackup)ModuleBackupDialog("Choose modules to backup",selectedModules,{selectedModules=it}){showBackup=false;if(selectedModules.isNotEmpty())backup.launch("CampusOS-backup.json")}
    if(showRecover)ModuleBackupDialog("Choose modules to recover",selectedModules,{selectedModules=it}){showRecover=false;if(selectedModules.isNotEmpty())restore.launch(arrayOf("application/json","text/plain"))}
}
@Composable
fun ModuleSettingsDialog(module:String,close:()->Unit,profile:()->Unit,scheduleManager:()->Unit,scheduleSettings:()->Unit,tableSettings:()->Unit,addClass:()->Unit,openTasks:()->Unit,openAcademics:()->Unit,editHome:()->Unit) {
    var showTaskSettings by remember { mutableStateOf(false) }
    var showHomeTiles by remember { mutableStateOf(false) }
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val homeStore = remember { LocalStore(homeContext) }
    var homeHiddenTiles by remember { mutableStateOf(homeStore.homeHiddenTiles()) }
    AlertDialog(onDismissRequest=close,title={Text("$module Settings")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        when(module){
            "Homepage"->{
                Text("Homepage controls",fontWeight=FontWeight.Bold)
                Text("Manage your Home screen layout here. Tile moving keeps the same drag-and-drop edit mode.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(editHome,Modifier.fillMaxWidth()){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(8.dp));Text("Edit Home Layout")}
                OutlinedButton({ showHomeTiles = true },Modifier.fillMaxWidth()){Icon(Icons.Default.ViewModule,null);Spacer(Modifier.width(8.dp));Text("Home Tiles")}
                OutlinedButton(profile,Modifier.fillMaxWidth()){Icon(Icons.Default.Person,null);Spacer(Modifier.width(8.dp));Text("Profile & homepage information")}
            }
            "Schedule"->{Text("Schedule controls",fontWeight=FontWeight.Bold);OutlinedButton(addClass,Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Add Class")};OutlinedButton(scheduleManager,Modifier.fillMaxWidth()){Icon(Icons.Default.EditCalendar,null);Spacer(Modifier.width(8.dp));Text("Edit / Delete Classes")};OutlinedButton(scheduleSettings,Modifier.fillMaxWidth()){Icon(Icons.Default.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text("Class Schedule Settings")};OutlinedButton(tableSettings,Modifier.fillMaxWidth()){Icon(Icons.Default.TableView,null);Spacer(Modifier.width(8.dp));Text("Schedule Table Settings")}}
            "Tasks"->{Text("Note controls",fontWeight=FontWeight.Bold);Text("Simple calendar and notes stored offline.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openTasks,Modifier.fillMaxWidth()){Icon(Icons.Default.CheckCircle,null);Spacer(Modifier.width(8.dp));Text("Open Notes")};OutlinedButton({ showTaskSettings = true },Modifier.fillMaxWidth()){Icon(Icons.Default.Settings,null);Spacer(Modifier.width(8.dp));Text("Notes Settings")}}
            "Academics"->{Text("Academics controls",fontWeight=FontWeight.Bold);Text("Subjects, Notepad, and Lecture Files are stored offline. Use the Academics screen to manage them.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openAcademics,Modifier.fillMaxWidth()){Icon(Icons.Default.School,null);Spacer(Modifier.width(8.dp));Text("Open Academics")}}
        }
    }},confirmButton={TextButton(close){Text("Close")}})
    if (showTaskSettings) TaskSettingsDialog(LocalStore(androidx.compose.ui.platform.LocalContext.current)) { showTaskSettings = false }
    if (showHomeTiles) HomeTileSettingsDialog(
        defaultOrder = listOf("profile", "stats", "classes", "pinned", "tasks"),
        hiddenTiles = homeHiddenTiles,
        onHiddenChanged = { homeHiddenTiles = it; homeStore.setHomeHiddenTiles(it) },
        onReset = { homeStore.resetHomeLayout(); homeHiddenTiles = emptySet(); showHomeTiles = false },
        done = { showHomeTiles = false }
    )
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
fun HomeScreen(store: LocalStore, go: (Screen) -> Unit, editRequest: Int = 0) {
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
    val mergedSchedule = remember(schedule) {
        schedule.groupBy { it.day.trim().lowercase(Locale.getDefault()) }
            .values
            .flatMap { mergeTodayClasses(it) }
    }
    val todaySchedule = remember(schedule, todayName) { mergeTodayClasses(schedule.filter { it.day.equals(todayName, true) }) }
    val pendingTasks = remember(tasks) { tasks.filter { !it.done }.sortedWith(compareBy({ it.dueDate }, { it.dueTime })).take(5) }
    val pinnedTasks = remember(tasks) { tasks.filter { !it.done && taskPinned(it) }.sortedWith(compareBy({ it.dueDate }, { it.dueTime })).take(5) }
    val photo = remember(photoPath, revision) { if (photoPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(photoPath) }.getOrNull() else null }
    val defaultOrder = listOf("profile", "stats", "classes", "pinned", "tasks")
    val savedOrder = store.homeLayoutOrder()
    var tileOrder by remember(revision) { mutableStateOf((savedOrder + defaultOrder).distinct().filter { it in defaultOrder }) }
    var editMode by remember { mutableStateOf(false) }
    var hiddenTiles by remember(revision) { mutableStateOf(store.homeHiddenTiles()) }
    val listState = rememberLazyListState()
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val visibleOrder = tileOrder.filter { it !in hiddenTiles }

    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            editMode = true
            tileOrder = (store.homeLayoutOrder() + defaultOrder).distinct().filter { it in defaultOrder }
        }
    }

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
        if (editMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Drag tiles to arrange your Home screen",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = {
                    editMode = false
                    store.setHomeLayoutOrder(tileOrder)
                    store.setHomeHiddenTiles(hiddenTiles)
                }) {
                    Icon(Icons.Default.Check, "Done")
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
                            val now = Calendar.getInstance()
                            val currentDayIndex = now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
                            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                            val weeklyClasses = schedule.groupBy { it.day.trim().lowercase(Locale.getDefault()) }
                                .values
                                .flatMap { mergeTodayClasses(it) }
                            val completedThisWeek = weeklyClasses.count { r ->
                                val dayIndex = listOf("sunday","monday","tuesday","wednesday","thursday","friday","saturday")
                                    .indexOf(r.day.trim().lowercase(Locale.getDefault()))
                                when {
                                    dayIndex < 0 -> false
                                    dayIndex < currentDayIndex -> true
                                    dayIndex > currentDayIndex -> false
                                    else -> (r.endTime.toMinutesOrNull() ?: Int.MAX_VALUE) <= currentMinutes
                                }
                            }
                            StatCard("Classes", completedThisWeek.toString() + "/" + weeklyClasses.size, Modifier.weight(1f))
                            StatCard("Subjects", subjects.size.toString(), Modifier.weight(1f))
                            StatCard("Notes", tasks.count { !it.done }.toString(), Modifier.weight(1f))
                        }
                        "classes" -> HomeClassesTile(todaySchedule)
                        "pinned" -> HomePinnedTile(pinnedTasks)
                        "tasks" -> HomeTasksTile(pendingTasks)
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
        "pinned" to "Pinned Notes",
        "tasks" to "Notes",
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
private fun String.toMinutesOrNull(): Int? {
    val parts = trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatClassDuration(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

private fun formatClassCountdown(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

@Composable
fun HomeTodayClassCard(r: Record,status:String?=null){
    val completed=status=="✓ Completed"
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=if(completed)MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(r.title,Modifier.weight(1f),fontWeight=FontWeight.Bold)
                if(status!=null)Text(status,fontWeight=FontWeight.SemiBold,color=if(completed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${r.startTime}–${r.endTime}",style=MaterialTheme.typography.bodyMedium)
            Text(if(r.classType.equals("Lab",true))"Lab" else "Lecture",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
            val duration=(r.endTime.toMinutesOrNull()?:0)-(r.startTime.toMinutesOrNull()?:0)
            if(duration>0)Text("Duration: ${formatClassDuration(duration)}",style=MaterialTheme.typography.bodySmall)
            if(r.room.isNotBlank())Text("Room ${r.room}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(8.dp))
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
        Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                OutlinedButton({if(start>0)start--},Modifier.weight(1f)){Text("Start −")}
                OutlinedButton({if(start<end-1)start++},Modifier.weight(1f)){Text("Start +")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                OutlinedButton({if(end>start+1)end--},Modifier.weight(1f)){Text("End −")}
                OutlinedButton({if(end<24)end++},Modifier.weight(1f)){Text("End +")}
            }
        }
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

    val dayOrder = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
    fun dayIndex(name: String) = dayOrder.indexOfFirst { it.equals(name, true) }.let { if (it < 0) 99 else it }
    val todayIndex = dayIndex(today)
    val orderedDays = remember(scheduleDays, today) {
        val configured = scheduleDays.distinct()
        configured.sortedWith(compareBy<String> {
            val idx = dayIndex(it)
            when {
                idx == todayIndex -> 0
                idx > todayIndex -> idx - todayIndex
                else -> 7 + idx - todayIndex
            }
        })
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)) {
            val visibleDayCount = orderedDays.size.coerceAtMost(3).coerceAtLeast(1)
            val baseDayWidth = if (customDayWidth > 0f) customDayWidth.dp else (maxWidth - 56.dp).coerceAtLeast(0.dp) / visibleDayCount
            val dayWidth = baseDayWidth * zoom
            val headerHeight = 34.dp
            val baseRowHeight = if (customRowHeight > 0f) customRowHeight.dp else ((maxHeight - headerHeight).coerceAtLeast(0.dp) / hours.size.coerceAtLeast(1)).coerceAtLeast(30.dp)
            val rowHeight = baseRowHeight * zoom
            val needsDaySwipe = orderedDays.size >= 4
            val dayScroll = rememberScrollState()

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (horizontalScrollEnabled || verticalScrollEnabled) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Zoom ${zoom.toInt()}x", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }) { Text("−") }
                        TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
                        TextButton(onClick = { zoom = 1f }) { Text("Reset") }
                    }
                }
                if (needsDaySwipe) {
                    Text("Swipe left or right to see more days • Time stays fixed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Row(Modifier.height(headerHeight)) {
                    Box(Modifier.width(56.dp).fillMaxHeight().background(tableBg).border(1.dp, tableBorder), contentAlignment = Alignment.Center) {
                        Text("Time", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                    }
                    Row(Modifier.weight(1f).then(if (needsDaySwipe) Modifier.horizontalScroll(dayScroll) else Modifier)) {
                        orderedDays.forEach { d ->
                            val isToday = d.equals(today, true)
                            Box(Modifier.width(dayWidth).fillMaxHeight().background(if (isToday) dayHighlight.copy(alpha = 0.16f) else tableBg).border(if (isToday) 2.dp else 1.dp, if (isToday) dayHighlight else tableBorder), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (isToday) Box(Modifier.size(6.dp).background(dayHighlight, RoundedCornerShape(50)))
                                    Text(d.take(3), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                                }
                            }
                        }
                    }
                }
                hours.forEach { h ->
                    val isCurrentHour = h == currentHour
                    Row(Modifier.height(rowHeight)) {
                        Box(Modifier.width(56.dp).fillMaxHeight().background(tableBg).border(1.dp, tableBorder).then(if (isCurrentHour) Modifier.border(2.dp, timeHighlight) else Modifier), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isCurrentHour) Box(Modifier.size(6.dp).background(timeHighlight, RoundedCornerShape(50)))
                                Text(formatHourRange(h, h + 1), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp))
                            }
                        }
                        Row(Modifier.weight(1f).then(if (needsDaySwipe) Modifier.horizontalScroll(dayScroll) else Modifier)) {
                            orderedDays.forEach { day ->
                                val classes = all.filter { it.day.equals(day, true) && it.startTime.toHourOrNull() == h }
                                Box(Modifier.width(dayWidth).fillMaxHeight().background(if (day.equals(today, true) && isCurrentHour) timeHighlight.copy(alpha = 0.10f) else tableBg).border(if (day.equals(today, true) && isCurrentHour) 2.dp else 1.dp, if (day.equals(today, true) && isCurrentHour) timeHighlight else tableBorder).padding(1.dp)) {
                                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        classes.groupBy { it.title.trim().uppercase(Locale.getDefault()) }.values.take(2).forEach { subjectClasses ->
                                            val r = subjectClasses.first()
                                            val types = subjectClasses.map { if (it.classType.equals("Lecture", true)) "Lec" else "Lab" }.distinct().joinToString(" + ")
                                            val rooms = subjectClasses.map { it.room.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
                                            val bg = if (r.color != 0L) Color(r.color) else MaterialTheme.colorScheme.primaryContainer
                                            Card(Modifier.fillMaxWidth().weight(1f, fill = false), colors = CardDefaults.cardColors(containerColor = bg, contentColor = readableContentColor(bg)), shape = RoundedCornerShape(6.dp)) {
                                                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                                                    Text("${r.title} - ${types.ifBlank { "Class" }}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                    if (rooms.isNotBlank()) Text(rooms, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 3, softWrap = true, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    }

    if (showAdd) ScheduleDialog(store) { showAdd = false; clear(); refresh++ }
    if (showDetails) SubjectDetailsDialog(all, { showDetails = false })
    if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
    if (showDaySetup) ScheduleDaySetupDialog(store) { showDaySetup = false }
}

@Composable
fun SubjectDetailsDialog(all: List<Record>, done: () -> Unit) {
    data class SubjectGroup(val key: String, val subject: Record, val classes: List<Record>)
    val groups = all.groupBy { it.title.trim().uppercase(Locale.getDefault()) + "|" + it.professor.trim().lowercase(Locale.getDefault()) }
        .values.map { records ->
            val first = records.first()
            SubjectGroup(first.title.trim().uppercase(Locale.getDefault()) + "|" + first.professor.trim().lowercase(Locale.getDefault()), first, records.sortedWith(compareBy<Record>({ it.day }, { it.startTime }, { it.endTime }, { it.classType })))
        }.sortedBy { it.subject.title.lowercase(Locale.getDefault()) }
    AlertDialog(onDismissRequest=done,title={Text("Subject details")},text={
        if(groups.isEmpty()) EmptyCard("No classes in the current schedule.")
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(groups,key={it.key}) { group ->
                val r=group.subject
                val lecture=group.classes.filter { it.classType.equals("Lecture",true) }
                val lab=group.classes.filter { it.classType.equals("Lab",true) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(r.title,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                        if(r.subtitle.isNotBlank()) Text(r.subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(r.professor.isNotBlank()) Text("Professor • "+r.professor,fontWeight=FontWeight.SemiBold)
                        if(lecture.isNotEmpty()) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.MenuBook,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Lecture",fontWeight=FontWeight.Bold) }
                            lecture.forEach { c -> Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(c.day.take(3)+" • "+c.startTime+"–"+c.endTime,fontWeight=FontWeight.SemiBold); if(c.room.isNotBlank()) Text("Room "+c.room,style=MaterialTheme.typography.bodySmall) } } }
                        }
                        if(lab.isNotEmpty()) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Science,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Lab",fontWeight=FontWeight.Bold) }
                            lab.forEach { c -> Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(c.day.take(3)+" • "+c.startTime+"–"+c.endTime,fontWeight=FontWeight.SemiBold); if(c.room.isNotBlank()) Text("Room "+c.room,style=MaterialTheme.typography.bodySmall) } } }
                        }
                        if(r.extra.isNotBlank()) Text("Notes • "+r.extra,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    },confirmButton={TextButton(done){Text("Close")}})
}

@Composable
fun ScheduleManagerDialog(store: LocalStore, done: () -> Unit) {
    var subject by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var lectureRoom by remember { mutableStateOf("") }
    var labRoom by remember { mutableStateOf("") }
    var professor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var classType by remember { mutableStateOf("Lecture") }
    var color by remember { mutableLongStateOf(0xFFE3F2FD) }
    var selectedSlots by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var removedSlots by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember { mutableIntStateOf(0) }

    val colors = listOf(0xFFE3F2FDL,0xFFE8F5E9L,0xFFFFF3E0L,0xFFF3E5F5L,0xFFFFEBEEL,0xFFE0F7FAL)
    val startHour = store.scheduleStartHour().coerceIn(0,23)
    val endHour = store.scheduleEndHour().coerceIn(startHour,23)
    val hours = (startHour until endHour).toList()
    val weekDays = store.scheduleDays()
    val existingSchedule = remember(refresh, store.revision) { store.get("schedule") }
    val existingSubjects = remember(existingSchedule) {
        existingSchedule.map { it.title.trim() }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedBy { it.lowercase(Locale.getDefault()) }
    }

    AlertDialog(
        onDismissRequest = done,
        title = { Text("Edit / Delete Classes") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject code") }, placeholder = { Text("e.g. DCIT 25") }, singleLine = true)
                if (existingSubjects.isNotEmpty()) {
                    Text("Existing subject codes", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        existingSubjects.forEach { code ->
                            FilterChip(
                                selected = subject.equals(code, true),
                                onClick = {
                                    subject = code
                                    val records = existingSchedule.filter { it.title.trim().equals(code.trim(), true) }
                                    selectedSlots = records.associate { r ->
                                        val hour = r.startTime.toMinutesOrNull()?.div(60) ?: 0
                                        r.day + "|" + hour to if (r.classType.equals("Lab", true)) "Lab" else "Lecture"
                                    }
                                    removedSlots = emptySet()
                                    records.firstOrNull()?.let { first ->
                                        fullName = first.subtitle
                                        professor = first.professor
                                        notes = first.extra
                                        color = first.color
                                        classType = if (first.classType.equals("Lab", true)) "Lab" else "Lecture"
                                    }
                                    lectureRoom = records.firstOrNull { it.classType.equals("Lecture", true) && it.room.isNotBlank() }?.room ?: ""
                                    labRoom = records.firstOrNull { it.classType.equals("Lab", true) && it.room.isNotBlank() }?.room ?: ""
                                },
                                label = { Text(code) }
                            )
                        }
                    }
                }
                OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Whole subject name") })
                Text("Class type", fontWeight = FontWeight.SemiBold)
                Text("Lecture and Lab cannot occupy the same subject/day/time cell. To change a cell from Lecture to Lab, tap the existing subject cell to remove it first, then select the new type and tap that cell again.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Lecture", "Lab").forEach { option ->
                        FilterChip(selected = classType == option, onClick = { classType = option }, label = { Text(option) })
                    }
                }
                Text("Pick class time(s) and day(s)", fontWeight = FontWeight.SemiBold)
                Text("Choose Lecture or Lab, then add/remove both types in this same screen. Tap this subject's cell to remove it, then tap the same cell again after choosing the new type. Other subjects are locked and cannot be overwritten.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp)) {
                    Column {
                        Row {
                            Box(Modifier.width(48.dp).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Text("Time", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                            weekDays.forEach { d -> Box(Modifier.width(86.dp).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Text(d.take(3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
                        }
                        hours.forEach { h ->
                            Row {
                                Box(Modifier.width(48.dp).height(52.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall); Text("%02d:00".format(h + 1), style = MaterialTheme.typography.labelSmall) } }
                                weekDays.forEach { d ->
                                    val key = "$d|$h"
                                    val selectedType = selectedSlots[key]
                                    val cellRecords = existingSchedule.filter { it.day.equals(d, true) && it.startTime.toMinutesOrNull() == h * 60 }
                                    val sameSubject = cellRecords.firstOrNull { subject.isNotBlank() && it.title.trim().equals(subject.trim(), true) }
                                    val otherSubject = cellRecords.firstOrNull { subject.isBlank() || !it.title.trim().equals(subject.trim(), true) }
                                    val occupant = cellRecords.groupBy { it.title.trim().uppercase(Locale.getDefault()) }.values.take(2).joinToString(" • ") { group ->
                                        val first = group.first()
                                        val types = group.map { it.classType }.distinct().joinToString("+")
                                        val rooms = group.map { it.room.trim() }.filter { it.isNotBlank() }.distinct().joinToString("/")
                                        buildString { append(first.title); append(" "); append(types); if (rooms.isNotBlank()) append(" • ").append(rooms) }
                                    }
                                    Box(
                                        Modifier.width(86.dp).height(52.dp)
                                            .border(2.dp, if (selectedType != null) MaterialTheme.colorScheme.primary else if (sameSubject != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant)
                                            .background(if (selectedType != null) MaterialTheme.colorScheme.primaryContainer else if (sameSubject != null) MaterialTheme.colorScheme.secondaryContainer else if (otherSubject != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                                            .clickable {
                                                when {
                                                    otherSubject != null && cellRecords.isNotEmpty() -> Unit
                                                    selectedType != null -> {
                                                        selectedSlots = selectedSlots - key
                                                        removedSlots = removedSlots + key
                                                    }
                                                    sameSubject != null && key !in removedSlots -> {
                                                        selectedSlots = selectedSlots - key
                                                        removedSlots = removedSlots + key
                                                    }
                                                    else -> {
                                                        selectedSlots = selectedSlots + (key to classType)
                                                        removedSlots = removedSlots - key
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            when {
                                                selectedType != null -> {
                                                    Text(if (sameSubject != null) occupant else "Selected $selectedType", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                    if (sameSubject != null) Text("Tap to remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                }
                                                cellRecords.isNotEmpty() -> {
                                                    Text(occupant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                    Text(if (otherSubject != null) "Occupied • locked" else "Tap to remove", style = MaterialTheme.typography.labelSmall, color = if (otherSubject != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                                                }
                                                else -> Text("Empty", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(if (selectedSlots.isEmpty()) "No schedule selected" else selectedSlots.size.toString() + " slot(s) selected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("Rooms", fontWeight = FontWeight.SemiBold)
                Text("Lecture and Lab rooms are loaded automatically from the subject. Change them only if the room itself changes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(lectureRoom, { lectureRoom = it }, Modifier.fillMaxWidth(), label = { Text("Lecture room") }, singleLine = true)
                OutlinedTextField(labRoom, { labRoom = it }, Modifier.fillMaxWidth(), label = { Text("Lab room") }, singleLine = true)
                OutlinedTextField(professor, { professor = it }, Modifier.fillMaxWidth(), label = { Text("Professor") })
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
                Text("Class color", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c ->
                        Box(Modifier.size(40.dp).border(3.dp, if (color == c) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(50)).padding(4.dp).background(Color(c), RoundedCornerShape(50)).clickable { color = c }, contentAlignment = Alignment.Center) {
                            if (color == c) Text("✓", color = readableContentColor(Color(c)), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val normalizedSubject = subject.trim()
                if (normalizedSubject.isNotBlank()) {
                    val existing = store.get("schedule")
                    val idBase = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.id } ?: 0L) + 1L)
                    val newRecords = selectedSlots.entries.mapIndexed { index, entry ->
                        val p = entry.key.split("|")
                        val day = p.getOrNull(0).orEmpty()
                        val hour = p.getOrNull(1)?.toIntOrNull() ?: 0
                        val type = if (entry.value.equals("Lab", true)) "Lab" else "Lecture"
                        Record(id = idBase + index, title = normalizedSubject, subtitle = fullName.trim(), extra = notes.trim(), day = day, startTime = "%02d:00".format(hour), endTime = "%02d:00".format(hour + 1), room = if (type == "Lab") labRoom.trim() else lectureRoom.trim(), professor = professor.trim(), color = color, classType = type)
                    }
                    val withoutSubject = existing.filterNot { it.title.trim().equals(normalizedSubject, true) }
                    store.put("schedule", withoutSubject + newRecords)
                    if (newRecords.isEmpty()) {
                        store.put("subjects", store.get("subjects").filterNot { it.title.trim().equals(normalizedSubject, true) })
                    } else {
                        newRecords.forEach { syncSubjectFromClass(store, it) }
                    }
                }
                done()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@Composable
fun EditScheduleRecordDialog(record: Record, store: LocalStore, done: () -> Unit, cancel: () -> Unit) {
    var subject by remember(record.id) { mutableStateOf(record.title) }
    var fullName by remember(record.id) { mutableStateOf(record.subtitle) }
    var room by remember(record.id) { mutableStateOf(record.room) }
    var professor by remember(record.id) { mutableStateOf(record.professor) }
    var notes by remember(record.id) { mutableStateOf(record.extra) }
    var type by remember(record.id) { mutableStateOf(record.classType.ifBlank { "Lecture" }) }
    var selectedSlot by remember(record.id) { mutableStateOf("${record.day}|${record.startTime.toHourOrNull() ?: 7}") }
    val days = store.scheduleDays().ifEmpty { listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday") }
    val startHour = store.scheduleStartHour().coerceIn(0,23)
    val endHour = store.scheduleEndHour().coerceAtLeast(startHour).coerceAtMost(23)
    val hours = (startHour until endHour).toList()
    val existing = store.get("schedule")
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Edit class") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject code") }, singleLine = true)
                OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Whole subject name") })
                Text("Class type", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Lecture", "Lab").forEach { option -> FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) }) }
                }
                Text("Pick day and hour", fontWeight = FontWeight.SemiBold)
                Text("Tap a cell just like when adding a class.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp)) {
                    Column {
                        Row {
                            Box(Modifier.width(48.dp).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Text("Time", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                            days.forEach { d -> Box(Modifier.width(86.dp).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Text(d.take(3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
                        }
                        hours.forEach { h ->
                            Row {
                                Box(Modifier.width(48.dp).height(52.dp).border(1.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall); Text("%02d:00".format(h + 1), style = MaterialTheme.typography.labelSmall) } }
                                days.forEach { d ->
                                    val key = "$d|$h"
                                    val selected = key == selectedSlot
                                    val occupied = existing.any {
                                        it.id != record.id &&
                                            it.day.equals(d, true) &&
                                            it.startTime.toMinutesOrNull() == h * 60 &&
                                            it.title.trim().equals(subject.trim(), true)
                                    }
                                    Box(Modifier.width(86.dp).height(52.dp).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).background(if (selected) MaterialTheme.colorScheme.primaryContainer else if (occupied) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface).clickable { selectedSlot = key }, contentAlignment = Alignment.Center) {
                                        Text(if (selected) "Selected" else if (occupied) "Occupied" else "Empty", style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    room,
                    { room = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (type.equals("Lab", true)) "Lab room" else "Lecture room") },
                    placeholder = { Text(if (type.equals("Lab", true)) "Enter lab room" else "Enter lecture room") },
                    singleLine = true
                )
                OutlinedTextField(professor, { professor = it }, Modifier.fillMaxWidth(), label = { Text("Professor") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            }
        },
        confirmButton = {
            Button({
                val p = selectedSlot.split("|")
                val newDay = p.getOrNull(0).orEmpty()
                val newStartHour = p.getOrNull(1)?.toIntOrNull()
                val oldDuration = ((record.endTime.toMinutesOrNull() ?: 0) - (record.startTime.toMinutesOrNull() ?: 0)).coerceAtLeast(60)
                if (subject.isNotBlank() && newDay.isNotBlank() && newStartHour != null) {
                    val newEnd = newStartHour * 60 + oldDuration
                    val updated = record.copy(title = subject.trim(), subtitle = fullName.trim(), extra = notes.trim(), day = newDay, startTime = "%02d:00".format(newStartHour), endTime = "%02d:%02d".format(newEnd / 60, newEnd % 60), room = room.trim(), professor = professor.trim(), classType = type)
                    val cleaned = store.get("schedule").filterNot {
                        it.id != record.id &&
                            it.day.equals(newDay, true) &&
                            it.startTime.toMinutesOrNull() == newStartHour * 60 &&
                            it.title.trim().equals(subject.trim(), true)
                    }
                    store.put(
                        "schedule",
                        cleaned.map { if (it.id == record.id) updated else it } +
                            if (cleaned.none { it.id == record.id }) listOf(updated) else emptyList()
                    )
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
private fun formatHourRange(start: Int, end: Int) = "%02d:00\n%02d:00".format(start, end)
private /**
 * Canonical timetable merge used by Home and widgets.
 *
 * Two adjacent one-hour records are one displayed class only when they describe
 * the same subject, full subject name, class type, room, and professor.
 * This prevents unrelated records from being joined while guaranteeing
 * 07:00–08:00 + 08:00–09:00 for the same Lecture/room/subject becomes 07:00–09:00.
 */
fun mergeAdjacentClassRecords(records: List<Record>): List<Record> {
    fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.getDefault())

    val sorted = records.sortedWith(
        compareBy<Record>(
            { it.startTime.toMinutesOrNull() ?: Int.MAX_VALUE },
            { it.endTime.toMinutesOrNull() ?: Int.MAX_VALUE },
            { normalized(it.title) },
            { normalized(it.classType) },
            { normalized(it.room) }
        )
    )
    val out = mutableListOf<Record>()
    for (r in sorted) {
        val previous = out.lastOrNull()
        val previousEnd = previous?.endTime?.toMinutesOrNull()
        val start = r.startTime.toMinutesOrNull()
        val sameSubject = previous != null && normalized(previous.title) == normalized(r.title)
        val sameFullName = previous != null && normalized(previous.subtitle) == normalized(r.subtitle)
        val sameType = previous != null && normalized(previous.classType) == normalized(r.classType)
        val sameRoom = previous != null && normalized(previous.room) == normalized(r.room)
        val sameProfessor = previous != null && normalized(previous.professor) == normalized(r.professor)
        val consecutive = previousEnd != null && start != null && previousEnd == start

        if (previous != null && sameSubject && sameFullName && sameType && sameRoom && sameProfessor && consecutive) {
            out[out.lastIndex] = previous.copy(endTime = r.endTime)
        } else {
            out += r
        }
    }
    return out
}

private fun mergeTodayClasses(records: List<Record>): List<Record> =
    mergeAdjacentClassRecords(records)
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
    val initialHour = initial.substringBefore(":").toIntOrNull()?.coerceIn(7, 20) ?: 7
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
                            maxValue = 20
                            value = hour
                            wrapSelectorWheel = false
                            displayedValues = (7..20).map { "%02d:00".format(it) }.toTypedArray()
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
    var professor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var classType by remember { mutableStateOf("Lecture") }
    var color by remember { mutableLongStateOf(0xFFE3F2FD) }
    var selectedSlots by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
            Text("Class type",fontWeight=FontWeight.SemiBold)
            Text("Choose Lecture, select its cells, then switch to Lab and select its cells. Add both for this subject and save once. Existing classes cannot be overwritten.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Lecture","Lab").forEach{option->FilterChip(selected=classType==option,onClick={classType=option},label={Text(option)})}}
            Text("Pick class time(s) and day(s)",fontWeight=FontWeight.SemiBold)
            Text("Currently selecting: $classType. Tap empty cells to add/remove them for this type. Existing classes are locked.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=2.dp)){Column{
                Row{
                    Box(Modifier.width(48.dp).height(32.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Text("Time",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}
                    weekDays.forEach{d->Box(Modifier.width(86.dp).height(32.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Text(d.take(3),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}}
                }
                hours.forEach{h->Row{
                    Box(Modifier.width(48.dp).height(52.dp).border(1.dp,MaterialTheme.colorScheme.outline),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("%02d:00".format(h),style=MaterialTheme.typography.labelSmall); Text("%02d:00".format(h + 1),style=MaterialTheme.typography.labelSmall) }}
                    weekDays.forEach{d->
                        val currentKey="$d|$h"
                        val selectedType=selectedSlots[currentKey]
                        val cellRecords=existingSchedule.filter{it.day.equals(d,true)&&it.startTime.toMinutesOrNull()==h*60}
                        val sameSubjectRecord=cellRecords.firstOrNull{subject.isNotBlank()&&it.title.trim().equals(subject.trim(),true)}
                        val occupantText=cellRecords.groupBy{it.title.trim().uppercase(Locale.getDefault())}.values.take(2).joinToString(" • "){group->
                            val first=group.first()
                            val types=group.map{it.classType}.distinct().joinToString("+")
                            val rooms=group.map{it.room.trim()}.filter{it.isNotBlank()}.distinct().joinToString("/")
                            buildString{append(first.title);append(" ");append(types);if(rooms.isNotBlank())append(" • ").append(rooms)}
                        }
                        Box(Modifier.width(86.dp).height(52.dp)
                            .border(2.dp,if(selectedType!=null)MaterialTheme.colorScheme.primary else if(cellRecords.isNotEmpty())MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)
                            .background(if(selectedType!=null)MaterialTheme.colorScheme.primaryContainer else if(cellRecords.isNotEmpty())MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                            .clickable{if(cellRecords.isEmpty()){selectedSlots=if(selectedType==null)selectedSlots+(currentKey to classType) else if(selectedType.equals(classType,true))selectedSlots-currentKey else selectedSlots+(currentKey to classType)}},
                            contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                if(cellRecords.isNotEmpty()) Text(occupantText,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                else Text("Empty",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(selectedType!=null) Text("Selected $selectedType",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1)
                                else if(cellRecords.isNotEmpty()) Text("Occupied • locked",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                            }
                        }
                    }
                }}
            }}
            Text(if(selectedSlots.isEmpty())"No time selected" else selectedSlots.entries.groupingBy{it.value}.eachCount().entries.joinToString(" • "){it.key+": "+it.value},color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            val lectureSelectedAny = selectedSlots.values.any { it.equals("Lecture", true) }
            val labSelectedAny = selectedSlots.values.any { it.equals("Lab", true) }
            Text("Rooms", fontWeight=FontWeight.SemiBold)
            Text(
                "The room field follows the selected class type. Lecture uses Lecture room; Lab uses Lab room.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall
            )
            if (lectureSelectedAny) {
                OutlinedTextField(
                    lectureRoom,
                    { lectureRoom = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Lecture room") },
                    placeholder = { Text("Enter lecture room") },
                    singleLine = true
                )
            }
            if (labSelectedAny) {
                OutlinedTextField(
                    labRoom,
                    { labRoom = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Lab room") },
                    placeholder = { Text("Enter lab room") },
                    singleLine = true
                )
            }
            OutlinedTextField(professor,{professor=it},Modifier.fillMaxWidth(),label={Text("Professor")})
            OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")})
            Text("Class color",fontWeight=FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){colors.forEach{c->Box(Modifier.size(40.dp).border(3.dp,if(color==c)MaterialTheme.colorScheme.onSurface else Color.Transparent,RoundedCornerShape(50)).padding(4.dp).background(Color(c),RoundedCornerShape(50)).clickable{color=c},contentAlignment=Alignment.Center){if(color==c)Text("✓",color=readableContentColor(Color(c)),fontWeight=FontWeight.Bold)}}}
        }
    },confirmButton={Button({
        if(subject.isNotBlank()&&selectedSlots.isNotEmpty()){
            val normalizedSubject=subject.trim()
            val existing=store.get("schedule")
            val slots=selectedSlots.entries.map { entry ->
                val p=entry.key.split("|")
                Triple(p.getOrNull(0)?:"",p.getOrNull(1)?.toIntOrNull()?:7,entry.value)
            }
            if(slots.isNotEmpty()){
                val idBase=maxOf(System.currentTimeMillis(),(existing.maxOfOrNull{it.id}?:0L)+1L)
                val selected=slots.mapIndexed{index,slot->
                    val (day,h,type)=slot
                    Record(id=idBase+index,title=normalizedSubject,subtitle=fullName.trim(),extra=notes.trim(),day=day,startTime="%02d:00".format(h),endTime="%02d:00".format(h+1),room=(if (type.equals("Lecture", true)) lectureRoom else labRoom).trim(),professor=professor.trim(),color=color,classType=type)
                }
                val conflict = slots.any { (day,h) ->
                    existing.any { old -> old.day.equals(day,true) && old.startTime.toMinutesOrNull() == h * 60 }
                }
                if (!conflict) {
                    store.put("schedule", existing + selected)
                    selected.forEach{syncSubjectFromClass(store,it)}
                }
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
    var refresh by remember{mutableIntStateOf(0)}
    var selectedDate by remember{mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))}
    var showDateTasks by remember{mutableStateOf(false)}
    val isTasks = key == "tasks"

    LaunchedEffect(query) {
        if (query == "__ADD__" && isTasks) {
            showDateTasks = true
            clear()
        }
    }

    if (isTasks) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaskCalendar(selectedDate) {
                selectedDate = it
                showDateTasks = true
            }
        }
        if (showDateTasks) {
            TaskDateDialog(
                selectedDate = selectedDate,
                store = store,
                refresh = { refresh++ },
                done = { showDateTasks = false }
            )
        }
        return
    }

    var showAdd by remember{mutableStateOf(false)}
    LaunchedEffect(query){if(query=="__ADD__")showAdd=true}
    val list=remember(refresh,revision,query){store.get(key).filter{query.isBlank()||query=="__ADD__"||(it.title+" "+it.subtitle+" "+it.extra).contains(query,true)}.sortedWith(compareBy<Record>({it.done},{it.dueDate},{it.dueTime}))}
    Column(Modifier.fillMaxSize()){
        Text(title,Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        if(list.isEmpty())EmptyCard("No items yet.") else LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(list,key={it.id}){r->RecordCard(r,key,store){refresh++}}}
    }
    if(showAdd)AddRecordDialog(title,key,store,done={showAdd=false;clear();refresh++})
}

@Composable
fun TaskDateDialog(
    selectedDate: String,
    store: LocalStore,
    refresh: () -> Unit,
    done: () -> Unit
) {
    val revision = store.revision
    var showAdd by remember(selectedDate) { mutableStateOf(false) }
    val tasks = remember(selectedDate, revision) {
        store.get("tasks")
            .filter { it.dueDate == selectedDate }
            .sortedWith(compareBy<Record>({ it.done }, { it.dueTime }, { it.title.lowercase(Locale.getDefault()) }))
    }

    Dialog(onDismissRequest = done) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(selectedDate) ?: Date()
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            tasks.size.toString() + " task" + if (tasks.size == 1) "" else "s",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = done) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()

                if (tasks.isEmpty()) {
                    EmptyCard("No tasks on this date yet.")
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            RecordCard(task, "tasks", store) { refresh() }
                        }
                    }
                }

                Button(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add note to this date")
                }
            }
        }
    }

    if (showAdd) {
        AddRecordDialog(
            label = "Note",
            key = "tasks",
            store = store,
            done = {
                showAdd = false
                refresh()
            },
            initialDueDate = selectedDate
        )
    }
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
        if (tab == 1) {
            Card(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Construction, null); Spacer(Modifier.width(8.dp)); Text("Feature development", fontWeight=FontWeight.Bold) }
                    Text("Reviewer tools are still being developed. Planned functions include question-bank management, reviewer categories, quiz/practice mode, search and sorting, and import/export.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
    if (query == "__ADD__") AddRecordDialog(labels[tab], keys[tab], store, done={ clear(); refresh++ })
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
    val ext = fileExtension(file)

    LaunchedEffect(file.absolutePath) {
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        activity?.window?.decorView?.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            activity?.window?.decorView?.systemUiVisibility = 0
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            ext == "pdf" -> {
                val pageCount = remember(file) {
                    runCatching {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { d ->
                            PdfRenderer(d).use { it.pageCount }
                        }
                    }.getOrDefault(0)
                }
                if (pageCount > 0) {
                    val safePage = page.coerceIn(0, pageCount - 1)
                    Box(
                        Modifier.fillMaxSize().pointerInput(page, pageCount) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, amount -> dragTotal += amount },
                                onDragEnd = {
                                    if (dragTotal < -70f && page < pageCount - 1) page++
                                    else if (dragTotal > 70f && page > 0) page--
                                    dragTotal = 0f
                                },
                                onDragCancel = { dragTotal = 0f }
                            )
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        renderPdfPage(file, safePage)?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                file.name,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: EmptyCard("Unable to render this PDF.")
                    }
                    ViewerBackButton(done)
                    ViewerPageIndicator(safePage + 1, pageCount)
                } else {
                    EmptyCard("Unable to open this PDF.")
                    ViewerBackButton(done)
                }
            }
            ext == "pptx" || ext == "ppt" -> {
                val slides = remember(file) { readOfficeSlides(file) }
                if (slides.isNotEmpty()) {
                    val safeSlide = slide.coerceIn(0, slides.lastIndex)
                    Box(
                        Modifier.fillMaxSize().pointerInput(slide, slides.size) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, amount -> dragTotal += amount },
                                onDragEnd = {
                                    if (dragTotal < -70f && slide < slides.lastIndex) slide++
                                    else if (dragTotal > 70f && slide > 0) slide--
                                    dragTotal = 0f
                                },
                                onDragCancel = { dragTotal = 0f }
                            )
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            slides[safeSlide].ifBlank { "Blank slide" },
                            Modifier.padding(24.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    ViewerBackButton(done)
                    ViewerPageIndicator(safeSlide + 1, slides.size)
                } else {
                    EmptyCard("Unable to read this PowerPoint offline.")
                    ViewerBackButton(done)
                }
            }
            ext == "docx" -> {
                val text = remember(file) {
                    readOfficeText(file) ?: "No readable text was found in this Word document."
                }
                LazyColumn(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().widthIn(max = 794.dp),
                            shape = RoundedCornerShape(6.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 36.dp, vertical = 42.dp)) {
                                Text("A4 PRINT LAYOUT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                            }
                        }
                    }
                }
                ViewerBackButton(done)
            }
            else -> {
                val text = remember(file) { readDisplayText(file) }
                LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                        }
                    }
                }
                ViewerBackButton(done)
            }
        }
    }
}

@Composable
private fun BoxScope.ViewerBackButton(done: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(start = 10.dp, top = 10.dp)
            .size(42.dp)
            .align(Alignment.TopStart)
            .clickable(onClick = done),
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.28f),
        contentColor = Color.White
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
    }
}

@Composable
private fun BoxScope.ViewerPageIndicator(current: Int, total: Int) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 10.dp),
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.28f),
        contentColor = Color.White
    ) {
        Text(
            "$current / $total",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
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
fun AddRecordDialog(
    label:String,
    key:String,
    store:LocalStore,
    done:()->Unit,
    initialDueDate:String = SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date())
){
    var subjectId by remember{mutableLongStateOf(0L)}
    var title by remember{mutableStateOf("")}
    var subtitle by remember{mutableStateOf("")}
    var extra by remember{mutableStateOf("")}
    var value by remember{mutableStateOf("")}
    var dueDate by remember(initialDueDate){mutableStateOf(initialDueDate)}
    val subjects=store.get("subjects")
    val isTask=key=="tasks"
    AlertDialog(
        onDismissRequest=done,
        title={Text("Add $label")},
        text={
            Column(
                Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ){
                if(isTask){
                    Text("Subject (optional)",fontWeight=FontWeight.Bold)
                    if(subjects.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)
                        ){
                            subjects.forEach{s->
                                FilterChip(
                                    subjectId==s.id,
                                    {subjectId=if(subjectId==s.id)0L else s.id},
                                    label={Text(s.title)}
                                )
                            }
                        }
                    }
                    Surface(
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(12.dp),
                        color=MaterialTheme.colorScheme.surfaceVariant
                    ){
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Icon(Icons.Default.Event,null)
                            Spacer(Modifier.width(8.dp))
                            Text("Date: $dueDate",fontWeight=FontWeight.SemiBold)
                        }
                    }
                }
                OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("Title")})
                OutlinedTextField(subtitle,{subtitle=it},Modifier.fillMaxWidth(),label={Text("Description")})
                if(key=="tasks"||key=="reviewers")OutlinedTextField(extra,{extra=it},Modifier.fillMaxWidth(),label={Text("Notes")})
                if(key=="grades"||key=="expenses")OutlinedTextField(value,{value=it},Modifier.fillMaxWidth(),label={Text(if(key=="grades")"Grade" else "Amount")})
            }
        },
        confirmButton={
            Button({
                if(title.isNotBlank()){
                    store.put(
                        key,
                        store.get(key)+Record(
                            title=title.trim(),
                            subtitle=subtitle.trim(),
                            extra=extra.trim(),
                            value=value.toDoubleOrNull()?:0.0,
                            subjectId=subjectId,
                            dueDate=if(isTask)dueDate else "",
                            dueTime=""
                        )
                    )
                    done()
                }
            }){Text("Save")}
        },
        dismissButton={TextButton(done){Text("Cancel")}}
    )
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
    val modules=listOf("homepage" to "Homepage","schedule" to "Schedule","tasks" to "Notes","academics" to "Academics / Lessons")
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
    var notificationsOn by remember { mutableStateOf(context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).getBoolean("enabled", false) && CampusReminders.notificationsEnabled(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            notificationsOn = true
            context.getSharedPreferences("campusos_reminders", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            CampusReminders.reschedule(context)
        }
    }
    var backupMode by remember { mutableStateOf(false) }
    var restoreMode by remember { mutableStateOf(false) }
    var selectedModules by remember { mutableStateOf(setOf("homepage","schedule","tasks","academics")) }
    var restoreError by remember { mutableStateOf("") }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.backupJson(selectedModules).toByteArray()) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.restoreJson(it.readText(), selectedModules) }
        }.onFailure { restoreError = it.message ?: "Unable to recover this backup file." }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notifications", fontWeight = FontWeight.Bold)
                    Text("Get reminders before your next class and upcoming notes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        item { Text("Academics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Subjects & lecture files", fontWeight = FontWeight.Bold)
                    Text("Manage Notepad and Lecture Files from the Academics screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notes & Reminders", fontWeight = FontWeight.Bold)
                    Text("Manage notes and reminders from the Notes screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Backup & Recovery", fontWeight = FontWeight.Bold)
                    Text("Save a backup file or recover only the CampusOS modules you choose.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button({ selectedModules = setOf("homepage","schedule","tasks","academics"); backupMode=true }) {
                            Icon(Icons.Default.Backup, null); Spacer(Modifier.width(6.dp)); Text("Backup file")
                        }
                        OutlinedButton({ selectedModules = setOf("homepage","schedule","tasks","academics"); restoreError = ""; restoreMode=true }) {
                            Icon(Icons.Default.Restore, null); Spacer(Modifier.width(6.dp)); Text("Recover")
                        }
                    }
                    Text("You can back up or recover Homepage, Schedule, Notes, or Academics/Lessons separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Text("CampusOS 1.0.0 • Offline-first", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Transfer tip: select Schedule to share your timetable, or Academics / Lessons to share subjects, notes, and lecture files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (backupMode) ModuleBackupDialog("Choose modules to backup", selectedModules, { selectedModules=it }) {
        backupMode=false
        if(selectedModules.isNotEmpty()) backup.launch("CampusOS-backup.json")
    }
    if (restoreMode) ModuleBackupDialog("Choose modules to recover", selectedModules, { selectedModules=it }) {
        restoreMode=false
        if(selectedModules.isNotEmpty()) restore.launch(arrayOf("application/json","text/plain"))
    }
    if (restoreError.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { restoreError = "" },
            title = { Text("Recovery failed") },
            text = { Text(restoreError) },
            confirmButton = { TextButton(onClick = { restoreError = "" }) { Text("OK") } }
        )
    }
    if (showTableSettings) {
        ScheduleTableSettingsDialog(store) { showTableSettings = false }
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
    if (store.authMethod() == "pattern") PatternUnlockScreen(store, unlock) else PinUnlockScreen(store, unlock)
}

@Composable
fun PinUnlockScreen(store: LocalStore, unlock: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(64.dp)); Spacer(Modifier.height(18.dp))
        Text("CampusOS is locked", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("Enter your PIN to continue.", color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp))
        OutlinedTextField(entered, { entered = it.filter(Char::isDigit).take(8) }, label={Text("PIN")}, singleLine=true)
        if (error) Text("Incorrect PIN", color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp)); Button({ if (entered == store.pin()) unlock() else error=true }) { Text("Unlock") }
    }
}

@Composable
fun PatternUnlockScreen(store: LocalStore, unlock: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun add(n:Int) { if (!entered.split("-").contains(n.toString())) entered = if (entered.isBlank()) n.toString() else "$" + "entered-$n" }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Icon(Icons.Default.Grid3x3, null, Modifier.size(64.dp)); Spacer(Modifier.height(18.dp))
        Text("CampusOS is locked", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("Draw your pattern to continue.", color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp))
        PatternGrid(entered.split("-").filter{it.isNotBlank()}.mapNotNull{it.toIntOrNull()}, ::add, { entered="" })
        if (error) Text("Incorrect pattern", color=MaterialTheme.colorScheme.error)
        Button({ if (entered == store.pattern()) unlock() else { error=true; entered="" } }) { Text("Unlock") }
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
    LaunchedEffect(Unit) { while (true) { nowMillis=System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
    val now=Calendar.getInstance().apply{timeInMillis=nowMillis}
    val currentMinutes=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE)
    val mergedTodaySchedule=mergeTodayClasses(todaySchedule)
    val parsed=mergedTodaySchedule.mapNotNull{r->val st=r.startTime.toMinutesOrNull();val en=r.endTime.toMinutesOrNull();if(st!=null&&en!=null&&en>st) Triple(r,st,en) else null}.sortedBy{it.second}
    val current=parsed.firstOrNull{(_,st,en)->currentMinutes>=st&&currentMinutes<en}
    val next=parsed.firstOrNull{(_,st,_)->st>currentMinutes}
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){SectionTitle("Today's classes");Spacer(Modifier.width(8.dp));Surface(shape=RoundedCornerShape(50.dp),color=MaterialTheme.colorScheme.primaryContainer){Text(todaySchedule.size.toString(),Modifier.padding(horizontal=9.dp,vertical=3.dp),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)}}
        Spacer(Modifier.height(8.dp))
        current?.let{(r,st,en)->
            val duration=(en-st).coerceAtLeast(1);val elapsed=(currentMinutes-st).coerceAtLeast(0)
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),shape=RoundedCornerShape(16.dp)){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("CURRENT CLASS",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text(r.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(if(r.classType.equals("Lab",true))"Lab" else "Lecture",fontWeight=FontWeight.SemiBold)
                    Text("${r.startTime}–${r.endTime}")
                    if(r.room.isNotBlank())Text("Room ${r.room}",fontWeight=FontWeight.SemiBold)
                    Text("Duration: ${formatClassDuration(duration)}",style=MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress={(elapsed.toFloat()/duration).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Started " + formatClassCountdown(elapsed) + " ago",style=MaterialTheme.typography.bodySmall)
                        Text((en-currentMinutes).coerceAtLeast(0).toString() + " min remaining",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        next?.let{(r,st,en)->
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),shape=RoundedCornerShape(16.dp)){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("NEXT CLASS",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.secondary)
                    Text("UPCOMING • Starts in ${formatClassCountdown((st-currentMinutes).coerceAtLeast(0))}",fontWeight=FontWeight.Bold)
                    Text(r.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(if(r.classType.equals("Lab",true))"Lab" else "Lecture",fontWeight=FontWeight.SemiBold)
                    Text("${r.startTime}–${r.endTime}")
                    if(r.room.isNotBlank())Text("Room ${r.room}",fontWeight=FontWeight.SemiBold)
                    Text("Duration: " + formatClassDuration((en-st).coerceAtLeast(0)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress={0f},modifier=Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if(todaySchedule.isEmpty()) EmptyCard("No classes scheduled for today.") else {
            val featuredSubjects = setOfNotNull(
                current?.first?.title?.trim()?.uppercase(Locale.getDefault()),
                next?.first?.title?.trim()?.uppercase(Locale.getDefault())
            )
            val remaining=mergedTodaySchedule.filterNot {
                it.title.trim().uppercase(Locale.getDefault()) in featuredSubjects
            }.sortedWith(
                compareBy<Record> {
                    val end = it.endTime.toMinutesOrNull() ?: Int.MAX_VALUE
                    // Completed classes are always placed after current/upcoming classes.
                    if (currentMinutes >= end) 1 else 0
                }.thenBy { it.startTime.toMinutesOrNull() ?: Int.MAX_VALUE }
            )
            remaining.forEach{r->
                val st=r.startTime.toMinutesOrNull();val en=r.endTime.toMinutesOrNull()
                val completed = st != null && en != null && currentMinutes >= en
                val status=when {
                    completed -> "✓ Completed"
                    st != null -> "Starts in " + formatClassCountdown((st-currentMinutes).coerceAtLeast(0))
                    else -> null
                }
                HomeTodayClassCard(r,status)
            }
        }
    }
}

@Composable
fun HomePinnedTile(pinnedTasks: List<Record>) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("📍 Pinned")
        Spacer(Modifier.height(8.dp))
        if (pinnedTasks.isEmpty()) EmptyCard("Nothing pinned yet.")
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

