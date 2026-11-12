package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.online.SetItemState
import net.ts.taild80ca0.atlas.twa.online.liveItems
import net.ts.taild80ca0.atlas.twa.sync.ForegroundInvalidation
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/**
 * Screens depend only on the [OnlineRepository]: the server's list, and actions that wait for its answer.
 * No HTTP call or database query lives here, and nothing typed is thrown away unless the server took it.
 */
@Composable
fun ShoppingScreens(
    repository: OnlineRepository,
    modifier: Modifier = Modifier,
    accessActions: (@Composable () -> Unit)? = null,
    invalidation: ForegroundInvalidation? = null,
) {
    val state by repository.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf("add") }
    val addState = rememberAddScreenState(repository)
    var feedback by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val busyItems = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(screen) {
        if (screen == "list") keyboard?.hide()
    }
    // Opening or resuming the app reads the list again; the live signal exists only while this screen is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(repository, invalidation, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { invalidation?.start(); scope.launch { repository.refresh() } }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> invalidation?.stop()
                else -> Unit
            }
        }
        // Adding an observer replays the events up to the current state: entering an already resumed screen
        // (right after connecting) gets its ON_RESUME too.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            invalidation?.stop()
        }
    }

    val ready = state as? OnlineState.Ready
    val items = remember(ready?.snapshot) { ready?.snapshot?.liveItems().orEmpty() }

    fun mark(item: ListItem) {
        if (item.publicId in busyItems || ready == null) return
        busyItems += item.publicId
        scope.launch {
            try {
                val desired = if (item.status == ItemStatus.PURCHASED) ItemStatus.PENDING else ItemStatus.PURCHASED
                val result = repository.execute(SetItemState(item.publicId, desired))
                if (result !is ActionResult.Success) feedback = OnlineMessages.failure(result)
            } finally { busyItems -= item.publicId }
        }
    }

    val pending = items.count { it.status == ItemStatus.PENDING }
    Scaffold(
        modifier = modifier.imePadding(),
        bottomBar = {
            NavigationBar(containerColor = AlvaTheme.colors.canvas) {
                NavigationBarItem(selected = screen == "add", onClick = { screen = "add" }, colors = alvaNavigationItemColors(),
                    icon = { Text("+") }, label = { Text("Adicionar", style = AlvaTheme.type.navigation) }, modifier = Modifier.testTag("nav-add"))
                NavigationBarItem(selected = screen == "list", onClick = { screen = "list" }, colors = alvaNavigationItemColors(),
                    icon = { Text("☷") }, label = { Text("Lista ($pending)", style = AlvaTheme.type.navigation) }, modifier = Modifier.testTag("nav-list"))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).testTag("shopping-home")) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (screen == "add") "Alva" else "Lista de compras", style = AlvaTheme.type.pageTitle,
                    modifier = Modifier.weight(1f))
                if (accessActions != null) Box {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("house-menu")
                        .semantics { contentDescription = "Menu da casa" }) { Text("⋯") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Column(Modifier.widthIn(max = 280.dp).padding(12.dp)) { accessActions() }
                    }
                }
            }
            Text("Nossa casa", style = AlvaTheme.type.caption, color = AlvaTheme.colors.inkFaint)
            // The latest reload failed but a list is on screen: say it may be out of date, and offer to read again.
            ready?.problem?.let { problem ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(problem.message, style = AlvaTheme.type.caption, color = AlvaTheme.colors.warn, modifier = Modifier.weight(1f)
                        .testTag("connection-notice").semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { scope.launch { repository.refresh() } }, modifier = Modifier.testTag("reload")) { Text("Atualizar") }
                }
            }
            feedback?.let {
                Text(it, maxLines = 3, modifier = Modifier.testTag("feedback").semantics { liveRegion = LiveRegionMode.Polite })
            }
            when {
                state is OnlineState.Failed -> {
                    Text((state as OnlineState.Failed).problem.message, modifier = Modifier.testTag("load-failed")
                        .padding(vertical = 8.dp))
                    Button(onClick = { scope.launch { repository.refresh() } }, modifier = Modifier.testTag("reload")) { Text("Tentar de novo") }
                }
                ready == null -> Text("Abrindo sua lista…", modifier = Modifier.testTag("loading"))
                screen == "add" -> AddScreen(addState, ready.snapshot, Modifier.weight(1f).fillMaxWidth())
                else -> {
                    Text("Pendentes: $pending · Comprados: ${items.size - pending}", style = AlvaTheme.type.summary, color = AlvaTheme.colors.inkSoft, modifier = Modifier.testTag("list-summary"))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (items.isEmpty()) item { Text("Sua lista está vazia. Adicione o que acabou em casa.") }
                        val known = ready.snapshot.categories.sortedBy { it.position }
                        val unknown = items.filter { item -> known.none { it.publicId == item.categoryPublicId } }
                        val groups = known.map { category ->
                            category.name to (items.filter { it.categoryPublicId == category.publicId } +
                                if (category.name == "Outros") unknown else emptyList())
                        } + if (known.none { it.name == "Outros" }) listOf("Outros" to unknown) else emptyList()
                        groups.filter { it.second.isNotEmpty() }.forEach { (category, rows) ->
                            item {
                                val bought = rows.count { it.status == ItemStatus.PURCHASED }
                                Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CategoryIcon(category)
                                    Text(category, style = AlvaTheme.type.sectionLabel, color = AlvaTheme.colors.inkSoft,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f))
                                    if (bought > 0) Text("$bought/${rows.size}", style = AlvaTheme.type.caption, color = AlvaTheme.colors.inkFaint)
                                }
                            }
                            items(rows, key = { it.publicId }) { item ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("item-${item.publicId}")
                                        .toggleable(value = item.status == ItemStatus.PURCHASED,
                                            enabled = item.publicId !in busyItems, role = Role.Checkbox, onValueChange = { mark(item) })
                                        .then(if (item === rows.first()) Modifier else Modifier.rowDivider(AlvaTheme.colors.line))
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val bought = item.status == ItemStatus.PURCHASED
                                    AlvaCheck(checked = bought)
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(item.name, style = AlvaTheme.type.row,
                                            color = if (bought) AlvaTheme.colors.inkFaint else AlvaTheme.colors.ink,
                                            textDecoration = if (bought) TextDecoration.LineThrough else null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
