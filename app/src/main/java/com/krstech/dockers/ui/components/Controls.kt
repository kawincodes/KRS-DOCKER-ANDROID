package com.krstech.dockers.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme

/** shadcn `<Switch>` */
@Composable
fun ShadSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ShadTheme.colors
    val track by animateColorAsState(if (checked) c.primary else c.input, label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp, label = "thumb")
    Box(
        modifier
            .width(44.dp).height(24.dp)
            .clip(ShadRadius.full)
            .background(track)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
    ) {
        Box(Modifier.offset(x = offset).align(Alignment.CenterStart).size(20.dp).clip(CircleShape).background(if (checked) c.primaryForeground else c.background))
    }
}

@Composable
fun SwitchRow(title: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ShadTheme.colors.foreground)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = ShadTheme.colors.mutedForeground)
        }
        Spacer(Modifier.width(12.dp))
        ShadSwitch(checked, onCheckedChange, enabled = enabled)
    }
}

/** shadcn `<Tabs>` (the "list" variant with a muted background). */
@Composable
fun ShadTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, scrollable: Boolean = false) {
    val c = ShadTheme.colors
    Row(
        modifier
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
            .clip(ShadRadius.md)
            .background(c.muted)
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val active = i == selected
            Box(
                Modifier
                    .then(if (scrollable) Modifier else Modifier.weight(1f))
                    .clip(ShadRadius.sm)
                    .background(if (active) c.background else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t, style = MaterialTheme.typography.labelLarge, color = if (active) c.foreground else c.mutedForeground, maxLines = 1)
            }
        }
    }
}

/** Filter chips used for status filters. */
@Composable
fun ShadChip(text: String, selected: Boolean, onClick: () -> Unit, count: Int? = null) {
    val c = ShadTheme.colors
    Row(
        Modifier
            .clip(ShadRadius.full)
            .background(if (selected) c.primary else c.background)
            .border(1.dp, if (selected) c.primary else c.border, ShadRadius.full)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.primaryForeground else c.foreground)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = if (selected) c.primaryForeground.copy(alpha = 0.7f) else c.mutedForeground)
        }
    }
}

/** shadcn `<Separator>` */
@Composable
fun ShadSeparator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ShadTheme.colors.border))
}

/** shadcn `<Select>` built on a DropdownMenu. */
@Composable
fun <T> ShadSelect(
    value: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    text: (T) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    val c = ShadTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) { Text(label, style = MaterialTheme.typography.labelLarge, color = c.foreground); Spacer(Modifier.height(6.dp)) }
        Box {
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(ShadRadius.md).background(c.background).border(1.dp, c.input, ShadRadius.md)
                    .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text(value), style = MaterialTheme.typography.bodyMedium, color = c.foreground, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Lucide.ChevronDown, null, tint = c.mutedForeground, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.popover) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(text(o), color = c.popoverForeground) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}

data class MenuAction(val label: String, val icon: ImageVector? = null, val destructive: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** shadcn `<DropdownMenu>` anchored to an icon button. */
@Composable
fun ShadMenu(actions: List<MenuAction>, icon: ImageVector, contentDescription: String = "More") {
    var open by remember { mutableStateOf(false) }
    val c = ShadTheme.colors
    Box {
        ShadIconButton(icon, contentDescription, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.popover) {
            actions.forEach { a ->
                DropdownMenuItem(
                    enabled = a.enabled,
                    leadingIcon = a.icon?.let { { Icon(it, null, modifier = Modifier.size(16.dp), tint = if (a.destructive) c.destructive else c.popoverForeground) } },
                    text = { Text(a.label, color = if (a.destructive) c.destructive else c.popoverForeground, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

/** shadcn `<Progress>` */
@Composable
fun ShadProgress(progress: Float, modifier: Modifier = Modifier, color: Color? = null) {
    val c = ShadTheme.colors
    Box(modifier.fillMaxWidth().height(8.dp).clip(ShadRadius.full).background(c.secondary)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).background(color ?: c.primary))
    }
}

/** shadcn `<Skeleton>` */
@Composable
fun ShadSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.clip(ShadRadius.md).background(ShadTheme.colors.muted))
}

@Composable
fun KeyValueRow(key: String, value: String, mono: Boolean = false) {
    val c = ShadTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, modifier = Modifier.width(120.dp))
        Text(
            value, style = if (mono) com.krstech.dockers.ui.theme.MonoStyle else MaterialTheme.typography.bodySmall,
            color = c.foreground, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, accent: Color? = null) {
    val c = ShadTheme.colors
    ShadCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.mutedForeground, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = accent ?: c.mutedForeground, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = c.foreground, maxLines = 1)
    }
}
