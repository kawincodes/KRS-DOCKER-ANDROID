package com.krstech.dockers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme

enum class BadgeVariant { Default, Secondary, Outline, Destructive, Success, Warning, Info }

/** shadcn `<Badge>` */
@Composable
fun ShadBadge(text: String, variant: BadgeVariant = BadgeVariant.Default, modifier: Modifier = Modifier, dot: Boolean = false) {
    val c = ShadTheme.colors
    val (bg, fg, border) = when (variant) {
        BadgeVariant.Default -> Triple(c.primary, c.primaryForeground, Color.Transparent)
        BadgeVariant.Secondary -> Triple(c.secondary, c.secondaryForeground, Color.Transparent)
        BadgeVariant.Outline -> Triple(Color.Transparent, c.foreground, c.border)
        BadgeVariant.Destructive -> Triple(c.destructive.copy(alpha = 0.15f), c.destructive, Color.Transparent)
        BadgeVariant.Success -> Triple(c.success.copy(alpha = 0.15f), c.success, Color.Transparent)
        BadgeVariant.Warning -> Triple(c.warning.copy(alpha = 0.15f), c.warning, Color.Transparent)
        BadgeVariant.Info -> Triple(c.info.copy(alpha = 0.15f), c.info, Color.Transparent)
    }
    Row(
        modifier
            .clip(ShadRadius.full)
            .background(bg)
            .border(1.dp, border, ShadRadius.full)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Maps a docker container state string to a badge. */
@Composable
fun StateBadge(state: String) {
    val variant = when (state.lowercase()) {
        "running" -> BadgeVariant.Success
        "paused" -> BadgeVariant.Warning
        "restarting", "created" -> BadgeVariant.Info
        "exited", "dead" -> BadgeVariant.Secondary
        else -> BadgeVariant.Outline
    }
    ShadBadge(state.replaceFirstChar { it.uppercase() }, variant, dot = true)
}

@Composable
fun StatusDot(state: String, modifier: Modifier = Modifier) {
    val c = ShadTheme.colors
    val color = when (state.lowercase()) {
        "running" -> c.success; "paused" -> c.warning; "restarting" -> c.info; "exited", "dead" -> c.mutedForeground; else -> c.border
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}
