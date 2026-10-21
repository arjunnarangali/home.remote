package com.myhome.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class HomeService(
    val name: String,
    val description: String,
    val url: String? = null,
)

private val homeServices = listOf(
    HomeService("Remote", "Drives the TV — buttons, keyboard, launches apps"),
    HomeService("Jellyfin", "Web app — everything, on any screen", BuildConfig.JELLYFIN_URL),
    HomeService("Matchday", "Live football & F1 — acestream paste-and-play", BuildConfig.MATCHDAY_URL),
    HomeService("Requests", "Ask for movies & shows — works away from home too", BuildConfig.REQUESTS_URL),
)

@Composable
internal fun HomeScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onShareHome: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenService: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = onShareHome,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share home page link",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ) {
                    Icon(
                        imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle ${if (darkTheme) "light" else "dark"} theme",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildAnnotatedString {
                    append("my")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
                    append("home")
                },
                fontSize = 58.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.7).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "What the Lenovo in the corner serves.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                modifier = Modifier.padding(top = 3.dp, bottom = 27.dp),
            )

            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                homeServices.forEach { service ->
                    Surface(
                        onClick = {
                            if (service.url == null) onOpenRemote() else onOpenService(service.url)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(service.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    service.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.size(36.dp),
                            ) {
                                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }

            Text(
                text = buildAnnotatedString {
                    append("Served from ")
                    withStyle(SpanStyle(background = MaterialTheme.colorScheme.surfaceVariant, fontWeight = FontWeight.Medium)) {
                        append(BuildConfig.LAN_ADDRESS)
                    }
                    append(" over your Wi-Fi.\nNames live in dnsmasq on the Lenovo (")
                    withStyle(SpanStyle(background = MaterialTheme.colorScheme.surfaceVariant, fontWeight = FontWeight.Medium)) {
                    append(BuildConfig.HOME_DNS_NAME)
                    }
                    append(", ")
                    withStyle(SpanStyle(background = MaterialTheme.colorScheme.surfaceVariant, fontWeight = FontWeight.Medium)) {
                        append(BuildConfig.REMOTE_DNS_NAME)
                    }
                    append(").")
                },
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}
