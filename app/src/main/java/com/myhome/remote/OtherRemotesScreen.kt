package com.myhome.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.ArrayDeque
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

private const val ANDROID_TV_REMOTE_SERVICE = "_androidtvremote2._tcp."

internal data class DiscoveredTv(val name: String, val host: String, val port: Int)

@Composable
internal fun OtherRemotesScreen(
    onBack: () -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recorder = remember(context) { PcmAudioRecorder(context) }
    val devices = remember { mutableStateListOf<DiscoveredTv>() }
    var scanning by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanGeneration by remember { mutableStateOf(0) }
    var manualHost by remember { mutableStateOf("") }
    var selectedTv by remember { mutableStateOf<DiscoveredTv?>(null) }
    val clientState = remember { mutableStateOf<AndroidTvRemoteClient?>(null) }
    var client by clientState
    var pairingDialog by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var keyboardShown by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var voiceMode by remember { mutableStateOf(RockyMode.IDLE) }
    var voiceSessionId by remember { mutableStateOf<Int?>(null) }
    var voiceTimer by remember { mutableStateOf<Job?>(null) }
    var voiceStartJob by remember { mutableStateOf<Job?>(null) }
    var releasedDuringStartup by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var connectionError by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client) {
        while (client != null) {
            connected = client?.connected == true
            connectionError = client?.lastError.orEmpty()
            delay(300)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            voiceTimer?.cancel()
            voiceStartJob?.cancel()
            recorder.cancelNow()
            voiceSessionId?.let { clientState.value?.endVoiceSession(it) }
            clientState.value?.close()
        }
    }
    fun finishVoice() {
        if (voiceMode !in setOf(RockyMode.RECORDING, RockyMode.LISTENING)) return
        voiceTimer?.cancel()
        voiceMode = RockyMode.SENDING
        val sessionId = voiceSessionId
        val activeClient = client
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { recorder.stop() }
                val pcm8k = wav16kToPcm8k(wav)
                if (sessionId != null && activeClient != null) {
                    withContext(Dispatchers.IO) {
                        activeClient.sendVoiceChunk(sessionId, pcm8k)
                        activeClient.endVoiceSession(sessionId)
                    }
                }
            } catch (error: Exception) {
                sessionId?.let { activeClient?.endVoiceSession(it) }
                errorMessage = error.message ?: "Voice request failed"
            } finally {
                voiceSessionId = null
                voiceMode = RockyMode.IDLE
            }
        }
    }

    fun releaseVoice(pressedMillis: Long) {
        when (voiceMode) {
            RockyMode.STARTING -> releasedDuringStartup = pressedMillis
            RockyMode.RECORDING -> if (pressedMillis < 260) {
                voiceMode = RockyMode.LISTENING
            } else finishVoice()
            else -> Unit
        }
    }

    fun cancelVoice() {
        voiceTimer?.cancel()
        voiceStartJob?.cancel()
        voiceStartJob = null
        recorder.cancelNow()
        voiceSessionId?.let { client?.endVoiceSession(it) }
        voiceSessionId = null
        releasedDuringStartup = null
        voiceMode = RockyMode.IDLE
    }

    fun startVoiceCapture() {
        if (voiceMode != RockyMode.STARTING) voiceMode = RockyMode.STARTING
        voiceStartJob?.cancel()
        voiceStartJob = scope.launch {
            try {
                val activeClient = client ?: throw IllegalStateException("Select a TV first")
                val sessionId = withContext(Dispatchers.IO) { activeClient.startVoiceSession() }
                voiceSessionId = sessionId
                withContext(Dispatchers.IO) { recorder.start() }
                voiceMode = RockyMode.RECORDING
                voiceTimer?.cancel()
                voiceTimer = scope.launch { delay(12_000); finishVoice() }
                releasedDuringStartup?.let { releasedDuringStartup = null; releaseVoice(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                voiceSessionId?.let { client?.endVoiceSession(it) }
                voiceSessionId = null
                voiceMode = RockyMode.IDLE
                errorMessage = error.message ?: "Could not start TV voice input"
            }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceCapture() else {
            voiceMode = RockyMode.IDLE
            errorMessage = "Microphone permission is needed for TV voice search"
        }
    }

    fun startVoice() {
        if (voiceMode == RockyMode.LISTENING) { finishVoice(); return }
        if (voiceMode != RockyMode.IDLE) return
        releasedDuringStartup = null
        voiceMode = RockyMode.STARTING
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceCapture()
        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelVoice() }

    DisposableEffect(context, scanGeneration) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("my.home-android-tv-discovery").apply {
            setReferenceCounted(false)
            runCatching { acquire() }.onFailure { scanError = it.localizedMessage ?: "Could not enable Wi-Fi discovery" }
        }
        val listener = createDiscoveryListener(manager, devices,
            onScanning = { scanning = it }, onError = { scanError = it })
        devices.clear()
        scanError = null
        scanning = true
        try {
            manager.discoverServices(ANDROID_TV_REMOTE_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            scanning = false
            scanError = error.localizedMessage ?: "Could not start device discovery"
        }
        onDispose {
            runCatching { manager.stopServiceDiscovery(listener) }
            if (multicastLock.isHeld) runCatching { multicastLock.release() }
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectedTv == null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to home") }
                Text("Other remotes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { scanGeneration++ }) { Icon(Icons.Default.Refresh, "Scan again") }
            } else {
                IconButton(onClick = { client?.close(); client = null; selectedTv = null; connected = false; errorMessage = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Choose another TV")
                }
                Text("#63 Remote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(
                        if (connected) Color(0xFF1FA35C) else Color(0xFFB9BDC6), androidx.compose.foundation.shape.CircleShape))
                    Text(if (connected) "Live" else "Connecting…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), maxLines = 1)
                    IconButton(onClick = { client?.connect() }, enabled = !connected, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, "Reconnect to TV", tint = Color(0xFFAD2030))
                    }
                    IconButton(onClick = onToggleTheme, modifier = Modifier.size(36.dp)) {
                        Icon(if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    }
                }
            }
        }
        if (selectedTv == null) Text(
            "Searching this Wi-Fi for Android TV and Google TV devices. The TV needs Android TV Remote Service enabled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 20.dp),
        )
        if (selectedTv != null) {
            val tv = selectedTv!!
            if (connected) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(top = 28.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 0.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            DirectionPad(client!!::sendKey, darkTheme, if (maxWidth <= 318.dp) 230.dp else 260.dp)
                        }
                        AndroidTvActionGrid(
                            onSendKey = client!!::sendKey,
                            keyboardShown = keyboardShown,
                            onToggleKeyboard = { keyboardShown = !keyboardShown },
                            voiceMode = voiceMode,
                            onVoiceStart = { startVoice() },
                            onVoiceEnd = { releaseVoice(it) },
                            onVoiceCancel = { cancelVoice() },
                        )
                        if (keyboardShown) KeyboardPanel(
                            textValue = keyboardText,
                            onTextChange = { keyboardText = it },
                            onSend = {
                                try {
                                    client?.sendText(keyboardText)
                                    client?.sendKey(android.view.KeyEvent.KEYCODE_ENTER)
                                    keyboardText = ""
                                    errorMessage = null
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "Could not send text to the TV"
                                }
                            },
                        )
                        if (connectionError.isNotBlank()) Text(connectionError, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Connected to ${tv.name}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (busy) "Pairing with ${tv.name}…" else if (connectionError.isNotBlank()) "Reconnecting to ${tv.name}…" else "Connecting to ${tv.name}…")
                }
                if (connectionError.isNotBlank()) Text(connectionError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
                if (!busy && client?.isPaired() == true) {
                    TextButton(onClick = { client?.connect() }) { Text("Reconnect") }
                }
                if (!busy && client?.isPaired() != true) {
                    TextButton(onClick = {
                        busy = true
                        startPairing(client, scope, { busy = false; pairingDialog = true; errorMessage = null }, { busy = false; errorMessage = it })
                    }) {
                        Text("Pair with this TV")
                    }
                }
            }
            if (!connected) Text("${tv.name} · ${if (busy) "Pairing" else "Connecting"}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        } else when {
            devices.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { "${it.name}:${it.host}:${it.port}" }) { device ->
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedTv = device
                        val newClient = AndroidTvRemoteClient(context, device.host)
                        client = newClient
                        errorMessage = null
                        if (newClient.isPaired()) newClient.connect()
                        else {
                            busy = true
                            startPairing(newClient, scope, { busy = false; pairingDialog = true }, { busy = false; errorMessage = it })
                        }
                    }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        }
                    }
                }
            }
            scanning -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                CircularProgressIndicator()
                Spacer(Modifier.width(14.dp))
                Text("Looking for TVs… this scan continues until you leave this screen.")
            }
            scanError != null -> Text("Discovery error: $scanError", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            else -> Text("No compatible TVs found yet. Check that both devices are on the same Wi-Fi, or enter the TV IP below.", modifier = Modifier.padding(12.dp))
        }
        if (selectedTv == null) {
            Text("Can’t find your TV? Enter its local IP address:", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(manualHost, { manualHost = it.trim().take(64) }, label = { Text("TV IP address") },
                    placeholder = { Text("192.168.1.20") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Button(onClick = {
                    val host = manualHost.trim().removePrefix("http://").removePrefix("https://").substringBefore(':').trim()
                    if (host.isBlank() || host.contains('/') || host.contains(' ')) {
                        errorMessage = "Enter the TV’s IP address on your Wi-Fi"
                    } else {
                        val tv = DiscoveredTv("Android TV ($host)", host, 6467)
                        selectedTv = tv
                        val newClient = AndroidTvRemoteClient(context, tv.host)
                        client = newClient
                        errorMessage = null
                        busy = true
                        startPairing(newClient, scope, { busy = false; pairingDialog = true }, { busy = false; errorMessage = it })
                    }
                }) { Text("Connect") }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
    }
    if (pairingDialog) AlertDialog(
        onDismissRequest = { pairingDialog = false; client?.cancelPairing() },
        title = { Text("Enter the TV code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A 6-character pairing code should be displayed on ${selectedTv?.name}.")
                OutlinedTextField(pairingCode, { pairingCode = it.uppercase().take(6) }, label = { Text("Pairing code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val active = client ?: return@TextButton
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { active.finishPairing(pairingCode) }
                    pairingDialog = false
                    pairingCode = ""
                    active.connect()
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Pairing failed"
                    if (!active.pairingSessionActive()) {
                        pairingDialog = false
                        pairingCode = ""
                        startPairing(active, scope, { busy = false; pairingDialog = true }, { busy = false; errorMessage = it })
                    }
                }
                finally { busy = false }
            }
        }) { Text(if (busy) "Pairing…" else "Pair") } },
        dismissButton = { TextButton(onClick = { pairingDialog = false; client?.cancelPairing() }) { Text("Cancel") } },
    )
}

private fun startPairing(client: AndroidTvRemoteClient?, scope: kotlinx.coroutines.CoroutineScope,
    onReady: () -> Unit, onError: (String) -> Unit) {
    if (client == null) return
    scope.launch {
        try {
            withContext(Dispatchers.IO) { client.beginPairing() }
            onReady()
        } catch (error: Exception) { onError(error.message ?: "Could not start TV pairing") }
    }
}

private fun sendKey(client: AndroidTvRemoteClient?, key: Int, onError: (String) -> Unit) {
    try { client?.sendKey(key) ?: onError("No TV selected") }
    catch (error: Exception) { onError(error.message ?: error.javaClass.simpleName) }
}

internal fun wav16kToPcm8k(wav: ByteArray): ByteArray {
    require(wav.size > 44) { "No microphone audio was captured" }
    val pcmSize = wav.size - 44
    val outputSize = pcmSize / 4 * 2
    require(outputSize > 0) { "Nothing captured" }
    val pcm8k = ByteArray(outputSize)
    var source = 44
    var target = 0
    while (source + 1 < wav.size && target + 1 < pcm8k.size) {
        pcm8k[target] = wav[source]
        pcm8k[target + 1] = wav[source + 1]
        source += 4 // 16 kHz source: keep every other 16-bit sample for 8 kHz voice input.
        target += 2
    }
    return pcm8k
}

private fun createDiscoveryListener(
    manager: NsdManager,
    devices: MutableList<DiscoveredTv>,
    onScanning: (Boolean) -> Unit,
    onError: (String) -> Unit,
): NsdManager.DiscoveryListener {
    val pending = ArrayDeque<NsdServiceInfo>()
    val queuedOrResolved = mutableSetOf<String>()
    var resolving = false

    fun resolveNext() {
        if (resolving || pending.isEmpty()) return
        val next = pending.removeFirst()
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    queuedOrResolved.remove(info.serviceName)
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    val host = info.host?.hostAddress
                    if (queuedOrResolved.contains(info.serviceName) && !host.isNullOrBlank() &&
                        devices.none { it.name == info.serviceName && it.host == host }) {
                        devices.add(DiscoveredTv(info.serviceName, host, info.port))
                    } else if (host.isNullOrBlank()) {
                        queuedOrResolved.remove(info.serviceName)
                    }
                    resolveNext()
                }
            })
        } catch (error: Exception) {
            resolving = false
            queuedOrResolved.remove(next.serviceName)
            onError(error.localizedMessage ?: "Could not inspect a discovered TV")
            resolveNext()
        }
    }

    return object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = onScanning(true)
        override fun onDiscoveryStopped(serviceType: String) = onScanning(false)
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            onScanning(false)
            onError("Could not start Wi-Fi discovery (code $errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            onError("Could not stop Wi-Fi discovery (code $errorCode)")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            if (queuedOrResolved.add(name)) {
                pending.addLast(serviceInfo)
                resolveNext()
            }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            queuedOrResolved.remove(serviceInfo.serviceName)
            pending.removeIf { it.serviceName == serviceInfo.serviceName }
            devices.removeAll { it.name == serviceInfo.serviceName }
        }
    }
}
