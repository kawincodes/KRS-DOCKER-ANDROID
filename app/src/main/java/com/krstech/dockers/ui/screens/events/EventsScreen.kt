package com.krstech.dockers.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Lucide
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format

@Composable
fun EventsScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val events by app.engine.recentEvents.collectAsStateWithLifecycle()
    val c = ShadTheme.colors
    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Events", subtitle = "docker events (live)", onBack = { nav.popBackStack() })
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }) {
            if (events.isEmpty()) EmptyState("Waiting for events", "Container, image, volume and network events appear here as they happen.", icon = Lucide.Activity)
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events, key = { "${it.timeNano}-${it.actor.id}-${it.action}" }) { ev ->
                    ShadCard(contentPadding = PaddingValues(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShadBadge(ev.type, BadgeVariant.Secondary); Spacer(Modifier.width(8.dp))
                            ShadBadge(ev.action, when (ev.action) { "start", "create", "pull" -> BadgeVariant.Success; "die", "kill", "destroy", "delete" -> BadgeVariant.Destructive; else -> BadgeVariant.Outline })
                            Spacer(Modifier.width(8.dp))
                            Text(ev.name, style = MaterialTheme.typography.bodySmall, color = c.foreground, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(Format.relative(ev.time), style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                        }
                    }
                }
                item { BottomPadding() }
            }
        }
    }
}
