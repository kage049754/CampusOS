package com.kage049754.campusos

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

data class CampusSession(val accessToken: String, val userId: String, val email: String, val role: String = "student")
data class CampusAnnouncement(
    val id: String, val authorId: String, val author: String, val pagePhotoPath: String?, val body: String,
    val createdAt: String, val imagePaths: List<String>
)
data class CampusGroup(val id: String, val name: String, val ownerId: String = "", val inviteToken: String = "")
data class CampusMessage(val senderId: String, val sender: String, val body: String, val createdAt: String)
data class CampusProfile(val id: String, val fullName: String, val schoolId: String, val section: String, val status: String, val role: String)
data class CampusLeaderAssignment(
    val userId: String, val title: String, val organizationType: String,
    val organizationName: String, val canAnnounce: Boolean, val active: Boolean,
    val pageEnabled: Boolean, val pageName: String
)

object CampusNativeApi {
    private fun request(path: String, method: String = "GET", token: String? = null, body: String? = null): String {
        val c = URL(CAMPUS_SUPABASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("apikey", CAMPUS_KEY)
        c.setRequestProperty("Authorization", "Bearer " + (token ?: CAMPUS_KEY))
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Prefer", "return=representation")
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
        CampusSession(o.getString("access_token"), u.getString("id"), email, role = "student")
    }

    suspend fun register(email: String, password: String, name: String, schoolId: String, section: String) =
        withContext(Dispatchers.IO) {
            request("/functions/v1/register-account", "POST",
                body = JSONObject().put("email", email).put("password", password)
                    .put("full_name", name).put("school_id", schoolId).put("year_section", section).toString())
            signIn(email, password)
        }

    suspend fun loadRole(s: CampusSession): CampusSession = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/profiles?select=role&id=eq." + s.userId + "&limit=1", token = s.accessToken))
        val role = if (a.length() > 0) a.getJSONObject(0).optString("role", "student") else "student"
        s.copy(role = role)
    }

    suspend fun profiles(s: CampusSession) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/profiles?select=id,full_name,school_id,year_section,status,role&order=full_name.asc", token = s.accessToken))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CampusProfile(o.optString("id"), o.optString("full_name", "Student"), o.optString("school_id"), o.optString("year_section"), o.optString("status", "pending"), o.optString("role", "student"))
        }
    }

    suspend fun updateProfileRoleOrStatus(s: CampusSession, userId: String, role: String, status: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/profiles?id=eq." + userId, "PATCH", s.accessToken, JSONObject().put("role", role).put("status", status).toString())
    }

    suspend fun leaderAssignments(s: CampusSession) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/leader_assignments?select=user_id,title,organization_type,organization_name,can_announce,active,page_enabled,page_name&order=organization_name.asc", token = s.accessToken))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CampusLeaderAssignment(o.optString("user_id"), o.optString("title"), o.optString("organization_type"), o.optString("organization_name"), o.optBoolean("can_announce"), o.optBoolean("active", true), o.optBoolean("page_enabled"), o.optString("page_name"))
        }
    }

    suspend fun saveLeaderAssignment(s: CampusSession, userId: String, title: String, organizationType: String, organizationName: String, canAnnounce: Boolean, active: Boolean, pageEnabled: Boolean, pageName: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/leader_assignments?on_conflict=user_id", "POST", s.accessToken,
            JSONObject().put("user_id", userId).put("title", title).put("organization_type", organizationType).put("organization_name", organizationName).put("can_announce", canAnnounce).put("active", active).put("page_enabled", pageEnabled).put("page_name", pageName).put("assigned_by", s.userId).toString())
    }

    suspend fun announcements(s: CampusSession) = withContext(Dispatchers.IO) {
        val a = JSONArray(request(
            "/rest/v1/announcements?select=id,author_id,author_name,author_page_name,author_page_photo_path,body,created_at,announcement_images(storage_path,sort_order)&order=created_at.desc",
            token = s.accessToken
        ))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val images = mutableListOf<Pair<Int, String>>()
            val ia = o.optJSONArray("announcement_images")
            if (ia != null) for (i in 0 until ia.length()) {
                val io = ia.getJSONObject(i)
                val path = io.optString("storage_path")
                if (path.isNotBlank()) images += io.optInt("sort_order", i) to path
            }
            CampusAnnouncement(
                o.optString("id"), o.optString("author_id"),
                o.optString("author_page_name").ifBlank { o.optString("author_name", "CampusOS") },
                o.optString("author_page_photo_path").ifBlank { null },
                o.optString("body"), o.optString("created_at"),
                images.sortedBy { it.first }.take(4).map { it.second }
            )
        }
    }

    suspend fun createAnnouncement(s: CampusSession, body: String) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/announcements", "POST", s.accessToken, JSONObject().put("author_id", s.userId).put("author_name", s.email).put("body", body.trim()).toString()))
        if (a.length() == 0) error("Announcement was not created")
        a.getJSONObject(0).getString("id")
    }
    suspend fun updateAnnouncement(s: CampusSession, id: String, body: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/announcements?id=eq.$id", "PATCH", s.accessToken, JSONObject().put("body", body.trim()).toString())
    }
    suspend fun deleteAnnouncement(s: CampusSession, id: String, imagePaths: List<String>) = withContext(Dispatchers.IO) {
        imagePaths.forEach { path -> runCatching { request("/storage/v1/object/campus-announcements/$path", "DELETE", s.accessToken) } }
        request("/rest/v1/announcement_images?announcement_id=eq.$id", "DELETE", s.accessToken)
        request("/rest/v1/announcements?id=eq.$id", "DELETE", s.accessToken)
    }
    suspend fun uploadAnnouncementImage(s: CampusSession, path: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        val c = URL(CAMPUS_SUPABASE + "/storage/v1/object/campus-announcements/" + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("apikey", CAMPUS_KEY); c.setRequestProperty("Authorization", "Bearer " + s.accessToken); c.setRequestProperty("Content-Type", contentType)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode !in 200..299) throw IllegalStateException("Image upload failed")
    }
    suspend fun addAnnouncementImage(s: CampusSession, announcementId: String, path: String, sortOrder: Int) = withContext(Dispatchers.IO) {
        request("/rest/v1/announcement_images", "POST", s.accessToken, JSONObject().put("announcement_id", announcementId).put("storage_path", path).put("sort_order", sortOrder).toString())
    }

    suspend fun downloadAnnouncementImage(s: CampusSession, path: String) = withContext(Dispatchers.IO) {
        val c = URL(CAMPUS_SUPABASE + "/storage/v1/object/authenticated/campus-announcements/" + path)
            .openConnection() as HttpURLConnection
        c.setRequestProperty("apikey", CAMPUS_KEY)
        c.setRequestProperty("Authorization", "Bearer " + s.accessToken)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val code = c.responseCode
        if (code !in 200..299) return@withContext null
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    }

    suspend fun groups(s: CampusSession): List<CampusGroup> = withContext(Dispatchers.IO) {
        val path = if (s.role.equals("admin", true)) "/rest/v1/chat_groups?select=id,name,owner_id,invite_token&order=name.asc" else "/rest/v1/chat_members?select=chat_groups(id,name,owner_id,invite_token)&user_id=eq." + s.userId
        val rows = JSONArray(request(path, token = s.accessToken))
        (0 until rows.length()).mapNotNull { i ->
            val g = if (s.role.equals("admin", true)) rows.optJSONObject(i) else rows.optJSONObject(i)?.optJSONObject("chat_groups")
            if (g == null) null else CampusGroup(g.optString("id"), g.optString("name", "Group"), g.optString("owner_id"), g.optString("invite_token"))
        }
    }

    suspend fun createGroup(s: CampusSession, name: String): CampusGroup = withContext(Dispatchers.IO) {
        val raw = request("/rest/v1/rpc/create_chat_group", "POST", s.accessToken, JSONObject().put("p_name", name.trim()).put("p_photo_path", JSONObject.NULL).toString())
        val id = raw.trim().trim('"')
        val rows = JSONArray(request("/rest/v1/chat_groups?select=id,name,owner_id,invite_token&id=eq." + id, token = s.accessToken))
        if (rows.length() == 0) error("Group was created but could not be loaded")
        val g = rows.getJSONObject(0)
        CampusGroup(id, g.optString("name", name.trim()), g.optString("owner_id"), g.optString("invite_token"))
    }

    suspend fun deleteGroup(s: CampusSession, groupId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/delete_chat_group", "POST", s.accessToken, JSONObject().put("p_group", groupId).toString())
    }

    suspend fun searchApprovedStudents(s: CampusSession, query: String): List<CampusProfile> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val path = "/rest/v1/profiles?select=id,full_name,school_id,year_section,status,role&status=eq.approved&or=(full_name.ilike.%25" + q + "%25,school_id.ilike.%25" + q + "%25)&order=full_name.asc&limit=30"
        val a = JSONArray(request(path, token = s.accessToken))
        (0 until a.length()).map { i -> val o=a.getJSONObject(i); CampusProfile(o.optString("id"),o.optString("full_name","Student"),o.optString("school_id"),o.optString("year_section"),o.optString("status"),o.optString("role")) }.filter { it.id != s.userId }
    }

    suspend fun inviteMember(s: CampusSession, groupId: String, userId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/invite_chat_member", "POST", s.accessToken, JSONObject().put("p_group",groupId).put("p_user",userId).toString())
    }

    suspend fun messages(s: CampusSession, groupId: String) = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/chat_messages?select=sender_id,body,created_at,profiles(full_name)&group_id=eq." + groupId + "&order=created_at.asc&limit=100", token = s.accessToken))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CampusMessage(o.optString("sender_id"), o.optJSONObject("profiles")?.optString("full_name", "Member") ?: "Member",
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
                }.mapCatching { CampusNativeApi.loadRole(it) }
                 .onSuccess { onSuccess(it) }
                 .onFailure { error = it.message ?: "Sign in failed"; busy = false }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Signing in…" else if (signup) "Create account" else "Sign in") }
        TextButton(onClick = { signup = !signup; error = "" }) {
            Text(if (signup) "Sign in" else "Create account")
        }
    }
}

@Composable
private fun NativeAnnouncementImage(session: CampusSession, path: String, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) { bitmap = CampusNativeApi.downloadAnnouncementImage(session, path) }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun NativeAnnouncementsScreen() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val session=remember { AuthStore.load(context) ?: CampusSession("", "", "", "student") }
    var items by remember{mutableStateOf<List<CampusAnnouncement>>(emptyList())}; var error by remember{mutableStateOf("")}
    var composer by rememberSaveable{mutableStateOf(false)}; var editing by remember{mutableStateOf<CampusAnnouncement?>(null)}
    var draft by rememberSaveable{mutableStateOf("")}; var busy by remember{mutableStateOf(false)}; var selectedUris by remember{mutableStateOf<List<Uri>>(emptyList())}
    val scope=rememberCoroutineScope(); val canPublish=session.role.equals("admin",true)||session.role.equals("leader",true)
    fun reload(){scope.launch{runCatching{items=CampusNativeApi.announcements(session)}.onFailure{error=it.message?:"Unable to load announcements"}}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){selectedUris=it.take(4)}
    LaunchedEffect(Unit){reload()}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text("Announcements",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(canPublish)Button({editing=null;draft="";selectedUris=emptyList();composer=true}){Text("Post")}}
        if(error.isNotBlank())Text(error,Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)
        LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(items){a->Card(Modifier.fillMaxWidth()){Column{
                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.small)){if(!a.pagePhotoPath.isNullOrBlank())NativeAnnouncementImage(session,a.pagePhotoPath,Modifier.fillMaxSize())else Text(a.author.take(1).uppercase(),Modifier.align(Alignment.Center),fontWeight=FontWeight.Bold)}
                    Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(a.author,fontWeight=FontWeight.Bold);Text(a.createdAt.replace("T"," ").replace("Z",""),style=MaterialTheme.typography.bodySmall)}
                    if(a.authorId==session.userId||session.role.equals("admin",true)){var menu by remember(a.id){mutableStateOf(false)};Box{IconButton({menu=true}){Text("⋮")};DropdownMenu(menu,{menu=false}){
                        DropdownMenuItem({Text("Edit")},{menu=false;editing=a;draft=a.body;selectedUris=emptyList();composer=true})
                        DropdownMenuItem({Text("Delete")},{menu=false;scope.launch{runCatching{CampusNativeApi.deleteAnnouncement(session,a.id,a.imagePaths);reload()}.onFailure{error=it.message?:"Delete failed"}}})
                    }}}
                }
                Text(a.body,Modifier.padding(horizontal=14.dp,vertical=4.dp))
                if(a.imagePaths.isNotEmpty())Row(Modifier.fillMaxWidth().height(280.dp).horizontalScroll(rememberScrollState())){a.imagePaths.forEach{path->NativeAnnouncementImage(session,path,Modifier.fillMaxHeight().width(280.dp));Spacer(Modifier.width(2.dp))}}
            }}
        }}
    }
    if (composer) {
        AlertDialog(
            onDismissRequest = { if (!busy) composer = false },
            title = { Text(if (editing == null) "Create announcement" else "Edit announcement") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        label = { Text("Announcement") }
                    )
                    if (editing == null) {
                        OutlinedButton(
                            onClick = { picker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (selectedUris.isEmpty()) "Add up to 4 images" else "${selectedUris.size} image(s) selected")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && draft.trim().isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                val id = if (editing == null) {
                                    CampusNativeApi.createAnnouncement(session, draft)
                                } else {
                                    CampusNativeApi.updateAnnouncement(session, editing!!.id, draft)
                                    editing!!.id
                                }
                                if (editing == null) {
                                    selectedUris.forEachIndexed { index, uri ->
                                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            ?: error("Could not read image")
                                        val type = context.contentResolver.getType(uri) ?: "image/jpeg"
                                        val path = session.userId + "/" + System.currentTimeMillis() + "_" + index + "." + type.substringAfterLast('/')
                                        CampusNativeApi.uploadAnnouncementImage(session, path, bytes, type)
                                        CampusNativeApi.addAnnouncementImage(session, id, path, index)
                                    }
                                }
                                items = CampusNativeApi.announcements(session)
                                composer = false
                                selectedUris = emptyList()
                            }.onFailure { error = it.message ?: "Save failed" }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) composer = false }) { Text("Cancel") }
            }
        )
    }
}
@Composable
fun NativeChatScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember { AuthStore.load(context) ?: CampusSession("", "", "", "student") }
    var groups by remember { mutableStateOf<List<CampusGroup>>(emptyList()) }
    var selected by remember { mutableStateOf<CampusGroup?>(null) }
    var messages by remember { mutableStateOf<List<CampusMessage>>(emptyList()) }
    val messageListState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf<List<CampusProfile>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var inviteDialog by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val canCreate = session.role.equals("admin", true) || session.role.equals("leader", true)
    val isAdmin = session.role.equals("admin", true)
    fun reload() {
        scope.launch {
            loading = true
            runCatching {
                val fresh = CampusNativeApi.groups(session)
                groups = fresh
                if (selected == null || fresh.none { it.id == selected?.id }) selected = fresh.firstOrNull()
            }.onFailure { error = it.message ?: "Unable to load GCs" }
            loading = false
        }
    }
    LaunchedEffect(session.userId) { reload() }
    LaunchedEffect(selected?.id) {
        val id = selected?.id
        if (id != null) runCatching { messages = CampusNativeApi.messages(session, id) }.onFailure { error = it.message ?: "Unable to load messages" }
        else messages = emptyList()
    }
    LaunchedEffect(selected?.id, messages.size) {
        if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.size - 1)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Private groups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canCreate) Button(onClick = { groupName = ""; createDialog = true }) { Text("＋ New") }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, placeholder = { Text("Search conversations") }, leadingIcon = { Text("⌕") })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.filter { it.name.contains(search, true) }.forEach { g ->
                FilterChip(selected?.id == g.id, { selected = g }, label = { Text(g.name) })
            }
        }
        if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        selected?.let { g ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) { Box(contentAlignment = Alignment.Center) { Text(g.name.take(1).uppercase(), fontWeight = FontWeight.Bold) } }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(g.name, fontWeight = FontWeight.Bold); Text("Group conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { inviteDialog = true }) { Text("＋ Invite") }
                var menu by remember(g.id) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        TextButton(onClick = {
                            menu = false
                            val link = "https://kage049754.github.io/CampusOS-Web/?gc=" + g.inviteToken
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join " + g.name + " on CampusOS: " + link), "Share invite"))
                        }) { Text("Share invite link") }
                        if (isAdmin || g.ownerId == session.userId) TextButton(onClick = {
                            menu = false
                            scope.launch {
                                runCatching { CampusNativeApi.deleteGroup(session, g.id); selected = null; reload() }
                                    .onFailure { error = it.message ?: "Delete failed" }
                            }
                        }) { Text("Delete group", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Divider()
            LazyColumn(state = messageListState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(messages) { m ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.senderId == session.userId) Alignment.End else Alignment.Start) {
                        Surface(color = if (m.senderId == session.userId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Text(m.sender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(m.body)
                            }
                        }
                        Text(m.createdAt.replace("T"," ").replace("Z",""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message…") }, maxLines = 4)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    val body = draft.trim()
                    if (body.isNotBlank()) scope.launch {
                        runCatching { CampusNativeApi.send(session, g.id, body); messages = CampusNativeApi.messages(session, g.id); draft = "" }
                            .onFailure { error = it.message ?: "Send failed" }
                    }
                }, enabled = draft.trim().isNotBlank()) { Text("➤") }
            }
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (loading) "Loading conversations…" else "No conversations yet", fontWeight = FontWeight.Bold)
                Text(if (canCreate) "Create a private group to get started." else "Ask a group leader to invite you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canCreate) Button(onClick = { createDialog = true }) { Text("Create group") }
            }
        }
    }
    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("Create private group") },
        text = { OutlinedTextField(groupName, { groupName = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(enabled = groupName.trim().isNotBlank(), onClick = { scope.launch {
            runCatching { val made = CampusNativeApi.createGroup(session, groupName); groups = CampusNativeApi.groups(session); selected = made; createDialog = false }
                .onFailure { error = it.message ?: "Create group failed" }
        } }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } }
    )
    if (inviteDialog) AlertDialog(
        onDismissRequest = { inviteDialog = false; people = emptyList() },
        title = { Text("Invite approved students") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search name or school ID") }, singleLine = true)
                TextButton(onClick = { scope.launch { runCatching { people = CampusNativeApi.searchApprovedStudents(session, search) }.onFailure { error = it.message ?: "Search failed" } } }) { Text("Search students") }
                LazyColumn { items(people) { person ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(person.fullName, fontWeight = FontWeight.Medium); Text(person.schoolId + " • " + person.section, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { val gid = selected?.id ?: return@TextButton; scope.launch {
                            runCatching { CampusNativeApi.inviteMember(session, gid, person.id); people = people.filterNot { it.id == person.id }; error = "Invite sent" }
                                .onFailure { error = it.message ?: "Invite failed" }
                        } }) { Text("Invite") }
                    }
                } }
            }
        },
        confirmButton = { TextButton(onClick = { inviteDialog = false; people = emptyList() }) { Text("Done") } }
    )
}
@Composable
fun NativeCampusManagementScreen(session: CampusSession) {
    var profiles by remember { mutableStateOf<List<CampusProfile>>(emptyList()) }
    var assignments by remember { mutableStateOf<List<CampusLeaderAssignment>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CampusProfile?>(null) }
    var assignmentFor by remember { mutableStateOf<CampusProfile?>(null) }
    val scope = rememberCoroutineScope()
    val isAdmin = session.role.equals("admin", true)
    val isLeader = session.role.equals("leader", true) || isAdmin

    fun reload() {
        scope.launch {
            loading = true
            error = ""
            runCatching {
                if (isAdmin) profiles = CampusNativeApi.profiles(session)
                if (isLeader) assignments = CampusNativeApi.leaderAssignments(session)
            }.onFailure { error = it.message ?: "Unable to load campus management data" }
            loading = false
        }
    }
    LaunchedEffect(session.userId, session.role) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Campus Management", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Text(if (isAdmin) "ADMIN" else "LEADER", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        if (isAdmin) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("People") })
                Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("Leaders") })
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (error.isNotBlank()) {
            Column(Modifier.fillMaxSize()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { reload() }) { Text("Retry") }
            }
        } else if (isAdmin && selectedTab == 0) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { Text("Account management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(profiles) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.fullName, fontWeight = FontWeight.Bold)
                            Text(listOf(p.schoolId, p.section).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = { editing = p }, label = { Text(p.role) })
                                Spacer(Modifier.width(6.dp))
                                AssistChip(onClick = { editing = p }, label = { Text(p.status) })
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = p }) { Text("Manage") }
                            }
                        }
                    }
                }
            }
        } else if (isAdmin && selectedTab == 1) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text("Leader assignments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Configure position, club/group/org, page and announcement access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(profiles.filter { it.role.equals("leader", true) }) { p ->
                    val a = assignments.firstOrNull { it.userId == p.id }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.fullName, fontWeight = FontWeight.Bold)
                            Text(a?.organizationName?.ifBlank { "No organization" } ?: "No organization")
                            Text(a?.title?.ifBlank { "Leader" } ?: "Leader", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { assignmentFor = p }) { Text(if (a == null) "Set up leader" else "Edit assignment") }
                        }
                    }
                }
                if (profiles.none { it.role.equals("leader", true) }) item {
                    EmptyCampusManagementCard("No leader accounts yet. Change an approved person's role to leader in People first.")
                }
            }
        } else {
            val mine = assignments.firstOrNull { it.userId == session.userId }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text("Your leader access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(mine?.title?.ifBlank { "Leader" } ?: "Leader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(mine?.organizationType?.takeIf { it.isNotBlank() }, mine?.organizationName?.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { "No organization assignment found." })
                            if (!mine?.pageName.isNullOrBlank()) Text("Page: " + mine?.pageName)
                            Text(if (mine?.canAnnounce == true) "You can publish announcements." else "Announcement publishing is not enabled for your account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (mine?.pageEnabled == true) Text("Organization page: enabled")
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        var role by remember(p.id) { mutableStateOf(p.role) }
        var status by remember(p.id) { mutableStateOf(p.status) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Manage " + p.fullName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Role", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("student", "leader", "admin").forEach { value -> FilterChip(role == value, { role = value }, label = { Text(value) }) }
                    }
                    Text("Status", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("approved", "pending", "rejected").forEach { value -> FilterChip(status == value, { status = value }, label = { Text(value) }) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            CampusNativeApi.updateProfileRoleOrStatus(session, p.id, role, status)
                            profiles = CampusNativeApi.profiles(session)
                            assignments = CampusNativeApi.leaderAssignments(session)
                            editing = null
                        }.onFailure { error = it.message ?: "Unable to update account" }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    assignmentFor?.let { p ->
        val existing = assignments.firstOrNull { it.userId == p.id }
        var title by remember(p.id) { mutableStateOf(existing?.title ?: "Leader") }
        var type by remember(p.id) { mutableStateOf(existing?.organizationType ?: "Organization") }
        var org by remember(p.id) { mutableStateOf(existing?.organizationName ?: "") }
        var page by remember(p.id) { mutableStateOf(existing?.pageName ?: "") }
        var canAnnounce by remember(p.id) { mutableStateOf(existing?.canAnnounce ?: true) }
        var active by remember(p.id) { mutableStateOf(existing?.active ?: true) }
        var pageEnabled by remember(p.id) { mutableStateOf(existing?.pageEnabled ?: true) }
        AlertDialog(
            onDismissRequest = { assignmentFor = null },
            title = { Text("Leader assignment") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Position") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(type, { type = it }, label = { Text("Club / Group / Org") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(org, { org = it }, label = { Text("Organization name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(page, { page = it }, label = { Text("Page name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Can publish announcements"); Switch(canAnnounce, { canAnnounce = it }) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Organization page enabled"); Switch(pageEnabled, { pageEnabled = it }) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Active leader assignment"); Switch(active, { active = it }) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            CampusNativeApi.saveLeaderAssignment(session, p.id, title.trim(), type.trim(), org.trim(), canAnnounce, active, pageEnabled, page.trim())
                            assignments = CampusNativeApi.leaderAssignments(session)
                            assignmentFor = null
                        }.onFailure { error = it.message ?: "Unable to save leader assignment" }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { assignmentFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyCampusManagementCard(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}