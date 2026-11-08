package com.myhome.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class RemoteAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val repeatWhileHeld: Boolean = false,
)

@Composable
internal fun DirectionPad(onSendKey: (Int) -> Unit, dark: Boolean, padSize: Dp) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, modifier = Modifier.size(padSize)) {
            Column {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f)); HoldButton(Icons.Default.ArrowUpward, "Direction up", { onSendKey(KeyCodes.getValue("up")) }, Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    HoldButton(Icons.AutoMirrored.Filled.ArrowBack, "Direction left", { onSendKey(KeyCodes.getValue("left")) }, Modifier.weight(1f))
                    Button(
                        onClick = { onSendKey(23) }, shape = CircleShape, modifier = Modifier.size(88.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF2E3139) else Color(0xFFE3E6EC), contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text("OK", fontWeight = FontWeight.SemiBold) }
                    HoldButton(Icons.AutoMirrored.Filled.ArrowForward, "Direction right", { onSendKey(KeyCodes.getValue("right")) }, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f)); HoldButton(Icons.Default.ArrowDownward, "Direction down", { onSendKey(KeyCodes.getValue("down")) }, Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                }
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp, modifier = Modifier.width(258.dp).height(64.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoldButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", { onSendKey(KeyCodes.getValue("voldown")) }, Modifier.weight(1f))
                IconButton(onClick = { onSendKey(KeyCodes.getValue("mute")) }, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.VolumeOff, "Mute") }
                HoldButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", { onSendKey(KeyCodes.getValue("volup")) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun AndroidTvActionGrid(
    onSendKey: (Int) -> Unit,
    keyboardShown: Boolean,
    onToggleKeyboard: () -> Unit,
    voiceMode: RockyMode,
    onVoiceStart: () -> Unit,
    onVoiceEnd: (Long) -> Unit,
    onVoiceCancel: () -> Unit,
) {
    val actions = listOf(
        RemoteAction("Back", Icons.AutoMirrored.Filled.ArrowBack, { onSendKey(KeyCodes.getValue("back")) }, true),
        RemoteAction("Home", Icons.Default.Home, { onSendKey(KeyCodes.getValue("home")) }),
        RemoteAction("Input", Icons.Default.SettingsInputHdmi, { onSendKey(KeyCodes.getValue("input")) }),
        RemoteAction(if (keyboardShown) "Hide" else "Keyboard", Icons.Default.Keyboard, onToggleKeyboard),
        RemoteAction("Power", Icons.Default.PowerSettingsNew, { onSendKey(android.view.KeyEvent.KEYCODE_POWER) }),
        RemoteAction(when (voiceMode) {
            RockyMode.LISTENING -> "Listening… tap to send"
            RockyMode.RECORDING, RockyMode.STARTING -> "Listening…"
            RockyMode.SENDING -> "Sending…"
            RockyMode.CANCELLING -> "Stopping…"
            RockyMode.IDLE -> "Mic · hold to talk"
        }, Icons.Default.Mic, {}),
    )
    actions.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { action ->
                if (action.icon == Icons.Default.Mic) VoiceHoldActionChip(
                    label = action.label, onStart = onVoiceStart, onEnd = onVoiceEnd, onCancel = onVoiceCancel,
                    modifier = Modifier.weight(1f),
                )
                else if (action.repeatWhileHeld) HoldActionChip(action, Modifier.weight(1f))
                else OutlinedButton(onClick = action.onClick, modifier = Modifier.weight(1f).height(74.dp), contentPadding = PaddingValues(4.dp)) {
                    ActionContent(action, null)
                }
            }
        }
    }
}

@Composable
private fun VoiceHoldActionChip(
    label: String,
    onStart: () -> Unit,
    onEnd: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestStart by rememberUpdatedState(onStart)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestCancel by rememberUpdatedState(onCancel)
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.height(74.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true
                val startedAt = SystemClock.elapsedRealtime()
                latestStart()
                val released = tryAwaitRelease()
                val duration = SystemClock.elapsedRealtime() - startedAt
                if (released) latestEnd(duration) else latestCancel()
                pressed = false
            })
        }.semantics { contentDescription = "Microphone, hold to talk"; role = Role.Button },
        shape = RoundedCornerShape(18.dp),
        color = if (pressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Mic, null, Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun HoldButton(icon: ImageVector, label: String, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val currentPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.size(64.dp).background(if (pressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    currentPress()
                    coroutineScope {
                        val repeatJob = launch {
                            delay(220)
                            while (true) { currentPress(); delay(220) }
                        }
                        try { tryAwaitRelease() } finally { repeatJob.cancel(); pressed = false }
                    }
                })
            }
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
internal fun ActionGrid(state: RemoteUiState, model: RemoteViewModel, onWallpaperClick: () -> Unit) {
    val actions = listOf(
        RemoteAction("Back", Icons.AutoMirrored.Filled.ArrowBack, { model.sendKey(KeyCodes.getValue("back")); Unit }, true),
        RemoteAction("Home", Icons.Default.Home, { model.sendKey(KeyCodes.getValue("home")); Unit }),
        RemoteAction("Input", Icons.Default.SettingsInputHdmi, { model.sendKey(KeyCodes.getValue("input")); Unit }),
        RemoteAction(if (state.keyboardShown) "Hide" else "Keyboard", Icons.Default.Keyboard, { model.setKeyboardShown(!state.keyboardShown) }),
        RemoteAction("Sleep", Icons.Default.PowerSettingsNew, { model.sleep(); Unit }),
        RemoteAction("Wallpaper", Icons.Default.Wallpaper, onWallpaperClick),
    )
    actions.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { action ->
                if (action.repeatWhileHeld) {
                    HoldActionChip(action, Modifier.weight(1f))
                } else {
                    OutlinedButton(onClick = action.onClick, modifier = Modifier.weight(1f).height(74.dp), contentPadding = PaddingValues(4.dp)) {
                        ActionContent(action, state)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HoldActionChip(action: RemoteAction, modifier: Modifier = Modifier) {
    val currentAction by rememberUpdatedState(action.onClick)
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.height(74.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    currentAction()
                    coroutineScope {
                        val repeatJob = launch { delay(220); while (true) { currentAction(); delay(220) } }
                        try { tryAwaitRelease() } finally { repeatJob.cancel(); pressed = false }
                    }
                })
            }
            .semantics {
                contentDescription = action.label
                role = Role.Button
                onClick(label = action.label) {
                    currentAction()
                    true
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = if (pressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        ActionContent(action, null)
    }
}

@Composable
private fun ActionContent(action: RemoteAction, state: RemoteUiState?) {
    Column(
        Modifier.fillMaxSize().padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(action.icon, null, Modifier.size(20.dp))
            if (action.label == "Wallpaper" && state != null && (state.wallpaper.running || state.wallpaper.count != null)) {
                Text(state.wallpaper.badge, modifier = Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = (-6).dp), color = Violet, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(action.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun KeyboardPanel(state: RemoteUiState, model: RemoteViewModel) {
    KeyboardPanel(state.keyboardText, model::setKeyboardText, model::sendText)
}

@Composable
internal fun KeyboardPanel(textValue: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = textValue, onValueChange = onTextChange, modifier = Modifier.weight(1f),
                    placeholder = { Text("Type for the TV") }, singleLine = true,
                )
                Button(onClick = onSend) { Text("Send") }
            }
            Text("Enter sends the text and confirms it on the TV.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}
