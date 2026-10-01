package com.kage049754.campusos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val SUPABASE_URL = "https://pgniovlvofvkwjhyoqcg.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_UghfMQF0mqMdDL3-i8TvUQ_t3pWFwoe"
private const val ANNOUNCEMENT_BUCKET = "campus-announcements"

data class OnlineProfile(
    val id: String,
    val fullName: String,
    val schoolId: String,
    val yearSection: String,
    val status: String,
    val role: String,
    val title: String = "",
    val canAnnounce: Boolean = false
)

data class OnlineUser(
    val id: String,
    val fullName: String,
    val schoolId: String,
    val yearSection: String,
    val status: String,
    val role: String,
    val title: String = "",
    val canAnnounce: Boolean = false,
    val active: Boolean = false
)

data class OnlineAnnouncementImage(
    val id: String,
    val storagePath: String,
    val signedUrl: String = ""
)

data class OnlineAnnouncement(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorTitle: String,
    val body: String,
    val links: List<String>,
    val images: List<OnlineAnnouncementImage>,
    val createdAt: Long,
    val expiresAt: Long?
)

private data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String)

class OnlineCampusClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("campusos_online", Context.MODE_PRIVATE)

    var lastError: String = ""
        private set

    private fun accessToken() = prefs.getString("access_token", "") ?: ""
    private fun refreshToken() = prefs.getString("refresh_token", "") ?: ""
    fun isSignedIn() = accessToken().isNotBlank() && prefs.getString("user_id", "").orEmpty().isNotBlank()
    fun userId() = prefs.getString("user_id", "").orEmpty()

    suspend fun signUp(email: String, password: String, fullName: String, schoolId: String, yearSection: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("email", email.trim())
                put("password", password)
                put("data", JSONObject().apply {
                    put("full_name", fullName.trim())
                    put("school_id", schoolId.trim())
                    put("year_section", yearSection.trim())
                })
            }
            val json = request("POST", "/auth/v1/signup", body.toString(), null, false)
            val token = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val user = json.optJSONObject("user")
            val id = user?.optString("id").orEmpty()
            if (token.isNotBlank() && refresh.isNotBlank() && id.isNotBlank()) saveSession(AuthSession(token, refresh, id))
            "Registration successful. Your account must be approved by a CampusOS administrator before online announcements are available."
        }.onFailure { lastError = it.message ?: "Registration failed." }
    }

    suspend fun signIn(email: String, password: String): Result<OnlineProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply { put("email", email.trim()); put("password", password) }
            val json = request("POST", "/auth/v1/token?grant_type=password", body.toString(), null, false)
            val token = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val user = json.optJSONObject("user")
            val id = user?.optString("id").orEmpty()
            require(token.isNotBlank() && refresh.isNotBlank() && id.isNotBlank()) { "Login did not return a valid session." }
            saveSession(AuthSession(token, refresh, id))
            loadProfile()
        }.onFailure { lastError = it.message ?: "Login failed." }
    }

    suspend fun restoreSession(): Result<OnlineProfile?> = withContext(Dispatchers.IO) {
        if (!isSignedIn()) return@withContext Result.success(null)
        runCatching { loadProfile() }.recoverCatching {
            val refresh = refreshToken()
            require(refresh.isNotBlank()) { "Session expired." }
            val json = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), null, false)
            val token = json.optString("access_token")
            val newRefresh = json.optString("refresh_token", refresh)
            val id = json.optJSONObject("user")?.optString("id").orEmpty().ifBlank { userId() }
            require(token.isNotBlank()) { "Session expired. Please sign in again." }
            saveSession(AuthSession(token, newRefresh, id))
            loadProfile()
        }.onFailure { clearSession(); lastError = it.message ?: "Session expired." }
    }

    suspend fun loadProfile(): OnlineProfile = withContext(Dispatchers.IO) {
        val id = userId()
        require(id.isNotBlank()) { "No signed-in user." }
        val path = "/rest/v1/profiles?id=eq." + encode(id) + "&select=id,full_name,school_id,year_section,status,role,leader_assignments!leader_assignments_user_id_fkey(title,can_announce,active)"
        val arr = JSONArray(requestText("GET", path, null, null))
        require(arr.length() > 0) { "Your account profile is not ready yet." }
        val o = arr.getJSONObject(0)
        val assignment = o.optJSONArray("leader_assignments")?.optJSONObject(0)
        OnlineProfile(
            o.optString("id"), o.optString("full_name"), o.optString("school_id"), o.optString("year_section"),
            o.optString("status"), o.optString("role"), assignment?.optString("title").orEmpty(),
            assignment?.optBoolean("can_announce", false) == true && assignment.optBoolean("active", false)
        )
    }

    suspend fun listUsers(): List<OnlineUser> = withContext(Dispatchers.IO) {
        val path = "/rest/v1/profiles?select=id,full_name,school_id,year_section,status,role,leader_assignments!leader_assignments_user_id_fkey(title,can_announce,active)&order=created_at.desc"
        val arr = JSONArray(requestText("GET", path, null, null))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.optJSONArray("leader_assignments")?.optJSONObject(0)
            OnlineUser(o.optString("id"), o.optString("full_name"), o.optString("school_id"), o.optString("year_section"),
                o.optString("status"), o.optString("role"), a?.optString("title").orEmpty(),
                a?.optBoolean("can_announce", false) == true, a?.optBoolean("active", false) == true)
        }
    }

    suspend fun updateUserStatus(userId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(status in listOf("pending", "approved", "denied", "suspended"))
            requestText("PATCH", "/rest/v1/profiles?id=" + encode(userId), JSONObject().put("status", status).toString(), "return=minimal")
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not update account status."
            Result.failure(e)
        }
    }

    suspend fun updateUserRole(userId: String, role: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(role in listOf("student", "leader", "admin"))
            requestText("PATCH", "/rest/v1/profiles?id=" + encode(userId), JSONObject().put("role", role).toString(), "return=minimal")
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not update account role."
            Result.failure(e)
        }
    }

    suspend fun listAnnouncementStorage(): List<AdminStorageItem> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("prefix", "")
            put("limit", 100)
            put("offset", 0)
            put("sortBy", JSONObject().put("column", "created_at").put("order", "desc"))
        }
        val arr = JSONArray(requestText("POST", "/storage/v1/object/list/" + ANNOUNCEMENT_BUCKET, body.toString(), null))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank() || o.optString("id").isBlank()) return@mapNotNull null
            val metadata = o.optJSONObject("metadata")
            AdminStorageItem(
                name = name,
                id = o.optString("id"),
                size = metadata?.optLong("size", 0L) ?: 0L,
                createdAt = o.optString("created_at")
            )
        }
    }

    suspend fun adminUploadImage(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bytes = compressImage(uri)
            val path = userId() + "/admin/" + UUID.randomUUID() + ".jpg"
            uploadObject(path, bytes)
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not upload image."
            Result.failure(e)
        }
    }

    suspend fun deleteAnnouncementStorage(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            deleteStorageObjects(listOf(path))
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not delete storage file."
            Result.failure(e)
        }
    }

    suspend fun setLeaderAssignment(userId: String, title: String, canAnnounce: Boolean, active: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("title", title.trim())
                put("can_announce", canAnnounce)
                put("active", active)
                put("assigned_by", this@OnlineCampusClient.userId().ifBlank { JSONObject.NULL })
            }
            requestText("POST", "/rest/v1/leader_assignments?on_conflict=user_id", body.toString(), "resolution=merge-duplicates,return=minimal")
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not update leader assignment."
            Result.failure(e)
        }
    }

    suspend fun revokeLeaderAssignment(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            requestText("DELETE", "/rest/v1/leader_assignments?user_id=" + encode(userId), null, "return=minimal")
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not revoke leader assignment."
            Result.failure(e)
        }
    }

    suspend fun listAnnouncements(): List<OnlineAnnouncement> = withContext(Dispatchers.IO) {
        val path = "/rest/v1/announcements?select=id,author_id,author_name,author_title,body,created_at,expires_at,announcement_links(url,sort_order),announcement_images(id,storage_path,sort_order)&order=created_at.desc"
        val arr = JSONArray(requestText("GET", path, null, null))
        val result = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val links = o.optJSONArray("announcement_links")
                ?.let { a -> (0 until a.length()).map { j -> a.getJSONObject(j) }.sortedBy { it.optInt("sort_order") }.map { it.optString("url") }.filter { it.isNotBlank() } }
                ?: emptyList()
            val imageMeta = o.optJSONArray("announcement_images")
                ?.let { a -> (0 until a.length()).map { j -> a.getJSONObject(j) }.sortedBy { it.optInt("sort_order") }.map {
                    OnlineAnnouncementImage(it.optString("id"), it.optString("storage_path"))
                } } ?: emptyList()
            OnlineAnnouncement(o.optString("id"), o.optString("author_id"), o.optString("author_name"), o.optString("author_title"),
                o.optString("body"), links, imageMeta, parseIso(o.optString("created_at")),
                o.optString("expires_at").takeIf { it.isNotBlank() }?.let(::parseIso))
        }
        result.map { announcement ->
            val signed = if (announcement.images.isEmpty()) emptyMap() else createSignedUrls(announcement.images.map { it.storagePath })
            announcement.copy(images = announcement.images.map { it.copy(signedUrl = signed[it.storagePath].orEmpty()) })
        }
    }

    suspend fun createAnnouncement(body: String, links: List<String>, expiresAt: Long?, imageUris: List<Uri>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val profile = loadProfile()
                require(profile.status == "approved" && (profile.canAnnounce || profile.role == "admin")) {
                    "Your account is not authorized to publish announcements."
                }
                require(imageUris.size <= 4) { "You can attach up to 4 images." }
                // Keep a maximum of 10 active posts per publisher. Before creating the
                // 11th post, remove that publisher's oldest post and its images.
                while (countAuthorAnnouncements(profile.id) >= 10) {
                    val oldest = oldestAuthorAnnouncementId(profile.id) ?: break
                    val oldImages = listImagePaths(oldest)
                    runCatching { deleteStorageObjects(oldImages) }
                    requestText("DELETE", "/rest/v1/announcements?" + uuidFilter("id", oldest), null, "return=minimal")
                }
                val id = insertAnnouncement(profile, body, expiresAt)
                insertLinks(id, links)
                uploadImages(id, imageUris)
                Result.success(Unit)
            } catch (e: Exception) {
                lastError = e.message ?: "Could not publish announcement."
                Result.failure(e)
            }
        }

    suspend fun updateAnnouncement(id: String, body: String, links: List<String>, expiresAt: Long?, imageUris: List<Uri>?, replaceImages: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val profile = loadProfile()
                require(profile.status == "approved" && (profile.role == "admin" || profile.canAnnounce)) {
                    "Your account is not authorized to edit announcements."
                }
                require(body.isNotBlank()) { "Announcement cannot be empty." }
                require(imageUris == null || imageUris.size <= 4) { "You can attach up to 4 images." }
                val patch = JSONObject().apply {
                    put("body", body.trim())
                    if (expiresAt == null) put("expires_at", JSONObject.NULL) else put("expires_at", iso(expiresAt))
                }
                requestText("PATCH", "/rest/v1/announcements?" + uuidFilter("id", id), patch.toString(), "return=minimal")
                requestText("DELETE", "/rest/v1/announcement_links?" + uuidFilter("announcement_id", id), null, "return=minimal")
                insertLinks(id, links)
                if (replaceImages) {
                    val old = listImagePaths(id)
                    deleteStorageObjects(old)
                    requestText("DELETE", "/rest/v1/announcement_images?" + uuidFilter("announcement_id", id), null, "return=minimal")
                    if (!imageUris.isNullOrEmpty()) uploadImages(id, imageUris)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                lastError = e.message ?: "Could not update announcement."
                Result.failure(e)
            }
        }

    suspend fun deleteAnnouncement(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val old = listImagePaths(id)
            deleteStorageObjects(old)
            requestText("DELETE", "/rest/v1/announcements?id=" + encode(id), null, "return=minimal")
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not delete announcement."
            Result.failure<Unit>(e)
        }
    }

    private fun normalizedUuid(value: String): String {
        val cleaned = value.trim()
        return UUID.fromString(cleaned).toString()
    }

    private fun uuidFilter(column: String, value: String): String =
        column + "=eq." + encode(normalizedUuid(value))

    private fun countAuthorAnnouncements(authorId: String): Int {
        val arr = JSONArray(requestText(
            "GET",
            "/rest/v1/announcements?" + uuidFilter("author_id", authorId) + "&select=id",
            null,
            null
        ))
        return arr.length()
    }

    private fun oldestAuthorAnnouncementId(authorId: String): String? {
        val arr = JSONArray(requestText(
            "GET",
            "/rest/v1/announcements?" + uuidFilter("author_id", authorId) + "&select=id&order=created_at.asc&limit=1",
            null,
            null
        ))
        return arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun insertAnnouncement(profile: OnlineProfile, body: String, expiresAt: Long?): String {
        require(body.isNotBlank()) { "Announcement cannot be empty." }
        val o = JSONObject().apply {
            put("author_id", profile.id)
            put("author_name", profile.fullName)
            put("author_title", profile.title.ifBlank { if (profile.role == "admin") "Campus Admin" else "Campus Leader" })
            put("body", body.trim())
            if (expiresAt != null) put("expires_at", iso(expiresAt))
        }
        val created = JSONArray(requestText("POST", "/rest/v1/announcements", o.toString(), "return=representation"))
        val id = created.optJSONObject(0)?.optString("id").orEmpty()
        require(id.isNotBlank()) { "Announcement was not created." }
        return id
    }

    private fun insertLinks(id: String, links: List<String>) {
        links.filter { it.startsWith("http://") || it.startsWith("https://") }.take(20).forEachIndexed { index, link ->
            requestText("POST", "/rest/v1/announcement_links", JSONObject().apply {
                put("announcement_id", id)
                put("url", link)
                put("sort_order", index)
            }.toString(), "return=minimal")
        }
    }

    private fun listImagePaths(id: String): List<String> {
        val arr = JSONArray(requestText("GET", "/rest/v1/announcement_images?announcement_id=eq." + encode(id) + "&select=storage_path&order=sort_order.asc", null, null))
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("storage_path") }.filter { it.isNotBlank() }
    }

    private fun uploadImages(announcementId: String, uris: List<Uri>) {
        require(uris.size <= 4) { "You can attach up to 4 images." }
        uris.forEachIndexed { index, uri ->
            val bytes = compressImage(uri)
            val path = userId() + "/" + announcementId + "/" + UUID.randomUUID() + ".jpg"
            uploadObject(path, bytes)
            requestText("POST", "/rest/v1/announcement_images", JSONObject().apply {
                put("announcement_id", announcementId)
                put("storage_path", path)
                put("sort_order", index)
            }.toString(), "return=minimal")
        }
    }

    private fun compressImage(uri: Uri): ByteArray {
        val input = appContext.contentResolver.openInputStream(uri) ?: error("Could not read selected image.")
        val bitmap = input.use { BitmapFactory.decodeStream(it) } ?: error("Selected file is not a supported image.")
        val maxDimension = 1920
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val bytes = out.toByteArray()
        require(bytes.size <= 6 * 1024 * 1024) { "An image is still too large after compression. Please choose a smaller image." }
        return bytes
    }

    private fun uploadObject(path: String, bytes: ByteArray) {
        val url = SUPABASE_URL + "/storage/v1/object/" + ANNOUNCEMENT_BUCKET + "/" +
            path.split('/').joinToString("/") { encodePath(it) }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken())
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Cache-Control", "3600")
            setRequestProperty("x-upsert", "false")
        }
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val response = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("Image upload failed: HTTP " + code + " " + response.take(200))
    }

    private fun deleteStorageObjects(paths: List<String>) {
        paths.forEach { path ->
            val url = SUPABASE_URL + "/storage/v1/object/" + ANNOUNCEMENT_BUCKET + "/" +
                path.split('/').joinToString("/") { encodePath(it) }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("apikey", SUPABASE_KEY)
                setRequestProperty("Authorization", "Bearer " + accessToken())
            }
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299 && code != 404) error("Could not delete announcement image.")
        }
    }

    private fun createSignedUrls(paths: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        paths.forEach { path ->
            val encodedPath = path.split('/').joinToString("/") { encodePath(it) }
            val json = request(
                "POST",
                "/storage/v1/object/sign/" + ANNOUNCEMENT_BUCKET + "/" + encodedPath,
                JSONObject().put("expiresIn", 3600).toString(),
                null
            )
            val signed = json.optString("signedURL").ifBlank { json.optString("signedUrl") }
            if (signed.isNotBlank()) {
                result[path] = if (signed.startsWith("http")) signed else SUPABASE_URL + "/storage/v1" + signed
            }
        }
        return result
    }

    fun signOut() {
        val token = accessToken()
        if (token.isNotBlank()) Thread { runCatching { requestText("POST", "/auth/v1/logout", "", null) } }.start()
        clearSession()
    }

    private fun saveSession(s: AuthSession) {
        prefs.edit().putString("access_token", s.accessToken).putString("refresh_token", s.refreshToken).putString("user_id", s.userId).apply()
    }

    private fun clearSession() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("user_id").apply()
    }

    private fun request(method: String, path: String, body: String?, prefer: String?, includeAuth: Boolean = true): JSONObject =
        JSONObject(requestText(method, path, body, prefer, includeAuth))

    private fun requestText(method: String, path: String, body: String?, prefer: String?, includeAuth: Boolean = true): String {
        val connection = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Accept", "application/json")
            if (includeAuth && accessToken().isNotBlank()) setRequestProperty("Authorization", "Bearer " + accessToken())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (!prefer.isNullOrBlank()) setRequestProperty("Prefer", prefer)
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching {
                val o = JSONObject(response)
                o.optString("msg").ifBlank { o.optString("message") }.ifBlank { o.optString("error_description") }
            }.getOrNull()
            throw IllegalStateException(message?.ifBlank { response } ?: "Server returned HTTP " + code)
        }
        return response
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun encodePath(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))
    private fun parseIso(value: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(value)?.time ?: 0L
    }.getOrElse {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(value)?.time ?: 0L }.getOrDefault(0L)
    }
}
