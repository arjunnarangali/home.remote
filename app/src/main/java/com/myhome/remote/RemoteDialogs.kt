package com.myhome.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun WallpaperDialog(
    current: WallpaperStatus,
    onDismiss: () -> Unit,
    onApply: (String, String, Int, Boolean, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("hdq") }
    var sort by remember { mutableStateOf("views") }
    var count by remember { mutableStateOf("50") }
    var replace by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wallpaper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Topic") }, placeholder = { Text(current.rememberedQuery.ifBlank { "Try anything your mind wishes…" }) }, singleLine = true)
                Text("Source", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(provider == "unsplash", { provider = "unsplash" }, label = { Text("Photos") })
                    FilterChip(provider == "hdq", { provider = "hdq" }, label = { Text("HD picks") })
                }
                if (provider == "hdq") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort"); Spacer(Modifier.width(8.dp))
                    FilterChip(sort == "views", { sort = "views" }, label = { Text("Most viewed") })
                    Spacer(Modifier.width(4.dp)); FilterChip(sort == "latest", { sort = "latest" }, label = { Text("Latest") })
                }
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(2) }, label = { Text("Photos (1–50)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replace, { replace = it }); Text(if (replace) "Replace existing wallpapers" else "Append to existing wallpapers")
                }
                if (current.running) {
                    if (current.count == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                    val note = if (current.append == true) "Adding ${current.freshPhotos}/${current.count ?: "…"} — ${current.photos} in the pool"
                        else "Fetching ${current.freshPhotos}/${current.count ?: "…"} …"
                    Text(if (current.count != null && current.freshPhotos >= current.count) "Pushing to TV…" else note, style = MaterialTheme.typography.bodySmall)
                } else Text("${current.photos} photos currently on TV", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val photoCount = count.toIntOrNull()?.coerceIn(1, 50) ?: 50
                count = photoCount.toString()
                onApply(provider, sort, photoCount, replace, query.trim())
            }, enabled = !current.running) { Text(if (current.running) "Fetching…" else "Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ServerSettingsDialog(currentUrl: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote server") },
        text = {
            OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, placeholder = { Text(RemoteApi.DEFAULT_BASE_URL) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(url) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
