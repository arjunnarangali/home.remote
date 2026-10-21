package com.myhome.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class DockTile(
    val name: String,
    val color: String,
    val type: String,
    val pkg: String = "",
    val input: String = "",
    val icon: String? = null,
    val fit: String? = null,
    val iconDark: String? = null,
    val fitDark: String? = null,
)

data class WallpaperStatus(
    val running: Boolean = false,
    val photos: Int = 0,
    val count: Int? = null,
    val base: Int? = null,
    val append: Boolean? = null,
    val query: String = "",
    val lastQuery: String = "",
    val lastAppend: Boolean? = null,
    val lastBase: Int? = null,
) {
    val freshPhotos: Int get() = if (append == true) (photos - (base ?: 0)).coerceAtLeast(0) else photos
    val progress: Float get() = count?.takeIf { it > 0 }?.let { (freshPhotos.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val badge: String get() = if (running && count != null) "$freshPhotos/$count" else "…"
    val rememberedQuery: String get() = query.ifBlank { lastQuery }

    companion object {
        fun fromJson(json: JSONObject): WallpaperStatus {
            val last = json.optJSONObject("last") ?: JSONObject()
            return WallpaperStatus(
                running = json.optBoolean("running"), photos = json.optInt("photos"),
                count = json.intOrNull("count"), base = json.intOrNull("base"),
                append = json.boolOrNull("append"), query = json.optString("q").takeUnless { it == "null" }.orEmpty(),
                lastQuery = last.optString("q").takeUnless { it == "null" }.orEmpty(),
                lastAppend = last.boolOrNull("append"), lastBase = last.intOrNull("base"),
            )
        }
    }
}

private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
private fun JSONObject.boolOrNull(key: String): Boolean? = if (isNull(key) || !has(key)) null else optBoolean(key)

data class VoiceAction(val ok: Boolean, val message: String)
data class VoiceResult(val transcript: String, val actions: List<VoiceAction>) {
    fun userMessage(): String = buildList {
        if (transcript.isNotBlank()) add("Heard: \"$transcript\"") else add("Rocky heard nothing")
        actions.firstOrNull()?.let { add("${if (it.ok) "✓" else "✗"} ${it.message}") }
    }.joinToString("\n")
}

class RemoteApi(context: Context, private val initialBaseUrl: String = DEFAULT_BASE_URL) {
    private val preferences = context.applicationContext.getSharedPreferences("remote", Context.MODE_PRIVATE)
    val baseUrl: String get() = preferences.getString(KEY_BASE_URL, null) ?: initialBaseUrl

    fun saveBaseUrl(value: String) {
        val normalized = ServerAddress.normalize(value)
        preferences.edit { putString(KEY_BASE_URL, normalized) }
    }

    suspend fun health(): JSONObject = get("/healthz")

    suspend fun dock(): List<DockTile> {
        val list = get("/dock").optJSONArray("tiles") ?: JSONArray()
        return (0 until list.length()).map { index ->
            val tile = list.getJSONObject(index)
            DockTile(
                name = tile.optString("name", "TV app"), color = tile.optString("color", "#775BF4"),
                type = tile.optString("type", "app"), pkg = tile.optString("pkg"), input = tile.optString("input"),
                icon = tile.optString("icon").takeUnless { it.isBlank() || it == "null" }, fit = tile.optString("fit"),
                iconDark = tile.optString("iconDark").takeUnless { it.isBlank() || it == "null" }, fitDark = tile.optString("fitDark"),
            )
        }
    }

    suspend fun key(code: Int) { post("/key", JSONObject().put("code", code)) }
    suspend fun launch(tile: DockTile) {
        val request = if (tile.type == "app") JSONObject().put("type", "app").put("pkg", tile.pkg)
        else JSONObject().put("type", "input").put("input", tile.input)
        post("/launch", request)
    }
    suspend fun sendText(text: String) {
        post("/text", JSONObject().put("text", text))
        key(66)
    }
    suspend fun sleep() { post("/sleep", JSONObject()) }
    suspend fun reconnect(): JSONObject = post("/reconnect", JSONObject())
    suspend fun wallpaperStatus(): WallpaperStatus = WallpaperStatus.fromJson(get("/wallpaper/status"))
    suspend fun startWallpaper(body: JSONObject): JSONObject = post("/wallpaper", body)

    suspend fun sendVoice(wav: ByteArray): VoiceResult {
        val json = request("/voice", "audio/wav", wav)
        val actions = json.optJSONArray("actions") ?: JSONArray()
        return VoiceResult(json.optString("text"), (0 until actions.length()).map { index ->
            val action = actions.getJSONObject(index)
            VoiceAction(action.optBoolean("ok"), action.optString("msg"))
        })
    }

    private suspend fun get(path: String): JSONObject = request(path, null, null)
    private suspend fun post(path: String, json: JSONObject): JSONObject = request(path, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private suspend fun request(path: String, contentType: String?, body: ByteArray?): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = when (path) {
                "/voice" -> 90_000 // Speech transcription may cold-start its local model.
                "/reconnect" -> 150_000 // The server can restart ADB and probe multiple TV ports.
                "/healthz", "/key", "/launch", "/sleep", "/text" -> 30_000 // May wait behind ADB recovery.
                else -> 12_000
            }
            requestMethod = if (body == null) "GET" else "POST"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (contentType != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse {
                if (code in 200..299) throw RemoteServiceException("Remote service returned invalid JSON", code)
                JSONObject()
            }
            if (code !in 200..299) throw RemoteServiceException(
                json.optString("error").ifBlank { "Request failed (HTTP $code)" }, code
            )
            json
        } finally { connection.disconnect() }
    }

    companion object {
        const val DEFAULT_BASE_URL = BuildConfig.REMOTE_API_URL
        private const val KEY_BASE_URL = "base_url"
    }
}

class RemoteServiceException(message: String, val statusCode: Int) : Exception(message)

object ServerAddress {
    fun normalize(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = try { URI(normalized) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid server URL, for example ${RemoteApi.DEFAULT_BASE_URL}")
        }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Server URL must use http:// or https://"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Server URL must contain a host and no credentials" }
        require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") { "Enter only the server origin, without a path" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Server URL cannot include a query or fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Server port must be between 1 and 65535" }
        require(isLanHost(uri.host)) { "Server must be on the local network (private IP or local DNS name)" }
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port, null, null, null).toASCIIString()
    }

    private fun isLanHost(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        val octets = name.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val ip = octets.map(String::toInt)
            return ip[0] == 10 || ip[0] == 192 && ip[1] == 168 ||
                ip[0] == 172 && ip[1] in 16..31 || ip[0] == 169 && ip[1] == 254
        }
        // Single-label LAN DNS names (e.g. "lenovo") and local DNS suffixes.
        val labels = name.split('.')
        val validDnsName = name.length <= 253 && labels.all {
            it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
        }
        return validDnsName && (name == "localhost" || labels.size == 1 ||
            listOf(".home", ".local", ".lan", ".internal").any { name.endsWith(it) })
    }
}

/** Resolve dock artwork only from this server's /icons directory; never fetch third-party URLs. */
internal fun resolveDockIconUrl(baseUrl: String, icon: String): String? = runCatching {
    val origin = URI(ServerAddress.normalize(baseUrl))
    val target = origin.resolve(URI(icon)).normalize()
    fun effectivePort(uri: URI) = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    require(target.scheme.equals(origin.scheme, ignoreCase = true))
    require(target.host.equals(origin.host, ignoreCase = true) && effectivePort(target) == effectivePort(origin))
    require(target.rawUserInfo == null && target.rawQuery == null && target.rawFragment == null)
    require(target.path.startsWith("/icons/") && "/../" !in target.path)
    target.toASCIIString()
}.getOrNull()
