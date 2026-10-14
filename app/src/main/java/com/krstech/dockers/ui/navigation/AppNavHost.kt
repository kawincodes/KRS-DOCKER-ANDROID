package com.krstech.dockers.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.krstech.dockers.ui.screens.MoreScreen
import com.krstech.dockers.ui.screens.containers.ContainerDetailScreen
import com.krstech.dockers.ui.screens.containers.ContainersScreen
import com.krstech.dockers.ui.screens.dashboard.DashboardScreen
import com.krstech.dockers.ui.screens.distros.DistrosScreen
import com.krstech.dockers.ui.screens.engines.EngineEditorScreen
import com.krstech.dockers.ui.screens.engines.EnginesScreen
import com.krstech.dockers.ui.screens.engines.LocalEngineScreen
import com.krstech.dockers.ui.screens.events.EventsScreen
import com.krstech.dockers.ui.screens.images.ImagesScreen
import com.krstech.dockers.ui.screens.networks.NetworksScreen
import com.krstech.dockers.ui.screens.settings.AboutScreen
import com.krstech.dockers.ui.screens.settings.SettingsScreen
import com.krstech.dockers.ui.screens.stacks.StacksScreen
import com.krstech.dockers.ui.screens.terminal.TerminalScreen
import com.krstech.dockers.ui.screens.volumes.VolumesScreen

@Composable
fun AppNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(nav) }
        composable(Routes.CONTAINERS) { ContainersScreen(nav) }
        composable(Routes.CONTAINER_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            ContainerDetailScreen(nav, it.arguments?.getString("id").orEmpty())
        }
        composable(Routes.IMAGES) { ImagesScreen(nav) }
        composable(Routes.DISTROS) { DistrosScreen(nav) }
        composable(Routes.MORE) { MoreScreen(nav) }
        composable(Routes.VOLUMES) { VolumesScreen(nav) }
        composable(Routes.NETWORKS) { NetworksScreen(nav) }
        composable(Routes.STACKS) { StacksScreen(nav) }
        composable(
            Routes.TERMINAL,
            arguments = listOf(navArgument("container") { type = NavType.StringType; defaultValue = "" }, navArgument("title") { type = NavType.StringType; defaultValue = "" }),
        ) {
            TerminalScreen(nav, it.arguments?.getString("container").orEmpty().ifBlank { null }, java.net.URLDecoder.decode(it.arguments?.getString("title").orEmpty(), "UTF-8").ifBlank { null })
        }
        composable(Routes.ENGINES) { EnginesScreen(nav) }
        composable(
            Routes.ENGINE_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }, navArgument("type") { type = NavType.StringType; defaultValue = "" }),
        ) {
            EngineEditorScreen(nav, it.arguments?.getString("id").orEmpty().ifBlank { null }, it.arguments?.getString("type").orEmpty().ifBlank { null })
        }
        composable(Routes.LOCAL_ENGINE) { LocalEngineScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.EVENTS) { EventsScreen(nav) }
        composable(Routes.ABOUT) { AboutScreen(nav) }
    }
}
