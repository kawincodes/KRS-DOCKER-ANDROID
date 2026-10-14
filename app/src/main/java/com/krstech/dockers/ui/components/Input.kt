package com.krstech.dockers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme

/** shadcn `<Input>` with optional `<Label>` and helper/description text. */
@Composable
fun ShadInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    description: String? = null,
    error: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = ShadTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when { error != null -> c.destructive; focused -> c.ring; else -> c.input }
    val textStyle = if (monospace) com.krstech.dockers.ui.theme.MonoStyle.copy(color = c.foreground, fontSize = 13.sp) else MaterialTheme.typography.bodyMedium.copy(color = c.foreground)

    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.foreground)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (singleLine) Modifier.height(40.dp) else Modifier.heightIn(min = 40.dp))
                .clip(ShadRadius.md)
                .background(c.background)
                .border(1.dp, borderColor, ShadRadius.md)
                .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = c.mutedForeground, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = textStyle.copy(color = c.mutedForeground), maxLines = 1)
                }
                BasicTextField(
                    value = value, onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = textStyle,
                    singleLine = singleLine, minLines = minLines,
                    enabled = enabled,
                    cursorBrush = SolidColor(c.foreground),
                    interactionSource = interaction,
                    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                )
            }
            trailing?.invoke()
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp)); Text(error, style = MaterialTheme.typography.bodySmall, color = c.destructive)
        } else if (description != null) {
            Spacer(Modifier.height(4.dp)); Text(description, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        }
    }
}
