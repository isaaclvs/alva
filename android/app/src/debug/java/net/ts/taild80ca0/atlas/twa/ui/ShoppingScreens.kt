package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.DuplicateHint
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.AddItems
import net.ts.taild80ca0.atlas.twa.online.AddedItem
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.online.SetItemState
import net.ts.taild80ca0.atlas.twa.online.addedItems
import net.ts.taild80ca0.atlas.twa.online.liveItems
import net.ts.taild80ca0.atlas.twa.sync.ForegroundInvalidation

/** The same dark canvas and teal accent as the current Alva. Preferences arrive in #55. */
@Composable
fun AlvaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF09090B),
            background = Color(0xFF09090B), surface = Color(0xFF18181B),
            onBackground = Color(0xFFF4F4F5), onSurface = Color(0xFFF4F4F5),
            onSurfaceVariant = Color(0xFFB4B4BC), error = Color(0xFFF87171),
            secondaryContainer = Color(0xFF14332F), onSecondaryContainer = Color(0xFFF4F4F5),
        ),
        content = content,
    )
}

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
    var draft by rememberSaveable { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<AddedItem>>(emptyList()) }
    val busyItems = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
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

    fun add() {
        if (saving || ready == null) return
        val submitted = draft
        saving = true
        scope.launch {
            try {
                when (val result = repository.execute(AddItems.fromText(submitted))) {
                    is ActionResult.Success -> {
                        // Do not erase text typed while the request was in flight.
                        if (draft == submitted) draft = ""
                        val added = result.receipt.addedItems()
                        recent = added.filter { it.outcome != AddOutcome.DUPLICATE }
                        feedback = OnlineMessages.added(added)
                    }
                    else -> feedback = OnlineMessages.failure(result, keepsText = true)
                }
            } finally { saving = false }
        }
    }

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
            NavigationBar {
                NavigationBarItem(selected = screen == "add", onClick = { screen = "add" },
                    icon = { Text("+") }, label = { Text("Adicionar") }, modifier = Modifier.testTag("nav-add"))
                NavigationBarItem(selected = screen == "list", onClick = { screen = "list" },
                    icon = { Text("☷") }, label = { Text("Lista ($pending)") }, modifier = Modifier.testTag("nav-list"))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).testTag("shopping-home")) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (screen == "add") "Alva" else "Lista de compras", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f))
                if (accessActions != null) Box {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("house-menu")
                        .semantics { contentDescription = "Menu da casa" }) { Text("⋯") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Column(Modifier.widthIn(max = 280.dp).padding(12.dp)) { accessActions() }
                    }
                }
            }
            Text("Nossa casa", style = MaterialTheme.typography.labelLarge)
            // The latest reload failed but a list is on screen: say it may be out of date, and offer to read again.
            ready?.problem?.let { problem ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(problem.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
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
                screen == "add" -> {
                    val categories = ready.snapshot.categories
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                        if (recent.isNotEmpty()) {
                            item { Text("Acabou de adicionar", style = MaterialTheme.typography.titleMedium) }
                            items(recent, key = { it.itemPublicId }) { addition ->
                                val item = items.firstOrNull { it.publicId == addition.itemPublicId }
                                val category = categories.firstOrNull { it.publicId == item?.categoryPublicId }?.name ?: "Outros"
                                Text("${addition.name} · $category", modifier = Modifier.padding(vertical = 8.dp))
                            }
                        } else item { Text("Acabou em casa? Adicione aqui.") }
                    }
                    val duplicates = DuplicateHint.alreadyPending(draft, items)
                    if (duplicates.isNotEmpty()) Text("Já na lista: ${duplicates.joinToString()}", maxLines = 2, modifier = Modifier.testTag("duplicate-hint"))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, label = { Text("O que acabou?") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("add-input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }), maxLines = 4,
                    )
                    // Scaffold subcomposes its content during layout. Request focus
                    // from this composition, after the field's node is attached.
                    LaunchedEffect(Unit) {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                    Text("Separe por vírgula, ponto e vírgula ou quebra de linha.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { add() }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("save-add")) {
                        Text(if (saving) "Adicionando…" else "Adicionar à lista")
                    }
                }
                else -> {
                    Text("Pendentes: $pending · Comprados: ${items.size - pending}", modifier = Modifier.testTag("list-summary"))
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
                                Text(category + if (bought > 0) " · $bought/${rows.size}" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                            }
                            items(rows, key = { it.publicId }) { item ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("item-${item.publicId}")
                                        .toggleable(value = item.status == ItemStatus.PURCHASED,
                                            enabled = item.publicId !in busyItems, role = Role.Checkbox, onValueChange = { mark(item) })
                                        .padding(vertical = 8.dp),
                                ) {
                                    Checkbox(checked = item.status == ItemStatus.PURCHASED, onCheckedChange = null)
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(item.name, textDecoration = if (item.status == ItemStatus.PURCHASED) TextDecoration.LineThrough else null)
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
