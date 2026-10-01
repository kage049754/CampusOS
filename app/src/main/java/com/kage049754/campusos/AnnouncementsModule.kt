package com.kage049754.campusos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { OnlineCampusClient(context) }
    var profile by remember { mutableStateOf<OnlineProfile?>(null) }
    var announcements by remember { mutableStateOf<List<OnlineAnnouncement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showComposer by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = ""
            val session = client.restoreSession()
            profile = session.getOrNull()
            if (session.isFailure) error = session.exceptionOrNull()?.message ?: "Could not restore online session."
            if (profile?.status == "approved") {
                runCatching { client.listAnnouncements() }
                    .onSuccess { announcements = it }
                    .onFailure { error = it.message ?: "Could not load announcements." }
            } else announcements = emptyList()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Campus", fontWeight = FontWeight.Bold); Text("Announcements", style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showAccount = true }) { Icon(Icons.Default.AccountCircle, "Online account") }
                    if (profile?.canAnnounce == true || profile?.role == "admin") {
                        val ownCount = announcements.count { it.authorId == profile?.id }
                        Text(ownCount.toString() + "/10", style = MaterialTheme.typography.labelMedium)
                        IconButton(enabled = ownCount < 10, onClick = { showComposer = true }) { Icon(Icons.Default.Add, "Create announcement") }
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            profile == null -> LoginRequiredCard(Modifier.fillMaxSize().padding(padding)) { showAccount = true }
            profile?.status != "approved" -> PendingAccessCard(Modifier.fillMaxSize().padding(padding), profile!!) { showAccount = true }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Official campus announcements", fontWeight = FontWeight.SemiBold)
                                Text("Updates from approved campus leaders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (error.isNotBlank()) item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, null); Spacer(Modifier.width(10.dp))
                            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { refresh() }) { Text("Retry") }
                        }
                    }
                }
                if (announcements.isEmpty()) item { EmptyCard("No announcements yet.") }
                items(announcements, key = { it.id }) { item ->
                    OnlineAnnouncementCard(
                        item,
                        item.authorId == profile?.id || profile?.role == "admin",
                        onDelete = {
                            scope.launch {
                                client.deleteAnnouncement(item.id)
                                    .onSuccess { announcements = client.listAnnouncements() }
                                    .onFailure { error = it.message ?: "Could not delete announcement." }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showComposer) OnlineAnnouncementComposer(
        onDismiss = { showComposer = false },
        onSave = { body, links, expiry ->
            scope.launch {
                client.createAnnouncement(body, links, expiry)
                    .onSuccess { showComposer = false; announcements = client.listAnnouncements() }
                    .onFailure { error = it.message ?: "Could not publish announcement." }
            }
        }
    )

    if (showAccount) OnlineAccountDialog(client, profile, { showAccount = false }, { refresh() }, { showAccount = false; showAdmin = true })
    if (showAdmin && profile?.role == "admin") AdminControlDialog(client, { showAdmin = false })
}

@Composable
private fun LoginRequiredCard(modifier: Modifier, onAccount: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Cloud, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Online access required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Announcements use the campus online service. Your normal CampusOS offline features remain available without an account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAccount) { Text("Sign in / Register") }
            }
        }
    }
}

@Composable
private fun PendingAccessCard(modifier: Modifier, profile: OnlineProfile, onAccount: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.HourglassTop, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Account pending approval", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Signed in as " + profile.fullName.ifBlank { "CampusOS student" } + ".")
                Text("An administrator must approve this account before it can access campus announcements.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Status: " + profile.status.uppercase(), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onAccount) { Text("Account") }
            }
        }
    }
}

@Composable
private fun OnlineAnnouncementCard(item: OnlineAnnouncement, canManage: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(item.authorName.take(1).uppercase().ifBlank { "C" }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.authorName.ifBlank { "Campus Leader" }, fontWeight = FontWeight.SemiBold)
                    if (item.authorTitle.isNotBlank()) Text(item.authorTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(formatAnnouncementDate(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canManage) Icon(Icons.Default.VerifiedUser, "Authorized publisher", tint = MaterialTheme.colorScheme.primary)
            }
            Text(item.body, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
            item.links.forEach { link ->
                Text(link, Modifier.fillMaxWidth().clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.expiresAt != null) Text("Expires " + formatAnnouncementDate(item.expiresAt), Modifier.padding(14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canManage) TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) { Text("Delete") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineAnnouncementComposer(onDismiss: () -> Unit, onSave: (String, List<String>, Long?) -> Unit) {
    var body by remember { mutableStateOf("") }
    var linksText by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New announcement") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The 10 active-post limit is enforced by the CampusOS server.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Announcement") })
                OutlinedTextField(linksText, { linksText = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Links (one per line)") })
                OutlinedTextField(expires, { expires = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Expire date (optional)") }, placeholder = { Text("yyyy-MM-dd HH:mm") })
                Text("Expired posts are removed automatically by the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                val expiry = expires.trim().takeIf { it.isNotBlank() }?.let { runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(it)?.time }.getOrNull() }
                onSave(body.trim(), linksText.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }, expiry)
            }, enabled = body.isNotBlank()) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OnlineAccountDialog(client: OnlineCampusClient, profile: OnlineProfile?, onClose: () -> Unit, onChanged: () -> Unit, onAdmin: () -> Unit) {
    var mode by remember { mutableStateOf(if (profile == null) 0 else 2) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var schoolId by remember { mutableStateOf("") }
    var yearSection by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (mode == 1) "Create CampusOS account" else if (mode == 2) "Online account" else "CampusOS online access") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == 2 && profile != null) {
                    Text(profile.fullName.ifBlank { "CampusOS user" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(profile.schoolId.ifBlank { "School ID not set" })
                    Text(profile.yearSection.ifBlank { "Year / Section not set" })
                    Text("Approval: " + profile.status.uppercase(), fontWeight = FontWeight.SemiBold)
                    Text(if (profile.canAnnounce || profile.role == "admin") "Announcement permission: ENABLED" else "Announcement permission: View only", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.role == "admin") OutlinedButton(onClick = onAdmin) { Text("Admin controls") }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                } else {
                    if (mode == 1) {
                        OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Full name") })
                        OutlinedTextField(schoolId, { schoolId = it }, Modifier.fillMaxWidth(), label = { Text("School ID") })
                        OutlinedTextField(yearSection, { yearSection = it }, Modifier.fillMaxWidth(), label = { Text("Year / Section") })
                    }
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password (8+ characters)") }, singleLine = true)
                    if (mode == 1) OutlinedTextField(confirmPassword, { confirmPassword = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true)
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (mode) {
                2 -> Button(onClick = { client.signOut(); onClose(); onChanged() }) { Text("Sign out") }
                1 -> Button(enabled = !busy && fullName.isNotBlank() && schoolId.isNotBlank() && yearSection.isNotBlank() && email.contains("@") && password.length >= 8 && password == confirmPassword, onClick = {
                    busy = true
                    scope.launch {
                        client.signUp(email, password, fullName, schoolId, yearSection)
                            .onSuccess { message = it; mode = 0 }
                            .onFailure { message = it.message ?: "Registration failed." }
                        busy = false
                    }
                }) { Text(if (busy) "Submitting…" else "Register") }
                else -> Button(enabled = !busy && email.contains("@") && password.length >= 8, onClick = {
                    busy = true
                    scope.launch {
                        client.signIn(email, password)
                            .onSuccess { onClose(); onChanged() }
                            .onFailure { message = it.message ?: "Login failed." }
                        busy = false
                    }
                }) { Text(if (busy) "Signing in…" else "Sign in") }
            }
        },
        dismissButton = {
            if (mode != 2) TextButton(onClick = { mode = if (mode == 1) 0 else 1 }) { Text(if (mode == 1) "Already have an account" else "Create account") }
            else TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

private fun formatAnnouncementDate(ms: Long): String = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminControlDialog(client: OnlineCampusClient, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<OnlineUser>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            busy = true
            runCatching { client.listUsers() }
                .onSuccess { users = it; message = "" }
                .onFailure { message = it.message ?: "Could not load accounts." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Admin controls") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Approve student accounts and manage announcement leaders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (users.isEmpty() && !busy) Text("No registered accounts yet.")
                users.forEach { user ->
                    AdminUserRow(user, busy) { action ->
                        scope.launch {
                            busy = true
                            val result = when (action) {
                                "approve" -> client.updateUserStatus(user.id, "approved")
                                "deny" -> client.updateUserStatus(user.id, "denied")
                                "pending" -> client.updateUserStatus(user.id, "pending")
                                "revoke" -> client.revokeLeaderAssignment(user.id)
                                else -> if (action.startsWith("leader|")) {
                                    client.setLeaderAssignment(user.id, action.removePrefix("leader|"), true, true)
                                } else {
                                    Result.success(Unit)
                                }
                            }
                            result.onFailure { message = it.message ?: "Action failed." }
                            busy = false
                            if (result.isSuccess) reload()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { reload() }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun AdminUserRow(user: OnlineUser, disabled: Boolean, onAction: (String) -> Unit) {
    var editLeader by remember(user.id) { mutableStateOf(false) }
    var title by remember(user.id, user.title) { mutableStateOf(user.title.ifBlank { "Campus Leader" }) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(user.fullName.ifBlank { "Unnamed user" }, fontWeight = FontWeight.SemiBold)
            Text(listOf(user.schoolId, user.yearSection).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "No school details" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Status: " + user.status.uppercase() + " • Role: " + user.role, style = MaterialTheme.typography.labelSmall)
            if (user.canAnnounce && user.active) Text("Leader: " + user.title.ifBlank { "Campus Leader" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (user.status != "approved") TextButton(enabled = !disabled, onClick = { onAction("approve") }) { Text("Approve") }
                if (user.status != "denied") TextButton(enabled = !disabled, onClick = { onAction("deny") }) { Text("Deny") }
                if (user.status != "pending") TextButton(enabled = !disabled, onClick = { onAction("pending") }) { Text("Pending") }
                if (user.canAnnounce && user.active) TextButton(enabled = !disabled, onClick = { onAction("revoke") }) { Text("Revoke leader") }
                else if (user.status == "approved") TextButton(enabled = !disabled, onClick = { editLeader = true }) { Text("Assign leader") }
            }
            if (editLeader) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Leader title") })
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editLeader = false }) { Text("Cancel") }
                    Button(enabled = title.isNotBlank() && !disabled, onClick = { onAction("leader|" + title); editLeader = false }) { Text("Save") }
                }
            }
        }
    }
}