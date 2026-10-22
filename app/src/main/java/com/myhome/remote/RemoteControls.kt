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
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
internal fun DirectionPad(model: RemoteViewModel, dark: Boolean, padSize: Dp) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, modifier = Modifier.size(padSize)) {
            Column {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f)); HoldButton(Icons.Default.ArrowUpward, "Direction up", { model.sendKey(KeyCodes.getValue("up")) }, Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    HoldButton(Icons.AutoMirrored.Filled.ArrowBack, "Direction left", { model.sendKey(KeyCodes.getValue("left")) }, Modifier.weight(1f))
                    Button(
                        onClick = { model.sendKey(23) }, shape = CircleShape, modifier = Modifier.size(88.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF2E3139) else Color(0xFFE3E6EC), contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text("OK", fontWeight = FontWeight.SemiBold) }
                    HoldButton(Icons.AutoMirrored.Filled.ArrowForward, "Direction right", { model.sendKey(KeyCodes.getValue("right")) }, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f)); HoldButton(Icons.Default.ArrowDownward, "Direction down", { model.sendKey(KeyCodes.getValue("down")) }, Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                }
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp, modifier = Modifier.width(258.dp).height(64.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoldButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", { model.sendKey(KeyCodes.getValue("voldown")) }, Modifier.weight(1f))
                IconButton(onClick = { model.sendKey(KeyCodes.getValue("mute")) }, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.VolumeOff, "Mute") }
                HoldButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", { model.sendKey(KeyCodes.getValue("volup")) }, Modifier.weight(1f))
            }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.keyboardText, onValueChange = model::setKeyboardText, modifier = Modifier.weight(1f),
                    placeholder = { Text("Type for the TV") }, singleLine = true,
                )
                Button(onClick = model::sendText) { Text("Send") }
            }
            Text("Enter sends the text and confirms it on the TV.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}
