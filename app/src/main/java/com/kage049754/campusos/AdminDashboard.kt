package com.kage049754.campusos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdminStorageItem(val name: String, val id: String, val size: Long, val createdAt: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { OnlineCampusClient(context) }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<OnlineProfile?>(null) }
    var users by remember { mutableStateOf<List<OnlineUser>>(emptyList()) }
    var announcements by remember { mutableStateOf<List<OnlineAnnouncement>>(emptyList()) }
    var storage by remember { mutableStateOf<List<AdminStorageItem>>(emptyList()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            error = ""
            runCatching {
                val p = client.loadProfile()
                require(p.status == "approved" && p.role == "admin") { "Admin access is required." }
                AdminLoad(p, client.listUsers(), client.listAnnouncements(), client.listAnnouncementStorage())
            }.onSuccess {
                profile = it.profile
                users = it.users
                announcements = it.announcements
                storage = it.storage
            }.onFailure { error = it.message ?: "Could not load admin data." }
            loading = false
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                loading = true
                var success = 0
                val failures = mutableListOf<String>()
                uris.take(20).forEach { uri ->
                    client.adminUploadImage(uri).onSuccess { success++ }.onFailure { failures += it.message ?: "Upload failed." }
                }
                if (success > 0) message = "Uploaded " + success + " image(s)."
                if (failures.isNotEmpty()) error = failures.joinToString("\n")
                refresh()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CampusOS Admin") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            profile?.let { p ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Administrator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(p.fullName)
                        Text(listOf(p.schoolId, p.yearSection).filter { it.isNotBlank() }.joinToString(" • "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Full CampusOS admin access", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (error.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text(error, Modifier.weight(1f))
                        TextButton(onClick = { error = "" }) { Text("Dismiss") }
                    }
                }
            }
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Students") }, icon = { Icon(Icons.Default.People, null) })
                Tab(tab == 1, { tab = 1 }, text = { Text("Announcements") }, icon = { Icon(Icons.Default.Campaign, null) })
                Tab(tab == 2, { tab = 2 }, text = { Text("Storage") }, icon = { Icon(Icons.Default.Cloud, null) })
            }

            if (loading && profile == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                when (tab) {
                    0 -> AdminStudentsTab(users, client, scope, { message = it; refresh() }, { error = it })
                    1 -> AdminAnnouncementsTab(announcements, client, scope, { message = it; refresh() }, { error = it })
                    else -> AdminStorageTab(storage, client, scope, { message = it; refresh() }, { error = it }, { uploadLauncher.launch("image/*") })
                }
            }
        }
    }
}

private data class AdminLoad(
    val profile: OnlineProfile,
    val users: List<OnlineUser>,
    val announcements: List<OnlineAnnouncement>,
    val storage: List<AdminStorageItem>
)

@Composable
private fun AdminStudentsTab(users: List<OnlineUser>, client: OnlineCampusClient, scope: CoroutineScope, onMessage: (String) -> Unit, onError: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("pending") }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = users.filter {
        (filter == "all" || it.status == filter) &&
            (search.isBlank() || it.fullName.contains(search, true) || it.schoolId.contains(search, true) || it.yearSection.contains(search, true))
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search students") }, leadingIcon = { Icon(Icons.Default.Search, null) }) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("pending", "approved", "denied", "suspended", "all").forEach { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value.replaceFirstChar { ch -> ch.uppercase() }) })
                }
            }
        }
        item { Text(filtered.size.toString() + " account(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(filtered, key = { it.id }) { user ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(user.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOf(user.schoolId, user.yearSection, user.role).filter { it.isNotBlank() }.joinToString(" • "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Status: " + user.status)
                    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (user.status != "approved") Button(onClick = { scope.launch { client.updateUserStatus(user.id, "approved").onSuccess { onMessage("Approved " + user.fullName) }.onFailure { onError(it.message ?: "Approval failed.") } } }) { Text("Approve") }
                        if (user.status == "approved") OutlinedButton(onClick = { scope.launch { client.updateUserStatus(user.id, "suspended").onSuccess { onMessage("Suspended " + user.fullName) }.onFailure { onError(it.message ?: "Suspend failed.") } } }) { Text("Suspend") }
                        if (user.status == "suspended") OutlinedButton(onClick = { scope.launch { client.updateUserStatus(user.id, "approved").onSuccess { onMessage("Restored " + user.fullName) }.onFailure { onError(it.message ?: "Restore failed.") } } }) { Text("Restore") }
                        if (user.status != "denied") TextButton(onClick = { scope.launch { client.updateUserStatus(user.id, "denied").onSuccess { onMessage("Denied " + user.fullName) }.onFailure { onError(it.message ?: "Deny failed.") } } }) { Text("Deny") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Role", fontWeight = FontWeight.SemiBold)
                        listOf("student", "leader", "admin").forEach { role ->
                            FilterChip(selected = user.role == role, onClick = {
                                scope.launch { client.updateUserRole(user.id, role).onSuccess { onMessage(user.fullName + " is now " + role) }.onFailure { onError(it.message ?: "Role update failed.") } }
                            }, label = { Text(role.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAnnouncementsTab(announcements: List<OnlineAnnouncement>, client: OnlineCampusClient, scope: CoroutineScope, onMessage: (String) -> Unit, onError: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(announcements.size.toString() + " announcement(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (announcements.isEmpty()) item { Text("No announcements yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(announcements, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.authorName, fontWeight = FontWeight.Bold)
                    Text(item.body)
                    Text(SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(item.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.images.size.toString() + " image(s) • " + item.links.size + " link(s)", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { client.deleteAnnouncement(item.id).onSuccess { onMessage("Announcement deleted.") }.onFailure { onError(it.message ?: "Delete failed.") } } }) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminStorageTab(items: List<AdminStorageItem>, client: OnlineCampusClient, scope: CoroutineScope, onMessage: (String) -> Unit, onError: (String) -> Unit, onUpload: () -> Unit) {
    val total = items.sumOf { it.size }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Announcement storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(items.size.toString() + " file(s) • " + formatAdminBytes(total))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onUpload) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Upload image") }
                    Text("Private campus-announcements bucket • 6 MB per image • JPEG/PNG/WebP.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (items.isEmpty()) item { Text("Storage is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(items, key = { it.id }) { file ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontWeight = FontWeight.SemiBold)
                        Text(formatAdminBytes(file.size) + if (file.createdAt.isNotBlank()) " • " + file.createdAt else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { client.deleteAnnouncementStorage(file.name).onSuccess { onMessage("File deleted.") }.onFailure { onError(it.message ?: "Storage delete failed.") } } }) { Icon(Icons.Default.Delete, "Delete file") }
                }
            }
        }
    }
}

private fun formatAdminBytes(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    if (bytes < 1024 * 1024) return (bytes / 1024).toString() + " KB"
    return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
}
