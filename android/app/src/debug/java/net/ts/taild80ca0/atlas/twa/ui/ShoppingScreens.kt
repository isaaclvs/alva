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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.*
import net.ts.taild80ca0.atlas.twa.data.matching.DuplicateHint

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

/** Screens depend only on the store. No HTTP call or database query lives here. */
@Composable
fun ShoppingScreens(store: ShoppingListStore, modifier: Modifier = Modifier, accessActions: (@Composable () -> Unit)? = null) {
    var state by remember(store) { mutableStateOf<ListState?>(null) }
    var readFailed by remember(store) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("add") }
    var draft by rememberSaveable { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<ItemAddition>>(emptyList()) }
    val busyItems = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(store, retry) {
        readFailed = false
        store.observe().catch { error ->
            if (error is CancellationException) throw error
            readFailed = true
        }.collect { state = it }
    }
    LaunchedEffect(screen, state != null) {
        if (screen == "add" && state != null) {
            focus.requestFocus()
            keyboard?.show()
        } else keyboard?.hide()
    }

    fun add() {
        if (saving || state == null || readFailed) return
        val submitted = draft
        saving = true
        scope.launch {
            try {
                when (val result = store.execute(ListIntent.AddText(submitted))) {
                    is IntentResult.Saved -> {
                        // Do not erase text typed while this transaction was in flight.
                        if (draft == submitted) draft = ""
                        recent = result.additions.filter { it.outcome != AddOutcome.DUPLICATE }
                        val duplicates = result.additions.count { it.outcome == AddOutcome.DUPLICATE }
                        feedback = "Salvo neste aparelho." + if (duplicates > 0) " $duplicates já estavam na lista." else ""
                    }
                    is IntentResult.AlreadyOnList -> feedback = "Já está na lista: " + result.additions.joinToString { it.name }
                    is IntentResult.Invalid -> feedback = "Digite pelo menos um item."
                    is IntentResult.Failed -> feedback = "Não foi possível salvar. Seu texto foi mantido; tente de novo."
                    IntentResult.Unchanged -> Unit
                }
            } finally { saving = false }
        }
    }

    fun mark(item: ListItem) {
        if (item.publicId in busyItems || readFailed) return
        busyItems += item.publicId
        scope.launch {
            try {
                val desired = if (item.status == ItemStatus.PURCHASED) ItemStatus.PENDING else ItemStatus.PURCHASED
                when (store.execute(ListIntent.SetItemState(item.publicId, desired))) {
                    is IntentResult.Failed -> feedback = "Não foi possível salvar a marcação. Tente de novo."
                    is IntentResult.Invalid -> feedback = "Este item não está mais na lista."
                    else -> Unit
                }
            } finally { busyItems -= item.publicId }
        }
    }

    val current = state
    val pending = current?.items?.count { it.status == ItemStatus.PENDING } ?: 0
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
            if (current != null) {
                Text(
                    when {
                        current.needsAttention > 0 -> "Salvo neste aparelho · há alterações que precisam de atenção."
                        current.queuedCommands > 0 -> "Salvo neste aparelho · aguardando sincronização."
                        else -> "Pronto para adicionar, mesmo sem internet."
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            feedback?.let {
                Text(it, maxLines = 3, modifier = Modifier.testTag("feedback").semantics { liveRegion = LiveRegionMode.Polite })
            }
            when {
                readFailed -> {
                    Text("Não foi possível ler a lista. Seus dados foram mantidos.")
                    Button(onClick = { retry++ }) { Text("Tentar de novo") }
                }
                current == null -> Text("Abrindo sua lista…")
                screen == "add" -> {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                        if (recent.isNotEmpty()) {
                            item { Text("Acabou de adicionar", style = MaterialTheme.typography.titleMedium) }
                            items(recent, key = { it.itemPublicId }) { addition ->
                                val item = current.items.firstOrNull { it.publicId == addition.itemPublicId }
                                val category = current.categories.firstOrNull { it.publicId == item?.categoryPublicId }?.name ?: "Outros"
                                Text("${addition.name} · $category", modifier = Modifier.padding(vertical = 8.dp))
                            }
                        } else item { Text("Acabou em casa? Adicione aqui.") }
                    }
                    val duplicates = DuplicateHint.alreadyPending(draft, current.items)
                    if (duplicates.isNotEmpty()) Text("Já na lista: ${duplicates.joinToString()}", maxLines = 2, modifier = Modifier.testTag("duplicate-hint"))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, label = { Text("O que acabou?") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("add-input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }), maxLines = 4,
                    )
                    Text("Separe por vírgula, ponto e vírgula ou quebra de linha.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { add() }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("save-add")) {
                        Text(if (saving) "Salvando…" else "Adicionar à lista")
                    }
                }
                else -> {
                    Text("Pendentes: $pending · Comprados: ${current.items.size - pending}", modifier = Modifier.testTag("list-summary"))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (current.items.isEmpty()) item { Text("Sua lista está vazia. Adicione o que acabou em casa.") }
                        val known = current.categories.sortedBy { it.position }
                        val unknown = current.items.filter { item -> known.none { it.publicId == item.categoryPublicId } }
                        val groups = known.map { category ->
                            category.name to (current.items.filter { it.categoryPublicId == category.publicId } +
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
                                        if (item.awaitingSync) Text("Aguardando sincronização", style = MaterialTheme.typography.labelSmall)
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
