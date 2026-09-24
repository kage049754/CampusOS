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

data class Record(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val subtitle: String = "",
    val extra: String = "",
    val value: Double = 0.0,
    val done: Boolean = false
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
                o.optString("extra"), o.optDouble("value", 0.0), o.optBoolean("done", false))
        }
        return out
    }
    private fun save(key: String, list: List<Record>) {
        val a = JSONArray()
        list.forEach { r -> a.put(JSONObject().apply {
            put("id", r.id); put("title", r.title); put("subtitle", r.subtitle)
            put("extra", r.extra); put("value", r.value); put("done", r.done)
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

private enum class Screen(val label: String) {
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
                    Screen.SCHEDULE -> CrudScreen("Class Schedule", "schedule", store, search) { search = "" }
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
    val labels = listOf("Subjects","Grades","Attendance","Reviewers")
    Column(Modifier.fillMaxSize()) {
        Text("Academics", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            labels.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        val key = listOf("subjects","grades","attendance","reviewers")[tab]
        val list = remember(refresh, query, tab) { store.get(key).filter {
            query.isBlank() || query == "__ADD__" || (it.title+" "+it.subtitle+" "+it.extra).contains(query, true)
        }}
        if (list.isEmpty()) EmptyCard("No "+labels[tab].lowercase()+" yet. Use + to add.")
        else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { r -> RecordCard(r, key, store) { refresh++ } }
        }
    }
    if (query == "__ADD__") AddRecordDialog(labels[tab], listOf("subjects","grades","attendance","reviewers")[tab], store) { clear(); refresh++ }
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
    OutlinedButton(click, Modifier.weight(1f)) { Icon(icon, null); Spacer(Modifier.width(4.dp)); Text(text) }
}
