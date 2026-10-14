package com.krstech.dockers.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme

enum class ButtonVariant { Default, Secondary, Outline, Ghost, Destructive, Link }
enum class ButtonSize { Default, Sm, Lg, Icon }

/** shadcn `<Button>` */
@Composable
fun ShadButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = ShadTheme.colors
    val (bg, fg, border) = when (variant) {
        ButtonVariant.Default -> Triple(c.primary, c.primaryForeground, null)
        ButtonVariant.Secondary -> Triple(c.secondary, c.secondaryForeground, null)
        ButtonVariant.Outline -> Triple(c.background, c.foreground, c.input)
        ButtonVariant.Ghost -> Triple(Color.Transparent, c.foreground, null)
        ButtonVariant.Destructive -> Triple(c.destructive, c.destructiveForeground, null)
        ButtonVariant.Link -> Triple(Color.Transparent, c.primary, null)
    }
    val height = when (size) { ButtonSize.Default -> 40.dp; ButtonSize.Sm -> 36.dp; ButtonSize.Lg -> 44.dp; ButtonSize.Icon -> 40.dp }
    val hPad = when (size) { ButtonSize.Default -> 16.dp; ButtonSize.Sm -> 12.dp; ButtonSize.Lg -> 32.dp; ButtonSize.Icon -> 0.dp }
    val interaction = remember { MutableInteractionSource() }
    val isEnabled = enabled && !loading

    Row(
        modifier = modifier
            .alpha(if (isEnabled) 1f else 0.5f)
            .height(height)
            .then(if (size == ButtonSize.Icon) Modifier.width(height) else Modifier)
            .clip(ShadRadius.md)
            .background(bg)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), ShadRadius.md) else Modifier)
            .clickable(enabled = isEnabled, interactionSource = interaction, indication = ripple(color = fg), role = Role.Button, onClick = onClick)
            .padding(horizontal = hPad),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides fg,
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
                textDecoration = if (variant == ButtonVariant.Link) TextDecoration.Underline else null,
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
                Spacer(Modifier.width(8.dp))
            } else if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                if (size != ButtonSize.Icon) Spacer(Modifier.width(8.dp))
            }
            content()
        }
    }
}

@Composable
fun ShadIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Ghost,
    enabled: Boolean = true,
    tint: Color? = null,
    size: androidx.compose.ui.unit.Dp = 36.dp,
) {
    val c = ShadTheme.colors
    val bg = when (variant) {
        ButtonVariant.Outline -> c.background; ButtonVariant.Secondary -> c.secondary
        ButtonVariant.Destructive -> c.destructive; ButtonVariant.Default -> c.primary; else -> Color.Transparent
    }
    val fg = tint ?: when (variant) {
        ButtonVariant.Destructive -> c.destructiveForeground; ButtonVariant.Default -> c.primaryForeground; else -> c.foreground
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(size)
            .clip(ShadRadius.md)
            .background(bg)
            .then(if (variant == ButtonVariant.Outline) Modifier.border(1.dp, c.input, ShadRadius.md) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ButtonText(text: String) = Text(text, maxLines = 1)
