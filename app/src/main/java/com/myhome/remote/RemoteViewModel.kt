package com.myhome.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class RockyMode { IDLE, STARTING, RECORDING, LISTENING, SENDING, CANCELLING }

data class RemoteUiState(
    val serverUrl: String = RemoteApi.DEFAULT_BASE_URL,
    val status: String = "Connecting…",
    val isOnline: Boolean? = null,
    val reconnecting: Boolean = false,
    val tiles: List<DockTile> = emptyList(),
    val darkTheme: Boolean = false,
    val keyboardShown: Boolean = false,
    val keyboardText: String = "",
    val wallpaper: WallpaperStatus = WallpaperStatus(),
    val rockyMode: RockyMode = RockyMode.IDLE,
    val message: String? = null,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val api = RemoteApi(application)
    private val prefs = application.getSharedPreferences("remote", 0)
    private val recorder = PcmAudioRecorder(application)
    private val mutableState = MutableStateFlow(RemoteUiState(
        serverUrl = api.baseUrl,
        darkTheme = prefs.getBoolean("dark_theme", false),
    ))
    val state: StateFlow<RemoteUiState> = mutableState.asStateFlow()
    private var releaseDuringStartup: Long? = null
    private var rockyTimer: Job? = null
    private var wallpaperWasRunning = false

    /** Poll only while the activity is visible; cancellation stops in-flight jobs as well. */
    suspend fun pollWhileResumed() = coroutineScope {
        launch { while (isActive) { refreshHealth(); delay(5_000) } }
        launch { while (isActive) { refreshDock(); delay(30_000) } }
        launch { while (isActive) { refreshWallpaper(silent = true); delay(4_000) } }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun showMessage(text: String) { message(text) }
    private fun message(text: String) { mutableState.value = mutableState.value.copy(message = text) }

    fun setDarkTheme(dark: Boolean) {
        prefs.edit { putBoolean("dark_theme", dark) }
        mutableState.value = mutableState.value.copy(darkTheme = dark)
    }
    fun setKeyboardShown(shown: Boolean) { mutableState.value = mutableState.value.copy(keyboardShown = shown) }
    fun setKeyboardText(text: String) { mutableState.value = mutableState.value.copy(keyboardText = text) }

    private suspend fun refreshHealth() {
        try {
            val health = api.health()
            val online = health.optBoolean("ok")
            val current = mutableState.value
            mutableState.value = current.copy(
                isOnline = online,
                status = if (current.reconnecting) current.status else if (online) "Live" else "TV not responding",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val current = mutableState.value
            mutableState.value = current.copy(isOnline = false, status = if (current.reconnecting) current.status else "Server unreachable")
        }
    }

    private suspend fun refreshDock() {
        try { mutableState.value = mutableState.value.copy(tiles = api.dock()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep connection health separate from dock-loading errors. */ }
    }

    private suspend fun refreshWallpaper(silent: Boolean) {
        try {
            val status = api.wallpaperStatus()
            mutableState.value = mutableState.value.copy(wallpaper = status)
            if (!status.running && wallpaperWasRunning) {
                val total = status.photos
                val last = if (status.lastAppend == true && status.lastBase != null) "+${(total - status.lastBase).coerceAtLeast(0)}, $total total" else "$total photos on TV"
                message("Done — $last")
            }
            wallpaperWasRunning = status.running
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!silent) message(error.message ?: "Could not read wallpaper status")
        }
    }

    fun refreshNow() = viewModelScope.launch { refreshHealth(); refreshDock() }

    fun sendKey(code: Int) = launchAction("Key failed") { api.key(code) }

    fun launchTile(tile: DockTile) = launchAction("Launch failed") {
        api.launch(tile)
        message("Opening ${tile.name}")
    }

    fun sendText() {
        val text = mutableState.value.keyboardText
        if (text.isBlank()) return
        launchAction("Could not send text") {
            api.sendText(text)
            mutableState.value = mutableState.value.copy(keyboardText = "")
            message("Sent")
        }
    }

    fun sleep() = launchAction("Sleep failed") {
        api.sleep()
        message("Screensaver showing — any key leaves it")
    }

    fun reconnect() = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(reconnecting = true, status = "Reconnecting…")
        try {
            val response = api.reconnect()
            val ok = response.optBoolean("ok")
            val hint = response.optString("hint").takeUnless { it.isBlank() || it == "null" }
            mutableState.value = mutableState.value.copy(isOnline = ok, status = if (ok) "Live" else hint ?: "TV not responding")
            message(if (ok) "Reconnected" else hint ?: "Could not reach TV")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(isOnline = false, status = error.message ?: "Server unreachable")
            message(error.message ?: "Server unreachable")
        } finally { mutableState.value = mutableState.value.copy(reconnecting = false) }
    }

    fun startWallpaper(provider: String, sort: String, count: Int, replace: Boolean, topic: String) = viewModelScope.launch {
        val body = JSONObject().put("provider", provider).put("sort", sort)
            .put("count", count.coerceIn(1, 50)).put("replace", replace)
        if (topic.isNotBlank()) body.put("q", topic.trim())
        try {
            val result = api.startWallpaper(body)
            message("Fetching ${result.optString("q", "wallpapers")}…")
            wallpaperWasRunning = true
            refreshWallpaper(silent = true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            message(error.message ?: "Wallpaper fetch failed")
        }
    }

    fun saveServerAddress(address: String) {
        try {
            api.saveBaseUrl(address)
            mutableState.value = mutableState.value.copy(serverUrl = api.baseUrl, status = "Connecting…", isOnline = null, tiles = emptyList())
            message("Server address updated")
            refreshNow()
        } catch (error: Exception) { message(error.message ?: "Invalid server address") }
    }

    /** Called on microphone button down; returns false when a sticky recording was ended. */
    fun rockyPressStart(): Boolean {
        val current = mutableState.value.rockyMode
        if (current == RockyMode.LISTENING) {
            finishRocky()
            return false
        }
        if (current != RockyMode.IDLE) return false
        releaseDuringStartup = null
        mutableState.value = mutableState.value.copy(rockyMode = RockyMode.STARTING)
        viewModelScope.launch {
            try {
                recorder.start()
                mutableState.value = mutableState.value.copy(rockyMode = RockyMode.RECORDING)
                rockyTimer?.cancel()
                rockyTimer = viewModelScope.launch { delay(12_000); finishRocky(timedOut = true) }
                releaseDuringStartup?.let(::rockyPressEnd)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(rockyMode = RockyMode.IDLE)
                message(error.message ?: "Could not start microphone")
            }
        }
        return true
    }

    fun rockyPressEnd(pressedMillis: Long) {
        when (mutableState.value.rockyMode) {
            RockyMode.STARTING -> releaseDuringStartup = pressedMillis
            RockyMode.RECORDING -> if (pressedMillis < 260) {
                mutableState.value = mutableState.value.copy(rockyMode = RockyMode.LISTENING)
                message("Listening… tap again to ask")
            } else finishRocky()
            else -> Unit
        }
    }

    fun cancelRocky() = viewModelScope.launch {
        if (mutableState.value.rockyMode !in setOf(RockyMode.STARTING, RockyMode.RECORDING, RockyMode.LISTENING)) return@launch
        mutableState.value = mutableState.value.copy(rockyMode = RockyMode.CANCELLING)
        rockyTimer?.cancel()
        try { recorder.cancel() }
        catch (cancelled: CancellationException) { throw cancelled }
        finally { mutableState.value = mutableState.value.copy(rockyMode = RockyMode.IDLE) }
    }

    fun cancelRecordingIfActive() {
        if (mutableState.value.rockyMode in setOf(RockyMode.STARTING, RockyMode.RECORDING, RockyMode.LISTENING)) cancelRocky()
    }

    private fun finishRocky(timedOut: Boolean = false) {
        if (mutableState.value.rockyMode !in setOf(RockyMode.RECORDING, RockyMode.LISTENING)) return
        rockyTimer?.cancel()
        mutableState.value = mutableState.value.copy(rockyMode = RockyMode.SENDING)
        viewModelScope.launch {
            try {
                val wav = recorder.stop()
                if (timedOut) message("Listening limit reached — thinking…") else message("Thinking…")
                val result = api.sendVoice(wav)
                message(result.userMessage())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message(error.message ?: "Voice request failed")
            }
            finally { mutableState.value = mutableState.value.copy(rockyMode = RockyMode.IDLE) }
        }
    }

    private fun launchAction(fallback: String, action: suspend () -> Unit) = viewModelScope.launch {
        try { action() } catch (error: Exception) {
            if (error is CancellationException) throw error
            message(error.message ?: fallback)
        }
    }

    override fun onCleared() {
        rockyTimer?.cancel()
        recorder.cancelNow()
        super.onCleared()
    }
}
