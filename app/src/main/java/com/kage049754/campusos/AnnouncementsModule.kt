package com.kage049754.campusos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CampusAnnouncement(
    val id: Long,
    val authorName: String,
    val authorTitle: String,
    val body: String,
    val links: List<String>,
    val imageUris: List<String>,
    val createdAt: Long,
    val expiresAt: Long?
)

class AnnouncementStore(context: Context) {
    private val prefs = context.getSharedPreferences("campusos_announcements", Context.MODE_PRIVATE)
    fun getAll(): List<CampusAnnouncement> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                val links = o.optJSONArray("links") ?: JSONArray()
                val images = o.optJSONArray("images") ?: JSONArray()
                CampusAnnouncement(
                    o.optLong("id"), o.optString("authorName"), o.optString("authorTitle"),
                    o.optString("body"),
                    (0 until links.length()).map { j -> links.optString(j) },
                    (0 until images.length()).map { j -> images.optString(j) },
                    o.optLong("createdAt"),
                    if (o.has("expiresAt") && !o.isNull("expiresAt")) o.optLong("expiresAt") else null
                )
            }
        }.filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
            .sortedByDescending { it.createdAt }
    }.getOrElse { emptyList() }

    fun save(item: CampusAnnouncement) {
        val items = getAll().filterNot { it.id == item.id }.toMutableList()
        items.add(item)
        saveAll(items)
    }

    fun delete(id: Long) = saveAll(getAll().filterNot { it.id == id })

    private fun saveAll(items: List<CampusAnnouncement>) {
        val a = JSONArray()
        items.forEach { n ->
            a.put(JSONObject().apply {
                put("id", n.id); put("authorName", n.authorName); put("authorTitle", n.authorTitle)
                put("body", n.body); put("links", JSONArray(n.links)); put("images", JSONArray(n.imageUris))
                put("createdAt", n.createdAt)
                if (n.expiresAt != null) put("expiresAt", n.expiresAt) else put("expiresAt", JSONObject.NULL)
            })
        }
        prefs.edit().putString("items", a.toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AnnouncementStore(context) }
    var items by remember { mutableStateOf(store.getAll()) }
    var showComposer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CampusAnnouncement?>(null) }

    // Temporary local capability for this feature branch. Supabase role/capability
    // enforcement will replace this when online accounts are connected.
    val canPost = true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Campus", fontWeight = FontWeight.Bold)
                        Text("Announcements", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (canPost) {
                        Text("\${items.size}/10", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = {
                            if (items.size < 10) { editing = null; showComposer = true }
                        }) { Icon(Icons.Default.Add, "Create announcement") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
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
            if (items.isEmpty()) {
                item { EmptyCard("No announcements yet.") }
            } else {
                items(items, key = { it.id }) { item ->
                    AnnouncementCard(item, canPost, onEdit = { editing = item; showComposer = true }, onDelete = {
                        store.delete(item.id); items = store.getAll()
                    })
                }
            }
        }
    }

    if (showComposer) {
        AnnouncementComposer(
            existing = editing,
            remaining = 10 - items.size + if (editing != null) 1 else 0,
            onDismiss = { showComposer = false },
            onSave = { item -> store.save(item); items = store.getAll(); showComposer = false }
        )
    }
}

@Composable
private fun AnnouncementCard(
    item: CampusAnnouncement,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(item.authorName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.authorName, fontWeight = FontWeight.SemiBold)
                    if (item.authorTitle.isNotBlank()) Text(item.authorTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(item.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canManage) IconButton(onClick = onEdit) { Icon(Icons.Default.MoreVert, "Manage announcement") }
            }
            Text(item.body, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
            item.links.forEach { link ->
                Text(
                    link,
                    Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.imageUris.isNotEmpty()) {
                Text(
                    "\${item.imageUris.size} photo(s) attached",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.expiresAt != null) {
                Text(
                    "Expires " + SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(item.expiresAt)),
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canManage) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementComposer(
    existing: CampusAnnouncement?,
    remaining: Int,
    onDismiss: () -> Unit,
    onSave: (CampusAnnouncement) -> Unit
) {
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var linksText by remember { mutableStateOf(existing?.links?.joinToString("\\n") ?: "") }
    var expires by remember {
        mutableStateOf(existing?.expiresAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "")
    }
    var selectedImages by remember { mutableStateOf(existing?.imageUris ?: emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedImages = (selectedImages + uris.map { it.toString() }).distinct().take(4)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New announcement" else "Edit announcement") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Posts remaining: \${remaining.coerceAtLeast(0)}/10", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Announcement") }, placeholder = { Text("Write an important campus update…") }
                )
                OutlinedTextField(
                    linksText, { linksText = it }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Links (one per line)") }
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add up to 4 photos")
                }
                if (selectedImages.isNotEmpty()) Text("\${selectedImages.size} photo(s) selected", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    expires, { expires = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Expire date (optional)") }, placeholder = { Text("yyyy-MM-dd HH:mm") }
                )
                Text(
                    "If an expiry is set, the post can be automatically removed after that date.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val expiry = expires.trim().takeIf { it.isNotBlank() }?.let {
                        runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(it)?.time }.getOrNull()
                    }
                    onSave(
                        CampusAnnouncement(
                            existing?.id ?: System.currentTimeMillis(),
                            "Campus Leader",
                            "Assigned Leader",
                            body.trim(),
                            linksText.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") },
                            selectedImages,
                            existing?.createdAt ?: System.currentTimeMillis(),
                            expiry
                        )
                    )
                },
                enabled = body.isNotBlank() && (existing != null || remaining > 0)
            ) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
