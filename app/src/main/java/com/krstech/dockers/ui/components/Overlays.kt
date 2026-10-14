package com.krstech.dockers.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PackageOpen
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme

/** shadcn `<Dialog>` / `<AlertDialog>` */
@Composable
fun ShadDialog(
    onDismiss: () -> Unit,
    title: String,
    description: String? = null,
    confirmText: String = "Continue",
    onConfirm: (() -> Unit)? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    loading: Boolean = false,
    dismissText: String = "Cancel",
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ShadCard(Modifier.padding(24.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ShadTheme.colors.foreground)
            if (description != null) {
                Spacer(Modifier.height(6.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = ShadTheme.colors.mutedForeground)
            }
            if (content != null) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) { content() }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ShadButton(onClick = onDismiss, variant = ButtonVariant.Outline, enabled = !loading) { ButtonText(if (onConfirm == null) "Close" else dismissText) }
                if (onConfirm != null) {
                    Spacer(Modifier.width(8.dp))
                    ShadButton(onClick = onConfirm, variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default, enabled = confirmEnabled, loading = loading) { ButtonText(confirmText) }
                }
            }
        }
    }
}

/** shadcn `<Sheet side="bottom">` */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadSheet(onDismiss: () -> Unit, title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = ShadTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        containerColor = c.card, contentColor = c.cardForeground, shape = ShadRadius.xl,
        dragHandle = { Spacer(Modifier.height(8.dp)) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.foreground)
            if (description != null) { Spacer(Modifier.height(4.dp)); Text(description, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground) }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: ImageVector = Lucide.PackageOpen, action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = ShadTheme.colors
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = c.mutedForeground, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.foreground)
        Spacer(Modifier.height(4.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** shadcn-styled Snackbar ("Toast"). */
@Composable
fun ShadToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val c = ShadTheme.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(snackbarData = data, containerColor = c.foreground, contentColor = c.background, actionColor = c.info, shape = ShadRadius.md)
    }
}

val LocalToast = compositionLocalOf<(String) -> Unit> { {} }
