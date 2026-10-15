package com.krstech.dockers.ui.screens.images

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.model.ImageSearchResult
import com.krstech.dockers.engine.model.ImageSummary
import com.krstech.dockers.engine.model.PullProgress
import com.krstech.dockers.engine.model.RegistryAuth
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.JsonViewer
import com.krstech.dockers.ui.screens.containers.RunContainerSheet
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class PullState(val reference: String, val layers: Map<String, Pair<Long, Long>> = emptyMap(), val status: String = "", val done: Boolean = false, val error: String? = null) {
    val fraction: Float get() {
        val total = layers.values.sumOf { it.second }; val cur = layers.values.sumOf { it.first }
        return if (total > 0) (cur.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
}

data class ImagesUiState(
    val images: List<ImageSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val pull: PullState? = null,
    val search: List<ImageSearchResult> = emptyList(),
    val searching: Boolean = false,
) {
    val filtered get() = images.filter { query.isBlank() || it.primaryTag.contains(query, true) || it.shortId.startsWith(query) }.sortedByDescending { it.created }
    val totalSize get() = images.sumOf { it.size }
}

@OptIn(FlowPreview::class)
class ImagesViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImagesUiState())
    val state: StateFlow<ImagesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() } }
        viewModelScope.launch { app.engine.events.filter { it.type == "image" }.debounce(500).collect { load(true) } }
    }

    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching { client.listImages() }
                .onSuccess { l -> _state.update { it.copy(images = l, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun pull(reference: String, auth: RegistryAuth?, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(pull = PullState(reference)) }
            runCatching {
                client.pull(reference, auth).collect { p: PullProgress ->
                    _state.update { s ->
                        val cur = s.pull ?: PullState(reference)
                        val layers = if (p.id != null && p.progressDetail != null && p.progressDetail.total > 0) cur.layers + (p.id to (p.progressDetail.current to p.progressDetail.total)) else cur.layers
                        s.copy(pull = cur.copy(layers = layers, status = p.status + (p.id?.let { " $it" } ?: "")))
                    }
                }
            }.onSuccess { _state.update { it.copy(pull = null) }; onDone("Pulled $reference"); load(true) }
                .onFailure { e -> _state.update { it.copy(pull = null) }; onDone(e.message ?: "Pull failed") }
        }
    }

    fun remove(image: ImageSummary, force: Boolean, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching { client.removeImage(if (image.isDangling) image.id else image.primaryTag, force = force) }
                .onSuccess { onDone("Removed ${image.primaryTag}"); load(true) }
                .onFailure { onDone(it.message ?: "Remove failed") }
        }
    }

    fun prune(onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching { client.pruneImages() }.onSuccess { onDone("Reclaimed ${Format.bytes(it.spaceReclaimed)}"); load(true) }.onFailure { onDone(it.message ?: "Prune failed") }
        }
    }

    fun tag(image: ImageSummary, repo: String, tag: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.tagImage(image.id, repo, tag) }.onSuccess { onDone("Tagged $repo:$tag"); load(true) }.onFailure { onDone(it.message ?: "Tag failed") } }
    }

    fun search(term: String) {
        val client = app.engine.client ?: return
        if (term.isBlank()) { _state.update { it.copy(search = emptyList()) }; return }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            runCatching { client.searchImages(term) }.onSuccess { r -> _state.update { it.copy(search = r, searching = false) } }.onFailure { _state.update { it.copy(searching = false) } }
        }
    }

    suspend fun inspectRaw(image: ImageSummary): JsonElement? = app.engine.client?.let { runCatching { it.inspectImageRaw(image.id) }.getOrNull() }
}

@Composable
fun ImagesScreen(nav: NavHostController) {
    val vm = appViewModel { ImagesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var showPull by remember { mutableStateOf(false) }
    var confirmPrune by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ImageSummary?>(null) }
    var runImage by remember { mutableStateOf<String?>(null) }
    var inspectTarget by remember { mutableStateOf<ImageSummary?>(null) }
    var tagTarget by remember { mutableStateOf<ImageSummary?>(null) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Images", subtitle = "${state.images.size} images · ${Format.bytes(state.totalSize)}") {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.load() })
            ShadMenu(listOf(
                MenuAction("Pull image", Lucide.Download) { showPull = true },
                MenuAction("Prune dangling", Lucide.Trash2, destructive = true) { confirmPrune = true },
            ), icon = Lucide.EllipsisVertical)
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    ShadInput(state.query, vm::setQuery, placeholder = "Filter images", leadingIcon = Lucide.Search)
                }
                state.pull?.let { p ->
                    ShadCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), contentPadding = PaddingValues(12.dp)) {
                        Text("Pulling ${p.reference}", style = MaterialTheme.typography.titleSmall, color = c.foreground)
                        Text(p.status, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp)); ShadProgress(p.fraction)
                    }
                }
                ErrorBanner(state.error, Modifier.padding(horizontal = 16.dp))
                if (state.loading && state.images.isEmpty()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(5) { ShadSkeleton(Modifier.fillMaxWidth().height(64.dp)) } }
                } else if (state.filtered.isEmpty()) {
                    EmptyState("No images", "Pull an image from Docker Hub or any registry.", icon = Lucide.Layers, action = { ShadButton(onClick = { showPull = true }, leadingIcon = Lucide.Download) { ButtonText("Pull image") } })
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.filtered, key = { it.id }) { img ->
                            ShadCard(contentPadding = PaddingValues(12.dp), onClick = { inspectTarget = img }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(img.repository, style = MaterialTheme.typography.titleSmall, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            ShadBadge(img.tag, if (img.isDangling) BadgeVariant.Warning else BadgeVariant.Secondary)
                                            Text(img.shortId, style = MonoStyle, color = c.mutedForeground)
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text("${Format.bytes(img.size)} · ${Format.relative(img.created)}${if (img.containers > 0) " · ${img.containers} in use" else ""}", style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                                    }
                                    ShadIconButton(Lucide.Play, "Run", onClick = { runImage = img.primaryTag.takeUnless { img.isDangling } ?: img.id }, size = 34.dp, tint = c.success)
                                    ShadMenu(listOf(
                                        MenuAction("Run container", Lucide.Play) { runImage = img.primaryTag.takeUnless { img.isDangling } ?: img.id },
                                        MenuAction("Inspect", Lucide.FileJson) { inspectTarget = img },
                                        MenuAction("Add tag", Lucide.Tag) { tagTarget = img },
                                        MenuAction("Pull latest", Lucide.Download, enabled = !img.isDangling) { vm.pull(img.primaryTag, null, toast) },
                                        MenuAction("Remove", Lucide.Trash2, destructive = true) { removeTarget = img },
                                    ), icon = Lucide.EllipsisVertical)
                                }
                            }
                        }
                        item { BottomPadding() }
                    }
                }
            }
        }
    }

    if (showPull) PullImageDialog(vm, onDismiss = { showPull = false }, onPull = { ref, auth -> showPull = false; vm.pull(ref, auth, toast) })
    if (confirmPrune) ShadDialog(onDismiss = { confirmPrune = false }, title = "Prune dangling images?", description = "Untagged layers not referenced by any container will be deleted.", confirmText = "Prune", destructive = true, onConfirm = { confirmPrune = false; vm.prune(toast) })
    removeTarget?.let { img ->
        var force by remember { mutableStateOf(false) }
        ShadDialog(onDismiss = { removeTarget = null }, title = "Remove ${img.primaryTag}?", description = "The image and its untagged parent layers will be removed.", confirmText = "Remove", destructive = true, onConfirm = { removeTarget = null; vm.remove(img, force, toast) }) {
            SwitchRow("Force", "Remove even if containers use it.", checked = force, onCheckedChange = { force = it })
        }
    }
    runImage?.let { ref -> RunContainerSheet(onDismiss = { runImage = null }, onCreated = { runImage = null; toast("Container started") }, initialImage = ref) }
    inspectTarget?.let { img ->
        var raw by remember { mutableStateOf<JsonElement?>(null) }
        androidx.compose.runtime.LaunchedEffect(img.id) { raw = vm.inspectRaw(img) }
        ShadSheet(onDismiss = { inspectTarget = null }, title = img.primaryTag, description = "${Format.bytes(img.size)} · ${img.shortId}") {
            JsonViewer(raw, Modifier.height(480.dp))
        }
    }
    tagTarget?.let { img ->
        var repo by remember { mutableStateOf(img.repository.takeUnless { it == "<none>" } ?: "") }
        var tag by remember { mutableStateOf("latest") }
        ShadDialog(onDismiss = { tagTarget = null }, title = "Tag image", confirmText = "Tag", confirmEnabled = repo.isNotBlank() && tag.isNotBlank(), onConfirm = { tagTarget = null; vm.tag(img, repo.trim(), tag.trim(), toast) }) {
            ShadInput(repo, { repo = it }, label = "Repository", placeholder = "myorg/app"); Spacer(Modifier.height(10.dp))
            ShadInput(tag, { tag = it }, label = "Tag", placeholder = "latest")
        }
    }
}

@Composable
private fun PullImageDialog(vm: ImagesViewModel, onDismiss: () -> Unit, onPull: (String, RegistryAuth?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = ShadTheme.colors
    var ref by remember { mutableStateOf("") }
    var showAuth by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("https://index.docker.io/v1/") }
    androidx.compose.runtime.LaunchedEffect(ref) { kotlinx.coroutines.delay(400); if (ref.length >= 2 && !ref.contains(':')) vm.search(ref) }

    ShadDialog(onDismiss = onDismiss, title = "Pull image", description = "Search Docker Hub or type any reference (registry/repo:tag).", confirmText = "Pull", confirmEnabled = ref.isNotBlank(),
        onConfirm = { onPull(ref.trim(), if (showAuth && user.isNotBlank()) RegistryAuth(user, pass, server) else null) }) {
        ShadInput(ref, { ref = it }, placeholder = "nginx:alpine", leadingIcon = Lucide.Search, description = if (state.searching) "Searching…" else null)
        if (state.search.isNotEmpty() && !ref.contains(':')) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.search.take(6).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { ref = r.name }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                            Text(r.description.ifBlank { "–" }, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (r.isOfficial) ShadBadge("official", BadgeVariant.Info)
                        Spacer(Modifier.width(6.dp))
                        Text("★ ${r.starCount}", style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Private registry credentials", checked = showAuth, onCheckedChange = { showAuth = it })
        if (showAuth) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadInput(server, { server = it }, label = "Registry", placeholder = "https://index.docker.io/v1/")
                ShadInput(user, { user = it }, label = "Username")
                ShadInput(pass, { pass = it }, label = "Password / token", visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        }
    }
}
