package com.kage049754.campusos

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val CAMPUS_SUPABASE = "https://pgniovlvofvkwjhyoqcg.supabase.co"
private const val CAMPUS_KEY = "sb_publishable_UghfMQF0mqMdDL3-i8TvUQ_t3pWFwoe"

data class CampusSession(val accessToken: String, val userId: String, val email: String)
data class CampusAnnouncement(val author: String, val body: String, val createdAt: String)
data class CampusGroup(val id: String, val name: String)
data class CampusMessage(val sender: String, val body: String, val createdAt: String)

object CampusNativeApi {
    private fun request(path: String, method: String = "GET", token: String? = null, body: String? = null): String {
        val c = URL(CAMPUS_SUPABASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("apikey", CAMPUS_KEY)
        c.setRequestProperty("Authorization", "Bearer " + (token ?: CAMPUS_KEY))
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        if (body != null) {
            c.doOutput = true
            OutputStreamWriter(c.outputStream).use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw IllegalStateException(runCatching {
                val o = JSONObject(text)
                o.optString("msg").ifBlank { o.optString("message") }.ifBlank { o.optString("error_description") }
            }.getOrDefault("").ifBlank { "Request failed ($code)" })
        }
        return text
    }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val o = JSONObject(request("/auth/v1/token?grant_type=password", "POST",
            body = JSONObject().put("email", email).put("password", password).toString()))
        val u = o.getJSONObject("user")
        CampusSession(o.getString("access_token"), u.getString("id"), email)
    }

    suspend fun register(email: String, password: String, name: String, schoolId: String, section: String) =
        withContext(Dispatchers.IO) {
            request("/functions/v1/register-account", "POST",
                body = JSONObject().put("email", email).put("password", password)
                    .put("full_name", name).put("school_id", schoolId).put("year_section", section).toString())
            signIn(email, password)
        }

    suspend fun announcements(s: CampusSession) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/announcements?select=author_name,author_page_name,body,created_at&order=created_at.desc", token = s.accessToken))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CampusAnnouncement(o.optString("author_page_name").ifBlank { o.optString("author_name", "CampusOS") },
                o.optString("body"), o.optString("created_at"))
        }
    }

    suspend fun groups(s: CampusSession) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/chat_members?select=group_id,chat_groups(id,name)&user_id=eq." + s.userId, token = s.accessToken))
        (0 until a.length()).mapNotNull {
            val g = a.getJSONObject(it).optJSONObject("chat_groups") ?: return@mapNotNull null
            CampusGroup(g.optString("id"), g.optString("name", "Group"))
        }
    }

    suspend fun messages(s: CampusSession, groupId: String) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/chat_messages?select=body,created_at,profiles(full_name)&group_id=eq." + groupId + "&order=created_at.asc&limit=100", token = s.accessToken))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CampusMessage(o.optJSONObject("profiles")?.optString("full_name", "Member") ?: "Member",
                o.optString("body"), o.optString("created_at"))
        }
    }

    suspend fun send(s: CampusSession, groupId: String, body: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/chat_messages", "POST", s.accessToken,
            JSONObject().put("group_id", groupId).put("sender_id", s.userId).put("body", body).toString())
    }
}

@Composable
fun NativeLoginScreen(onSuccess: (CampusSession) -> Unit) {
    var signup by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var schoolId by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("CampusOS", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Campus community", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (signup) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(schoolId, { schoolId = it }, Modifier.fillMaxWidth(), label = { Text("School ID") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(section, { section = it }, Modifier.fillMaxWidth(), label = { Text("Year & section") })
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
        if (error.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            scope.launch {
                busy = true
                error = ""
                runCatching {
                    if (signup) CampusNativeApi.register(email.trim().lowercase(), password, name.trim(), schoolId.trim(), section.trim())
                    else CampusNativeApi.signIn(email.trim().lowercase(), password)
                }.onSuccess { onSuccess(it) }
                 .onFailure { error = it.message ?: "Sign in failed"; busy = false }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Signing in…" else if (signup) "Create account" else "Sign in") }
        TextButton(onClick = { signup = !signup; error = "" }) {
            Text(if (signup) "Sign in" else "Create account")
        }
    }
}

@Composable
fun NativeAnnouncementsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = context.getSharedPreferences("campusos_auth", Context.MODE_PRIVATE)
    val session = remember { CampusSession(p.getString("token", "") ?: "", p.getString("uid", "") ?: "", p.getString("email", "") ?: "") }
    var items by remember { mutableStateOf<List<CampusAnnouncement>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { items = CampusNativeApi.announcements(session) }
            .onFailure { error = it.message ?: "Unable to load announcements" }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Announcements", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        items(items) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.author, fontWeight = FontWeight.Bold)
                    Text(a.body)
                    Text(a.createdAt.replace("T", " ").replace("Z", ""), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun NativeChatScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = context.getSharedPreferences("campusos_auth", Context.MODE_PRIVATE)
    val session = remember { CampusSession(p.getString("token", "") ?: "", p.getString("uid", "") ?: "", p.getString("email", "") ?: "") }
    var groups by remember { mutableStateOf<List<CampusGroup>>(emptyList()) }
    var selected by remember { mutableStateOf<CampusGroup?>(null) }
    var messages by remember { mutableStateOf<List<CampusMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { groups = CampusNativeApi.groups(session); if (groups.size == 1) selected = groups.first() }
            .onFailure { error = it.message ?: "Unable to load group chats" }
    }
    LaunchedEffect(selected?.id) {
        selected?.let { g -> runCatching { messages = CampusNativeApi.messages(session, g.id) }
            .onFailure { error = it.message ?: "Unable to load messages" } }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Private GCs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            groups.forEach { g -> FilterChip(selected?.id == g.id, { selected = g }, label = { Text(g.name) }) }
        }
        selected?.let { g ->
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.sender, fontWeight = FontWeight.Bold)
                            Text(m.body)
                            Text(m.createdAt.replace("T", " ").replace("Z", ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Message…") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val body = text.trim()
                    if (body.isNotBlank()) scope.launch {
                        runCatching { CampusNativeApi.send(session, g.id, body); messages = CampusNativeApi.messages(session, g.id); text = "" }
                            .onFailure { error = it.message ?: "Send failed" }
                    }
                }) { Text("Send") }
            }
        }
    }
}
