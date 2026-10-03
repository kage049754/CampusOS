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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
data class AiChatAttachment(val name: String, val mimeType: String, val localPath: String = "", val base64: String = "", val extractedText: String = "")
data class AiChatMessage(val role: String, val text: String, val attachmentName: String = "", val attachmentMime: String = "", val attachmentPath: String = "", val createdAt: Long = System.currentTimeMillis())
data class AiChatConversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val messages: List<AiChatMessage>)

private class AiChatStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "ai_chats").apply { mkdirs() }
    fun list(): List<AiChatConversation> = dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }?.sortedByDescending { it.updatedAt }.orEmpty()
    fun save(chat: AiChatConversation) { File(dir, chat.id + ".json").writeText(toJson(chat).toString()) }
    fun delete(id: String) { File(dir, id + ".json").delete(); File(context.filesDir, "ai_chat_attachments").listFiles()?.filter { it.name.startsWith(id + "_") }?.forEach { it.delete() } }
    private fun toJson(c: AiChatConversation) = JSONObject().apply { put("id", c.id); put("title", c.title); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt); put("messages", JSONArray().apply { c.messages.forEach { m -> put(JSONObject().apply { put("role", m.role); put("text", m.text); put("attachmentName", m.attachmentName); put("attachmentMime", m.attachmentMime); put("attachmentPath", m.attachmentPath); put("createdAt", m.createdAt) }) } }) }
    private fun fromJson(o: JSONObject): AiChatConversation {
        val messages = mutableListOf<AiChatMessage>(); val a = o.optJSONArray("messages")
        if (a != null) for (i in 0 until a.length()) { val m=a.optJSONObject(i) ?: continue; messages += AiChatMessage(m.optString("role"),m.optString("text"),m.optString("attachmentName"),m.optString("attachmentMime"),m.optString("attachmentPath"),m.optLong("createdAt")) }
        return AiChatConversation(o.optString("id"), o.optString("title").ifBlank { "New chat" }, o.optLong("createdAt"), o.optLong("updatedAt"), messages)
    }
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

private fun aiChatPrompt(history: List<AiChatMessage>, newText: String): String { val previous=history.takeLast(20).joinToString("\\n") { (if(it.role=="user") "STUDENT" else "ASSISTANT")+": "+it.text }; return "You are CampusOS AI, a helpful student assistant. Answer directly and clearly. Preserve Markdown formatting when useful. When showing source code, ALWAYS put executable code inside fenced Markdown code blocks with the language name. Never put code in ordinary prose.\\n\\n"+(if(previous.isBlank()) "" else "CONVERSATION:\\n"+previous+"\\n\\n")+"STUDENT QUESTION:\\n"+newText }
private fun chatAttachmentPrompt(attachments: List<AiChatAttachment>): String = attachments.filter { it.extractedText.isNotBlank() }.joinToString("\\n\\n") { "ATTACHED FILE: "+it.name+"\\n"+it.extractedText.take(120000) }

@Composable private fun AiRichMessage(text: String) {
    val clipboard=LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val fence="`"+"`"+"`"; val regex=Regex("(?s)"+fence+"([\\\\w+-]*)\\\\n?(.*?)"+fence); val matches=regex.findAll(text).toList()
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
    tool("get_schedule","Read the student's current schedule.",JSONObject())
    tool("get_tasks","Read the student's current tasks.",JSONObject())
    tool("add_schedule","Add one class to the student's schedule.",scheduleProps,listOf("title","day","startTime","endTime"))
    tool("edit_schedule","Edit an existing schedule entry. Prefer id; otherwise title/day.",scheduleProps)
    tool("delete_schedule","Delete a schedule entry. Prefer id; otherwise title/day.",scheduleProps)
    tool("add_task","Add a task.",taskProps,listOf("title")); tool("edit_task","Edit an existing task. Prefer id; otherwise title.",taskProps); tool("delete_task","Delete a task. Prefer id; otherwise title.",taskProps)
    tool("add_subject","Add a subject.",subjectProps,listOf("title")); tool("edit_subject","Edit an existing subject. Prefer id; otherwise title.",subjectProps); tool("delete_subject","Delete a subject. Prefer id; otherwise title.",subjectProps)
}

private fun campusAiRecordJson(r: Record) = JSONObject().apply {
    put("id",r.id); put("title",r.title); put("subtitle",r.subtitle); put("extra",r.extra); put("done",r.done); put("day",r.day); put("startTime",r.startTime); put("endTime",r.endTime); put("room",r.room); put("professor",r.professor); put("classType",r.classType); put("dueDate",r.dueDate); put("dueTime",r.dueTime)
}

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
        "get_schedule" -> JSONArray(store.get("schedule").map(::campusAiRecordJson)).toString()
        "get_tasks" -> JSONArray(store.get("tasks").map(::campusAiRecordJson)).toString()
        "add_schedule" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),day=args.optString("day"),startTime=args.optString("startTime"),endTime=args.optString("endTime"),room=args.optString("room"),classType=args.optString("classType","Lecture")); store.put("schedule",store.get("schedule")+r); "Added schedule entry "+r.title+" on "+r.day+" "+r.startTime+"-"+r.endTime+"." }
        "edit_schedule" -> { val list=store.get("schedule").toMutableList(); val old=find(list) ?: return "No matching schedule entry was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("schedule",list); "Updated schedule entry "+list[index].title+"." }
        "delete_schedule" -> { val list=store.get("schedule"); val old=find(list) ?: return "No matching schedule entry was found."; store.put("schedule",list.filterNot{it.id==old.id}); "Deleted schedule entry "+old.title+"." }
        "add_task" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),extra=args.optString("extra"),dueDate=args.optString("dueDate"),dueTime=args.optString("dueTime")); store.put("tasks",store.get("tasks")+r); "Added task "+r.title+"." }
        "edit_task" -> { val list=store.get("tasks").toMutableList(); val old=find(list) ?: return "No matching task was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("tasks",list); "Updated task "+list[index].title+"." }
        "delete_task" -> { val list=store.get("tasks"); val old=find(list) ?: return "No matching task was found."; store.put("tasks",list.filterNot{it.id==old.id}); "Deleted task "+old.title+"." }
        "add_subject" -> { val r=Record(title=args.optString("title"),subtitle=args.optString("subtitle"),room=args.optString("room"),professor=args.optString("professor")); store.put("subjects",store.get("subjects")+r); "Added subject "+r.title+"." }
        "edit_subject" -> { val list=store.get("subjects").toMutableList(); val old=find(list) ?: return "No matching subject was found."; val index=list.indexOfFirst{it.id==old.id}; list[index]=updated(old); store.put("subjects",list); "Updated subject "+list[index].title+"." }
        "delete_subject" -> { val list=store.get("subjects"); val old=find(list) ?: return "No matching subject was found."; store.put("subjects",list.filterNot{it.id==old.id}); "Deleted subject "+old.title+"." }
        else -> "Unknown CampusOS action."
    }
}

private suspend fun studyAiCall(provider: String, model: String, key: String, prompt: String, attachments: List<AiChatAttachment> = emptyList(), store: LocalStore? = null): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(key.isNotBlank()){"Add your AI API key first."}
        val safeProvider=allowedStudyProvider(provider); val safeModel=enforceFreeStudyModel(safeProvider,model); val tools=if(store!=null) campusAiToolDefinitions() else JSONArray()
        var currentPrompt=prompt
        repeat(3){ round ->
            val endpoint:String; val body:String; val headers=mutableMapOf("Content-Type" to "application/json")
            if(safeProvider=="Gemini"){
                endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+safeModel+":generateContent"
                body=JSONObject().apply{
                    val parts=JSONArray().put(JSONObject().put("text",currentPrompt))
                    attachments.filter{it.base64.isNotBlank()}.forEach{a->parts.put(JSONObject().put("inline_data",JSONObject().put("mime_type",a.mimeType).put("data",a.base64)))}
                    put("contents",JSONArray().put(JSONObject().apply{put("parts",parts)}))
                    if(tools.length()>0) { val declarations=JSONArray(); for(i in 0 until tools.length()) declarations.put(tools.optJSONObject(i)?.optJSONObject("function")); put("tools",JSONArray().put(JSONObject().put("functionDeclarations",declarations))) }
                    put("generationConfig",JSONObject().put("temperature",0.35).put("maxOutputTokens",6000))
                }.toString()
                headers["x-goog-api-key"]=key
            } else {
                endpoint="https://openrouter.ai/api/v1/chat/completions"
                body=JSONObject().apply{
                    put("model",safeModel); val content=JSONArray().put(JSONObject().put("type","text").put("text",currentPrompt))
                    attachments.filter{it.base64.isNotBlank()}.forEach{a->content.put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:"+a.mimeType+";base64,"+a.base64)))}
                    put("messages",JSONArray().put(JSONObject().put("role","user").put("content",content))); if(tools.length()>0) put("tools",tools); put("temperature",0.35)
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
                val fc=parts?.let{a->(0 until a.length()).mapNotNull{a.optJSONObject(it)}.firstOrNull{it.has("functionCall")}}
                if(fc!=null && round<2){ val call=fc.optJSONObject("functionCall")!!; val name=call.optString("name"); val args=call.optJSONObject("args")?:JSONObject(); val result=executeCampusAiTool(store,name,args); currentPrompt="CampusOS tool "+name+" executed. Result: "+result+". Now reply naturally. Do not call another tool unless necessary."; continue }
            } else if(store!=null){
                val message=json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"); val calls=message?.optJSONArray("tool_calls")
                if(calls!=null&&calls.length()>0&&round<2){ val call=calls.optJSONObject(0)!!; val fn=call.optJSONObject("function"); val name=fn?.optString("name").orEmpty(); val args=runCatching{JSONObject(fn?.optString("arguments").orEmpty())}.getOrElse{JSONObject()}; val result=executeCampusAiTool(store,name,args); currentPrompt="CampusOS tool "+name+" executed. Result: "+result+". Now reply naturally. Do not call another tool unless necessary."; continue }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusAiBubble(activity: Activity, store: LocalStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val secure = remember { StudyAiSecureStore(context) }
    val provider = secure.provider()
    val model = secure.model()
    val key = secure.getApiKey(provider)
    var input by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String,String>>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        activity.window.decorView.systemUiVisibility = 0
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 14.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.SmartToy, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CampusOS AI", fontWeight = FontWeight.Bold)
                    Text("Ask or manage your CampusOS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            if (messages.isEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("What's my next class?","Show my tasks","Add a task").forEach { suggestion ->
                        AssistChip(onClick={input=suggestion},label={Text(suggestion)})
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min=60.dp,max=280.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(messages) { (role,text) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if(role=="user") Arrangement.End else Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if(role=="user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(text, Modifier.padding(horizontal=12.dp,vertical=9.dp))
                        }
                    }
                }
                if (busy) item {
                    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text("Thinking…",Modifier.padding(horizontal=12.dp,vertical=9.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment=Alignment.Bottom) {
                OutlinedTextField(
                    value=input,onValueChange={input=it},modifier=Modifier.weight(1f),
                    placeholder={Text("Ask CampusOS AI…")},maxLines=3,shape=RoundedCornerShape(22.dp)
                )
                IconButton(enabled=input.isNotBlank()&&!busy,onClick={
                    val q=input.trim(); input=""; messages=messages+("user" to q); busy=true; error=""
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        studyAiCall(
                            provider,model,key,
                            "You are the CampusOS AI assistant. You can read and modify the student's CampusOS schedule, tasks, and subjects using the provided tools. Use tools when the student asks to add, edit, delete, or check CampusOS data. Be concise. If a requested change is ambiguous, ask a question instead of guessing. Student request: " + q,
                            store=store
                        ).onSuccess { messages=messages+("assistant" to it) }
                         .onFailure { error=it.message ?: "AI request failed." }
                        busy=false
                    }
                }) { Icon(Icons.Default.Send,"Send") }
            }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyMakerScreen(activity: Activity, store: LocalStore, done: () -> Unit) {
    if (!STUDY_AI_EXPERIMENT) { done(); return }
    val context = LocalContext.current
    val secure = remember { StudyAiSecureStore(context) }
    var provider by remember { mutableStateOf(secure.provider()) }
    var model by remember { mutableStateOf(secure.model()) }
    var apiKey by remember { mutableStateOf(secure.getApiKey(provider)) }
    var page by rememberSaveable { mutableStateOf("home") }
    var sources by remember { mutableStateOf(emptyList<StudySource>()) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var action by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var resultTitle by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chat by remember { mutableStateOf(listOf<AiChatMessage>()) }
    var chatId by rememberSaveable { mutableStateOf("") }
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
    BackHandler { when { page=="chat" -> { chatHistory=chatStore.list(); page="home" }; page=="chat_history" -> page="home"; page=="home" -> done(); else -> page="home" } }

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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (page == "home") "CampusOS AI" else when (page) {
                "sources" -> "Select Materials"; "builder" -> "Create Study Material"; "result" -> resultTitle
                "chat" -> "Chat"; "packs" -> "My Study Packs"; "settings" -> "AI Settings"; else -> "CampusOS AI"
            }, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ if (page == "home") done() else page = "home" }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { if (page == "home") IconButton({ page = "settings"; error = "" }) { Icon(Icons.Default.Settings, "AI settings") } }
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
                                    if(message.text.isNotBlank()){ if(message.role=="assistant") AiRichMessage(message.text) else Text(message.text,style=MaterialTheme.typography.bodyLarge) }
                                }
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
                                studyAiCall(provider,model,apiKey.trim(),prompt,listOfNotNull(attachment)).onSuccess{
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
