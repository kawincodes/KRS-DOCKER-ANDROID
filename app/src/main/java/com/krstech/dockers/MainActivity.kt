package com.krstech.dockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.LocalToast
import com.krstech.dockers.ui.components.ShadSeparator
import com.krstech.dockers.ui.components.ShadToastHost
import com.krstech.dockers.ui.navigation.AppNavHost
import com.krstech.dockers.ui.navigation.bottomTabs
import com.krstech.dockers.ui.theme.KRSDockersTheme
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DockersApp).container
        // Docker Desktop-style auto start of the built-in VM.
        if (savedInstanceState == null) {
            kotlinx.coroutines.MainScope().launch {
                val s = container.settings.settings.first()
                if (s.vmAutoStart && !container.vm.isRunning && container.engine.client == null) container.vm.start()
            }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            CompositionLocalProvider(LocalAppContainer provides container) {
                KRSDockersTheme(mode = settings.theme) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val c = ShadTheme.colors
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = bottomTabs.any { it.route == currentRoute }

    CompositionLocalProvider(LocalToast provides toast) {
        Scaffold(
            containerColor = c.background,
            snackbarHost = { ShadToastHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    androidx.compose.foundation.layout.Column {
                        ShadSeparator()
                        NavigationBar(containerColor = c.background, tonalElevation = 0.dp) {
                            bottomTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true; restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, tab.label, modifier = Modifier.padding(2.dp)) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.foreground, selectedTextColor = c.foreground,
                                        unselectedIconColor = c.mutedForeground, unselectedTextColor = c.mutedForeground,
                                        indicatorColor = c.secondary,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(c.background).padding(bottom = padding.calculateBottomPadding())) {
                AppNavHost(nav)
            }
        }
    }
}
