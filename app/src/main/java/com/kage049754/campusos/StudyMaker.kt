package com.kage049754.campusos

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

private const val STUDY_AI_KEY_ALIAS = "CampusOSStudyAiKeyV1"
private const val STUDY_AI_PREFS = "campusos_study_ai"
private const val STUDY_AI_EXPERIMENT = true

data class StudySource(val id: String, val title: String, val subject: String, val kind: String, val text: String)
data class StudyPack(val title: String, val type: String, val body: String, val createdAt: Long)

private fun defaultStudyModel(provider: String) = when (provider) {
    "Gemini" -> "gemini-3.8-flash"
    "OpenRouter" -> "openrouter/free"
    else -> "gemini-3.8-flash"
}

private fun allowedStudyProvider(provider: String) = if (provider == "OpenRouter") "OpenRouter" else "Gemini"

private fun enforceFreeStudyModel(provider: String, model: String): String = when (provider) {
    "OpenRouter" -> "openrouter/free"
    "Gemini" -> "gemini-3.8-flash"
    else -> error("Unsupported AI provider.")
}

private class StudyAiSecureStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(STUDY_AI_PREFS, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(STUDY_AI_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            STUDY_AI_KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun getApiKey(): String {
        val encoded = prefs.getString("api_key", "") ?: ""
        if (encoded.isBlank()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            }.doFinal(raw.copyOfRange(12, raw.size)).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }
    fun setApiKey(value: String) {
        if (value.isBlank()) { prefs.edit().remove("api_key").apply(); return }
        // Android Keystore requires a fresh provider-generated IV for encryption.
        // Supplying our own IV causes "IV not permitted" on affected devices.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = cipher.iv
        prefs.edit().putString("api_key", Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply()
    }
    fun provider() = allowedStudyProvider(prefs.getString("provider", "Gemini") ?: "Gemini")
    fun setProvider(value: String) = prefs.edit().putString("provider", allowedStudyProvider(value)).apply()
    fun model() = defaultStudyModel(provider())
    fun setModel(value: String) = prefs.edit().putString("model", defaultStudyModel(provider())).apply()
    fun packs(context: Context): List<StudyPack> {
        val dir = File(context.filesDir, "study_packs").apply { mkdirs() }
        return dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull {
            runCatching {
                val o = JSONObject(it.readText())
                StudyPack(o.optString("title"), o.optString("type"), o.optString("body"), o.optLong("createdAt"))
            }.getOrNull()
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }
    fun savePack(context: Context, pack: StudyPack) {
        val dir = File(context.filesDir, "study_packs").apply { mkdirs() }
        val safe = pack.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(70).ifBlank { "Study_Pack" }
        File(dir, System.currentTimeMillis().toString() + "_" + safe + ".json").writeText(JSONObject().apply {
            put("title", pack.title); put("type", pack.type); put("body", pack.body); put("createdAt", pack.createdAt)
        }.toString())
    }
}

private fun subjectStudyFolder(context: Context, subjectId: Long) = File(context.filesDir, "subject_files/" + subjectId)
private fun studyExt(file: File) = file.extension.lowercase(Locale.getDefault())

private fun studyOfficeText(file: File): String? = runCatching {
    ZipFile(file).use { zip ->
        when (studyExt(file)) {
            "docx" -> zip.getEntry("word/document.xml")?.let { e ->
                zip.getInputStream(e).use { s -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(s).documentElement.textContent }
            }
            "pptx" -> zip.entries().asSequence().filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .sortedBy { it.name }.joinToString("\n\n") { e ->
                    zip.getInputStream(e).use { s -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(s).documentElement.textContent }
                }
            "xlsx" -> zip.entries().asSequence().filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                .sortedBy { it.name }.joinToString("\n") { e ->
                    zip.getInputStream(e).bufferedReader().use { it.readText() }.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                }
            else -> null
        }
    }
}.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

private fun studyPdfText(file: File): String? = runCatching {
    PDDocument.load(file).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
}.getOrNull()

private fun studyFileText(file: File): String? = when (studyExt(file)) {
    "pdf" -> studyPdfText(file)
    "docx", "pptx", "xlsx" -> studyOfficeText(file)
    "txt", "md", "csv", "log", "json", "xml", "kt", "java" -> runCatching { file.readText() }.getOrNull()
    else -> null
}

private fun studySources(context: Context, store: LocalStore): List<StudySource> =
    store.get("subjects").flatMap { subject ->
        val notes = store.subjectNotes(subject.id).map { n ->
            StudySource("note_" + subject.id + "_" + n.id, n.title.ifBlank { "Untitled note" }, subject.title, "Notepad", n.body)
        }
        val files = subjectStudyFolder(context, subject.id).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty().map { f ->
            StudySource("file_" + subject.id + "_" + f.absolutePath, f.name, subject.title, "Lecture File", studyFileText(f).orEmpty())
        }
        notes + files
    }.filter { it.text.isNotBlank() }

private fun studyContext(sources: List<StudySource>, maxChars: Int = 180_000): String {
    val out = StringBuilder()
    for (s in sources) {
        if (out.length >= maxChars) break
        val text = s.text.take((maxChars - out.length).coerceAtLeast(0))
        out.append("\n\n===== SOURCE: ").append(s.subject).append(" — ").append(s.title).append(" (").append(s.kind).append(") =====\n").append(text)
        if (s.text.length > text.length) out.append("\n[Source truncated for this request]")
    }
    return out.toString().trim()
}

private suspend fun studyAiCall(provider: String, model: String, key: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(key.isNotBlank()) { "Add your AI API key first." }
        val safeProvider = allowedStudyProvider(provider)
        val safeModel = enforceFreeStudyModel(safeProvider, model)
        val endpoint: String
        val body: String
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (safeProvider == "Gemini") {
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + safeModel + ":generateContent"
            body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", prompt))) }))
                put("generationConfig", JSONObject().put("temperature", 0.35).put("maxOutputTokens", 6000))
            }.toString()
            headers["x-goog-api-key"] = key
        } else {
            endpoint = "https://openrouter.ai/api/v1/chat/completions"
            body = JSONObject().apply {
                put("model", safeModel)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                put("temperature", 0.35)
            }.toString()
            headers["Authorization"] = "Bearer " + key
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = connection.responseCode
        val resetHeader = connection.getHeaderField("X-RateLimit-Reset") ?: connection.getHeaderField("x-ratelimit-reset")
        val remainingHeader = connection.getHeaderField("X-RateLimit-Remaining") ?: connection.getHeaderField("x-ratelimit-remaining")
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val lower = response.lowercase(Locale.getDefault())
            val limitMessage = if (code == 429 || lower.contains("rate limit") || lower.contains("quota")) {
                val reset = resetHeader?.takeIf { it.isNotBlank() }?.let { " Reset: $it." }.orEmpty()
                "Free AI limit reached for $safeProvider.${if (remainingHeader == "0") " No requests remain." else ""}$reset Try again after the provider limit resets."
            } else null
            error(limitMessage ?: "AI request failed (" + code + "): " + response.take(500))
        }
        val json = JSONObject(response)
        val text = if (safeProvider == "Gemini") {
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            buildString { if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
        } else {
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }.trim()
        if (text.isBlank()) error("AI returned an empty response.")
        text
    }
}

private fun studyPrompt(action: String, context: String): String {
    val task = when (action) {
        "Reviewer" -> "Create a clear student-friendly reviewer with headings, key concepts, definitions, examples, and a short remember section."
        "Quiz" -> "Create a 20-question quiz mixing multiple choice, true/false, and identification. Put an answer key at the end."
        "ABCD" -> "Create 20 multiple-choice questions with exactly four choices A-D and an answer key."
        "True / False" -> "Create 20 true/false questions and an answer key."
        "Flashcards" -> "Create 20 flashcards using QUESTION: and ANSWER: labels."
        "Key Concepts" -> "Extract the most important concepts and briefly explain each."
        "Definitions" -> "Create a glossary of important terms with simple definitions."
        "Explain Simply" -> "Explain the material in very simple student-friendly language with examples."
        "Important Only" -> "Keep only information most important for an exam: concepts, terms, formulas, rules, and facts."
        "Practice Questions" -> "Create exam-style practice questions with answers and brief explanations."
        "Weak Topics" -> "Create a targeted mini-review of concepts that are easy to confuse or commonly misunderstood."
        else -> "Help the student study the material."
    }
    return "You are CampusOS Study Maker. Use the supplied student materials as the primary source. Do not invent facts. If something cannot be verified from the sources, say so clearly.\n\nTASK:\n" + task + "\n\nSTUDENT MATERIALS:\n" + context
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyMakerScreen(activity: Activity, store: LocalStore, done: () -> Unit) {
    if (!STUDY_AI_EXPERIMENT) { done(); return }
    val context = LocalContext.current
    val secure = remember { StudyAiSecureStore(context) }
    var provider by remember { mutableStateOf(secure.provider()) }
    var model by remember { mutableStateOf(secure.model()) }
    var apiKey by remember { mutableStateOf(secure.getApiKey()) }
    var page by rememberSaveable { mutableStateOf("home") }
    var sources by remember { mutableStateOf(emptyList<StudySource>()) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var action by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var resultTitle by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chat by rememberSaveable { mutableStateOf(listOf<Pair<String,String>>()) }
    var packs by remember { mutableStateOf(secure.packs(context)) }
    var testingConnection by remember { mutableStateOf(false) }
    var testRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { PDFBoxResourceLoader.init(context) }
        sources = studySources(context, store)
        activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    LaunchedEffect(testRequest) {
        if (testRequest > 0 && apiKey.isNotBlank()) {
            testingConnection = true
            error = "Testing connection…"
            try {
                studyAiCall(provider, model, apiKey.trim(), "Reply with only: OK")
                    .onSuccess { error = "Connection successful." }
                    .onFailure { error = it.message ?: "Connection test failed." }
            } catch (e: Exception) {
                error = e.message ?: "Connection test failed. Check the provider, model, and internet connection."
            } finally {
                testingConnection = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { activity.window.decorView.systemUiVisibility = 0 } }
    BackHandler { if (page == "home") done() else page = "home" }

    fun selectedSources() = sources.filter { it.id in selectedIds }
    fun hasSources(): Boolean {
        if (selectedIds.isEmpty()) { error = "Select at least one note or lecture file."; return false }
        if (selectedSources().none { it.text.isNotBlank() }) { error = "The selected materials have no readable text."; return false }
        return true
    }
    suspend fun generate(type: String, userPrompt: String? = null) {
        if (!hasSources()) return
        if (apiKey.isBlank()) { page = "settings"; error = "Add your API key to generate study material."; return }
        busy = true; error = ""
        val contextText = studyContext(selectedSources())
        val prompt = if (userPrompt != null)
            "You are CampusOS Study AI. Answer using the selected materials as the primary context. If the answer is not supported by them, say that clearly.\n\nMATERIALS:\n" + contextText + "\n\nSTUDENT QUESTION:\n" + userPrompt
        else studyPrompt(type, contextText)
        studyAiCall(provider, model, apiKey, prompt).onSuccess {
            result = it
            resultTitle = if (userPrompt != null) "Study AI" else type
            if (userPrompt == null) {
                secure.savePack(context, StudyPack((selectedSources().firstOrNull()?.subject ?: "Study") + " " + type, type, it, System.currentTimeMillis()))
                packs = secure.packs(context)
                page = "result"
            } else chat = chat + ("Study AI" to it)
        }.onFailure { error = it.message ?: "AI request failed." }
        busy = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (page == "home") "Study Maker" else when (page) {
                "sources" -> "Select Materials"; "builder" -> "Create Study Material"; "result" -> resultTitle
                "chat" -> "Study AI"; "packs" -> "My Study Packs"; "settings" -> "AI Settings"; else -> "Study Maker"
            }, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ if (page == "home") done() else page = "home" }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { if (page == "home") IconButton({ page = "settings"; error = "" }) { Icon(Icons.Default.Settings, "AI settings") } }
        )
    }) { padding ->
        when (page) {
            "home" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Study Maker + Study AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (apiKey.isBlank()) "Connect your own AI provider to generate study material and chat." else provider + " connected • " + selectedIds.size + " source(s) selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }}
                item { Button({ sources = studySources(context, store); page = "sources"; error = "" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Select Notes & Lecture Files") } }
                item { OutlinedButton({ if (hasSources()) { page = "chat"; error = "" } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Chat, null); Spacer(Modifier.width(8.dp)); Text("Study AI Chat") } }
                item { OutlinedButton({ page = "packs" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bookmark, null); Spacer(Modifier.width(8.dp)); Text("My Study Packs (" + packs.size + ")") } }
                item { Text("Custom Study Maker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                item { Card(Modifier.fillMaxWidth().clickable { if (hasSources()) page = "builder" }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Reviewer • Quiz • ABCD • Flashcards • More", fontWeight = FontWeight.SemiBold)
                        Text("Use multiple local notes and lecture files together.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }}
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            "sources" -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Choose one or more local sources. Original notes and files stay on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (sources.isEmpty()) item { Text("No readable Notepad notes or Lecture Files found.", color = MaterialTheme.colorScheme.error) }
                items(sources, key = { it.id }) { source ->
                    val selected = source.id in selectedIds
                    Card(Modifier.fillMaxWidth().clickable { selectedIds = if (selected) selectedIds - source.id else selectedIds + source.id },
                        colors = CardDefaults.cardColors(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected, { checked -> selectedIds = if (checked) selectedIds + source.id else selectedIds - source.id })
                            Column(Modifier.weight(1f)) {
                                Text(source.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(source.subject + " • " + source.kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Button({ if (hasSources()) page = "builder" }, Modifier.fillMaxWidth()) { Text("Continue (" + selectedIds.size + " selected)") } }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            "builder" -> {
                val actions = listOf("Reviewer","Quiz","ABCD","True / False","Flashcards","Key Concepts","Definitions","Explain Simply","Important Only","Practice Questions","Weak Topics")
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    item { Text(selectedIds.size.toString() + " source(s) selected", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(actions) { item ->
                        Card(Modifier.fillMaxWidth().clickable { action = item }, colors = CardDefaults.cardColors(if (action == item) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(when(item) {
                                    "Reviewer" -> Icons.Default.MenuBook; "Quiz","ABCD","True / False" -> Icons.Default.Quiz; "Flashcards" -> Icons.Default.Style
                                    "Key Concepts" -> Icons.Default.Lightbulb; "Definitions" -> Icons.Default.List; "Explain Simply" -> Icons.Default.Psychology
                                    "Important Only" -> Icons.Default.Star; "Practice Questions" -> Icons.Default.QuestionAnswer; else -> Icons.Default.TrackChanges
                                }, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp)); Text(item, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                if (action == item) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    item { Button(enabled = action.isNotBlank() && !busy, onClick = {
                        resultTitle = action; busy = true; page = "result"
                    }, modifier = Modifier.fillMaxWidth()) { if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Create " + action) } }
                    if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
            }
            "result" -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                if (busy) {
                    LaunchedEffect(resultTitle) { generate(resultTitle) }
                }
                if (busy) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Creating " + resultTitle + "…") }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(result, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ page = "builder" }) { Text("Create Another") }
                        OutlinedButton({ secure.savePack(context, StudyPack(resultTitle, resultTitle, result, System.currentTimeMillis())); packs = secure.packs(context) }) { Text("Save") }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            "chat" -> Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (chat.isEmpty()) item { Text("Ask questions about the selected notes and lecture files.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(chat) { pair -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(if (pair.first == "You") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp)) { Text(pair.first, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(pair.second) }
                    }}
                    if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Thinking…") } }
                }
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(chatInput, { chatInput = it }, Modifier.weight(1f), label = { Text("Ask about your materials") }, maxLines = 4)
                    IconButton(enabled = chatInput.isNotBlank() && !busy, onClick = {
                        val q = chatInput.trim(); chatInput = ""; chat = chat + ("You" to q)
                    }) { Icon(Icons.Default.Send, "Send") }
                }
                if (!busy && chat.lastOrNull()?.first == "You") LaunchedEffect(chat.size) { generate("Chat", chat.last().second) }
                if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
            }
            "packs" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (packs.isEmpty()) item { Text("No saved Study Packs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(packs) { pack -> Card(Modifier.fillMaxWidth().clickable { resultTitle = pack.title; result = pack.body; page = "result" }) {
                    Column(Modifier.padding(14.dp)) { Text(pack.title, fontWeight = FontWeight.Bold); Text(pack.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }}
            }
            "settings" -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BYOK: your provider key is encrypted locally with Android Keystore. CampusOS does not put it in Supabase or source code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Provider", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Gemini","OpenRouter").forEach { p ->
                    FilterChip(provider == p, { provider = p; secure.setProvider(p); model = defaultStudyModel(p); secure.setModel(model) }, label = { Text(p) })
                }}
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(model, {}, Modifier.fillMaxWidth(), label = { Text("Free model") }, singleLine = true, readOnly = true)
                Text(if (provider == "OpenRouter") "OpenRouter automatically selects an available free model. CampusOS only sends requests to the free router and will never select or fall back to a paid model." else "Gemini uses the fixed free-tier model configured by CampusOS. Paid model choices and fallback models are not used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({
                    runCatching { secure.setApiKey(apiKey.trim()); apiKey = secure.getApiKey() }
                        .onSuccess { error = "API key saved securely on this device." }
                        .onFailure { error = it.message ?: "Could not save the API key on this device." }
                }, Modifier.fillMaxWidth()) { Text("Save API Key") }
                OutlinedButton({
                    runCatching { secure.setApiKey(apiKey.trim()); apiKey = secure.getApiKey() }
                        .onSuccess { testRequest++ }
                        .onFailure { error = it.message ?: "Could not save the API key on this device." }
                }, Modifier.fillMaxWidth(), enabled = !testingConnection && apiKey.isNotBlank()) {
                    if (testingConnection) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Test Connection")
                }
                Text("If the free quota is reached, CampusOS stops the request and shows the provider limit message. It never switches to a paid model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = if (error.contains("saved", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
