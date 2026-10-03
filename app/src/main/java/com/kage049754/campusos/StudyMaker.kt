package com.kage049754.campusos

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.content.SharedPreferences
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.security.KeyStore
import java.util.Date
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
data class AiChatAttachment(val name: String, val mimeType: String, val localPath: String = "", val base64: String = "", val extractedText: String = "")
data class AiChatMessage(val role: String, val text: String, val attachmentName: String = "", val attachmentMime: String = "", val attachmentPath: String = "", val createdAt: Long = System.currentTimeMillis())
data class AiChatConversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val messages: List<AiChatMessage>)

class AiChatStore(private val context: Context) {
    fun historyEnabled(): Boolean = prefs.getBoolean("chat_history_enabled", true)
    fun setHistoryEnabled(value: Boolean) { prefs.edit().putBoolean("chat_history_enabled", value).apply(); if (!value) deleteAll() }
    fun recallEnabled(): Boolean = prefs.getBoolean("chat_recall_enabled", true)
    fun setRecallEnabled(value: Boolean) { prefs.edit().putBoolean("chat_recall_enabled", value).apply() }
    fun chatBackground(): String = prefs.getString("chat_background", "default") ?: "default"
    fun setChatBackground(value: String) { prefs.edit().putString("chat_background", value).apply() }
    private val dir get() = File(context.filesDir, "ai_chats").apply { mkdirs() }
    private val prefs get() = context.getSharedPreferences(STUDY_AI_PREFS, Context.MODE_PRIVATE)

    // 0 means unlimited. Retention is local to this device and applies to saved conversations.
    fun historyLimit(): Int = prefs.getInt("chat_history_limit", 30).coerceIn(0, 200)
    fun historyDays(): Int = prefs.getInt("chat_history_days", 0).coerceIn(0, 3650)
    fun setHistoryLimit(value: Int) { prefs.edit().putInt("chat_history_limit", value.coerceIn(0, 200)).apply(); cleanup() }
    fun setHistoryDays(value: Int) { prefs.edit().putInt("chat_history_days", value.coerceIn(0, 3650)).apply(); cleanup() }

    fun search(query: String): List<AiChatConversation> {
        val q = query.trim().lowercase(Locale.getDefault())
        val all = list()
        if (q.isBlank()) return all
        return all.filter { chat ->
            chat.title.lowercase(Locale.getDefault()).contains(q) ||
                chat.messages.any { msg ->
                    msg.text.lowercase(Locale.getDefault()).contains(q) ||
                        msg.attachmentName.lowercase(Locale.getDefault()).contains(q)
                }
        }
    }

    fun relevant(query: String, excludeId: String = ""): String {
        val tokens = query.lowercase(Locale.getDefault()).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        if (tokens.isEmpty()) return ""
        val ranked = mutableListOf<Pair<AiChatConversation, Int>>()
        for (chat in list()) {
            if (chat.id == excludeId) continue
            var score = 0
            for (token in tokens) {
                for (msg in chat.messages) {
                    if (msg.text.lowercase(Locale.getDefault()).contains(token)) score++
                }
            }
            if (score > 0) ranked.add(Pair(chat, score))
        }
        ranked.sortByDescending { pair -> pair.second }
        return ranked.take(3).joinToString("\n\n") { pair ->
            val chat = pair.first
            "CHAT: " + chat.title + "\n" + chat.messages.takeLast(6).joinToString("\n") { msg ->
                (if (msg.role == "user") "STUDENT" else "ASSISTANT") + ": " + msg.text.take(1200)
            }
        }
    }

    fun list(): List<AiChatConversation> {
        if (!historyEnabled()) return emptyList()
        cleanup()
        return dir.listFiles()?.filter { it.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?.let { chats -> if (historyLimit() == 0) chats else chats.take(historyLimit()) }
            .orEmpty()
    }

    fun save(chat: AiChatConversation) {
        if (!historyEnabled()) { delete(chat.id); return }
        File(dir, chat.id + ".json").writeText(toJson(chat).toString())
        cleanup()
    }

    fun delete(id: String) {
        File(dir, id + ".json").delete()
        File(context.filesDir, "ai_chat_attachments").listFiles()
            ?.filter { it.name.startsWith(id + "_") }?.forEach { it.delete() }
    }

    fun deleteAll() {
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
        File(context.filesDir, "ai_chat_attachments").listFiles()?.forEach { it.delete() }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val days = historyDays()
        val files = dir.listFiles()?.filter { it.extension == "json" }.orEmpty()
        val chats = files.mapNotNull { file ->
            runCatching { file to fromJson(JSONObject(file.readText())) }.getOrNull()
        }.sortedByDescending { it.second.updatedAt }

        if (days > 0) {
            val cutoff = now - days * 24L * 60L * 60L * 1000L
            chats.filter { it.second.updatedAt < cutoff }.forEach { (file, chat) ->
                file.delete()
                File(context.filesDir, "ai_chat_attachments").listFiles()
                    ?.filter { it.name.startsWith(chat.id + "_") }?.forEach { it.delete() }
            }
        }

        val remaining = dir.listFiles()?.filter { it.extension == "json" }
            ?.mapNotNull { file -> runCatching { file to fromJson(JSONObject(file.readText())) }.getOrNull() }
            ?.sortedByDescending { it.second.updatedAt }.orEmpty()
        if (historyLimit() > 0 && remaining.size > historyLimit()) {
            remaining.drop(historyLimit()).forEach { (file, chat) ->
                file.delete()
                File(context.filesDir, "ai_chat_attachments").listFiles()
                    ?.filter { it.name.startsWith(chat.id + "_") }?.forEach { it.delete() }
            }
        }
    }

    private fun toJson(c: AiChatConversation) = JSONObject().apply {
        put("id", c.id); put("title", c.title); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
        put("messages", JSONArray().apply { c.messages.forEach { m -> put(JSONObject().apply {
            put("role", m.role); put("text", m.text); put("attachmentName", m.attachmentName)
            put("attachmentMime", m.attachmentMime); put("attachmentPath", m.attachmentPath); put("createdAt", m.createdAt)
        }) } })
    }

    private fun fromJson(o: JSONObject): AiChatConversation {
        val messages = mutableListOf<AiChatMessage>(); val a = o.optJSONArray("messages")
        if (a != null) for (i in 0 until a.length()) {
            val m=a.optJSONObject(i) ?: continue
            messages += AiChatMessage(m.optString("role"),m.optString("text"),m.optString("attachmentName"),
                m.optString("attachmentMime"),m.optString("attachmentPath"),m.optLong("createdAt"))
        }
        return AiChatConversation(o.optString("id"), o.optString("title").ifBlank { "New chat" },
            o.optLong("createdAt"), o.optLong("updatedAt"), messages)
    }
}

@Composable
private fun aiChatBackgroundColor(background: String): androidx.compose.ui.graphics.Color = when (background) {
    "primary" -> MaterialTheme.colorScheme.primaryContainer
    "secondary" -> MaterialTheme.colorScheme.secondaryContainer
    "tertiary" -> MaterialTheme.colorScheme.tertiaryContainer
    "neutral" -> MaterialTheme.colorScheme.surfaceContainerHighest
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

private fun safeChatTitle(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(48).ifBlank { "New chat" }
private fun persistChatAttachment(context: Context, chatId: String, name: String, bytes: ByteArray): File { val dir=File(context.filesDir,"ai_chat_attachments").apply { mkdirs() }; val safe=name.replace(Regex("[^A-Za-z0-9._-]+"),"_").take(80).ifBlank { "attachment" }; return File(dir,chatId+"_"+System.currentTimeMillis()+"_"+safe).also { it.writeBytes(bytes) } }

private fun attachmentForFile(context: Context, chatId: String, uri: android.net.Uri, isImage: Boolean): AiChatAttachment? = runCatching {
    val resolver=context.contentResolver
    val originalName=resolver.query(uri,null,null,null,null)?.use { cursor -> val index=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(cursor.moveToFirst() && index>=0) cursor.getString(index) else null } ?: "attachment"
    val mime=resolver.getType(uri).orEmpty().ifBlank { if(isImage) "image/jpeg" else "application/octet-stream" }
    val raw=resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    if(raw.isEmpty()) return@runCatching null
    if(isImage) { val bitmap=BitmapFactory.decodeByteArray(raw,0,raw.size) ?: return@runCatching null; val out=ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,82,out); val bytes=out.toByteArray(); val file=persistChatAttachment(context,chatId,originalName.substringBeforeLast(".",originalName)+".jpg",bytes); AiChatAttachment(file.name,"image/jpeg",file.absolutePath,Base64.encodeToString(bytes,Base64.NO_WRAP)) }
    else { val file=persistChatAttachment(context,chatId,originalName,raw); AiChatAttachment(originalName,mime,file.absolutePath,extractedText=studyFileText(file).orEmpty()) }
}.getOrNull()

private fun aiChatPrompt(history: List<AiChatMessage>, newText: String): String {
    val previous=history.takeLast(20).joinToString("\n") { (if(it.role=="user") "STUDENT" else "ASSISTANT")+": "+it.text }
    return "You are CampusOS AI, the semantic interpreter and assistant for a student planner. The AI model itself must understand the student's intent; do NOT depend on a hardcoded word dictionary in the Android app. Interpret meaning from natural language, context, and the CampusOS tool schemas. The student may write in English, Filipino/Tagalog, Taglish, slang, abbreviations, phonetic spelling, missing spaces, wrong capitalization, or arbitrary typos. Do not require exact command words. Different phrasings with the same meaning should produce the same CampusOS action.\n\nBATCH REQUESTS ARE IMPORTANT: One student message can contain many independent actions. Extract and execute ALL requested actions in the same message, not just the first one. You may call the same tool multiple times with different arguments and may call different tools in one response. Examples: 'set my weekly budget to 600, add expenses 300 transportation and 200 food, and save 50' means set the weekly budget to 600, add a 300 Transportation expense, add a 200 Food expense, and add 50 to the saving goal. 'Add ITEC65 Monday 8-10 room CCL201 lecture and MATH Tuesday 10-12 room 204 lab' means add both schedule entries. A request can contain several subjects, classes, tasks, notes, files, expenses, savings, or edits. Never stop after the first understood action when more actions are clearly requested.\n\nTIME-BLOCK RULE: Schedule times are literal data. 'Monday 7-8 8-9 DCIT 25 room 201' means TWO schedule records: Monday 07:00-08:00 and Monday 08:00-09:00. Do not turn them into one 07:00-09:00 record. NUMBER RULE: Numeric values are literal data; never infer extra digits or change a stated amount. The examples are semantic examples, NOT an exhaustive dictionary. Infer intent from the complete message and conversation context rather than matching hardcoded words. For data changes, ALWAYS use the appropriate structured CampusOS function tool(s) instead of merely describing what the student should do. Tool arguments must use the canonical values required by each schema. If an action needs an existing record and no id is provided, use the relevant read tool first. Never invent existing records. If one part is genuinely ambiguous, clarify that part while still safely executing the other unambiguous requested actions when possible. The Android app validates and executes structured tool calls; the AI model is responsible for understanding the student's language and splitting a compound request into the required tool calls.\n\nFor financial requests, amount is a numeric Philippine-peso value; do not put the currency symbol inside numeric tool arguments. NEVER invent, round, combine, autocorrect, or alter a number. Copy each amount exactly from the student message. If the student says 'food 20', the tool argument MUST be 20, never 205, 200, 2.0, or another value. Verify every numeric argument against the original request before calling the tool. The app displays Philippine peso amounts as ₱. Preserve Markdown formatting when useful. When showing source code, ALWAYS put executable code inside fenced Markdown code blocks with the language name. Never put code in ordinary prose.\n\n"+(if(previous.isBlank()) "" else "CONVERSATION:\n"+previous+"\n\n")+"STUDENT QUESTION:\n"+newText
}
private fun chatAttachmentPrompt(attachments: List<AiChatAttachment>): String = attachments.filter { it.extractedText.isNotBlank() }.joinToString("\\n\\n") { "ATTACHED FILE: "+it.name+"\\n"+it.extractedText.take(120000) }

@Composable private fun AiRichMessage(text: String) {
    val clipboard=LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val fence="`"+"`"+"`"; val regex=Regex("(?s)"+fence+"([\\w+-]*)\\n?(.*?)"+fence); val matches=regex.findAll(text).toList()
    if(matches.isEmpty()){ Text(text,style=MaterialTheme.typography.bodyLarge); return }
    var cursor=0
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        matches.forEach { match ->
            if(match.range.first>cursor) Text(text.substring(cursor,match.range.first),style=MaterialTheme.typography.bodyLarge)
            val language=match.groupValues[1].ifBlank { "code" }; val code=match.groupValues[2].trimEnd()
            Card(colors=CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHighest)){ Column {
                Row(Modifier.fillMaxWidth().padding(start=12.dp,top=8.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically){ Text(language,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code",code))}){ Icon(Icons.Default.ContentCopy,null,Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") } }
                Text(code,Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace))
            } }
            cursor=match.range.last+1
        }
        if(cursor<text.length) Text(text.substring(cursor),style=MaterialTheme.typography.bodyLarge)
    }
}

private fun defaultStudyModel(provider: String) = when (provider) {
    "Gemini" -> "gemini-3.5-flash-lite"
    "OpenRouter" -> "openrouter/free"
    else -> "gemini-3.8-flash"
}

private fun allowedStudyProvider(provider: String) = if (provider == "OpenRouter") "OpenRouter" else "Gemini"

private fun enforceFreeStudyModel(provider: String, model: String): String = when (provider) {
    "OpenRouter" -> "openrouter/free"
    "Gemini" -> "gemini-3.5-flash-lite"
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
    private fun keyName(provider: String) = if (allowedStudyProvider(provider) == "OpenRouter") "api_key_openrouter" else "api_key_gemini"

    fun getApiKey(provider: String): String {
        val name = keyName(provider)
        var encoded = prefs.getString(name, "") ?: ""
        if (!prefs.getBoolean("api_keys_migrated", false)) {
            // Legacy key was provider-ambiguous. Never duplicate it into both providers.
            val legacy = prefs.getString("api_key", "") ?: ""
            if (legacy.isNotBlank() && encoded.isBlank()) {
                prefs.edit().putString(name, legacy).remove("api_key").putBoolean("api_keys_migrated", true).apply()
                encoded = legacy
            } else {
                prefs.edit().remove("api_key").putBoolean("api_keys_migrated", true).apply()
            }
        }
        if (encoded.isBlank()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            require(raw.size > 12)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            }.doFinal(raw.copyOfRange(12, raw.size)).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun setApiKey(provider: String, value: String) {
        val name = keyName(provider)
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = cipher.iv
        prefs.edit().putString(name, Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply()
        prefs.edit().remove("api_key").apply()
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

private fun campusAiCompact(raw: String): String =
    raw.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun campusAiEditDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val cur = IntArray(b.length + 1)
        cur[0] = i + 1
        for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

private fun campusAiCloseEnough(raw: String, canonical: String): Boolean {
    val a = campusAiCompact(raw); val b = campusAiCompact(canonical)
    if (a.isBlank() || b.isBlank()) return false
    if (a == b || a.contains(b) || b.contains(a)) return true
    val maxDistance = when { b.length <= 4 -> 1; b.length <= 7 -> 2; else -> 3 }
    return campusAiEditDistance(a, b) <= maxDistance
}

private fun campusAiNormalizeBudgetType(raw: String): String {
    val n = campusAiCompact(raw)
    return when {
        n in setOf("saving","savings","save","savng","ipon","pagipon") || campusAiCloseEnough(n, "saving") -> "saving"
        n in setOf("income","allowance","baon","kita","sahod","pasok") || campusAiCloseEnough(n, "income") -> "income"
        n in setOf("expense","expenses","expnse","expenditure","spent","spend","gastos","gastusin") || campusAiCloseEnough(n, "expense") -> "expense"
        else -> "expense"
    }
}

private fun campusAiNormalizeBudgetCategory(raw: String): String {
    val n = campusAiCompact(raw)
    return when {
        n.isBlank() -> ""
        n in setOf("food","pagkain","kain","meal","meals") || campusAiCloseEnough(n, "food") -> "Food"
        n in setOf("transportation","transport","pamasahe","commute","commuting","biyahe","byahe") || campusAiCloseEnough(n, "transportation") -> "Transportation"
        n in setOf("school","schooling","eskwela","paaralan","aral","study") || campusAiCloseEnough(n, "school") -> "School"
        n in setOf("project","projects","proyekto") || campusAiCloseEnough(n, "projects") -> "Projects"
        n in setOf("bill","bills","bayarin","bayad") || campusAiCloseEnough(n, "bills") -> "Bills"
        n in setOf("personal","sarili") || campusAiCloseEnough(n, "personal") -> "Personal"
        n in setOf("other","others","iba") || campusAiCloseEnough(n, "other") -> "Other"
        else -> raw.trim()
    }
}

private fun campusAiNormalizeDay(raw: String): String {
    val n = campusAiCompact(raw)
    return when (n) {
        "monday","lunes","mon" -> "Monday"
        "tuesday","martes","tue","tues" -> "Tuesday"
        "wednesday","miyerkules","wed" -> "Wednesday"
        "thursday","huwebes","thu","thur","thurs" -> "Thursday"
        "friday","biyernes","fri" -> "Friday"
        "saturday","sabado","sat" -> "Saturday"
        "sunday","linggo","sun" -> "Sunday"
        else -> raw.trim()
    }
}

private fun campusAiNormalizeClassType(raw: String): String {
    val n = campusAiCompact(raw)
    return when {
        n in setOf("lab","labs","laboratory","labratory","laboratry") || campusAiCloseEnough(n, "laboratory") -> "Lab"
        n in setOf("lec","lecture","lectr","lectre") || campusAiCloseEnough(n, "lecture") -> "Lecture"
        else -> raw.trim()
    }
}

private fun campusAiToolDefinitions(): JSONArray = JSONArray().apply {
    fun tool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name); put("description", description)
                put("parameters", JSONObject().apply { put("type","object"); put("properties",properties); put("required",JSONArray(required)) })
            })
        })
    }
    val scheduleProps = JSONObject().apply {
        put("id",JSONObject().put("type","integer")); put("title",JSONObject().put("type","string")); put("subtitle",JSONObject().put("type","string"))
        put("day",JSONObject().put("type","string")); put("startTime",JSONObject().put("type","string")); put("endTime",JSONObject().put("type","string"))
        put("room",JSONObject().put("type","string")); put("classType",JSONObject().put("type","string"))
    }
    val taskProps = JSONObject().apply {
        put("id",JSONObject().put("type","integer")); put("title",JSONObject().put("type","string")); put("subtitle",JSONObject().put("type","string")); put("extra",JSONObject().put("type","string"))
        put("dueDate",JSONObject().put("type","string")); put("dueTime",JSONObject().put("type","string")); put("done",JSONObject().put("type","boolean"))
    }
    val subjectProps = JSONObject().apply {
        put("id",JSONObject().put("type","integer")); put("title",JSONObject().put("type","string")); put("subtitle",JSONObject().put("type","string")); put("room",JSONObject().put("type","string")); put("professor",JSONObject().put("type","string"))
    }
    val noteProps = JSONObject().apply {
        put("subjectId",JSONObject().put("type","integer")); put("subject",JSONObject().put("type","string")); put("noteId",JSONObject().put("type","integer"))
        put("title",JSONObject().put("type","string")); put("body",JSONObject().put("type","string"))
    }
    val fileProps = JSONObject().apply {
        put("subjectId",JSONObject().put("type","integer")); put("subject",JSONObject().put("type","string")); put("fileName",JSONObject().put("type","string"))
        put("newName",JSONObject().put("type","string")); put("content",JSONObject().put("type","string"))
    }
    val budgetProps = JSONObject().apply {
        put("id",JSONObject().put("type","integer")); put("type",JSONObject().put("type","string")); put("amount",JSONObject().put("type","number"))
        put("category",JSONObject().put("type","string")); put("note",JSONObject().put("type","string")); put("date",JSONObject().put("type","string")); put("target",JSONObject().put("type","string"))
    }
    tool("get_dashboard","Read a compact overview of the student's schedule, tasks, subjects, budget, and saving goal.",JSONObject())
    tool("get_schedule","Read the student's current schedule.",JSONObject())
    tool("get_tasks","Read the student's current tasks.",JSONObject())
    tool("add_schedule","Add EXACTLY ONE time block to the student schedule. Each call is one literal block. If the student says 7-8 and 8-9, make TWO calls: 7:00-8:00 and 8:00-9:00. Never merge adjacent blocks into 7:00-9:00 unless the student explicitly says 7-9.",scheduleProps,listOf("title","day","startTime","endTime"))
    tool("edit_schedule","Edit an existing schedule entry. Prefer id; otherwise title/day.",scheduleProps)
    tool("delete_schedule","Delete a schedule entry. Prefer id; otherwise title/day.",scheduleProps)
    tool("add_task","Add a task.",taskProps,listOf("title")); tool("edit_task","Edit an existing task. Prefer id; otherwise title.",taskProps); tool("delete_task","Delete a task. Prefer id; otherwise title.",taskProps)
    tool("add_subject","Add a subject.",subjectProps,listOf("title")); tool("edit_subject","Edit an existing subject. Prefer id; otherwise title.",subjectProps); tool("delete_subject","Delete an existing subject. Prefer id; otherwise title.",subjectProps)
    tool("get_notes","Read notes from a subject. Use subjectId or subject name.",noteProps)
    tool("add_note","Create a note in a subject's Notepad.",noteProps,listOf("title","body"))
    tool("edit_note","Edit an existing subject note. Use noteId when possible.",noteProps)
    tool("delete_note","Delete a subject note.",noteProps)
    tool("get_lecture_files","Read lecture files stored for a subject.",fileProps)
    tool("add_lecture_file","Create a text lecture file inside a subject's Lecture Files.",fileProps,listOf("fileName","content"))
    tool("rename_lecture_file","Rename a lecture file.",fileProps,listOf("fileName","newName"))
    tool("delete_lecture_file","Delete a lecture file.",fileProps,listOf("fileName"))
    tool("get_budget","Read allowance, expenses, saving goal, and recent budget entries.",JSONObject())
    tool("add_budget","Add exactly ONE budget income, expense, or saving entry. For a compound financial request, call this tool once for EACH separate entry. The AI model must infer type, amount, and canonical category from the full natural-language request; do not rely on an app-side word dictionary. Example: 'expenses 300 transportation and 200 food' requires TWO add_budget calls.",budgetProps,listOf("type","amount"))
    tool("delete_budget","Delete a budget entry by id.",budgetProps,listOf("id"))
    tool("set_budget_plan","Set the budget period and allowance. This is separate from expense/saving entries. In a compound request, execute this together with all requested add_budget calls. Example: 'weekly budget 600, expenses 300 transportation and 200 food, saving 50' requires one set_budget_plan call plus three financial entry calls.",JSONObject().apply { put("period",JSONObject().put("type","string").put("description","Budget period such as Daily, Weekly, or Monthly, inferred from the request.")); put("amount",JSONObject().put("type","number")) },listOf("period","amount"))
    tool("set_saving_goal","Set the saving goal name and target amount.",JSONObject().apply { put("name",JSONObject().put("type","string")); put("amount",JSONObject().put("type","number")) },listOf("name","amount"))
    tool("add_saving","Add money to the current saving goal.",JSONObject().put("amount",JSONObject().put("type","number")),listOf("amount"))
    tool("calculate","Use the CampusOS calculator for a basic arithmetic expression.",JSONObject().put("expression",JSONObject().put("type","string")),listOf("expression"))
}

private fun campusAiRecordJson(r: Record) = JSONObject().apply {
    put("id",r.id); put("title",r.title); put("subtitle",r.subtitle); put("extra",r.extra); put("done",r.done); put("day",r.day); put("startTime",r.startTime); put("endTime",r.endTime); put("room",r.room); put("professor",r.professor); put("classType",r.classType); put("dueDate",r.dueDate); put("dueTime",r.dueTime)
}

private fun campusAiFindSubject(store: LocalStore, args: JSONObject): Record? {
    val id=args.optLong("subjectId",0L)
    val name=args.optString("subject").trim()
    if (id > 0) return store.get("subjects").firstOrNull { it.id == id }
    if (name.isBlank()) return null
    val subjects = store.get("subjects")
    val exact = subjects.firstOrNull {
        it.title.equals(name,true) || it.subtitle.equals(name,true) ||
            campusAiCompact(it.title) == campusAiCompact(name) ||
            campusAiCompact(it.subtitle) == campusAiCompact(name)
    }
    if (exact != null) return exact
    return subjects.map { it to minOf(
        campusAiEditDistance(campusAiCompact(name), campusAiCompact(it.title)),
        campusAiEditDistance(campusAiCompact(name), campusAiCompact(it.subtitle))
    )}.filter { it.second <= 3 }.minByOrNull { it.second }?.first
}

private fun campusAiSafeFileName(raw: String, fallback: String = "Lecture.txt"): String =
    raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(100).ifBlank { fallback }

private fun campusAiCalculate(raw: String): String = runCatching {
    val text = raw.replace("×","*").replace("÷","/").replace("−","-").replace(" ","")
    if (text.isBlank()) return@runCatching "Error"
    var total = 0.0
    text.split("+").forEach { part ->
        if (part.contains("*")) {
            val a = part.split("*")
            total += a[0].toDouble() * a[1].toDouble()
        } else if (part.contains("/")) {
            val a = part.split("/")
            total += a[0].toDouble() / a[1].toDouble()
        } else if (part.isNotBlank()) total += part.toDouble()
    }
    if (total == total.toLong().toDouble()) total.toLong().toString() else "%.8f".format(Locale.US, total)
}.getOrElse { "Error" }

private fun executeCampusAiTool(store: LocalStore, name: String, args: JSONObject): String {
    fun find(list: List<Record>): Record? {
        val id=args.optLong("id",0L); val title=args.optString("title").trim(); val day=args.optString("day").trim()
        return if(id>0) list.firstOrNull{it.id==id} else list.firstOrNull{it.title.equals(title,true) && (day.isBlank() || it.day.equals(day,true))}
    }
    fun updated(old: Record) = old.copy(
        title=args.optString("title").takeIf{it.isNotBlank()} ?: old.title, subtitle=args.optString("subtitle").takeIf{it.isNotBlank()} ?: old.subtitle,
        extra=args.optString("extra").takeIf{it.isNotBlank()} ?: old.extra, day=args.optString("day").takeIf{it.isNotBlank()} ?: old.day,
        startTime=args.optString("startTime").takeIf{it.isNotBlank()} ?: old.startTime, endTime=args.optString("endTime").takeIf{it.isNotBlank()} ?: old.endTime,
        room=args.optString("room").takeIf{it.isNotBlank()} ?: old.room, professor=args.optString("professor").takeIf{it.isNotBlank()} ?: old.professor,
        classType=args.optString("classType").takeIf{it.isNotBlank()} ?: old.classType, dueDate=args.optString("dueDate").takeIf{it.isNotBlank()} ?: old.dueDate,
        dueTime=args.optString("dueTime").takeIf{it.isNotBlank()} ?: old.dueTime, done=if(args.has("done")) args.optBoolean("done") else old.done
    )
    return when(name) {
        "get_dashboard" -> JSONObject().apply {
            put("schedule",JSONArray(store.get("schedule").map(::campusAiRecordJson)))
            put("tasks",JSONArray(store.get("tasks").map(::campusAiRecordJson)))
            put("subjects",JSONArray(store.get("subjects").map(::campusAiRecordJson)))
            put("budget",JSONArray(store.budgetEntries().map { e -> JSONObject().apply { put("id",e.id); put("type",e.type); put("amount",e.amount); put("category",e.category); put("note",e.note); put("date",e.date); put("target",e.target) } }))
            put("budgetPeriod",store.budgetPeriod()); put("allowance",store.budgetAllowance()); put("savingGoal",store.budgetTargetName()); put("savingTarget",store.budgetTargetAmount()); put("savingSaved",store.budgetTargetSaved())
        }.toString()
        "get_schedule" -> JSONArray(store.get("schedule").map(::campusAiRecordJson)).toString()
        "get_tasks" -> JSONArray(store.get("tasks").map(::campusAiRecordJson)).toString()
        "add_schedule" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),day=campusAiNormalizeDay(args.optString("day")),startTime=args.optString("startTime"),endTime=args.optString("endTime"),room=args.optString("room"),classType=campusAiNormalizeClassType(args.optString("classType","Lecture"))); store.put("schedule",store.get("schedule")+r); "Added schedule entry "+r.title+" on "+r.day+" "+r.startTime+"-"+r.endTime+"." }
        "edit_schedule" -> { val list=store.get("schedule").toMutableList(); val old=find(list) ?: return "No matching schedule entry was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("schedule",list); "Updated schedule entry "+list[index].title+"." }
        "delete_schedule" -> { val list=store.get("schedule"); val old=find(list) ?: return "No matching schedule entry was found."; store.put("schedule",list.filterNot{it.id==old.id}); "Deleted schedule entry "+old.title+"." }
        "add_task" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),extra=args.optString("extra"),dueDate=args.optString("dueDate"),dueTime=args.optString("dueTime")); store.put("tasks",store.get("tasks")+r); "Added task "+r.title+"." }
        "edit_task" -> { val list=store.get("tasks").toMutableList(); val old=find(list) ?: return "No matching task was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("tasks",list); "Updated task "+list[index].title+"." }
        "delete_task" -> { val list=store.get("tasks"); val old=find(list) ?: return "No matching task was found."; store.put("tasks",list.filterNot{it.id==old.id}); "Deleted task "+old.title+"." }
        "add_subject" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),room=args.optString("room"),professor=args.optString("professor")); store.put("subjects",store.get("subjects")+r); "Added subject "+r.title+"." }
        "edit_subject" -> { val list=store.get("subjects").toMutableList(); val old=find(list) ?: return "No matching subject was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("subjects",list); "Updated subject "+list[index].title+"." }
        "delete_subject" -> { val list=store.get("subjects"); val old=find(list) ?: return "No matching subject was found."; store.put("subjects",list.filterNot{it.id==old.id}); "Deleted subject "+old.title+"." }
        "get_notes" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; JSONArray(store.subjectNotes(subject.id).map { n -> JSONObject().apply { put("id",n.id); put("title",n.title); put("body",n.body); put("favorite",n.favorite); put("updatedAt",n.updatedAt) } }).toString() }
        "add_note" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val notes=store.subjectNotes(subject.id).toMutableList(); notes += SubjectNote(System.currentTimeMillis(),args.optString("title"),args.optString("body"),System.currentTimeMillis(),false,notes.size.toLong()); store.saveSubjectNotes(subject.id,notes); "Added note to "+subject.title+"." }
        "edit_note" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val notes=store.subjectNotes(subject.id).toMutableList(); val id=args.optLong("noteId",0L); val index=notes.indexOfFirst{it.id==id || (id==0L && it.title.equals(args.optString("title"),true))}; if(index<0) return "No matching note was found."; val old=notes[index]; notes[index]=old.copy(title=args.optString("title").takeIf{it.isNotBlank()}?:old.title,body=args.optString("body").takeIf{it.isNotBlank()}?:old.body,updatedAt=System.currentTimeMillis()); store.saveSubjectNotes(subject.id,notes); "Updated note "+notes[index].title+"." }
        "delete_note" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val notes=store.subjectNotes(subject.id); val id=args.optLong("noteId",0L); val old=notes.firstOrNull{it.id==id || (id==0L && it.title.equals(args.optString("title"),true))} ?: return "No matching note was found."; store.saveSubjectNotes(subject.id,notes.filterNot{it.id==old.id}); "Deleted note "+old.title+"." }
        "get_lecture_files" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val files=store.subjectFilesFolder(subject.id).listFiles()?.filter{it.isFile}.orEmpty(); JSONArray(files.map{JSONObject().apply{put("name",it.name);put("size",it.length());put("modifiedAt",it.lastModified())}}).toString() }
        "add_lecture_file" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val folder=store.subjectFilesFolder(subject.id).apply{mkdirs()}; val file=File(folder,campusAiSafeFileName(args.optString("fileName"))); file.writeText(args.optString("content")); "Created lecture file "+file.name+" for "+subject.title+"." }
        "rename_lecture_file" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val folder=store.subjectFilesFolder(subject.id); val old=File(folder,campusAiSafeFileName(args.optString("fileName"))); val target=File(folder,campusAiSafeFileName(args.optString("newName"))); if(!old.exists()) return "Lecture file not found."; if(target.exists()) return "A lecture file with that name already exists."; if(!old.renameTo(target)) return "Could not rename lecture file."; "Renamed lecture file to "+target.name+"." }
        "delete_lecture_file" -> { val subject=campusAiFindSubject(store,args) ?: return "No matching subject was found."; val file=File(store.subjectFilesFolder(subject.id),campusAiSafeFileName(args.optString("fileName"))); if(!file.exists()) return "Lecture file not found."; file.delete(); "Deleted lecture file "+file.name+"." }
        "get_budget" -> JSONObject().apply { put("period",store.budgetPeriod());put("allowance",store.budgetAllowance());put("savingGoal",store.budgetTargetName());put("savingTarget",store.budgetTargetAmount());put("savingSaved",store.budgetTargetSaved());put("entries",JSONArray(store.budgetEntries().map{e->JSONObject().apply{put("id",e.id);put("type",e.type);put("amount",e.amount);put("category",e.category);put("note",e.note);put("date",e.date);put("target",e.target)}})) }.toString()
        "add_budget" -> {
            val rawType = args.optString("type","expense").trim()
            val type = campusAiNormalizeBudgetType(rawType)
            val amount = kotlin.math.abs(args.optDouble("amount",0.0))
            val date = args.optString("date").trim().ifBlank {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            }
            val category = campusAiNormalizeBudgetCategory(args.optString("category"))
            val e = BudgetEntry(type=type, amount=amount, category=category, note=args.optString("note"), date=date, target=args.optString("target"))
            store.addBudgetEntry(e)
            val money = "₱" + String.format(Locale.getDefault(), "%,.2f", amount)
            "Added " + type + " of " + money + (if (category.isNotBlank()) " in " + category else "") + " for " + date + "."
        }
        "delete_budget" -> { val id=args.optLong("id",0L); if(id<=0) return "A budget entry id is required."; store.deleteBudgetEntry(id); "Deleted budget entry "+id+"." }
        "set_budget_plan" -> { store.setBudgetPlan(args.optString("period","Weekly"),args.optDouble("amount",0.0)); "Budget plan updated." }
        "set_saving_goal" -> { store.setBudgetTarget(args.optString("name"),args.optDouble("amount",0.0)); "Saving goal updated." }
        "add_saving" -> { val amount=kotlin.math.abs(args.optDouble("amount",0.0)); store.addBudgetTargetSaved(amount); "Added ₱"+String.format(Locale.getDefault(), "%,.2f", amount)+" to the saving goal." }
        "calculate" -> { val expression=args.optString("expression"); "Calculator result: "+campusAiCalculate(expression) }
        else -> "Unknown CampusOS action."
    }
}

private suspend fun studyAiCall(provider: String, model: String, key: String, prompt: String, attachments: List<AiChatAttachment> = emptyList(), store: LocalStore? = null, toolApproval: (suspend (String, JSONObject) -> Boolean)? = null, toolExecuted: (suspend (String) -> Unit)? = null): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(key.isNotBlank()){"Add your AI API key first."}
        val safeProvider=allowedStudyProvider(provider); val safeModel=enforceFreeStudyModel(safeProvider,model); val tools=if(store!=null) campusAiToolDefinitions() else JSONArray()
        var currentPrompt=prompt
        for (round in 0 until 4) {
            val endpoint:String; val body:String; val headers=mutableMapOf("Content-Type" to "application/json")
            if(safeProvider=="Gemini"){
                endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+safeModel+":generateContent"
                body=JSONObject().apply{
                    val parts=JSONArray().put(JSONObject().put("text",currentPrompt))
                    attachments.filter{it.base64.isNotBlank()}.forEach{a->parts.put(JSONObject().put("inline_data",JSONObject().put("mime_type",a.mimeType).put("data",a.base64)))}
                    put("contents",JSONArray().put(JSONObject().apply{put("parts",parts)}))
                    if(tools.length()>0) { val declarations=JSONArray(); for(i in 0 until tools.length()) declarations.put(tools.optJSONObject(i)?.optJSONObject("function")); put("tools",JSONArray().put(JSONObject().put("functionDeclarations",declarations))) }
                    put("generationConfig",JSONObject().put("temperature",0.0).put("maxOutputTokens",6000))
                }.toString()
                headers["x-goog-api-key"]=key
            } else {
                endpoint="https://openrouter.ai/api/v1/chat/completions"
                body=JSONObject().apply{
                    put("model",safeModel); val content=JSONArray().put(JSONObject().put("type","text").put("text",currentPrompt))
                    attachments.filter{it.base64.isNotBlank()}.forEach{a->content.put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:"+a.mimeType+";base64,"+a.base64)))}
                    put("messages",JSONArray().put(JSONObject().put("role","user").put("content",content))); if(tools.length()>0) put("tools",tools); put("temperature",0.0)
                }.toString()
                headers["Authorization"]="Bearer "+key
            }
            val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=15000;readTimeout=45000;doOutput=true;headers.forEach{(k,v)->setRequestProperty(k,v)}}
            connection.outputStream.use{it.write(body.toByteArray(StandardCharsets.UTF_8))}
            val code=connection.responseCode; val response=(if(code in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299) error("AI request failed ("+code+"): "+response.take(500))
            val json=JSONObject(response)
            if(store!=null && safeProvider=="Gemini"){
                val parts=json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val calls=parts?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("functionCall") } }
                if (!calls.isNullOrEmpty() && round < 3) {
                    val results = calls.map { call ->
                        val name = call.optString("name")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        val approved = toolApproval?.invoke(name, args) ?: !store.aiActionNeedsApproval(name)
                        val result = if (approved) executeCampusAiTool(store, name, args) else "The student did not approve this CampusOS action."
                        if (approved && toolExecuted != null) withContext(Dispatchers.Main) { toolExecuted.invoke(name) }
                        "[$name] " + (if (approved) "executed" else "not approved") + ": " + result
                    }
                    currentPrompt = "ORIGINAL STUDENT REQUEST:\n" + prompt + "\n\nCampusOS processed these requested actions in this batch:\n" + results.joinToString("\n") + "\n\nVerify every result against the ORIGINAL STUDENT REQUEST. If any requested item, time block, or numeric amount was missed or changed, execute the missing/corrected action before replying. For schedule, 7-8 and 8-9 are two records unless the original explicitly requested 7-9. For numbers, use exactly the digits supplied by the student. Then reply with a concise summary of ALL completed actions."
                    continue
                }
            } else if(store!=null){
                val message=json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"); val calls=message?.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0 && round < 3) {
                    val results = (0 until calls.length()).mapNotNull { index ->
                        val call = calls.optJSONObject(index) ?: return@mapNotNull null
                        val fn = call.optJSONObject("function") ?: return@mapNotNull null
                        val name = fn.optString("name").orEmpty()
                        if (name.isBlank()) return@mapNotNull null
                        val args = runCatching { JSONObject(fn.optString("arguments").orEmpty()) }.getOrElse { JSONObject() }
                        val approved = toolApproval?.invoke(name, args) ?: !store.aiActionNeedsApproval(name)
                        val result = if (approved) executeCampusAiTool(store, name, args) else "The student did not approve this CampusOS action."
                        if (approved && toolExecuted != null) withContext(Dispatchers.Main) { toolExecuted.invoke(name) }
                        "[$name] " + (if (approved) "executed" else "not approved") + ": " + result
                    }
                    currentPrompt = "CampusOS processed these requested actions in this batch:\n" + results.joinToString("\n") +
                        "\nNow reply naturally with a concise summary of ALL completed actions. Do not omit successful actions. Do not call another tool unless a necessary dependent action is still missing."
                    continue
                }
            }
            val text=if(safeProvider=="Gemini"){val parts=json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts");buildString{if(parts!=null)for(i in 0 until parts.length())append(parts.optJSONObject(i)?.optString("text").orEmpty())}}else{val content=json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content");when(content){is String->content;is JSONArray->buildString{for(i in 0 until content.length())append(content.optJSONObject(i)?.optString("text").orEmpty())};else->""}}.trim()
            if(text.isBlank()) error("AI returned an empty response."); return@runCatching text
        }
        error("CampusOS AI tool loop stopped before a final response.")
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
    return "You are CampusOS AI. Use the supplied student materials as the primary source. Do not invent facts. If something cannot be verified from the sources, say so clearly.\n\nTASK:\n" + task + "\n\nSTUDENT MATERIALS:\n" + context
}

private data class CampusAiConfirmState(val toolName: String, val decision: CompletableDeferred<Boolean>)

private fun campusAiActionDescription(toolName: String): String = when (toolName) {
    "get_dashboard" -> "read your CampusOS overview"
    "get_schedule" -> "read your class schedule"
    "get_tasks" -> "read your tasks"
    "add_schedule" -> "add a class to your schedule"
    "edit_schedule" -> "change a class in your schedule"
    "delete_schedule" -> "delete a class from your schedule"
    "add_task" -> "add a task"
    "edit_task" -> "change a task"
    "delete_task" -> "delete a task"
    "add_subject" -> "add a subject"
    "edit_subject" -> "change a subject"
    "delete_subject" -> "delete a subject"
    "get_notes" -> "read a subject's Notepad"
    "add_note" -> "create a subject note"
    "edit_note" -> "change a subject note"
    "delete_note" -> "delete a subject note"
    "get_lecture_files" -> "read a subject's Lecture Files"
    "add_lecture_file" -> "create a Lecture File"
    "rename_lecture_file" -> "rename a Lecture File"
    "delete_lecture_file" -> "delete a Lecture File"
    "get_budget" -> "read your Budget and Saving Goal"
    "add_budget" -> "add a Budget entry"
    "delete_budget" -> "delete a Budget entry"
    "set_budget_plan" -> "change your Budget plan"
    "set_saving_goal" -> "change your Saving Goal"
    "add_saving" -> "add money to your Saving Goal"
    "calculate" -> "use the CampusOS Calculator"
    else -> "perform a CampusOS data action"
}

@Composable
private fun CampusAiConfirmDialog(request: CampusAiConfirmState?, onDecision: (Boolean) -> Unit) {
    request ?: return
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = { Text("CampusOS AI wants to act") },
        text = { Text("Allow CampusOS AI to " + campusAiActionDescription(request.toolName) + "? You can change this in Settings.") },
        confirmButton = { Button(onClick = { onDecision(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text("Deny") } }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusAiBubble(activity: Activity, store: LocalStore, onModuleChanged: (String) -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val secure = remember { StudyAiSecureStore(context) }
    val chatStore = remember { AiChatStore(context) }
    val provider = secure.provider()
    val model = secure.model()
    val key = secure.getApiKey(provider)
    val aiBackground = chatStore.chatBackground()
    val initialChat = remember { chatStore.list().firstOrNull() }
    var chatId by rememberSaveable { mutableStateOf(initialChat?.id ?: "") }
    var messages by remember { mutableStateOf(initialChat?.messages.orEmpty()) }
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<AiChatAttachment?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var actionGate by remember { mutableStateOf<CampusAiConfirmState?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var attachmentMenu by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingAttachment = attachmentForFile(context, chatId.ifBlank { "new" }, uri, true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingAttachment = attachmentForFile(context, chatId.ifBlank { "new" }, uri, false)
    }

    suspend fun gateTool(name: String): Boolean {
        if (!store.aiActionNeedsApproval(name)) return true
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { actionGate = CampusAiConfirmState(name, decision) }
        val allowed = decision.await()
        withContext(Dispatchers.Main) { if (actionGate?.decision === decision) actionGate = null }
        return allowed
    }

    fun saveMessages(next: List<AiChatMessage>) {
        val id = chatId.ifBlank { "chat_" + System.currentTimeMillis() }.also { chatId = it }
        val now = System.currentTimeMillis()
        val existing = chatStore.list().firstOrNull { it.id == id }
        val title = next.firstOrNull { it.role == "user" }?.text?.let(::safeChatTitle)
            ?.ifBlank { next.firstOrNull { it.attachmentName.isNotBlank() }?.attachmentName ?: "New chat" } ?: "New chat"
        chatStore.save(AiChatConversation(id, title, existing?.createdAt ?: now, now, next))
    }



    // The expanded floating AI uses the exact available app display area.
    // The floating AI icon remains on top and is the toggle used to collapse it.
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(0.dp),
        color = aiChatBackgroundColor(aiBackground),
        shadowElevation = 18.dp
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.SmartToy, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CampusOS AI", fontWeight = FontWeight.Bold)
                    Text("Your CampusOS assistant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showHistory = !showHistory }) { Icon(Icons.Default.History, "Chat history") }            }
            if (showHistory) {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Chat history", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showHistory = false }) { Icon(Icons.Default.Close, "Close history") }
                        }
                        OutlinedTextField(historyQuery, { historyQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Search previous chats") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(chatStore.search(historyQuery)) { saved ->
                                ListItem(
                                    headlineContent = { Text(saved.title, maxLines = 1) },
                                    supportingContent = { Text(saved.messages.size.toString() + " messages") },
                                    leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) },
                                    modifier = Modifier.clickable { chatId = saved.id; messages = saved.messages; showHistory = false; error = "" }
                                )
                            }
                        }
                    }
                }
            }
            if (messages.isEmpty() && !showHistory) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("What's my next class?", "Show my tasks", "Add a task", "Explain my notes").forEach {
                        AssistChip(onClick = { input = it }, label = { Text(it) })
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(messages) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                        Surface(shape = RoundedCornerShape(18.dp),
                            color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else aiChatBackgroundColor(aiBackground)) {
                            Column(Modifier.padding(4.dp)) {
                                if (message.attachmentName.isNotBlank()) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (message.attachmentMime.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(5.dp)); Text(message.attachmentName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                                if (message.text.isNotBlank()) {
                                    SelectionContainer {
                                        if (message.role == "assistant") AiRichMessage(message.text)
                                        else Text(message.text, Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                    }
                                }                            }
                        }
                    }
                }
                if (busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Thinking…") }
                }
            }
            if (pendingAttachment != null) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (pendingAttachment!!.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile, null)
                        Spacer(Modifier.width(8.dp)); Text(pendingAttachment!!.name, Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { pendingAttachment = null }) { Icon(Icons.Default.Close, "Remove attachment") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachmentMenu = true }, enabled = !busy) { Icon(Icons.Default.AttachFile, "Attach") }
                    DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo or screenshot") }, onClick = { attachmentMenu = false; imagePicker.launch("image/*") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                        DropdownMenuItem(text = { Text("File, PDF or document") }, onClick = {
                            attachmentMenu = false
                            filePicker.launch(arrayOf("application/pdf", "text/*", "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "*/*"))
                        }, leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) })
                    }
                }
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message CampusOS AI…") }, maxLines = 4, shape = RoundedCornerShape(22.dp))
                IconButton(enabled = (input.isNotBlank() || pendingAttachment != null) && !busy, onClick = {
                    val q = input.trim(); val attachment = pendingAttachment
                    input = ""; pendingAttachment = null
                    if (q.isNotBlank() || attachment != null) {
                        if (chatId.isBlank()) chatId = "chat_" + System.currentTimeMillis()
                        val previous = messages
                        messages = previous + AiChatMessage("user", q, attachment?.name.orEmpty(), attachment?.mimeType.orEmpty(), attachment?.localPath.orEmpty())
                        saveMessages(messages); busy = true; error = ""
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            val recall = if (chatStore.recallEnabled()) chatStore.relevant(q, chatId) else ""
                            val prompt = aiChatPrompt(previous, q.ifBlank { "Please analyze the attached file." }) +
                                (if (attachment != null) "\n\n" + chatAttachmentPrompt(listOf(attachment)) else "") +
                                (if (recall.isNotBlank()) "\n\nRELEVANT PAST CHAT RECALL:\n" + recall else "")
                            studyAiCall(provider, model, key, prompt, listOfNotNull(attachment), store, { name, _ -> gateTool(name) }, { name -> onModuleChanged(name) })
                                .onSuccess { messages = messages + AiChatMessage("assistant", it); saveMessages(messages) }
                                .onFailure { error = it.message ?: "AI request failed." }
                            busy = false
                        }
                    }
                }) { Icon(Icons.Default.Send, "Send") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            Text("Chat history, recall and attachments stay on this device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CampusAiConfirmDialog(actionGate) { allowed -> actionGate?.decision?.complete(allowed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusAiScreen(activity: Activity, store: LocalStore, done: () -> Unit) {
    if (!STUDY_AI_EXPERIMENT) { done(); return }
    val context = LocalContext.current
    val secure = remember { StudyAiSecureStore(context) }
    var provider by remember { mutableStateOf(secure.provider()) }
    var model by remember { mutableStateOf(secure.model()) }
    var apiKey by remember { mutableStateOf(secure.getApiKey(provider)) }
    var page by rememberSaveable { mutableStateOf("chat") }
    var sources by remember { mutableStateOf(emptyList<StudySource>()) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var action by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var resultTitle by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var actionGate by remember { mutableStateOf<CampusAiConfirmState?>(null) }

    suspend fun gateTool(name: String): Boolean {
        if (!store.aiActionNeedsApproval(name)) return true
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { actionGate = CampusAiConfirmState(name, decision) }
        val allowed = decision.await()
        withContext(Dispatchers.Main) { if (actionGate?.decision === decision) actionGate = null }
        return allowed
    }
    var error by remember { mutableStateOf("") }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chat by remember { mutableStateOf(listOf<AiChatMessage>()) }
    var chatId by rememberSaveable { mutableStateOf(System.currentTimeMillis().toString()) }
    var chatAttachment by remember { mutableStateOf<AiChatAttachment?>(null) }
    var chatHistory by remember { mutableStateOf(emptyList<AiChatConversation>()) }
    var attachmentMenu by remember { mutableStateOf(false) }
    val chatStore=remember { AiChatStore(context) }
    var packs by remember { mutableStateOf(secure.packs(context)) }
    var testingConnection by remember { mutableStateOf(false) }
    var testRequest by remember { mutableIntStateOf(0) }

    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri!=null) chatAttachment=attachmentForFile(context,if(chatId.isBlank()) "new" else chatId,uri,true) }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) chatAttachment=attachmentForFile(context,if(chatId.isBlank()) "new" else chatId,uri,false) }

    LaunchedEffect(Unit) {
        runCatching { PDFBoxResourceLoader.init(context) }
        sources = studySources(context, store)
        chatHistory = chatStore.list()
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
    BackHandler { when { page=="chat" -> done(); page=="chat_history" -> page="chat"; page=="home" -> done(); else -> page="chat" } }

    fun copyCurrentChat() {
        val text = chat.joinToString("\n\n") { message ->
            (if (message.role == "user") "You" else "CampusOS AI") + ": " + message.text +
                if (message.attachmentName.isNotBlank()) "\n[Attachment: " + message.attachmentName + "]" else ""
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CampusOS AI chat", text))
    }

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
            } else { chat += AiChatMessage("assistant",it); chatStore.save(AiChatConversation(chatId,safeChatTitle(chat.firstOrNull()?.text.orEmpty()),chat.firstOrNull()?.createdAt ?: System.currentTimeMillis(),System.currentTimeMillis(),chat)); chatHistory=chatStore.list() }
        }.onFailure { error = it.message ?: "AI request failed." }
        busy = false
    }

    CampusAiConfirmDialog(actionGate) { allowed -> actionGate?.decision?.complete(allowed) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (page == "chat") "Campus AI" else when (page) {
                "sources" -> "Select Materials"; "builder" -> "Create Study Material"; "result" -> resultTitle
                "chat" -> "Chat"; "packs" -> "My Study Packs"; "settings" -> "AI Settings"; else -> "CampusOS AI"
            }, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ if (page == "chat") done() else page = "chat" }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                if (page == "chat") {                    IconButton({ page = "settings"; error = "" }) { Icon(Icons.Default.Settings, "AI settings") }
                }
            }
        )
    }) { padding ->
        when (page) {
            "home" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Card(campusTileModifier(Modifier.fillMaxWidth()), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CampusOS AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (apiKey.isBlank()) "Connect your own AI provider to generate study material and chat." else provider + " connected • " + selectedIds.size + " source(s) selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }}
                item { Button({ sources = studySources(context, store); page = "sources"; error = "" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Select Notes & Lecture Files") } }
                item { Button({ chatId=System.currentTimeMillis().toString(); chat=emptyList(); chatAttachment=null; page="chat"; error="" },Modifier.fillMaxWidth()){ Icon(Icons.Default.AddComment,null); Spacer(Modifier.width(8.dp)); Text("New AI Chat") } }
                item { OutlinedButton({ chatHistory=chatStore.list(); page="chat_history" },Modifier.fillMaxWidth()){ Icon(Icons.Default.History,null); Spacer(Modifier.width(8.dp)); Text("Chat History ("+chatHistory.size+")") } }
                item { OutlinedButton({ page = "packs" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bookmark, null); Spacer(Modifier.width(8.dp)); Text("My Study Packs (" + packs.size + ")") } }
                item { Text("Study tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                item { Card(campusTileModifier(Modifier.fillMaxWidth()).clickable { if (hasSources()) page = "builder" }) {
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
                    Card(campusTileModifier(Modifier.fillMaxWidth()).clickable { selectedIds = if (selected) selectedIds - source.id else selectedIds + source.id },
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
                        Card(campusTileModifier(Modifier.fillMaxWidth()).clickable { action = item }, colors = CardDefaults.cardColors(if (action == item) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
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
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){
                    if(chat.isEmpty()) item { Text("Ask anything. You can also attach a photo, screenshot, PDF, document, or text file.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(chat){ message ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(message.role=="user") Arrangement.End else Arrangement.Start){
                            Surface(Modifier.widthIn(max=340.dp),shape=RoundedCornerShape(18.dp),color=if(message.role=="user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow){
                                Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                                    if(message.attachmentName.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically){ Icon(if(message.attachmentMime.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(message.attachmentName,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold) }
                                    if(message.text.isNotBlank()){
                                        SelectionContainer {
                                            if(message.role=="assistant") AiRichMessage(message.text)
                                            else Text(message.text,style=MaterialTheme.typography.bodyLarge)
                                        }
                                    }                                }
                            }
                        }
                    }
                    if(busy) item { Row(verticalAlignment=Alignment.CenterVertically){ CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Thinking…") } }
                }
                if(chatAttachment!=null) Surface(Modifier.fillMaxWidth().padding(horizontal=10.dp),color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(12.dp)){
                    Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){ Icon(if(chatAttachment!!.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile,null); Spacer(Modifier.width(8.dp)); Text(chatAttachment!!.name,Modifier.weight(1f),maxLines=1); IconButton(onClick={chatAttachment=null}){Icon(Icons.Default.Close,"Remove attachment")} }
                }
                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.Bottom){
                    Box{
                        IconButton(onClick={attachmentMenu=true}){Icon(Icons.Default.AttachFile,"Attach")}
                        DropdownMenu(expanded=attachmentMenu,onDismissRequest={attachmentMenu=false}){
                            DropdownMenuItem(text={Text("Photo or screenshot")},onClick={attachmentMenu=false;imagePicker.launch("image/*")},leadingIcon={Icon(Icons.Default.Image,null)})
                            DropdownMenuItem(text={Text("File")},onClick={attachmentMenu=false;filePicker.launch(arrayOf("application/pdf","text/*","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","*/*"))},leadingIcon={Icon(Icons.Default.InsertDriveFile,null)})
                        }
                    }
                    OutlinedTextField(chatInput,{chatInput=it},Modifier.weight(1f),label={Text("Message CampusOS AI")},maxLines=4)
                    IconButton(enabled=(chatInput.isNotBlank()||chatAttachment!=null)&&!busy,onClick={
                        val q=chatInput.trim(); val attachment=chatAttachment; chatInput=""; chatAttachment=null
                        if(q.isNotBlank()||attachment!=null){
                            if(chatId.isBlank()) chatId=System.currentTimeMillis().toString()
                            val userMessage=AiChatMessage("user",q,attachment?.name.orEmpty(),attachment?.mimeType.orEmpty(),attachment?.localPath.orEmpty())
                            chat=chat+userMessage
                            chatStore.save(AiChatConversation(chatId,safeChatTitle(q.ifBlank{attachment?.name.orEmpty()}),chat.first().createdAt,System.currentTimeMillis(),chat)); chatHistory=chatStore.list(); busy=true; error=""
                            val prompt=aiChatPrompt(chat.dropLast(1),q.ifBlank{"Please analyze the attached file."})+(if(attachment!=null) "\\n\\n"+chatAttachmentPrompt(listOf(attachment)) else "")
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch{
                                studyAiCall(provider,model,apiKey.trim(),prompt,listOfNotNull(attachment),store,{ name, _ -> gateTool(name) }).onSuccess{
                                    chat=chat+AiChatMessage("assistant",it)
                                    chatStore.save(AiChatConversation(chatId,safeChatTitle(chat.firstOrNull()?.text.orEmpty()),chat.firstOrNull()?.createdAt ?: System.currentTimeMillis(),System.currentTimeMillis(),chat)); chatHistory=chatStore.list()
                                }.onFailure{error=it.message?:"AI request failed."}
                                busy=false
                            }
                        }
                    }){Icon(Icons.Default.Send,"Send")}
                }
                if(error.isNotBlank()) Text(error,Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error)
            }
            "chat_history" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                item { Button({chatId=System.currentTimeMillis().toString();chat=emptyList();page="chat"},Modifier.fillMaxWidth()){Icon(Icons.Default.AddComment,null);Spacer(Modifier.width(8.dp));Text("New Chat")} }
                if(chatHistory.isEmpty()) item { Text("No saved chats yet.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                items(chatHistory,key={it.id}){ conversation ->
                    Card(campusTileModifier(Modifier.fillMaxWidth()).clickable{chatId=conversation.id;chat=conversation.messages;chatAttachment=null;page="chat"}){
                        Column(Modifier.padding(14.dp)){Text(conversation.title,fontWeight=FontWeight.Bold);Text(conversation.messages.count{it.role=="user"}.toString()+" messages",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
            "packs" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (packs.isEmpty()) item { Text("No saved Study Packs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(packs) { pack -> Card(campusTileModifier(Modifier.fillMaxWidth()).clickable { resultTitle = pack.title; result = pack.body; page = "result" }) {
                    Column(Modifier.padding(14.dp)) { Text(pack.title, fontWeight = FontWeight.Bold); Text(pack.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }}
            }
            "settings" -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BYOK: your provider key is encrypted locally with Android Keystore. CampusOS does not put it in Supabase or source code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Provider", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Gemini","OpenRouter").forEach { p ->
                    FilterChip(provider == p, { secure.setApiKey(provider, apiKey.trim()); provider = p; secure.setProvider(p); model = defaultStudyModel(p); secure.setModel(model); apiKey = secure.getApiKey(p) }, label = { Text(p) })
                }}
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (provider == "Gemini") "Gemini API key" else "OpenRouter API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(model, {}, Modifier.fillMaxWidth(), label = { Text("Free model") }, singleLine = true, readOnly = true)
                Text(if (provider == "OpenRouter") "OpenRouter automatically selects an available free model. CampusOS only sends requests to the free router and will never select or fall back to a paid model." else "Gemini uses the fixed free-tier model configured by CampusOS. Paid model choices and fallback models are not used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var historyLimit by remember { mutableIntStateOf(chatStore.historyLimit()) }
                var historyDays by remember { mutableIntStateOf(chatStore.historyDays()) }
                var openChangedModule by remember { mutableStateOf(store.aiOpenChangedModule()) }
                Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Open changed module after AI action", fontWeight = FontWeight.Bold)
                            Text("After CampusOS AI adds or changes something, automatically open the related module. Turn this off if you want to stay in the AI chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = openChangedModule, onCheckedChange = { openChangedModule = it; store.setAiOpenChangedModule(it) })
                    }
                }
                Card(campusTileModifier(Modifier.fillMaxWidth()), colors = campusTileColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Chat memory & history", fontWeight = FontWeight.Bold)
                        Text(
                            "Choose how many conversations CampusOS keeps and how long they stay. The first limit reached removes the oldest saved chats. This history is stored locally on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Maximum saved conversations: " + if (historyLimit == 0) "Unlimited" else historyLimit.toString(),
                            style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 30, 50, 100, 0).forEach { value ->
                                FilterChip(
                                    selected = historyLimit == value,
                                    onClick = { historyLimit = value; chatStore.setHistoryLimit(value); chatHistory = chatStore.list() },
                                    label = { Text(if (value == 0) "Unlimited" else value.toString()) }
                                )
                            }
                        }
                        Text("Keep conversations for: " + if (historyDays == 0) "Forever" else "$historyDays day" + if (historyDays == 1) "" else "s",
                            style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 7, 30, 90, 365, 0).forEach { value ->
                                FilterChip(
                                    selected = historyDays == value,
                                    onClick = { historyDays = value; chatStore.setHistoryDays(value); chatHistory = chatStore.list() },
                                    label = { Text(if (value == 0) "Forever" else if (value == 1) "1 day" else "$value days") }
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { chatStore.deleteAll(); chatHistory = emptyList(); chat = emptyList(); chatId = System.currentTimeMillis().toString() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear All Chat History")
                        }
                    }
                }
                Button({
                    runCatching { secure.setApiKey(provider, apiKey.trim()); apiKey = secure.getApiKey(provider) }
                        .onSuccess { error = "API key saved securely on this device." }
                        .onFailure { error = it.message ?: "Could not save the API key on this device." }
                }, Modifier.fillMaxWidth()) { Text("Save API Key") }
                OutlinedButton({
                    runCatching { secure.setApiKey(provider, apiKey.trim()); apiKey = secure.getApiKey(provider) }
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
