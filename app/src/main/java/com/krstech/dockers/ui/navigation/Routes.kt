package com.krstech.dockers.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*

object Routes {
    const val DASHBOARD = "dashboard"
    const val CONTAINERS = "containers"
    const val CONTAINER_DETAIL = "containers/{id}"
    const val IMAGES = "images"
    const val DISTROS = "distros"
    const val MORE = "more"
    const val VOLUMES = "volumes"
    const val NETWORKS = "networks"
    const val STACKS = "stacks"
    const val TERMINAL = "terminal?container={container}&title={title}"
    const val ENGINES = "engines"
    const val ENGINE_EDIT = "engines/edit?id={id}&type={type}"
    const val LOCAL_ENGINE = "engines/local"
    const val SETTINGS = "settings"
    const val EVENTS = "events"
    const val ABOUT = "about"

    fun containerDetail(id: String) = "containers/$id"
    fun terminal(containerId: String? = null, title: String? = null) =
        "terminal?container=${containerId.orEmpty()}&title=${java.net.URLEncoder.encode(title.orEmpty(), "UTF-8")}"
    fun engineEdit(id: String? = null, type: String? = null) = "engines/edit?id=${id.orEmpty()}&type=${type.orEmpty()}"
}

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

val bottomTabs = listOf(
    BottomTab(Routes.DASHBOARD, "Home", Lucide.LayoutDashboard),
    BottomTab(Routes.CONTAINERS, "Containers", Lucide.Container),
    BottomTab(Routes.IMAGES, "Images", Lucide.Layers),
    BottomTab(Routes.DISTROS, "Distros", Lucide.Terminal),
    BottomTab(Routes.MORE, "More", Lucide.Menu),
)
