package com.myhome.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import kotlinx.coroutines.launch

internal val Violet = Color(0xFF775BF4)
internal val KeyCodes = mapOf(
    "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
    "volup" to 24, "voldown" to 25, "mute" to 164, "back" to 4, "home" to 3, "input" to 178,
)

class MainActivity : ComponentActivity() {
    private val remoteViewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RemoteApp(remoteViewModel) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { remoteViewModel.pollWhileResumed() }
        }
    }

    override fun onStop() {
        // A sticky voice capture must not keep using the microphone in the background.
        remoteViewModel.cancelRecordingIfActive()
        super.onStop()
    }
}

@Composable
private fun RemoteApp(model: RemoteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var wallpaperShown by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).components {
            add(SvgDecoder.Factory())
            add(OkHttpNetworkFetcherFactory())
        }.build()
    }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
    val colors = if (state.darkTheme) darkColorScheme(
        primary = Color(0xFF8F77FF), background = Color(0xFF0B0C10), surface = Color(0xFF16171D),
        onSurface = Color(0xFFF1F2F6), outline = Color(0xFF26282F),
    ) else lightColorScheme(
        primary = Violet, background = Color(0xFFF3F4F7), surface = Color.White,
        onSurface = Color(0xFF171A1F), outline = Color(0xFFE3E5EA),
    )
    val snackbar = remember { SnackbarHostState() }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            model.rockyPressStart()
            model.rockyPressEnd(0)
        } else {
            model.cancelRocky()
            model.showMessage("Microphone permission is needed for Ask Rocky")
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
        ) { inset ->
            Column(
                Modifier.fillMaxSize().padding(inset)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header(state, model, onSettings = { settingsOpen = true })
                Dock(state, model, imageLoader)
                RockyButton(
                    mode = state.rockyMode,
                    onPressStart = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            model.rockyPressStart()
                        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPressEnd = model::rockyPressEnd,
                    onCancel = model::cancelRocky,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    DirectionPad(model, state.darkTheme, if (maxWidth <= 318.dp) 230.dp else 260.dp)
                }
                ActionGrid(state, model, onWallpaperClick = { wallpaperShown = true })
                if (state.keyboardShown) KeyboardPanel(state, model)
                Text(
                    "Served by Lenovo · ${state.serverUrl.removePrefix("http://").removePrefix("https://")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface.copy(alpha = .55f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        if (settingsOpen) ServerSettingsDialog(state.serverUrl, onDismiss = { settingsOpen = false }, onSave = {
            settingsOpen = false
            model.saveServerAddress(it)
        })
        if (wallpaperShown) WallpaperDialog(state.wallpaper, onDismiss = { wallpaperShown = false }, onApply = { provider, sort, count, replace, topic ->
            model.startWallpaper(provider, sort, count, replace, topic)
        })
    }
}

@Composable
private fun Header(state: RemoteUiState, model: RemoteViewModel, onSettings: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "reconnect rotation")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800), RepeatMode.Restart), label = "reconnect angle")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("#63 Remote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(
                when (state.isOnline) { true -> Color(0xFF1FA35C); false -> Color(0xFFD5443C); null -> Color(0xFFB9BDC6) }, CircleShape,
            ))
            Text(state.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), maxLines = 1)
            IconButton(onClick = model::reconnect, enabled = !state.reconnecting, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, "Reconnect to TV", tint = Color(0xFFAD2030), modifier = Modifier.graphicsLayer { rotationZ = if (state.reconnecting) rotation else 0f })
            }
            IconButton(onClick = { model.setDarkTheme(!state.darkTheme) }, modifier = Modifier.size(36.dp)) {
                Icon(if (state.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Server address") }, onClick = { expanded = false; onSettings() })
                }
            }
        }
    }
}

@Composable
private fun Dock(state: RemoteUiState, model: RemoteViewModel, imageLoader: ImageLoader) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.tiles.take(5).forEach { tile ->
            val icon = if (state.darkTheme && !tile.iconDark.isNullOrBlank()) tile.iconDark else tile.icon
            val fit = if (state.darkTheme && !tile.fitDark.isNullOrBlank()) tile.fitDark else tile.fit
            val iconUrl = icon?.let { resolveDockIconUrl(state.serverUrl, it) }
            val tileColor = if (state.darkTheme) MaterialTheme.colorScheme.surface else parseColor(tile.color)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    onClick = { model.launchTile(tile) }, shape = RoundedCornerShape(16.dp), color = tileColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 1.dp, modifier = Modifier.size(62.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (iconUrl != null) {
                            AsyncImage(
                                model = iconUrl, contentDescription = null, imageLoader = imageLoader,
                                contentScale = if (fit == "cover") ContentScale.Crop else ContentScale.Fit,
                                modifier = if (fit == "cover") Modifier.fillMaxSize() else Modifier.fillMaxSize(.56f),
                            )
                        } else Text(tile.name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(tile.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private fun parseColor(value: String): Color = runCatching { Color(value.toColorInt()) }.getOrDefault(Violet)

@Composable
private fun RockyButton(mode: RockyMode, onPressStart: () -> Unit, onPressEnd: (Long) -> Unit, onCancel: () -> Unit) {
    val latestStart by rememberUpdatedState(onPressStart)
    val latestEnd by rememberUpdatedState(onPressEnd)
    val latestCancel by rememberUpdatedState(onCancel)
    val listening = mode == RockyMode.LISTENING || mode == RockyMode.RECORDING || mode == RockyMode.STARTING
    val label = when (mode) {
        RockyMode.LISTENING -> "Listening… tap to finish"
        RockyMode.RECORDING, RockyMode.STARTING -> "Listening…"
        RockyMode.SENDING -> "Thinking…"
        RockyMode.CANCELLING -> "Stopping microphone…"
        RockyMode.IDLE -> "Ask Rocky"
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (listening) Violet else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (listening) Violet else MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val downAt = SystemClock.elapsedRealtime()
                    latestStart()
                    val released = tryAwaitRelease()
                    val duration = SystemClock.elapsedRealtime() - downAt
                    if (released) latestEnd(duration) else latestCancel()
                })
            }
            .semantics { contentDescription = "Ask Rocky voice remote"; role = Role.Button },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Mic, null, tint = if (listening) Color.White else Violet)
        Spacer(Modifier.width(9.dp))
        Text(label, color = if (listening) Color.White else Violet, fontWeight = FontWeight.Medium)
    }
}
