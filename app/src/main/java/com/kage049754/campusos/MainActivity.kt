package com.kage049754.campusos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import kotlinx.coroutines.delay

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


data class BudgetEntry(
    val id: Long = System.currentTimeMillis(),
    val type: String,
    val amount: Double,
    val category: String = "",
    val note: String = "",
    val date: String = "",
    val target: String = ""
)

@Composable
fun campusTileModifier(modifier: Modifier = Modifier): Modifier =
    modifier

@Composable
fun campusTileColors(accent: Boolean = false): CardColors =
    CardDefaults.cardColors(
        containerColor = if (accent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    )

private val CampusShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private fun applyLauncherIcon(context: Context, preset: String) {
    val pm = context.packageManager
    val pkg = context.packageName

    // Keep MainActivity itself as a permanently enabled launcher entry.
    // This guarantees Android always has a normal launchable activity, even if
    // a launcher implementation ignores or resets an activity-alias state.
    val main = android.content.ComponentName(context, MainActivity::class.java)
    val presets = listOf("forest", "sunset")

    runCatching {
        pm.setComponentEnabledSetting(
            main,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )

        // Keep the optional #570 aliases disabled so they cannot replace or
        // hide the real MainActivity launcher entry.
        presets.forEach { name ->
            val componentName = name.replaceFirstChar { it.uppercase() } + "Launcher"
            pm.setComponentEnabledSetting(
                android.content.ComponentName(pkg, componentName),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        }
    }
}

private fun campusColorScheme(preset: String, dark: Boolean): ColorScheme {
    // Keep surfaces theme-aware too. Previously many cards used surfaceVariant, which
    // stayed nearly the same across presets and made tiles look like #E8E3E7.
    val colors = when (preset) {
        "forest" -> listOf(Color(0xFF176B3A), Color(0xFFB8F2C8), Color(0xFF326B67), Color(0xFFE8F4EC), Color(0xFFD5E9DC))
        "ocean" -> listOf(Color(0xFF006A6A), Color(0xFFB8F2F0), Color(0xFF315E72), Color(0xFFE9F6F6), Color(0xFFD5ECEC))
        "mint" -> listOf(Color(0xFF2E7D5B), Color(0xFFC8F2DC), Color(0xFF36706A), Color(0xFFEBF7F0), Color(0xFFD9EBDD))
        "sky" -> listOf(Color(0xFF2674C8), Color(0xFFD7E9FF), Color(0xFF4F6480), Color(0xFFF1F7FF), Color(0xFFDFEBF7))
        "lavender" -> listOf(Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFF7D5260), Color(0xFFF5F0FB), Color(0xFFE9DFF4))
        "plum" -> listOf(Color(0xFF7A4E9B), Color(0xFFEBD9F7), Color(0xFF6B5878), Color(0xFFF8F2FC), Color(0xFFEDE1F3))
        "rose" -> listOf(Color(0xFF9C4168), Color(0xFFFFD9E5), Color(0xFF70465A), Color(0xFFFFF0F4), Color(0xFFF1DDE5))
        "coral" -> listOf(Color(0xFFC85A4A), Color(0xFFFFDDD6), Color(0xFF795B55), Color(0xFFFFF4F1), Color(0xFFF3E1DD))
        "sunset" -> listOf(Color(0xFFB3261E), Color(0xFFFFDAD1), Color(0xFF705E1F), Color(0xFFFFF2EE), Color(0xFFF3DED7))
        "amber" -> listOf(Color(0xFF9A6700), Color(0xFFFFE2A8), Color(0xFF74602B), Color(0xFFFFF8EA), Color(0xFFF1E5C9))
        "teal" -> listOf(Color(0xFF00796B), Color(0xFFB8EEE6), Color(0xFF3F6864), Color(0xFFEBF8F5), Color(0xFFD7ECE8))
        "mono" -> listOf(Color(0xFF3F4650), Color(0xFFE0E4E9), Color(0xFF5D636B), Color(0xFFF1F3F5), Color(0xFFE1E5E9))
        else -> listOf(Color(0xFF176B3A), Color(0xFFB8F2C8), Color(0xFF326B67), Color(0xFFE8F4EC), Color(0xFFD5E9DC))
    }
    val primary = colors[0]
    val primaryContainer = colors[1]
    val tertiary = colors[2]
    val lightSurface = colors[3]
    val lightSurfaceVariant = colors[4]
    val darkSurface = when (preset) {
        "forest" -> Color(0xFF17251C)
        "lavender" -> Color(0xFF211B2B)
        "sunset" -> Color(0xFF2A1C1A)
        "mono" -> Color(0xFF202326)
        "ocean" -> Color(0xFF142526)
        "mint" -> Color(0xFF18251F)
        "rose" -> Color(0xFF291C23)
        else -> Color(0xFF17251C)
    }
    val darkVariant = when (preset) {
        "forest" -> Color(0xFF283C30)
        "lavender" -> Color(0xFF352A42)
        "sunset" -> Color(0xFF42302C)
        "mono" -> Color(0xFF34383D)
        "ocean" -> Color(0xFF263B3D)
        "mint" -> Color(0xFF2B3C34)
        "rose" -> Color(0xFF402B36)
        else -> Color(0xFF283C30)
    }
    return if (dark) darkColorScheme(
        primary=primary, primaryContainer=primaryContainer,
        secondary=primary.copy(alpha=.78f), secondaryContainer=primaryContainer.copy(alpha=.75f),
        tertiary=tertiary, tertiaryContainer=primaryContainer.copy(alpha=.65f),
        background=darkSurface, surface=darkSurface, surfaceVariant=darkVariant,
        surfaceContainerLowest=darkSurface,
        surfaceContainerLow=darkVariant,
        surfaceContainer=darkVariant,
        surfaceContainerHigh=darkVariant,
        surfaceContainerHighest=darkVariant,
        surfaceDim=darkSurface,
        surfaceBright=darkVariant
    ) else lightColorScheme(
        primary=primary, primaryContainer=primaryContainer,
        secondary=tertiary, secondaryContainer=primaryContainer.copy(alpha=.78f),
        tertiary=tertiary, tertiaryContainer=primaryContainer.copy(alpha=.68f),
        background=lightSurface, surface=lightSurfaceVariant, surfaceVariant=lightSurfaceVariant,
        surfaceContainerLowest=lightSurface,
        surfaceContainerLow=lightSurfaceVariant,
        surfaceContainer=lightSurfaceVariant,
        surfaceContainerHigh=lightSurfaceVariant,
        surfaceContainerHighest=lightSurfaceVariant,
        surfaceDim=lightSurface,
        surfaceBright=Color.White
    )
}

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
        // Repair/synchronize existing schedule data when an older build did not create subjects.
        syncSubjectsFromSchedule(this, read("schedule"))
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
        // Schedule is the source of truth for automatically-created subject records.
        // Sync the complete schedule in one pass so Lecture/Lab entries cannot overwrite
        // each other's subject metadata or prevent a missing subject from being created.
        if (key == "schedule") syncSubjectsFromSchedule(this, list)
        revision++
        CampusReminders.reschedule(appContext)
        CampusWidgets.updateAll(appContext)
    }
    fun get(key: String) = read(key)
    fun subjectFilesFolder(subjectId: Long): File = File(appContext.filesDir, "subject_files/" + subjectId)
    fun put(key: String, list: List<Record>) = save(key, list)
    fun delete(key: String, id: Long) = save(key, read(key).filterNot { it.id == id })
    fun pin() = prefs.getString("pin", "") ?: ""
    fun setPin(v: String) { prefs.edit().putString("pin", v).apply(); revision++ }
    fun pattern() = prefs.getString("lock_pattern", "") ?: ""
    fun setPattern(v: String) { prefs.edit().putString("lock_pattern", v).apply(); revision++ }
    fun authMethod(): String { val stored = prefs.getString("lock_method", "") ?: ""; if (stored.isNotBlank()) return stored; return if (pin().isNotBlank()) "pin" else "none" }
    fun setAuthMethod(v: String) { prefs.edit().putString("lock_method", v).apply(); revision++ }
    // CampusOS uses light mode only. Keep the stored key for backward compatibility,
    // but ignore old system/dark values from previous versions.
    fun theme() = "light"
    fun appearancePreset() = prefs.getString("appearance_preset", "forest") ?: "forest"
    fun setAppearancePreset(v: String) { prefs.edit().putString("appearance_preset", v).apply(); applyLauncherIcon(appContext, v); revision++ }
    fun homeLayoutOrder(): List<String> = (prefs.getString("home_layout_order", "") ?: "").split(",").filter { it.isNotBlank() }
    fun setHomeLayoutOrder(order: List<String>) { prefs.edit().putString("home_layout_order", order.joinToString(",")).apply(); revision++ }
    fun homeHiddenTiles(): Set<String> = (prefs.getString("home_hidden_tiles", "") ?: "").split(",").filter { it.isNotBlank() }.toSet()
    fun setHomeHiddenTiles(hidden: Set<String>) { prefs.edit().putString("home_hidden_tiles", hidden.joinToString(",")).apply(); revision++ }
    fun resetHomeLayout() { prefs.edit().remove("home_layout_order").remove("home_hidden_tiles").apply(); revision++ }
    fun setTheme(v: String) { prefs.edit().putString("theme", "light").apply(); revision++ }
    fun toolOrder(): List<String> {
        // Campus AI is a direct Tools shortcut. Keep legacy study_maker out of the visible list
        // while preserving the existing subjects storage key.
        val allowed = listOf("campus_ai", "subjects", "calculator", "budget")
        val saved = (prefs.getString("tool_order", "") ?: "").split(",").filter { it in allowed }
        // Campus AI is always the first visible Tools tab so it cannot be hidden off-screen.
        return (listOf("campus_ai") + saved + allowed).distinct()
    }
    fun setToolOrder(order: List<String>) {
        prefs.edit().putString("tool_order", order.filter { it in listOf("campus_ai","subjects","calculator","budget") }.distinct().joinToString(",")).apply()
        revision++
    }

    fun aiFloatingEnabled() = prefs.getBoolean("ai_floating_enabled", true)
    fun setAiFloatingEnabled(value: Boolean) { prefs.edit().putBoolean("ai_floating_enabled", value).apply(); revision++ }
    fun aiFloatingOpacity() = prefs.getFloat("ai_floating_opacity", 0.30f).coerceIn(0.30f, 1f)
    fun setAiFloatingOpacity(value: Float) { prefs.edit().putFloat("ai_floating_opacity", value.coerceIn(0.30f, 1f)).apply(); revision++ }
    fun aiFloatingSize() = prefs.getFloat("ai_floating_size", 52f).coerceIn(44f, 76f)
    fun setAiFloatingSize(value: Float) { prefs.edit().putFloat("ai_floating_size", value.coerceIn(44f, 76f)).apply(); revision++ }
    fun aiBubbleSize() = prefs.getFloat("ai_bubble_size", 90f).coerceIn(70f, 96f)
    fun setAiBubbleSize(value: Float) { prefs.edit().putFloat("ai_bubble_size", value.coerceIn(70f, 96f)).apply(); revision++ }
    fun aiFloatingX() = prefs.getFloat("ai_floating_x", -1f)
    fun aiFloatingY() = prefs.getFloat("ai_floating_y", -1f)
    fun setAiFloatingPosition(x: Float, y: Float) { prefs.edit().putFloat("ai_floating_x", x).putFloat("ai_floating_y", y).apply() }
    fun resetAiFloatingPosition() { prefs.edit().remove("ai_floating_x").remove("ai_floating_y").apply(); revision++ }
    fun aiActionPermission() = prefs.getString("ai_action_permission", "ask") ?: "ask"
    fun setAiActionPermission(value: String) { prefs.edit().putString("ai_action_permission", value).apply(); revision++ }
    fun aiOpenChangedModule() = prefs.getBoolean("ai_open_changed_module", false)
    fun setAiOpenChangedModule(value: Boolean) { prefs.edit().putBoolean("ai_open_changed_module", value).apply(); revision++ }
    fun aiActionNeedsApproval(toolName: String): Boolean = when (aiActionPermission()) {
        "all" -> false
        "read" -> !(toolName.startsWith("get_") || toolName == "calculate")
        else -> true
    }

    fun budgetPeriod() = prefs.getString("budget_period", "Weekly") ?: "Weekly"
    fun budgetAllowance() = prefs.getFloat("budget_allowance", 0f).toDouble()
    fun setBudgetPlan(period: String, amount: Double) {
        prefs.edit().putString("budget_period", period).putFloat("budget_allowance", amount.toFloat().coerceAtLeast(0f)).apply()
        revision++
    }
    fun budgetTargetName() = prefs.getString("budget_target_name", "") ?: ""
    fun budgetTargetAmount() = prefs.getFloat("budget_target_amount", 0f).toDouble()
    fun budgetTargetSaved() = prefs.getFloat("budget_target_saved", 0f).toDouble()
    fun setBudgetTarget(name: String, amount: Double) {
        prefs.edit().putString("budget_target_name", name).putFloat("budget_target_amount", amount.toFloat().coerceAtLeast(0f)).apply()
        revision++
    }
    fun addBudgetTargetSaved(amount: Double) {
        prefs.edit().putFloat("budget_target_saved", (budgetTargetSaved() + amount).toFloat().coerceAtLeast(0f)).apply()
        revision++
    }
    fun budgetEntries(): List<BudgetEntry> = runCatching {
        val raw = prefs.getString("budget_entries", "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                BudgetEntry(
                    id = o.optLong("id"),
                    type = when {
                        o.optString("type").trim().lowercase(Locale.getDefault()).contains("saving") -> "saving"
                        o.optString("type").trim().lowercase(Locale.getDefault()).contains("income") || o.optString("type").trim().lowercase(Locale.getDefault()).contains("allowance") -> "income"
                        else -> "expense"
                    },
                    amount = kotlin.math.abs(o.optDouble("amount", 0.0)),
                    category = o.optString("category"),
                    note = o.optString("note"),
                    date = o.optString("date").trim().ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) },
                    target = o.optString("target")
                )
            }
        }.sortedByDescending { it.id }
    }.getOrElse { emptyList() }
    fun addBudgetEntry(entry: BudgetEntry) {
        // Match the manual Budget UI: amounts are always positive; type determines the effect.
        val normalized = entry.copy(amount = kotlin.math.abs(entry.amount))
        val a = JSONArray()
        budgetEntries().forEach { e ->
            a.put(JSONObject().apply {
                put("id", e.id); put("type", e.type); put("amount", e.amount)
                put("category", e.category); put("note", e.note); put("date", e.date); put("target", e.target)
            })
        }
        a.put(JSONObject().apply {
            put("id", normalized.id); put("type", normalized.type); put("amount", normalized.amount)
            put("category", normalized.category); put("note", normalized.note); put("date", normalized.date); put("target", normalized.target)
        })
        prefs.edit().putString("budget_entries", a.toString()).apply()
        // Saving entries created by AI use the same target update as the manual Saving dialog.
        if (normalized.type.equals("saving", true) && normalized.target.isNotBlank()) {
            addBudgetTargetSaved(normalized.amount)
        }
        revision++
    }
    fun deleteBudgetEntry(id: Long) {
        val a = JSONArray()
        budgetEntries().filterNot { it.id == id }.forEach { e ->
            a.put(JSONObject().apply {
                put("id", e.id); put("type", e.type); put("amount", e.amount)
                put("category", e.category); put("note", e.note); put("date", e.date); put("target", e.target)
            })
        }
        prefs.edit().putString("budget_entries", a.toString()).apply()
        revision++
    }
    fun dynamicColorEnabled() = prefs.getBoolean("dynamic_color_enabled", false)
    fun setDynamicColorEnabled(v: Boolean) { prefs.edit().putBoolean("dynamic_color_enabled", v).apply(); revision++ }
    fun lockEnabled() = prefs.getBoolean("lock", false)
    fun setLockEnabled(v: Boolean) { prefs.edit().putBoolean("lock", v).apply(); revision++ }
    fun profileName() = prefs.getString("profile_name", "") ?: ""
    fun profileStudentId() = prefs.getString("profile_student_id", "") ?: ""
    fun profileSection() = prefs.getString("profile_section", "") ?: ""
    fun profilePhotoPath() = prefs.getString("profile_photo_path", "") ?: ""
    fun subjectFavorite(subjectId: Long): Boolean = prefs.getBoolean("subject_favorite_$subjectId", false)
    fun setSubjectFavorite(subjectId: Long, favorite: Boolean) {
        prefs.edit().putBoolean("subject_favorite_$subjectId", favorite).apply()
        revision++
    }
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
            val favorites = JSONObject(); prefs.all.filterKeys { it.startsWith("subject_favorite_") }.forEach { (k,v) -> if (v is Boolean && v) favorites.put(k.removePrefix("subject_favorite_"), true) }; root.put("subjectFavorites", favorites)
            val files = JSONArray(); val dir = File(appContext.filesDir, "subject_files")
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                files.put(JSONObject().apply { put("path", file.relativeTo(dir).path); put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)) })
            }
            root.put("subjectFiles", files)
        }
        if ("homepage" in selected) {
            root.put("theme", theme()); root.put("appearancePreset", appearancePreset()); root.put("lock", lockEnabled()); root.put("lockMethod", authMethod())
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
            root.optJSONObject("subjectFavorites")?.let { favorites -> favorites.keys().forEach { id -> e.putBoolean("subject_favorite_$id", favorites.optBoolean(id, false)) } }
        }
        if ("homepage" in selected) {
            if (root.has("theme")) e.putString("theme", root.getString("theme"))
            if (root.has("appearancePreset")) e.putString("appearance_preset", root.getString("appearancePreset"))
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
        e.apply()
        if ("schedule" in selected && root.has("schedule")) {
            syncSubjectsFromSchedule(this, read("schedule"))
        }
        revision++
        CampusReminders.reschedule(appContext)
        CampusWidgets.updateAll(appContext)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CampusOSApp(this) }
        applyLauncherIcon(this, getSharedPreferences("campusos", Context.MODE_PRIVATE).getString("appearance_preset", "forest") ?: "forest")
        CampusReminders.reschedule(this)
        CampusWidgets.updateAll(this)
    }
}

enum class Screen(val label: String) {
    HOME("Home"), SCHEDULE("Schedule"), TASKS("Notes"), ACADEMICS("Tools"), CAMPUS_AI("Campus AI"),
    FILES("Files"), CHAT("Chats"), ANNOUNCEMENTS("Announcements"), ADMIN("Campus Management"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusOSApp(activity: Activity) {
    val store = remember { LocalStore(activity) }
    // LocalStore.revision is the Compose-observable invalidation signal for settings/data.
    // Reading it here makes floating-AI enable/size/opacity/chat-size changes immediately
    // update the root UI instead of waiting for an unrelated recomposition.
    val storeRevision = store.revision
    var theme by remember { mutableStateOf(store.theme()) }
    var appearancePreset by remember { mutableStateOf(store.appearancePreset()) }
    var locked by remember { mutableStateOf(store.lockEnabled() && store.authMethod() != "none") }
    var screenName by rememberSaveable {
        mutableStateOf(activity.intent.getStringExtra("widget_open_screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull()?.name } ?: Screen.HOME.name)
    }
    var widgetSubjectId by rememberSaveable { mutableLongStateOf(activity.intent.getLongExtra("widget_subject_id", 0L)) }
    val screen = Screen.valueOf(screenName)
    var search by rememberSaveable { mutableStateOf("") }
    var showHomeAdd by remember { mutableStateOf(false) }
    var showHomeColors by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showAppLock by remember { mutableStateOf(false) }
    var showBackupRecovery by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var homeEditRequest by remember { mutableIntStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }
    var showScheduleSettings by remember { mutableStateOf(false) }
    var showScheduleManager by remember { mutableStateOf(false) }
    var showScheduleDetails by remember { mutableStateOf(false) }
    var settingsModule by remember { mutableStateOf<String?>(null) }
    var settingsParent by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleFullscreen by rememberSaveable { mutableStateOf(false) }
    var subjectPageId by rememberSaveable { mutableLongStateOf(0L) }
    var subjectPageMode by rememberSaveable { mutableIntStateOf(0) }
    var subjectOpenedFile by rememberSaveable { mutableStateOf("") }
    var aiBubbleOpen by rememberSaveable { mutableStateOf(false) }
    var academicsToolRequest by remember { mutableStateOf<String?>(null) }

    BackHandler {
        when {
            aiBubbleOpen -> aiBubbleOpen = false
            subjectPageId != 0L && subjectOpenedFile.isNotBlank() -> subjectOpenedFile = ""
            subjectPageId != 0L -> { subjectPageId = 0L; subjectOpenedFile = "" }
            showScheduleDetails -> showScheduleDetails = false
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
            screen == Screen.CAMPUS_AI -> screenName = Screen.ACADEMICS.name
            screen != Screen.HOME -> screenName = Screen.HOME.name
        }
    }

    if (locked) { LockScreen(store) { locked = false }; return }

    LaunchedEffect(widgetSubjectId) {
        if (widgetSubjectId > 0L && store.get("subjects").any { it.id == widgetSubjectId }) {
            subjectPageId = widgetSubjectId
            subjectPageMode = 0
            widgetSubjectId = 0L
        }
    }

    // Light mode is the only supported app theme now.
    val dark = false

    var subjectPageVisible by remember(subjectPageId, subjectPageMode, subjectOpenedFile) { mutableStateOf(false) }
    LaunchedEffect(subjectPageId, subjectPageMode, subjectOpenedFile) {
        subjectPageVisible = false
        delay(20)
        subjectPageVisible = true
    }

    val authPrefs = remember { activity.getSharedPreferences("campusos_auth", Context.MODE_PRIVATE) }
    var campusSession by remember {
        mutableStateOf(authPrefs.getString("token", null)?.let {
            CampusSession(
                it,
                authPrefs.getString("uid", "") ?: "",
                authPrefs.getString("email", "") ?: "",
                authPrefs.getString("role", "student") ?: "student"
            )
        })
    }

    LaunchedEffect(campusSession?.userId) {
        campusSession?.let { current ->
            runCatching { CampusNativeApi.loadRole(current) }.onSuccess { fresh ->
                campusSession = fresh
                authPrefs.edit().putString("role", fresh.role).apply()
            }
        }
    }

    val colorScheme = campusColorScheme(appearancePreset, dark)

    MaterialTheme(colorScheme = colorScheme, shapes = CampusShapes) {
        if (subjectPageId != 0L) {
            AnimatedVisibility(
                visible = subjectPageVisible,
                enter = fadeIn(animationSpec = tween(180)),
                label = "subject page transition"
            ) {
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
            }
        } else {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (!scheduleFullscreen && screen != Screen.CAMPUS_AI) {
                    TopAppBar(
                        title = { Text("CampusOS", fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { screenName = Screen.CHAT.name }) {
                                Icon(Icons.Default.Chat, "Private group chats")
                            }
                            IconButton(onClick = { screenName = Screen.ANNOUNCEMENTS.name }) {
                                Icon(Icons.Default.Campaign, "Announcements")
                            }
                            if (screen == Screen.SCHEDULE) {
                                IconButton(onClick = { showScheduleDetails = true }) {
                                    Icon(Icons.Default.Info, "Subject details")
                                }
                            }
                            if (campusSession?.role?.equals("admin", true) == true || campusSession?.role?.equals("leader", true) == true) {
                                IconButton(onClick = { screenName = Screen.ADMIN.name }) {
                                    Icon(Icons.Default.AdminPanelSettings, "Campus management")
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
                if (!scheduleFullscreen && screen != Screen.CAMPUS_AI) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                                label = { Text(it.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            },
            floatingActionButton = { }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(padding)
                    .imePadding()
            ) {
                Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = screenName,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(180)) togetherWith
                            fadeOut(animationSpec = tween(120))
                    },
                    label = "CampusOS screen transition"
                ) { targetScreen ->
                    when (Screen.valueOf(targetScreen)) {
                        Screen.HOME -> HomeScreen(store, { screenName = it.name }, homeEditRequest)
                        Screen.CAMPUS_AI -> CampusAiScreen(activity, store) { screenName = Screen.ACADEMICS.name }
                        Screen.CHAT -> {
                            if (campusSession == null) {
                                NativeLoginScreen { session ->
                                    authPrefs.edit()
                                        .putString("token", session.accessToken)
                                        .putString("uid", session.userId)
                                        .putString("email", session.email)
                                        .putString("role", session.role)
                                        .apply()
                                    campusSession = session
                                }
                            } else {
                                NativeChatScreen()
                            }
                        }
                        Screen.ANNOUNCEMENTS -> {
                            if (campusSession == null) {
                                NativeLoginScreen { session ->
                                    authPrefs.edit()
                                        .putString("token", session.accessToken)
                                        .putString("uid", session.userId)
                                        .putString("email", session.email)
                                        .putString("role", session.role)
                                        .apply()
                                    campusSession = session
                                }
                            } else {
                                NativeAnnouncementsScreen()
                            }
                        }
                        Screen.ADMIN -> {
                            if (campusSession == null || (campusSession?.role?.lowercase() != "admin" && campusSession?.role?.lowercase() != "leader")) {
                                screenName = Screen.HOME.name
                            } else {
                                NativeCampusManagementScreen(campusSession!!)
                            }
                        }
                        Screen.SCHEDULE -> ScheduleScreen(store, search, scheduleFullscreen, { scheduleFullscreen = it }, { showScheduleDetails = true }) { search = "" }
                        Screen.TASKS -> TasksScreen(store, search, { search = "" }, { id -> subjectPageId = id; subjectPageMode = 0 })
                        Screen.ACADEMICS -> AcademicsScreen(store, search, { search = "" }, { subjectPageId = it.id; subjectPageMode = 0 }, { subjectPageId = it.id; subjectPageMode = 1 }, { screenName = Screen.CAMPUS_AI.name }, academicsToolRequest, { academicsToolRequest = null })
                        Screen.FILES -> FilesScreen()
                        Screen.SETTINGS -> SettingsScreen(
                            store, theme,
                            { theme = it; store.setTheme(it) },
                            { locked = true },
                            { showScheduleSettings = true },
                            { showScheduleManager = true },
                            { screenName = Screen.CAMPUS_AI.name }
                        )
                    }
                }
                if (!scheduleFullscreen && screen != Screen.HOME && screen != Screen.SETTINGS && screen != Screen.FILES && screen != Screen.TASKS && screen != Screen.CAMPUS_AI) {
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
                if (store.aiFloatingEnabled() && !scheduleFullscreen && screen != Screen.CAMPUS_AI) {
                    if (aiBubbleOpen) {
                        Box(Modifier.fillMaxSize().padding(bottom = 8.dp, end = 8.dp), contentAlignment = Alignment.BottomEnd) {
                            CampusAiBubble(activity, store, onModuleChanged = { toolName ->
                                if (store.aiOpenChangedModule()) {
                                    when {
                                        toolName.contains("schedule") -> screenName = Screen.SCHEDULE.name
                                        toolName.contains("task") -> screenName = Screen.TASKS.name
                                        toolName.contains("subject") || toolName.contains("note") || toolName.contains("lecture_file") -> { screenName = Screen.ACADEMICS.name; academicsToolRequest = "subjects" }
                                        toolName.contains("budget") || toolName.contains("saving") -> { screenName = Screen.ACADEMICS.name; academicsToolRequest = "budget" }
                                    }
                                    aiBubbleOpen = false
                                }
                            }) { aiBubbleOpen = false }
                        }
                    }
                    CampusAiFloatingButton(store) { aiBubbleOpen = !aiBubbleOpen }
                }
            }
                if (showHomeSettings) {
                HomeSettingsDialog(store, campusSession?.role ?: "student", onManagement = { screenName = Screen.ADMIN.name; showHomeSettings = false }, onModule = { settingsModule = it; settingsParent = null; showHomeSettings = false }, onAppearance = { showHomeColors = true; settingsParent = null; showHomeSettings = false }, onAiSettings = { showAiSettings = true; showHomeSettings = false }, onLockNow = { locked = true }, done = { showHomeSettings = false }, openAppLock = { showHomeSettings = false; showAppLock = true }, openBackupRecovery = { showHomeSettings = false; showBackupRecovery = true }, openAbout = { showHomeSettings = false; showAbout = true })
            }
            if (showAiSettings) CampusAiSettingsDialog(store, { showAiSettings = false }, { showAiSettings = false; screenName = Screen.CAMPUS_AI.name })
            if (showAbout) AboutDialog { showAbout = false }
            if (showHomeAdd) ScheduleDialog(store) { showHomeAdd = false }
            if (showHomeColors) HomeAppearanceDialog(store, theme, { theme = it; store.setTheme(it) }, appearancePreset, { appearancePreset = it; store.setAppearancePreset(it) }) { showHomeColors = false }
            if (showProfile) ProfileDialog(store) { showProfile = false }
            if (showScheduleSettings) ScheduleSettingsDialog(store) { showScheduleSettings = false }
            if (showScheduleManager) ScheduleManagerDialog(store) { showScheduleManager = false }
            settingsModule?.let { module -> ModuleSettingsDialog(module, { settingsModule = null; settingsParent = null; showHomeSettings = true }, { settingsParent = module; settingsModule = null; showProfile = true }, { settingsParent = module; settingsModule = null; showScheduleManager = true }, { settingsParent = module; settingsModule = null; showScheduleSettings = true }, { settingsParent = module; settingsModule = null; showHomeAdd = true }, { settingsModule = null; settingsParent = null; screenName = Screen.TASKS.name }, { settingsModule = null; settingsParent = null; screenName = Screen.ACADEMICS.name }, { settingsModule = null; settingsParent = null; showHomeSettings = false; homeEditRequest++ }) }
            if (showScheduleDetails) SubjectDetailsDialog(store.get("schedule"), { showScheduleDetails = false })
            if (showAppLock) AppLockSettingsDialog(store, { showAppLock = false }, { locked = true })
            if (showBackupRecovery) BackupRecoverySettingsDialog(store, { showBackupRecovery = false })
        }
        }
    }
}private fun iconFor(s: Screen) = when(s) {
    Screen.HOME -> Icons.Default.Home
    Screen.CHAT -> Icons.Default.Chat
    Screen.ANNOUNCEMENTS -> Icons.Default.Campaign
    Screen.SCHEDULE -> Icons.Default.CalendarMonth
    Screen.TASKS -> Icons.Default.CheckCircle
    Screen.ACADEMICS -> Icons.Default.School
    Screen.CAMPUS_AI -> Icons.Default.AutoAwesome
    Screen.FILES -> Icons.Default.Folder
    Screen.ADMIN -> Icons.Default.AdminPanelSettings
    Screen.SETTINGS -> Icons.Default.Settings
}

@Composable
fun HomeSettingsDialog(
    store: LocalStore,
    campusRole: String,
    onManagement: () -> Unit,
    onModule:(String)->Unit,
    onAppearance:()->Unit,
    onAiSettings:()->Unit,
    onLockNow:()->Unit,
    done:()->Unit,
    openAppLock:()->Unit,
    openBackupRecovery:()->Unit,
    openAbout:()->Unit
) {
    Dialog(onDismissRequest = done) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CampusOS Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Manage each part of the app in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = done) { Icon(Icons.Default.Close, "Close") }
                }
                SettingsGroupTitle("CampusOS")
                if (campusRole.equals("admin", true) || campusRole.equals("leader", true)) {
                    SettingsRow(Icons.Default.AdminPanelSettings, "Campus Management", if (campusRole.equals("admin", true)) "Admin controls, accounts and leaders" else "Leader position and organization access") { onManagement() }
                }
                SettingsRow(Icons.Default.Person, "Profile", "Name, student ID, section and photo") { onModule("Homepage") }
                SettingsRow(Icons.Default.Home, "Homepage", "Dashboard layout and home tiles") { onModule("Homepage") }
                SettingsRow(Icons.Default.CalendarMonth, "Schedule", "Class days, timetable and class editing") { onModule("Class Schedule") }
                SettingsRow(Icons.Default.EventNote, "Notes", "Calendar, notes and reminders") { onModule("Notes") }
                SettingsRow(Icons.Default.Build, "Tools", "Campus AI, Notepad, Calculator and Budget") { onModule("Tools") }
                SettingsRow(Icons.Default.AutoAwesome, "CampusOS AI", "AI chat, floating button, memory, history and permissions") { onAiSettings() }
                SettingsGroupTitle("App")
                SettingsRow(Icons.Default.Notifications, "Notifications", "Next-class and deadline reminders") { onModule("Notifications") }
                SettingsRow(Icons.Default.Palette, "Appearance", "Light mode and color combinations") { onAppearance() }
                SettingsRow(Icons.Default.Backup, "Data & Backup", "Backup or recover selected modules") { openBackupRecovery() }
                SettingsRow(Icons.Default.Lock, "App Lock", "PIN or pattern protection") { openAppLock() }
                SettingsRow(Icons.Default.Info, "About", "Features, modules and offline functions") { openAbout() }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = done, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CampusAiSettingsDialog(store: LocalStore, done: () -> Unit, openAi: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val chatStore = remember { AiChatStore(context) }
    var enabled by remember { mutableStateOf(store.aiFloatingEnabled()) }
    var opacity by remember { mutableFloatStateOf(store.aiFloatingOpacity()) }
    var buttonSize by remember { mutableFloatStateOf(store.aiFloatingSize()) }
    var bubbleSize by remember { mutableFloatStateOf(store.aiBubbleSize()) }
    var aiChatBackground by remember { mutableStateOf(chatStore.chatBackground()) }
    var permission by remember { mutableStateOf(store.aiActionPermission()) }
    var openChangedModule by remember { mutableStateOf(store.aiOpenChangedModule()) }
    var historyEnabled by remember { mutableStateOf(chatStore.historyEnabled()) }
    var recallEnabled by remember { mutableStateOf(chatStore.recallEnabled()) }
    var historyLimit by remember { mutableIntStateOf(chatStore.historyLimit()) }
    var historyDays by remember { mutableIntStateOf(chatStore.historyDays()) }
    var confirmClear by remember { mutableStateOf(false) }
    fun limitLabel(v: Int) = if (v == 0) "Unlimited" else "${v} conversations"
    fun daysLabel(v: Int) = if (v == 0) "Forever" else if (v == 1) "1 day" else "${v} days"

    Dialog(onDismissRequest = done) {
        Surface(Modifier.fillMaxWidth().padding(10.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CampusOS AI Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Control the floating AI, chat memory, history and what AI is allowed to do.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = done) { Icon(Icons.Default.Close, "Close") }
                }

                SettingsGroupTitle("Floating AI")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating AI button", fontWeight = FontWeight.SemiBold)
                        Text("Show the AI button over CampusOS screens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(enabled, { enabled = it; store.setAiFloatingEnabled(it) })
                }
                Text("Icon transparency: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(opacity, { opacity = it; store.setAiFloatingOpacity(it) }, valueRange = 0.30f..1f, steps = 13)
                Text("Icon size: ${buttonSize.roundToInt()} dp", style = MaterialTheme.typography.labelLarge)
                Slider(buttonSize, { buttonSize = it; store.setAiFloatingSize(it) }, valueRange = 44f..76f, steps = 7)
                Text("Floating chat size: ${bubbleSize.roundToInt()}% of screen width", style = MaterialTheme.typography.labelLarge)
                Slider(bubbleSize, { bubbleSize = it; store.setAiBubbleSize(it) }, valueRange = 70f..96f, steps = 12)
                Text("AI chat background", style = MaterialTheme.typography.labelLarge)
                Text("Choose the background used by the CampusOS AI chat and AI replies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("default" to "Default", "primary" to "Primary", "secondary" to "Secondary", "tertiary" to "Tertiary", "neutral" to "Neutral").forEach { (value, label) ->
                        FilterChip(selected = aiChatBackground == value, onClick = { aiChatBackground = value; chatStore.setChatBackground(value) }, label = { Text(label) })
                    }
                }
                OutlinedButton({ store.resetAiFloatingPosition() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text("Reset floating button position")
                }
                OutlinedButton(openAi, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SmartToy, null); Spacer(Modifier.width(8.dp)); Text("Open CampusOS AI & Provider/Model Settings")
                }

                SettingsGroupTitle("AI permissions")
                Text("Choose how much control CampusOS AI has over supported local CampusOS data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("ask" to "Always ask", "read" to "Allow read actions", "all" to "Allow all actions").forEach { (value, label) ->
                    FilterChip(selected = permission == value, onClick = { permission = value; store.setAiActionPermission(value) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                }
                Text(when (permission) {
                    "all" -> "AI can read and change supported schedule, tasks, subjects, notes, lecture files, budget and calculator data without an approval prompt."
                    "read" -> "AI can read supported CampusOS data and calculate. Any change still requires approval."
                    else -> "AI asks before every supported CampusOS data action."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Open changed module", fontWeight = FontWeight.SemiBold)
                        Text("After AI changes data, open the related Schedule, Notes, or Tools page automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(openChangedModule, { openChangedModule = it; store.setAiOpenChangedModule(it) })
                }

                SettingsGroupTitle("Chat memory & history")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save chat history", fontWeight = FontWeight.SemiBold)
                        Text("Keep conversations locally on this phone so they can be searched and reopened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(historyEnabled, { historyEnabled = it; chatStore.setHistoryEnabled(it) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use past chats for recall", fontWeight = FontWeight.SemiBold)
                        Text("Use relevant saved chat snippets for a new question. The whole history is not sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(recallEnabled, { recallEnabled = it; chatStore.setRecallEnabled(it) })
                }
                Text("Maximum saved conversations: ${limitLabel(historyLimit)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = historyLimit.toFloat(), onValueChange = { historyLimit = (it / 10f).roundToInt() * 10; chatStore.setHistoryLimit(historyLimit) }, valueRange = 0f..200f, steps = 19)
                Text("0 = unlimited", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Delete chats older than: ${daysLabel(historyDays)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = historyDays.toFloat(), onValueChange = { historyDays = (it / 7f).roundToInt() * 7; chatStore.setHistoryDays(historyDays) }, valueRange = 0f..3650f, steps = 20)
                Text("0 = keep forever. History and attachments stay in local app storage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ confirmClear = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear all AI chat history")
                }

                SettingsGroupTitle("Privacy & safety")
                Text("CampusOS AI can only use the CampusOS tools exposed to it. API keys remain in the app's secure AI settings and are not part of chat history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("For the safest setup, use “Always ask” so changes require your approval.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = done, Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear AI chat history?") },
        text = { Text("This permanently removes saved CampusOS AI conversations and their local attachments from this phone.") },
        confirmButton = { Button({ chatStore.deleteAll(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } }
    )
}

@Composable
fun AboutDialog(done: () -> Unit) {
    AlertDialog(onDismissRequest = done, title = { Text("About CampusOS") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CampusOS 1.0.0", fontWeight = FontWeight.Bold)
            Text("A student-focused offline-first planner for everyday school work.")
            Text("Included functions", fontWeight = FontWeight.SemiBold)
            listOf("Homepage dashboard and profile","Class Schedule with Lecture/Lab editing","Notes, calendar and reminders","Tools: Notepad, Calculator and Student Budget","Subject Notepad and Lecture Files","Appearance presets, light/dark mode and app lock","Local backup and recovery by module","Home-screen widgets for classes and tasks").forEach { Text("• $it") }
            Text("This About page lists local app functions only and intentionally does not describe online or campus-network functions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(done) { Text("Done") } })
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
        HorizontalDivider();Text("Modules",fontWeight=FontWeight.Bold);Text("Homepage • Class Schedule • Notes • Subjects / Lessons",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("Backups use the CampusOS JSON format. Recovery can restore only the modules you select.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(done){Text("Done")}})
    if(showBackup)ModuleBackupDialog("Choose modules to backup",selectedModules,{selectedModules=it}){showBackup=false;if(selectedModules.isNotEmpty())backup.launch("CampusOS-backup.json")}
    if(showRecover)ModuleBackupDialog("Choose modules to recover",selectedModules,{selectedModules=it}){showRecover=false;if(selectedModules.isNotEmpty())restore.launch(arrayOf("application/json","text/plain"))}
}
@Composable
fun ModuleSettingsDialog(module:String,close:()->Unit,profile:()->Unit,scheduleManager:()->Unit,scheduleSettings:()->Unit,addClass:()->Unit,openTasks:()->Unit,openAcademics:()->Unit,editHome:()->Unit) {
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
            "Class Schedule"->{Text("Class Schedule controls",fontWeight=FontWeight.Bold);OutlinedButton(addClass,Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Add Class")};OutlinedButton(scheduleManager,Modifier.fillMaxWidth()){Icon(Icons.Default.EditCalendar,null);Spacer(Modifier.width(8.dp));Text("Edit / Delete Classes")};OutlinedButton(scheduleSettings,Modifier.fillMaxWidth()){Icon(Icons.Default.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text("Schedule Settings")};}
            "Notes"->{Text("Notes controls",fontWeight=FontWeight.Bold);Text("Calendar, notes, reminders and deadlines are stored locally.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openTasks,Modifier.fillMaxWidth()){Icon(Icons.Default.EventNote,null);Spacer(Modifier.width(8.dp));Text("Open Notes")};OutlinedButton({ showTaskSettings = true },Modifier.fillMaxWidth()){Icon(Icons.Default.Settings,null);Spacer(Modifier.width(8.dp));Text("Notes Settings")}}
            "Tools"->{Text("Tools controls",fontWeight=FontWeight.Bold);Text("Campus AI, Notepad, Calculator and Budget are available from Tools.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openAcademics,Modifier.fillMaxWidth()){Icon(Icons.Default.Build,null);Spacer(Modifier.width(8.dp));Text("Open Tools")};OutlinedButton({ showTaskSettings = true },Modifier.fillMaxWidth()){Icon(Icons.Default.SwapVert,null);Spacer(Modifier.width(8.dp));Text("Tool Order")}}
        }
    }},confirmButton={TextButton(close){Text("Close")}})
    if (showTaskSettings) { if (module == "Tools") ToolOrderDialog(homeStore) { showTaskSettings = false } else TaskSettingsDialog(LocalStore(androidx.compose.ui.platform.LocalContext.current)) { showTaskSettings = false } }
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
    val todayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    val todaySchedule = remember(schedule, todayName) { mergeTodayClasses(schedule.filter { it.day.equals(todayName, true) }) }
    val pendingTasks = remember(tasks) {
        tasks.filter { !it.done }.sortedWith(compareBy<Record>({ it.dueDate.ifBlank { "9999-99-99" } }, { it.dueTime }, { it.title.lowercase(Locale.getDefault()) })).take(4)
    }
    val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val dueToday = remember(tasks, todayKey) { tasks.filter { !it.done && it.dueDate == todayKey } }
    val photo = remember(photoPath, revision) { if (photoPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(photoPath) }.getOrNull() else null }

    LaunchedEffect(editRequest) { if (editRequest > 0) { /* existing editor entry point remains available from settings */ } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp)) {
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (photo != null) Image(photo.asImageBitmap(), "Profile photo", Modifier.size(62.dp), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(62.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(profileName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Good " + if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) "morning" else "day", style = MaterialTheme.typography.labelLarge)
                        Text(profileName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        val details = listOf(studentId, section).filter { it.isNotBlank() }.joinToString(" • ")
                        if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodyMedium)
                        Text(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        item {
            Text("Next class", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            HomeClassesTile(todaySchedule)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Today's tasks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (dueToday.isNotEmpty()) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(dueToday.size.toString() + " due", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (pendingTasks.isEmpty()) EmptyCard("No pending tasks. You're all caught up.")
            else pendingTasks.forEach { task ->
                Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventNote, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (task.subtitle.isNotBlank()) Text(task.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            if (task.dueDate.isNotBlank()) Text("Due " + task.dueDate + if (task.dueTime.isNotBlank()) " • " + task.dueTime else "", style = MaterialTheme.typography.labelSmall, color = if (task.dueDate == todayKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) { Text("Subjects", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(subjects.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(todaySchedule.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Text("Pending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(tasks.count { !it.done }.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun HomeQuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
    Card(campusTileModifier(Modifier.fillMaxWidth()),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=if(completed)MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(r.title,Modifier.weight(1f),fontWeight=FontWeight.Bold)
                if(status!=null)Text(status,fontWeight=FontWeight.SemiBold,color=if(completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    AlertDialog(onDismissRequest=done,title={Text("Schedule Settings")},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
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
fun ToolOrderDialog(store: LocalStore, done: () -> Unit) {
    var order by remember { mutableStateOf(store.toolOrder()) }
    AlertDialog(onDismissRequest = done, title = { Text("Tool Order") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the order of Campus AI, Notepad, Calculator and Budget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            order.forEachIndexed { index, key ->
                val label = when (key) { "campus_ai" -> "Campus AI"; "subjects" -> "Notepad"; "calculator" -> "Calculator"; else -> "Budget" }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((index + 1).toString(), Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(enabled = index > 0, onClick = { order = order.toMutableList().also { v -> val x = v[index-1]; v[index-1] = v[index]; v[index] = x } }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = index < order.lastIndex, onClick = { order = order.toMutableList().also { v -> val x = v[index+1]; v[index+1] = v[index]; v[index] = x } }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                }
            }
        }
    }, confirmButton = { Button({ store.setToolOrder(order); done() }) { Text("Save") } }, dismissButton = { TextButton(done) { Text("Cancel") } })
}
@Composable
fun HomeAppearanceDialog(store: LocalStore, theme: String, setTheme: (String) -> Unit, appearancePreset: String, setAppearancePreset: (String) -> Unit, done: () -> Unit) {
    val presets = listOf("forest" to "Forest","ocean" to "Ocean","mint" to "Mint","sky" to "Sky","lavender" to "Lavender","plum" to "Plum","rose" to "Rose","coral" to "Coral","sunset" to "Sunset","amber" to "Amber","teal" to "Teal","mono" to "Monochrome")
    AlertDialog(
        onDismissRequest=done,
        title={Text("Appearance")},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text("Color appearance",fontWeight=FontWeight.SemiBold)
                Text("Choose a ready-made combination. It changes the whole CampusOS UI: background, surfaces, text, buttons, tiles, cards and schedule accents.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                presets.forEach{(id,label)->
                    val selected=appearancePreset==id
                    Card(Modifier.fillMaxWidth().clickable{setAppearancePreset(id)},colors=CardDefaults.cardColors(containerColor=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)){
                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.size(42.dp).background(when(id){"forest"->Color(0xFF176B3A);"sunset"->Color(0xFFB3261E);"ocean"->Color(0xFF006A6A);"mint"->Color(0xFF2E7D5B);"sky"->Color(0xFF2674C8);"lavender"->Color(0xFF6750A4);"plum"->Color(0xFF7A4E9B);"rose"->Color(0xFF9C4168);"coral"->Color(0xFFC85A4A);"amber"->Color(0xFF9A6700);"teal"->Color(0xFF00796B);"mono"->Color(0xFF3F4650);else->Color(0xFF176B3A)},RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){
                                Text(label,fontWeight=FontWeight.SemiBold)
                                Text(when(id){"forest"->"Green + teal";"ocean"->"Teal + blue";"mint"->"Fresh green + aqua";"sky"->"Sky blue + slate";"lavender"->"Purple + soft pink";"plum"->"Plum + lilac";"rose"->"Rose + plum";"coral"->"Coral + cream";"sunset"->"Warm red + gold";"amber"->"Amber + sand";"teal"->"Teal + mint";"mono"->"Neutral gray";else->"Green + teal"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(selected)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton={Button(done){Text("Done")}}
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
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dayPage by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { nowTick = System.currentTimeMillis(); delay(30_000) } }
    LaunchedEffect(query) { if (query == "__ADD__") showAdd = true }

    val all = remember(refresh, revision, query) {
        store.get("schedule").filter {
            query.isBlank() || query == "__ADD__" ||
                (it.title + " " + it.subtitle + " " + it.extra + " " + it.day + " " + it.room + " " + it.professor).contains(query, true)
        }
    }
    val scheduleDays = store.scheduleDays().distinct()
    val startHour = store.scheduleStartHour().coerceIn(0, 23)
    val endHour = store.scheduleEndHour().coerceIn(startHour + 1, 24)
    val hours = (startHour until endHour).toList()
    val calendar = remember(nowTick) { Calendar.getInstance() }
    val today = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    val dayOrder = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
    fun dayIndex(name: String) = dayOrder.indexOfFirst { it.equals(name, true) }.let { if (it < 0) 99 else it }
    val todayIndex = dayIndex(today)
    val orderedDays = remember(scheduleDays, today) {
        scheduleDays.sortedWith(compareBy<String> {
            val idx = dayIndex(it)
            when { idx == todayIndex -> 0; idx > todayIndex -> idx - todayIndex; else -> 7 + idx - todayIndex }
        })
    }
    LaunchedEffect(orderedDays.size) { dayPage = dayPage.coerceIn(0, ((orderedDays.size - 1).coerceAtLeast(0) / 3)) }
    val pageCount = ((orderedDays.size - 1).coerceAtLeast(0) / 3) + 1
    val pageDays = if (orderedDays.size <= 3) orderedDays else orderedDays.drop((dayPage * 3).coerceAtMost(orderedDays.size - 1)).take(3)
    val visibleDays = selectedDay?.let { selected -> orderedDays.firstOrNull { it.equals(selected, true) }?.let { listOf(it) } } ?: pageDays
    val dayHighlight = Color(store.scheduleDayHighlight())
    val timeHighlight = Color(store.scheduleTimeHighlight())
    val tableFontSize = remember(revision) { store.scheduleTableFontSize() }
    val customDayWidth = remember(revision) { store.scheduleTableDayWidth() }
    val customRowHeight = remember(revision) { store.scheduleTableRowHeight() }
    var zoom by remember(revision) { mutableFloatStateOf(1f) }

    fun changePage(delta: Int) { if (orderedDays.size > 3) dayPage = (dayPage + delta).coerceIn(0, pageCount - 1) }

    Column(Modifier.fillMaxSize().pointerInput(orderedDays, dayPage) {
        var dragTotal = 0f
        detectHorizontalDragGestures(
            onHorizontalDrag = { _, amount -> dragTotal += amount },
            onDragEnd = { if (dragTotal < -70f) changePage(1) else if (dragTotal > 70f) changePage(-1); dragTotal = 0f },
            onDragCancel = { dragTotal = 0f }
        )
    }) {
        if (orderedDays.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { EmptyCard("No class days configured yet. Open Schedule Settings to choose your class days.") }
        } else {
            if (store.scheduleTableHorizontalScroll() || store.scheduleTableVerticalScroll()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom " + zoom.toInt() + "x", style = MaterialTheme.typography.labelSmall)
                    TextButton({ zoom = (zoom - .25f).coerceAtLeast(1f) }) { Text("−") }
                    TextButton({ zoom = (zoom + .25f).coerceAtMost(3f) }) { Text("+") }
                    TextButton({ zoom = 1f }) { Text("Reset") }
                }
            }
            AnimatedContent(targetState = dayPage, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "schedule day transition") {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)) {
                    val baseDayWidth = if (customDayWidth > 0f) customDayWidth.dp else (maxWidth - 56.dp).coerceAtLeast(0.dp) / visibleDays.size.coerceAtLeast(1)
                    val dayWidth = baseDayWidth * zoom
                    val headerHeight = 38.dp
                    val baseRowHeight = if (customRowHeight > 0f) customRowHeight.dp else ((maxHeight - headerHeight).coerceAtLeast(0.dp) / hours.size.coerceAtLeast(1)).coerceAtLeast(38.dp)
                    val rowHeight = baseRowHeight * zoom
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Row(Modifier.height(headerHeight)) {
                            Box(Modifier.width(56.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { Text("Time", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp)) }
                            visibleDays.forEach { day ->
                                val isToday = day.equals(today, true)
                                Box(
                                    Modifier.width(dayWidth).fillMaxHeight()
                                        .background(Color.Transparent)
                                        .clickable {
                                            selectedDay = if (selectedDay?.equals(day, true) == true) null else day
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (isToday) Box(Modifier.size(6.dp).background(dayHighlight, RoundedCornerShape(50)))
                                        Text(day, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        hours.forEach { hour ->
                            val rowStart = hour * 60
                            Row(Modifier.height(rowHeight)) {
                                Box(Modifier.width(56.dp).fillMaxHeight().background(Color.Transparent), contentAlignment = Alignment.Center) { Text(formatHourRange(hour, hour + 1), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp)) }
                                visibleDays.forEach { day ->
                                    val cell = all.filter { it.day.equals(day, true) && it.startTime.toHourOrNull() == hour }
                                    val groups = cell.groupBy { it.title.trim().uppercase(Locale.getDefault()) }.values.take(2)
                                    val isCurrent = day.equals(today, true) && groups.any { g -> val r = g.first(); val st = r.startTime.toMinutesOrNull() ?: -1; val en = r.endTime.toMinutesOrNull() ?: -1; currentMinutes in st until en }
                                    val isPassed = day.equals(today, true) && groups.isNotEmpty() && groups.all { g -> (g.maxOfOrNull { it.endTime.toMinutesOrNull() ?: -1 } ?: -1) <= currentMinutes }
                                    Box(Modifier.width(dayWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.18f)).padding(2.dp)) {
                                        if (groups.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("—", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.labelSmall) }
                                        else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            groups.forEach { group ->
                                                val r = group.first()
                                                val lab = r.classType.equals("Lab", true)
                                                // Always use the active CampusOS appearance; legacy subject colors are ignored.
                                                val typeContainer = if (lab) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                                                val contentAlpha = if (isPassed) .62f else 1f
                                                Card(Modifier.fillMaxWidth().weight(1f, fill = false), colors = CardDefaults.cardColors(containerColor = typeContainer.copy(alpha = contentAlpha)), shape = RoundedCornerShape(8.dp)) {
                                                    Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 3.dp), verticalArrangement = Arrangement.Center) {
                                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                            Text(r.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall.copy(fontSize = tableFontSize.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = contentAlpha })
                                                            Text(
                                                                if (r.room.isNotBlank()) r.room + " " + if (lab) "LAB" else "LEC" else if (lab) "LAB" else "LEC",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = (tableFontSize - 1).coerceAtLeast(8f).sp),
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = if (lab) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                                modifier = Modifier.graphicsLayer { alpha = contentAlpha }
                                                            )
                                                        }
                                                        if (isCurrent) {
                                                            val st = r.startTime.toMinutesOrNull() ?: rowStart
                                                            val en = r.endTime.toMinutesOrNull() ?: rowStart + 60
                                                            LinearProgressIndicator(progress = { ((currentMinutes - st).toFloat() / (en - st).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
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
        }
    }
    if (showAdd) ScheduleDialog(store) { showAdd = false; clear(); refresh++ }
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
                Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(r.title,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                        if(r.subtitle.isNotBlank()) Text(r.subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(r.professor.isNotBlank()) Text("Professor • "+r.professor,fontWeight=FontWeight.SemiBold)
                        if(lecture.isNotEmpty()) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.MenuBook,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Lecture",fontWeight=FontWeight.Bold) }
                            lecture.forEach { c -> Card(campusTileModifier(Modifier.fillMaxWidth()),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(c.day.take(3)+" • "+c.startTime+"–"+c.endTime,fontWeight=FontWeight.SemiBold); if(c.room.isNotBlank()) Text("Room "+c.room,style=MaterialTheme.typography.bodySmall) } } }
                        }
                        if(lab.isNotEmpty()) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Science,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Lab",fontWeight=FontWeight.Bold) }
                            lab.forEach { c -> Card(campusTileModifier(Modifier.fillMaxWidth()),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(c.day.take(3)+" • "+c.startTime+"–"+c.endTime,fontWeight=FontWeight.SemiBold); if(c.room.isNotBlank()) Text("Room "+c.room,style=MaterialTheme.typography.bodySmall) } } }
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
    var selectedSlots by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var removedSlots by remember { mutableStateOf<Set<String>>(emptySet()) }
    var originalSubject by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

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
                                    originalSubject = code
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
                                            .background(if (selectedType != null) MaterialTheme.colorScheme.primaryContainer else if (sameSubject != null) MaterialTheme.colorScheme.secondaryContainer else if (otherSubject != null) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
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
                        Record(id = idBase + index, title = normalizedSubject, subtitle = fullName.trim(), extra = notes.trim(), day = day, startTime = "%02d:00".format(hour), endTime = "%02d:00".format(hour + 1), room = if (type == "Lab") labRoom.trim() else lectureRoom.trim(), professor = professor.trim(), color = 0L, classType = type)
                    }
                    val oldSubject = originalSubject.trim()
                    val withoutEditedSubject = existing.filterNot {
                        (oldSubject.isNotBlank() && it.title.trim().equals(oldSubject, true)) ||
                            it.title.trim().equals(normalizedSubject, true)
                    }
                    store.put("schedule", withoutEditedSubject + newRecords)
                    if (newRecords.isEmpty()) {
                        store.put("subjects", store.get("subjects").filterNot {
                            it.title.trim().equals(normalizedSubject, true) ||
                                (oldSubject.isNotBlank() && it.title.trim().equals(oldSubject, true))
                        })
                    } else {
                        val subjects = store.get("subjects").toMutableList()
                        if (oldSubject.isNotBlank() && !oldSubject.equals(normalizedSubject, true) &&
                            store.get("schedule").none { it.title.trim().equals(oldSubject, true) }) {
                            subjects.removeAll { it.title.trim().equals(oldSubject, true) }
                        }
                        store.put("subjects", subjects)
                        syncSubjectsFromSchedule(store, store.get("schedule"))
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
                                    Box(Modifier.width(86.dp).height(52.dp).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).background(if (selected) MaterialTheme.colorScheme.primaryContainer else if (occupied) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface).clickable { selectedSlot = key }, contentAlignment = Alignment.Center) {
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
                    if (!record.title.equals(updated.title, true)) {
                        val subjects = store.get("subjects").toMutableList()
                        val oldIndex = subjects.indexOfFirst { it.title.trim().equals(record.title.trim(), true) }
                        if (oldIndex >= 0) {
                            subjects[oldIndex] = subjects[oldIndex].copy(
                                title = updated.title.trim(), subtitle = updated.subtitle.trim(),
                                extra = updated.extra.trim(), room = updated.room.trim(),
                                professor = updated.professor.trim(), classType = updated.classType
                            )
                            store.put("subjects", subjects)
                        }
                    }
                    store.put(
                        "schedule",
                        cleaned.map { if (it.id == record.id) updated else it } +
                            if (cleaned.none { it.id == record.id }) listOf(updated) else emptyList()
                    )
                    syncSubjectsFromSchedule(store, store.get("schedule"))
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

private fun normalizeScheduleSubjectCode(raw: String): String {
    return raw.trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*[-–—]\\s*(lecture|lec|laboratory|lab)\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()
}

private fun syncSubjectsFromSchedule(store: LocalStore, schedule: List<Record>) {
    // Schedule is the source of truth. Every distinct subject code in the schedule
    // must have exactly one corresponding Subjects record.
    val validClasses = schedule.mapNotNull { record ->
        val code = normalizeScheduleSubjectCode(record.title)
        if (code.isBlank()) null else record to code
    }
    if (validClasses.isEmpty()) return

    val existingSubjects = store.get("subjects").toMutableList()
    var changed = false

    validClasses.groupBy { it.second.lowercase(Locale.getDefault()) }
        .values
        .forEach { entries ->
            val classes = entries.map { it.first }
            val code = entries.first().second
            val bestName = classes.firstOrNull { it.subtitle.isNotBlank() }?.subtitle?.trim().orEmpty()
            val bestProfessor = classes.firstOrNull { it.professor.isNotBlank() }?.professor?.trim().orEmpty()
            val bestRoom = classes.firstOrNull { it.room.isNotBlank() }?.room?.trim().orEmpty()
            var existingIndex = existingSubjects.indexOfFirst {
                normalizeScheduleSubjectCode(it.title).equals(code, true)
            }

            // If the code was edited in Schedule, reuse the old auto-synced subject
            // when its subject name/room still identifies the same class. This keeps
            // Tools > Subjects synchronized instead of leaving the old code behind.
            if (existingIndex < 0) {
                val nameKey = bestName
                val roomKey = bestRoom
                existingIndex = existingSubjects.indexOfFirst { existing ->
                    val titleLooksLikeCode = normalizeScheduleSubjectCode(existing.title).isNotBlank()
                    val sameName = nameKey.isNotBlank() &&
                        existing.subtitle.trim().equals(nameKey, true)
                    val sameRoom = roomKey.isNotBlank() &&
                        existing.room.trim().equals(roomKey, true)
                    titleLooksLikeCode && (sameName || sameRoom)
                }
            }
            val bestExtra = classes.firstOrNull { it.extra.isNotBlank() }?.extra?.trim().orEmpty()
            val bestType = classes.firstOrNull { it.classType.isNotBlank() }?.classType?.trim().orEmpty().ifBlank { "Lecture" }

            if (existingIndex < 0) {
                existingSubjects += Record(
                    title = code,
                    subtitle = bestName,
                    extra = bestExtra,
                    professor = bestProfessor,
                    room = bestRoom,
                    classType = bestType
                )
                changed = true
            } else {
                val existing = existingSubjects[existingIndex]
                val updated = existing.copy(
                    title = code,
                    subtitle = if (bestName.isNotBlank()) bestName else existing.subtitle,
                    extra = if (bestExtra.isNotBlank()) bestExtra else existing.extra,
                    professor = if (bestProfessor.isNotBlank()) bestProfessor else existing.professor,
                    room = if (bestRoom.isNotBlank()) bestRoom else existing.room,
                    classType = bestType
                )
                if (updated != existing) {
                    existingSubjects[existingIndex] = updated
                    changed = true
                }
            }
        }

    if (changed) store.put("subjects", existingSubjects)
}
private fun syncSubjectFromClass(store: LocalStore, classRecord: Record) {
    syncSubjectsFromSchedule(store, listOf(classRecord))
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
    var selectedSlots by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
                            .background(if(selectedType!=null)MaterialTheme.colorScheme.primaryContainer else if(cellRecords.isNotEmpty())MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
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
                    Record(id=idBase+index,title=normalizedSubject,subtitle=fullName.trim(),extra=notes.trim(),day=day,startTime="%02d:00".format(h),endTime="%02d:00".format(h+1),room=(if (type.equals("Lecture", true)) lectureRoom else labRoom).trim(),professor=professor.trim(),color=0L,classType=type)
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
        AnimatedContent(
            targetState = monthOffset,
            transitionSpec = {
                fadeIn(animationSpec = tween(150)) togetherWith
                    fadeOut(animationSpec = tween(100))
            },
            label = "calendar month transition"
        ) { offsetState ->
            val animatedFirst = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -offsetState)
            }
            val animatedOffset = ((animatedFirst.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7)
            val animatedMax = animatedFirst.getActualMaximum(Calendar.DAY_OF_MONTH)

            Column(Modifier.fillMaxWidth().animateContentSize()) {
                Text(
                    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(animatedFirst.time),
                    Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(Modifier.fillMaxWidth()) {
                    listOf("M","T","W","T","F","S","S").forEach {
                        Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                    }
                }
                for (row in 0..5) Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val n = row * 7 + col - animatedOffset + 1
                        if (n in 1..animatedMax) {
                            val d = (animatedFirst.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, n) }
                            val k = sdf.format(d.time)
                            val selected = k == selectedDate
                            val today = k == todayKey
                            Box(
                                Modifier.weight(1f).padding(2.dp).height(40.dp)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                        MaterialTheme.shapes.small
                                    )
                                    .then(
                                        if (today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                                        else Modifier
                                    )
                                    .clickable { onSelect(k) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(n.toString(), fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal)
                            }
                        } else {
                            Box(Modifier.weight(1f).height(44.dp))
                        }
                    }
                }
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
    openLectureFiles: (Record) -> Unit,
    openStudyMaker: () -> Unit,
    requestedTool: String? = null,
    onToolRequestConsumed: () -> Unit = {}
) {
    // Repair schedule → Subjects outside composition. This catches classes added
    // by Schedule, imported/restored schedules, and schedules created by older builds.
    val revision = store.revision
    LaunchedEffect(revision) {
        // Schedule is the source of truth for automatically-created Notepad subject records.
        syncSubjectsFromSchedule(store, store.get("schedule"))
    }
    LaunchedEffect(Unit) {
        // Repair older installs as soon as Tools > Notepad is opened.
        syncSubjectsFromSchedule(store, store.get("schedule"))
    }
    var selectedKey by rememberSaveable { mutableStateOf("campus_ai") }
    LaunchedEffect(requestedTool) {
        requestedTool?.takeIf { it in store.toolOrder() }?.let {
            selectedKey = it
            onToolRequestConsumed()
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    var showAddSubject by remember { mutableStateOf(false) }
    val keys = remember(store.revision, refresh) { store.toolOrder() }
    val labels = keys.map { when (it) { "campus_ai" -> "Campus AI"; "subjects" -> "Notepad"; "calculator" -> "Calculator"; else -> "Budget" } }
    val tab = keys.indexOf(selectedKey).coerceAtLeast(0)
    val list = remember(refresh, revision, query, tab) {
        if (keys[tab] == "campus_ai") emptyList() else store.get(keys[tab]).filter {
            query.isBlank() || query == "__ADD__" ||
                (it.title + " " + it.subtitle + " " + it.extra).contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tools", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (keys.getOrNull(tab) == "subjects") IconButton(onClick = { showAddSubject = true }) { Icon(Icons.Default.Add, "Add Notepad") }
            }
            Text(
                when (keys.getOrNull(tab)) {
                    "study_maker" -> "Create reviewers, quizzes, flashcards, and study chats from your local materials"
                    "subjects" -> "Your Notepad categories, notes, and lecture files"
                    "budget" -> "Track allowance, expenses, savings, and targets"
                    else -> "Study and productivity tools"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 16.dp,
            containerColor = Color.Transparent
        ) {
            labels.forEachIndexed { i, label ->
                Tab(tab == i, { selectedKey = keys[i] }, text = { Text(label) })
            }
        }

        if (keys.getOrNull(tab) == "campus_ai") {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(campusTileModifier(Modifier.fillMaxWidth()), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Campus AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Chat with Campus AI, ask questions, and manage CampusOS with natural-language commands.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(openStudyMaker, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Open Campus AI") }
            }
        } else if (keys.getOrNull(tab) == "calculator") {
            CalculatorScreen()
        } else if (keys.getOrNull(tab) == "budget") {
            BudgetScreen(store)
        }

        if (keys.getOrNull(tab) != "campus_ai" && keys.getOrNull(tab) != "calculator" && keys.getOrNull(tab) != "budget" && list.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(58.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.School,
                                    null,
                                    Modifier.size(30.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            if (query.isNotBlank()) "No matching " + labels[tab].lowercase()
                            else "No " + labels[tab].lowercase() + " yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Add a class from Schedule to create a subject here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (tab != 1 && tab != 2) {
            val sortedList = list.sortedWith(
                compareByDescending<Record> { store.subjectFavorite(it.id) }
                    .thenBy { it.title.trim().lowercase(Locale.getDefault()) }
            )

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (tab == 0 && sortedList.any { store.subjectFavorite(it.id) }) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 1.dp)
                        ) {
                            Icon(Icons.Default.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("Favorites", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                items(sortedList, key = { it.id }) { r ->
                    val favorite = tab == 0 && store.subjectFavorite(r.id)
                    Card(
                        onClick = { if (tab == 0) openNotepad(r) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (favorite)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.title.ifBlank { "Untitled subject" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (favorite) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (favorite) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.Star,
                                            "Favorite",
                                            Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (r.subtitle.isNotBlank()) {
                                    Text(
                                        r.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (favorite) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                if (r.professor.isNotBlank()) {
                                    Text(
                                        r.professor,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (favorite) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }

                            if (tab == 0) {
                                IconButton(onClick = {
                                    store.setSubjectFavorite(r.id, !favorite)
                                    refresh++
                                }) {
                                    Icon(
                                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                        if (favorite) "Unfavorite subject" else "Favorite subject",
                                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { openNotepad(r) }) {
                                    Icon(Icons.Default.StickyNote2, "Notepad")
                                }
                                IconButton(onClick = { openLectureFiles(r) }) {
                                    Icon(Icons.Default.Folder, "Lecture Files")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if ((query == "__ADD__" && tab == 0) || showAddSubject) AddNotepadDialog(
        store = store,
        done = { showAddSubject = false; if (query == "__ADD__") clear(); refresh++ }
    )
}


@Composable
fun AddNotepadDialog(
    store: LocalStore,
    done: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = done,
        title = { Text("New Notepad") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Create a subject/notebook or any category for your notes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text("e.g. ITEC65 or Personal") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description (optional)") },
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.trim().isNotBlank(),
                onClick = {
                    val title = name.trim()
                    val existing = store.get("subjects")
                    if (!existing.any { it.title.trim().equals(title, true) }) {
                        store.put(
                            "subjects",
                            existing + Record(
                                title = title,
                                subtitle = description.trim()
                            )
                        )
                    }
                    done()
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(store: LocalStore) {
    val revision = store.revision
    val chartColors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    var refresh by remember { mutableIntStateOf(0) }
    var showPlan by remember { mutableStateOf(false) }
    var showExpense by remember { mutableStateOf(false) }
    var showSaving by remember { mutableStateOf(false) }
    var showTarget by remember { mutableStateOf(false) }
    val entries = remember(revision, refresh) { store.budgetEntries() }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val period = store.budgetPeriod()
    val allowance = store.budgetAllowance()
    val categories = listOf("Food", "Transportation", "School", "Projects", "Bills", "Personal", "Other")

    fun inCurrentPeriod(date: String): Boolean {
        val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) }.getOrNull() ?: return false
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = parsed }
        return when (period) {
            "Daily" -> now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            "Monthly" -> now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.MONTH) == then.get(Calendar.MONTH)
            else -> now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR)
        }
    }

    val current = entries.filter { inCurrentPeriod(it.date) }
    val spent = current.filter { it.type == "expense" }.sumOf { it.amount }
    val saved = current.filter { it.type == "saving" }.sumOf { it.amount }
    val remaining = allowance - spent - saved
    val targetName = store.budgetTargetName()
    val targetAmount = store.budgetTargetAmount()
    val targetSaved = store.budgetTargetSaved()
    val targetRemaining = (targetAmount - targetSaved).coerceAtLeast(0.0)
    val targetProgress = if (targetAmount > 0) (targetSaved / targetAmount).coerceIn(0.0, 1.0) else 0.0
    val categoryTotals = categories.map { it to current.filter { e -> e.type == "expense" && e.category == it }.sumOf { e -> e.amount } }.filter { it.second > 0 }
    val totalCategorySpend = categoryTotals.sumOf { it.second }
    val topCategory = categoryTotals.maxByOrNull { it.second }
    val remainingPercent = if (allowance > 0) (remaining / allowance * 100).coerceIn(0.0, 100.0) else 0.0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Budget", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("$period • local only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showPlan = true }) { Text("Edit") }
            }
        }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Money left", style = MaterialTheme.typography.labelLarge)
                    Text("₱" + String.format(Locale.getDefault(), "%,.2f", remaining), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("₱" + String.format(Locale.getDefault(), "%,.2f", allowance) + " allowance", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Spent ₱" + String.format(Locale.getDefault(), "%,.0f", spent))
                        Text("Saved ₱" + String.format(Locale.getDefault(), "%,.0f", saved))
                    }
                    if (allowance > 0) LinearProgressIndicator(progress = { (remaining / allowance).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showExpense = true }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Expense")
                }
                OutlinedButton(onClick = { showSaving = true }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Savings, null); Spacer(Modifier.width(6.dp)); Text("Save")
                }
            }
        }
        if (categoryTotals.isNotEmpty()) item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium, colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Where your money goes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BudgetPieChart(categoryTotals)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            categoryTotals.take(5).forEachIndexed { index, pair ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).background(chartColors[index % chartColors.size], RoundedCornerShape(3.dp)))
                                    Spacer(Modifier.width(7.dp))
                                    Text(pair.first, Modifier.weight(1f), maxLines = 1)
                                    Text("${((pair.second / totalCategorySpend) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quick insight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            allowance <= 0 -> "Set your allowance first. The app will calculate your remaining money automatically."
                            current.isEmpty() -> "No spending recorded yet. Add an expense whenever you spend."
                            topCategory != null -> "Most spent: " + topCategory.first + " (₱" + String.format(Locale.getDefault(), "%,.2f", topCategory.second) + "). You have " + String.format(Locale.getDefault(), "%.0f", remainingPercent) + "% left."
                            else -> "Keep adding expenses to see your spending insight."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Saving goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (targetName.isBlank() || targetAmount <= 0) "Optional — save for something you want" else targetName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showTarget = true }) { Text(if (targetName.isBlank()) "Set" else "Edit") }
                    }
                    if (targetName.isNotBlank() && targetAmount > 0) {
                        LinearProgressIndicator(progress = { targetProgress.toFloat() }, Modifier.fillMaxWidth())
                        Text(if (targetRemaining <= 0) "Goal reached 🎉" else "₱" + String.format(Locale.getDefault(), "%,.2f", targetRemaining) + " more to go", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (entries.isNotEmpty()) item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    entries.take(5).forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.type == "saving") Icons.Default.Savings else Icons.Default.ReceiptLong, null, tint = if (entry.type == "saving") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.note.ifBlank { entry.category.ifBlank { if (entry.type == "saving") "Saving" else "Expense" } }, fontWeight = FontWeight.SemiBold)
                                Text(entry.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("₱" + String.format(Locale.getDefault(), "%,.2f", entry.amount), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = {
                                store.deleteBudgetEntry(entry.id)
                                if (entry.type == "saving" && entry.target.isNotBlank()) store.addBudgetTargetSaved(-entry.amount)
                                refresh++
                            }) { Icon(Icons.Default.DeleteOutline, "Delete") }
                        }
                    }
                }
            }
        }
    }
    if (showPlan) BudgetPlanDialog(store) { showPlan = false; refresh++ }
    if (showExpense) BudgetEntryDialog(store, "Expense", categories, today) { showExpense = false; refresh++ }
    if (showSaving) BudgetEntryDialog(store, "Saving", categories, today, targetName) { showSaving = false; refresh++ }
    if (showTarget) BudgetTargetDialog(store) { showTarget = false; refresh++ }
}

@Composable
private fun BudgetPieChart(data: List<Pair<String, Double>>) {
    val chartColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer
    )
    val total = data.sumOf { it.second }.coerceAtLeast(0.01)
    val holeColor = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(145.dp)) {
        var start = -90f
        data.forEachIndexed { index, item ->
            val sweep = (item.second / total * 360f).toFloat()
            drawArc(chartColors[index % chartColors.size], start, sweep, true)
            start += sweep
        }
        drawCircle(holeColor, radius = size.minDimension * .23f, center = center)
    }
}

@Composable
private fun RowScope.BudgetMiniStat(label: String, amount: Double) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
        Text(
            "₱" + String.format(Locale.getDefault(), "%,.0f", amount.coerceAtLeast(0.0)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun BudgetPlanDialog(store: LocalStore, done: () -> Unit) {
    var period by remember { mutableStateOf(store.budgetPeriod()) }
    var amount by remember { mutableStateOf(if (store.budgetAllowance() > 0) store.budgetAllowance().toString() else "") }
    val periods = listOf("Daily", "Weekly", "Monthly")
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Allowance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose how often you receive this allowance.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    periods.forEach { option ->
                        FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option) })
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount (₱)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.setBudgetPlan(period, amount.toDoubleOrNull() ?: 0.0)
                done()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetEntryDialog(
    store: LocalStore,
    typeLabel: String,
    categories: List<String>,
    date: String,
    targetName: String = "",
    done: () -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(if (typeLabel == "Saving") "Savings" else categories.first()) }
    var note by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Add $typeLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount (₱)") },
                    singleLine = true
                )
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(category) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        val options = if (typeLabel == "Saving") listOf("Savings") + categories else categories
                        options.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { category = option; expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (typeLabel == "Saving") "What are you saving for? (optional)" else "Note (optional)") },
                    singleLine = true
                )
                if (typeLabel == "Saving" && targetName.isNotBlank()) {
                    Text("Target: $targetName", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.toDoubleOrNull() ?: 0.0
                if (value > 0) {
                    val target = if (typeLabel == "Saving" && targetName.isNotBlank()) targetName else ""
                    store.addBudgetEntry(BudgetEntry(type = typeLabel.lowercase(), amount = value, category = category, note = note.trim(), date = date, target = target))
                }
                done()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@Composable
private fun BudgetTargetDialog(store: LocalStore, done: () -> Unit) {
    var name by remember { mutableStateOf(store.budgetTargetName()) }
    var amount by remember { mutableStateOf(if (store.budgetTargetAmount() > 0) store.budgetTargetAmount().toString() else "") }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Savings target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Target item") }, singleLine = true)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Target amount (₱)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.setBudgetTarget(name.trim(), amount.toDoubleOrNull() ?: 0.0)
                done()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectNotepadPage(subject: Record, store: LocalStore, done: () -> Unit) {
    val revision = store.revision
    var notes by remember(subject.id, revision) { mutableStateOf(store.subjectNotes(subject.id)) }
    var query by rememberSaveable(subject.id) { mutableStateOf("") }
    var sort by rememberSaveable(subject.id) { mutableStateOf("Modified") }
    var editorId by rememberSaveable(subject.id) { mutableStateOf<Long?>(null) }
    var title by rememberSaveable(subject.id) { mutableStateOf("") }
    var body by rememberSaveable(subject.id) { mutableStateOf("") }
    var saveStatus by remember { mutableStateOf("All changes saved") }
    var deleteTarget by remember { mutableStateOf<SubjectNote?>(null) }

    fun newNote() { editorId = -1L; title = ""; body = ""; saveStatus = "Not saved yet" }
    fun edit(note: SubjectNote) { editorId = note.id; title = note.title; body = note.body; saveStatus = "All changes saved" }
    fun save(close: Boolean = false) {
        if (title.isBlank() && body.isBlank()) { if (close) editorId = null; return }
        val old = if (editorId != null && editorId != -1L) notes.firstOrNull { it.id == editorId } else null
        val now = System.currentTimeMillis()
        val note = SubjectNote(old?.id ?: maxOf(now, (notes.maxOfOrNull { it.id } ?: 0L) + 1L), title.trim().ifBlank { "Untitled note" }, body, now, old?.favorite ?: false, old?.order ?: ((notes.maxOfOrNull { it.order } ?: 0L) + 1L))
        store.saveSubjectNotes(subject.id, if (old == null) notes + note else notes.map { if (it.id == old.id) note else it })
        notes = store.subjectNotes(subject.id)
        saveStatus = "Saved " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now))
        if (close) editorId = null
    }
    LaunchedEffect(editorId, title, body) {
        if (editorId != null && (title.isNotBlank() || body.isNotBlank())) {
            saveStatus = "Saving…"
            delay(700)
            save()
        }
    }
    LaunchedEffect(subject.id) {
        if (notes.isEmpty()) runCatching {
            val o = JSONObject(subject.extra)
            val t = o.optString("title"); val b = o.optString("body")
            if (t.isNotBlank() || b.isNotBlank()) {
                store.saveSubjectNotes(subject.id, listOf(SubjectNote(System.currentTimeMillis(), t.ifBlank { "Untitled note" }, b, System.currentTimeMillis())))
                notes = store.subjectNotes(subject.id)
            }
        }
    }
    val list = notes.filter { query.isBlank() || (it.title + " " + it.body).contains(query, true) }.sortedWith(
        compareByDescending<SubjectNote> { it.favorite }.thenBy { when (sort) { "Created" -> -it.id; "Alphabetical" -> it.title.lowercase(Locale.getDefault()); "Manual" -> it.order; else -> -it.updatedAt } }
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(targetState = editorId, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "notepad transition") { state ->
            if (state == null) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Column { Text("Notepad", fontWeight = FontWeight.Bold); Text(subject.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, navigationIcon = { IconButton({ done() }) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { IconButton({ newNote() }) { Icon(Icons.Default.NoteAdd, "New note") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search notes") }, shape = MaterialTheme.shapes.medium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Modified", "Created", "Alphabetical", "Manual").forEach { option -> FilterChip(sort == option, { sort = option }, label = { Text(option) }) } }
                        if (list.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.StickyNote2, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(if (query.isBlank()) "No notes yet" else "No matching notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(if (query.isBlank()) "Create a note for " + subject.title + "." else "Try a different search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (query.isBlank()) FilledTonalButton({ newNote() }) { Text("New note") }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(list, key = { it.id }) { note ->
                                    Card(onClick = { edit(note) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(Modifier.size(44.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.StickyNote2, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) } }
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) { if (note.favorite) { Icon(Icons.Default.PushPin, "Pinned", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }; Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f)) }
                                                if (note.body.isNotBlank()) Text(note.body, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("Modified " + SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(note.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton({ store.saveSubjectNotes(subject.id, notes.map { if (it.id == note.id) it.copy(favorite = !it.favorite) else it }); notes = store.subjectNotes(subject.id) }) { Icon(Icons.Default.PushPin, "Pin", tint = if (note.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                            IconButton({ deleteTarget = note }) { Icon(Icons.Default.DeleteOutline, "Delete") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Column { Text(if (state == -1L) "New note" else "Edit note", fontWeight = FontWeight.Bold); Text(subject.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, navigationIcon = { IconButton({ save(true) }) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { Text(saveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(false, { body += "\n• " }, label = { Text("• Bullet") })
                        FilterChip(false, { body += "\n☐ " }, label = { Text("☐ Checklist") })
                        FilterChip(false, { body += "\n────────\n" }, label = { Text("Divider") })
                    }
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") }, shape = MaterialTheme.shapes.medium)
                        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("Note") }, placeholder = { Text("Write your notes here…") }, textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), shape = MaterialTheme.shapes.medium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ editorId = null }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium) { Text("Cancel") }
                            Button({ save(true) }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium) { Text("Save now") }
                        }
                    }
                }
            }
        }
    }
    if (deleteTarget != null) AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete note?") }, text = { Text("This note will be permanently removed.") }, confirmButton = { TextButton({ val n = deleteTarget!!; store.saveSubjectNotes(subject.id, notes.filterNot { it.id == n.id }); notes = store.subjectNotes(subject.id); deleteTarget = null }) { Text("Delete") } }, dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } })
}

private data class LectureFileEntry(val file: File, val folder: String, val favorite: Boolean)
private fun lectureMetaPrefs(context: Context, subjectId: Long) = context.getSharedPreferences("lecture_meta_" + subjectId, Context.MODE_PRIVATE)
private fun lectureFolders(context: Context, subjectId: Long): MutableList<String> = (lectureMetaPrefs(context, subjectId).getString("folders", "Lecture 1|Lecture 2|Lecture 3") ?: "Lecture 1|Lecture 2|Lecture 3").split("|").filter { it.isNotBlank() }.toMutableList()
private fun lectureFolder(context: Context, subjectId: Long, file: File): String = lectureMetaPrefs(context, subjectId).getString("folder_" + file.name, "Lecture 1") ?: "Lecture 1"
private fun lectureFavorite(context: Context, subjectId: Long, file: File): Boolean = lectureMetaPrefs(context, subjectId).getBoolean("favorite_" + file.name, false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectLectureFilesPage(subject: Record, openFile: (String) -> Unit, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by remember(subject.id) { mutableStateOf(subjectFiles(context, subject.id)) }
    var folders by remember(subject.id) { mutableStateOf(lectureFolders(context, subject.id)) }
    var currentFolder by rememberSaveable(subject.id) { mutableStateOf("Lecture 1") }
    var query by rememberSaveable(subject.id) { mutableStateOf("") }
    var sort by rememberSaveable(subject.id) { mutableStateOf("Newest") }
    var selectMode by rememberSaveable(subject.id) { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var actionTarget by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<File>>(emptyList()) }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        copyUriToSubject(context, uri, subject.id)?.let { file ->
            lectureMetaPrefs(context, subject.id).edit().putString("folder_" + file.name, currentFolder).apply()
            files = subjectFiles(context, subject.id)
        }
    }
    val entries = files.map { LectureFileEntry(it, lectureFolder(context, subject.id, it), lectureFavorite(context, subject.id, it)) }
        .filter { it.folder == currentFolder && (query.isBlank() || it.file.name.contains(query, true)) }
        .sortedWith(compareByDescending<LectureFileEntry> { it.favorite }.thenBy {
            when (sort) { "Alphabetical" -> it.file.name.lowercase(Locale.getDefault()); "Oldest" -> it.file.lastModified(); "Size" -> -it.file.length(); else -> -it.file.lastModified() }
        })

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Column { Text("Lecture Files", fontWeight = FontWeight.Bold); Text(subject.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, navigationIcon = { IconButton({ done() }) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { if (selectMode) { Text(selected.size.toString(), modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold); IconButton({ deleteTargets = entries.filter { it.file.name in selected }.map { it.file } }) { Icon(Icons.Default.Delete, "Delete selected") } }; IconButton({ upload.launch(arrayOf("*/*")) }) { Icon(Icons.Default.UploadFile, "Add file") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search files") }, shape = MaterialTheme.shapes.medium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                folders.forEach { folder -> FilterChip(currentFolder == folder, { currentFolder = folder; selected = emptySet(); selectMode = false }, label = { Text(folder) }, leadingIcon = if (currentFolder == folder) ({ Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) }) else null) }
                FilterChip(false, { folderDialog = true }, label = { Text("+ Folder") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(16.dp)) })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Newest", "Oldest", "Alphabetical", "Size").forEach { option -> FilterChip(sort == option, { sort = option }, label = { Text(option) }) }
                TextButton({ selectMode = !selectMode; selected = emptySet() }) { Text(if (selectMode) "Done" else "Select") }
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Card(campusTileModifier(Modifier.fillMaxWidth()), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("No files in " + currentFolder, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (query.isBlank()) "Add lecture materials here." else "No files match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isBlank()) FilledTonalButton({ upload.launch(arrayOf("*/*")) }) { Text("Add file") }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.file.name }) { entry ->
                        val file = entry.file
                        val selectedFile = file.name in selected
                        Card(onClick = { if (selectMode) selected = if (selectedFile) selected - file.name else selected + file.name else openFile(file.name) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = if (selectedFile) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(46.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(when (fileExtension(file)) { "pdf" -> Icons.Default.PictureAsPdf; "ppt", "pptx" -> Icons.Default.Slideshow; "doc", "docx" -> Icons.Default.Description; "xls", "xlsx" -> Icons.Default.GridOn; "jpg", "jpeg", "png", "webp" -> Icons.Default.Image; else -> Icons.Default.InsertDriveFile }, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) } }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(file.name, maxLines = 2, fontWeight = FontWeight.SemiBold, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(file.lastModified())) + " • " + formatSize(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton({ lectureMetaPrefs(context, subject.id).edit().putBoolean("favorite_" + file.name, !entry.favorite).apply(); files = subjectFiles(context, subject.id) }) { Icon(if (entry.favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (entry.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton({ actionTarget = file }) { Icon(Icons.Default.MoreVert, "Actions") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (actionTarget != null) AlertDialog(onDismissRequest = { actionTarget = null }, title = { Text(actionTarget!!.name) }, text = { Text("Choose an action.") }, confirmButton = { TextButton({ openFile(actionTarget!!.name); actionTarget = null }) { Text("Open") } }, dismissButton = { Row {
        TextButton({ renameTarget = actionTarget; renameText = actionTarget!!.name; actionTarget = null }) { Text("Rename") }
        TextButton({ deleteTargets = listOf(actionTarget!!); actionTarget = null }) { Text("Delete") }
        TextButton({ runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, actionTarget!!.name) }, "Share file")) }; actionTarget = null }) { Text("Share") }
    } })
    if (renameTarget != null) AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename file") }, text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, label = { Text("File name") }) }, confirmButton = { TextButton({
        val old = renameTarget!!; val newName = safeFileName(renameText.trim())
        if (newName.isNotBlank() && newName != old.name) {
            val target = File(old.parentFile, newName)
            if (!target.exists() && old.renameTo(target)) {
                val p = lectureMetaPrefs(context, subject.id)
                val folder = p.getString("folder_" + old.name, currentFolder); val fav = p.getBoolean("favorite_" + old.name, false)
                p.edit().remove("folder_" + old.name).remove("favorite_" + old.name).putString("folder_" + target.name, folder).putBoolean("favorite_" + target.name, fav).apply()
                files = subjectFiles(context, subject.id)
            }
        }
        renameTarget = null
    }) { Text("Rename") } }, dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } })
    if (deleteTargets.isNotEmpty()) AlertDialog(onDismissRequest = { deleteTargets = emptyList() }, title = { Text("Delete selected files?") }, text = { Text("The selected files will be permanently removed.") }, confirmButton = { TextButton({
        val p = lectureMetaPrefs(context, subject.id)
        deleteTargets.forEach { it.delete(); p.edit().remove("folder_" + it.name).remove("favorite_" + it.name).apply() }
        files = subjectFiles(context, subject.id); selected = emptySet(); deleteTargets = emptyList()
    }) { Text("Delete") } }, dismissButton = { TextButton({ deleteTargets = emptyList() }) { Text("Cancel") } })
    if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false }, title = { Text("New lecture folder") }, text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true, label = { Text("Folder name") }) }, confirmButton = { TextButton({
        val name = folderName.trim()
        if (name.isNotBlank() && !folders.any { it.equals(name, true) }) {
            folders = (folders + name).toMutableList()
            lectureMetaPrefs(context, subject.id).edit().putString("folders", folders.joinToString("|")).apply()
            currentFolder = name
        }
        folderName = ""; folderDialog = false
    }) { Text("Create") } }, dismissButton = { TextButton({ folderName = ""; folderDialog = false }) { Text("Cancel") } })
}
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
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp),
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
                        Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
    Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
                        color=MaterialTheme.colorScheme.surfaceContainerLow
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
                Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) { ListItem(
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
    val modules=listOf("homepage" to "Homepage","schedule" to "Class Schedule","tasks" to "Notes","academics" to "Subjects / Lessons")
    AlertDialog(onDismissRequest=done,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("Check each module you want to transfer.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        modules.forEach{(id,label)->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{onSelected(if(id in selected)selected-id else selected+id)});Text(label)}}
    }},confirmButton={Button(enabled=selected.isNotEmpty(),onClick=done){Text("Continue")}},dismissButton={TextButton(done){Text("Cancel")}})
}

@Composable
private fun CampusAiFloatingButton(store: LocalStore, onClick: () -> Unit) {
    val density = LocalDensity.current
    val sizeDp = store.aiFloatingSize().dp
    val opacity = store.aiFloatingOpacity()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizePx = with(density) { sizeDp.toPx() }
        val edgePx = with(density) { 8.dp.toPx() }
        val maxX = (constraints.maxWidth.toFloat() - sizePx - edgePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight.toFloat() - sizePx - edgePx).coerceAtLeast(0f)
        var x by remember { mutableFloatStateOf(store.aiFloatingX()) }
        var y by remember { mutableFloatStateOf(store.aiFloatingY()) }

        LaunchedEffect(maxX, maxY, sizePx) {
            if (x < 0f || y < 0f) {
                x = maxX
                y = maxY
            } else {
                x = x.coerceIn(0f, maxX)
                y = y.coerceIn(0f, maxY)
            }
        }

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(sizeDp)
                .graphicsLayer(alpha = opacity)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { store.setAiFloatingPosition(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY)) },
                        onDragCancel = { store.setAiFloatingPosition(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY)) }
                    ) { change, amount ->
                        change.consume()
                        x = (x + amount.x).coerceIn(0f, maxX)
                        y = (y + amount.y).coerceIn(0f, maxY)
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SmartToy, contentDescription = "CampusOS AI", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: LocalStore, theme: String, setTheme: (String) -> Unit, lock: () -> Unit, openScheduleSettings: () -> Unit, openScheduleManager: () -> Unit, openCampusAi: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf(store.pin()) }
    var lockOn by remember { mutableStateOf(store.lockEnabled()) }
    var showPin by remember { mutableStateOf(false) }
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
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
                        Text("Schedule Settings")
                    }
                }
            }
        }

        item { Text("Academics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Subjects & lecture files", fontWeight = FontWeight.Bold)
                    Text("Manage Notepad and Lecture Files from the Tools screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notes & Reminders", fontWeight = FontWeight.Bold)
                    Text("Manage notes and reminders from the Notes screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("CampusOS AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }

        item {
            val aiChatStore = remember { AiChatStore(context) }
            var aiEnabled by remember { mutableStateOf(store.aiFloatingEnabled()) }
            var aiOpacity by remember { mutableFloatStateOf(store.aiFloatingOpacity()) }
            var aiSize by remember { mutableFloatStateOf(store.aiFloatingSize()) }
            var aiBubbleSize by remember { mutableFloatStateOf(store.aiBubbleSize()) }

            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Floating AI", fontWeight = FontWeight.Bold)
                    Text("Control the AI button that stays above CampusOS screens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable floating AI", fontWeight = FontWeight.SemiBold)
                            Text("Show or hide the floating AI button.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = aiEnabled, onCheckedChange = { aiEnabled = it; store.setAiFloatingEnabled(it) })
                    }
                    Text("AI icon transparency: "+(aiOpacity * 100).roundToInt()+"%", style = MaterialTheme.typography.labelLarge)
                    Slider(value = aiOpacity, onValueChange = { aiOpacity = it; store.setAiFloatingOpacity(it) }, valueRange = 0.30f..1f, steps = 13)
                    Text("AI icon size: "+aiSize.roundToInt()+" dp", style = MaterialTheme.typography.labelLarge)
                    Slider(value = aiSize, onValueChange = { aiSize = it; store.setAiFloatingSize(it) }, valueRange = 44f..76f, steps = 7)
                    Text("Floating chat size: "+aiBubbleSize.roundToInt()+"% of screen", style = MaterialTheme.typography.labelLarge)
                    Slider(value = aiBubbleSize, onValueChange = { aiBubbleSize = it; store.setAiBubbleSize(it) }, valueRange = 70f..96f, steps = 12)
                    OutlinedButton({ store.resetAiFloatingPosition() }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text("Reset Floating Button Position")
                    }
                    OutlinedButton(openCampusAi, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Open Full AI / Provider / Model Settings")
                    }
                }
            }
        }

        item {
            var aiPermission by remember { mutableStateOf(store.aiActionPermission()) }
            var openChangedModule by remember { mutableStateOf(store.aiOpenChangedModule()) }
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI permissions", fontWeight = FontWeight.Bold)
                    Text("Choose how much access CampusOS AI has to supported CampusOS data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("ask" to "Always ask", "read" to "Allow read actions", "all" to "Allow all actions").forEach { (value, label) ->
                        FilterChip(selected = aiPermission == value, onClick = { aiPermission = value; store.setAiActionPermission(value) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(when (aiPermission) {
                        "all" -> "AI may read and change supported schedule, tasks, subjects, notes, lecture files, budget and savings data without asking."
                        "read" -> "AI may read supported CampusOS data. Changes still require approval."
                        else -> "AI asks before each CampusOS data action."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatically open changed module", fontWeight = FontWeight.SemiBold)
                            Text("Open Schedule, Tasks or Academics after AI changes related data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = openChangedModule, onCheckedChange = { openChangedModule = it; store.setAiOpenChangedModule(it) })
                    }
                }
            }
        }

        item {
            val aiChatStore = remember { AiChatStore(context) }
            var historyEnabled by remember { mutableStateOf(aiChatStore.historyEnabled()) }
            var recallEnabled by remember { mutableStateOf(aiChatStore.recallEnabled()) }
            var historyLimit by remember { mutableIntStateOf(aiChatStore.historyLimit()) }
            var historyDays by remember { mutableIntStateOf(aiChatStore.historyDays()) }
            var clearConfirm by remember { mutableStateOf(false) }
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Chat memory & history", fontWeight = FontWeight.Bold)
                    Text("These controls are local to this device. They control saved conversations and whether past chats can be used for relevant recall.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Save chat history", fontWeight = FontWeight.SemiBold)
                            Text(if (historyEnabled) "Conversations are saved locally." else "New conversations are not saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = historyEnabled, onCheckedChange = { historyEnabled = it; aiChatStore.setHistoryEnabled(it) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use past chats for recall", fontWeight = FontWeight.SemiBold)
                            Text("Allow CampusOS AI to search saved past chats for relevant context.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = recallEnabled, onCheckedChange = { recallEnabled = it; aiChatStore.setRecallEnabled(it) })
                    }
                    Text("Maximum saved conversations: "+if (historyLimit == 0) "Unlimited" else historyLimit.toString(), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 30, 50, 100, 0).forEach { value ->
                            FilterChip(selected = historyLimit == value, onClick = { historyLimit = value; aiChatStore.setHistoryLimit(value) }, label = { Text(if (value == 0) "Unlimited" else value.toString()) })
                        }
                    }
                    Text("Automatic history expiration: "+if (historyDays == 0) "Never" else "$historyDays days", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 7, 30, 90, 365, 0).forEach { value ->
                            FilterChip(selected = historyDays == value, onClick = { historyDays = value; aiChatStore.setHistoryDays(value) }, label = { Text(if (value == 0) "Never" else if (value == 1) "1 day" else "$value days") })
                        }
                    }
                    OutlinedButton({ clearConfirm = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear All AI Chats & Attachments")
                    }
                    Text("Privacy & safety: chat history and uploaded attachment copies used by CampusOS AI are stored locally on this device. AI data actions use the permission setting above. Provider/API key settings remain in the dedicated AI settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Clear AI history?") }, text = { Text("This deletes all saved CampusOS AI conversations and their local attachment copies from this device. This cannot be undone.") }, confirmButton = { TextButton(onClick = { aiChatStore.deleteAll(); clearConfirm = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } })
        }
        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", fontWeight = FontWeight.Bold)
                    Text("Light mode only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Use Appearance settings to choose the app's color combination.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
            Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
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
                    Text("You can back up or recover Homepage, Class Schedule, Notes, or Tools separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Text("CampusOS 1.0.0 • Offline-first", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Transfer tip: select Class Schedule to share your timetable, or Tools to share subjects, notes, and lecture files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
@Composable fun EmptyCard(text: String) { Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
            Card(campusTileModifier(Modifier.fillMaxWidth()),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),shape=RoundedCornerShape(16.dp)){
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
            Card(campusTileModifier(Modifier.fillMaxWidth()),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),shape=RoundedCornerShape(16.dp)){
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
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = RoundedCornerShape(16.dp), colors = campusTileColors()) {
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
            Card(campusTileModifier(Modifier.fillMaxWidth()), shape = RoundedCornerShape(16.dp)) {
                ListItem(headlineContent = { Text(r.title, fontWeight = FontWeight.SemiBold) }, supportingContent = { Column { if (r.subtitle.isNotBlank()) Text(r.subtitle, maxLines = 2); if (r.dueDate.isNotBlank()) Text("Due ${r.dueDate} ${r.dueTime}") } }, leadingContent = { Icon(Icons.Default.CheckCircleOutline, null) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}



@Composable
fun CampusWebEmbeddedScreen(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url == null) webView.loadUrl(url)
        }
    )
}