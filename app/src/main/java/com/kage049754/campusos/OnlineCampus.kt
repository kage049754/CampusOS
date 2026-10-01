package com.kage049754.campusos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SUPABASE_URL = "https://pgniovlvofvkwjhyoqcg.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_UghfMQF0mqMdDL3-i8TvUQ_t3pWFwoe"

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

data class OnlineAnnouncement(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorTitle: String,
    val body: String,
    val links: List<String>,
    val createdAt: Long,
    val expiresAt: Long?
)

private data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String)

class OnlineCampusClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("campusos_online", Context.MODE_PRIVATE)

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
            "Registration submitted. Your account must be approved by a CampusOS administrator before online announcements are available."
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
        runCatching {
            loadProfile()
        }.recoverCatching {
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
        val path = "/rest/v1/profiles?id=eq." + encode(id) + "&select=id,full_name,school_id,year_section,status,role,leader_assignments(title,can_announce,active)"
        val arr = JSONArray(requestText("GET", path, null, null))
        require(arr.length() > 0) { "Your account profile is not ready yet." }
        val o = arr.getJSONObject(0)
        val assignment = o.optJSONArray("leader_assignments")?.optJSONObject(0)
        OnlineProfile(
            id = o.optString("id"),
            fullName = o.optString("full_name"),
            schoolId = o.optString("school_id"),
            yearSection = o.optString("year_section"),
            status = o.optString("status"),
            role = o.optString("role"),
            title = assignment?.optString("title").orEmpty(),
            canAnnounce = assignment?.optBoolean("can_announce", false) == true && assignment.optBoolean("active", false)
        )
    }

    suspend fun listAnnouncements(): List<OnlineAnnouncement> = withContext(Dispatchers.IO) {
        val path = "/rest/v1/announcements?select=id,author_id,author_name,author_title,body,created_at,expires_at,announcement_links(url,sort_order)&order=created_at.desc"
        val arr = JSONArray(requestText("GET", path, null, null))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val links = o.optJSONArray("announcement_links")
                ?.let { a -> (0 until a.length()).map { j -> a.getJSONObject(j) }.sortedBy { it.optInt("sort_order") }.map { it.optString("url") }.filter { it.isNotBlank() } }
                ?: emptyList()
            OnlineAnnouncement(
                o.optString("id"), o.optString("author_id"), o.optString("author_name"),
                o.optString("author_title"), o.optString("body"), links,
                parseIso(o.optString("created_at")), o.optString("expires_at").takeIf { it.isNotBlank() }?.let(::parseIso)
            )
        }
    }

    suspend fun createAnnouncement(body: String, links: List<String>, expiresAt: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profile = loadProfile()
            require(profile.status == "approved" && (profile.canAnnounce || profile.role == "admin")) {
                "Your account is not authorized to publish announcements."
            }
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
            links.forEachIndexed { index, link ->
                val linkBody = JSONObject().apply {
                    put("announcement_id", id)
                    put("url", link)
                    put("sort_order", index)
                }
                requestText("POST", "/rest/v1/announcement_links", linkBody.toString(), "return=minimal")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not publish announcement."
            Result.failure<Unit>(e)
        }
    }

    suspend fun deleteAnnouncement(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requestText("DELETE", "/rest/v1/announcements?id=" + encode(id), null, "return=minimal")
        }.onFailure { lastError = it.message ?: "Could not delete announcement." }
    }

    fun signOut() {
        val token = accessToken()
        if (token.isNotBlank()) Thread {
            runCatching { requestText("POST", "/auth/v1/logout", "", null) }
        }.start()
        clearSession()
    }

    private fun saveSession(s: AuthSession) {
        prefs.edit().putString("access_token", s.accessToken).putString("refresh_token", s.refreshToken).putString("user_id", s.userId).apply()
    }

    private fun clearSession() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("user_id").apply()
    }

    private fun request(method: String, path: String, body: String?, prefer: String?, includeAuth: Boolean): JSONObject =
        JSONObject(requestText(method, path, body, prefer, includeAuth))

    private fun requestText(method: String, path: String, body: String?, prefer: String?, includeAuth: Boolean = true): String {
        val connection = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
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
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))
    private fun parseIso(value: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(value)?.time ?: 0L
    }.getOrElse {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(value)?.time ?: 0L }.getOrDefault(0L)
    }
}
