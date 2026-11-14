package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import java.io.IOException
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
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
    preferences: DevicePreferences = DevicePreferences(),
    saveDark: suspend (Boolean) -> Unit = {},
    saveColor: suspend (AlvaColor) -> Unit = {},
    saveScreen: suspend (String) -> Unit = {},
    signOut: (suspend () -> String?)? = null,
    exportPdf: (suspend () -> String?)? = null,
) {
    val state by repository.state.collectAsState()
    val editState = rememberEditItemState()
    val listFilters = rememberListFilterState()
    var screen by rememberSaveable { mutableStateOf(preferences.screen) }
    val addState = rememberAddScreenState(repository)
    var feedback by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val shopping = remember { ShoppingMode(context.getSharedPreferences(SHOPPING_PREFERENCES, android.content.Context.MODE_PRIVATE)) }
    val shoppingMode = shopping.active
    val focus = LocalFocusManager.current
    val shownScreen = if (shoppingMode) "list" else screen
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    // Hosted here, with this scope and this state, so a mark, removal or correction sent from the Lista tab goes on
    // (and keeps its "sending" hint and its undo) when the person switches to Adicionar before the server answers.
    val listActions = rememberListActions(repository, editState,
        showToast = { toast = it }, onFailure = { feedback = it },
        onMarked = { if (shoppingMode) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        shoppingMode = { shoppingMode })
    DisposableEffect(shoppingMode, view) {
        if (shoppingMode) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(shownScreen) {
        if (shownScreen == "list") keyboard?.hide()
    }
    LaunchedEffect(shopping.until) {
        if (shoppingMode) {
            delay((shopping.until - System.currentTimeMillis()).coerceAtLeast(0L))
            shopping.refresh()
        }
    }
    // The undo toast is the device's own: it lives here, never in the snapshot, and fades after ~5 s.
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(UNDO_TOAST_MILLIS)
            toast = null
        }
    }
    // Opening or resuming the app reads the list again; the live signal exists only while this screen is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(repository, invalidation, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { shopping.refresh(); invalidation?.start(); scope.launch { repository.refresh() } }
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

    val pending = items.count { it.status == ItemStatus.PENDING }
    var menuWorking by remember { mutableStateOf(false) }
    fun menuAction(action: suspend () -> String?) {
        if (menuWorking) return
        menuWorking = true
        scope.launch {
            try { feedback = action() }
            catch (_: IOException) { feedback = "Não foi possível salvar a preferência. Tente de novo." }
            finally { menuWorking = false }
        }
    }
    val menu: @Composable () -> Unit = {
        HouseMenu(preferences, shownScreen == "list", menuWorking,
            toggleTheme = { menuAction { saveDark(!preferences.dark); null } },
            selectColor = { color -> menuAction { saveColor(color); null } },
            exportPdf = exportPdf?.let { { menuAction(it) } },
            signOut = signOut?.let { { menuAction(it) } }, actions = accessActions)
    }
    // The connection and feedback lines that sit under the header of either screen.
    val notices: @Composable (Dp) -> Unit = { gutter ->
        // The latest reload failed but a list is on screen: say it may be out of date, and offer to read again.
        ready?.problem?.let { problem ->
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp)) {
                Text(problem.message, style = AlvaTheme.type.caption, color = AlvaTheme.colors.warn, modifier = Modifier.weight(1f)
                    .testTag("connection-notice").semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { scope.launch { repository.refresh() } }, modifier = Modifier.testTag("reload")) { Text("Atualizar") }
            }
        }
        feedback?.let {
            Text(it, maxLines = 3, modifier = Modifier.padding(horizontal = gutter).testTag("feedback").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            bottomBar = {
                if (!shoppingMode)
                BottomNavigation(
                    current = if (screen == "list") AppScreen.List else AppScreen.Add, pending = pending,
                    onSelect = { selected ->
                        screen = selected.tag
                        scope.launch { try { saveScreen(selected.tag) } catch (_: IOException) { feedback = "Não foi possível lembrar a tela. Tente de novo." } }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).testTag("shopping-home")) {
                when {
                    state is OnlineState.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        BrandHeader(trailing = menu)
                        Text((state as OnlineState.Failed).problem.message, modifier = Modifier.testTag("load-failed")
                            .padding(vertical = 8.dp))
                        Button(onClick = { scope.launch { repository.refresh() } }, modifier = Modifier.testTag("reload")) { Text("Tentar de novo") }
                    }
                    ready == null -> Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        BrandHeader(trailing = menu)
                        Text("Abrindo sua lista…", modifier = Modifier.padding(top = 16.dp).testTag("loading"))
                    }
                    shownScreen == "list" -> ListScreen(
                        repository = repository, snapshot = ready.snapshot, editState = editState, filterState = listFilters,
                        onAddItems = { if (!shoppingMode) screen = "add" },
                        onFailure = { feedback = it },
                        showToast = { toast = it },
                        notice = { notices(20.dp) },
                        headerActions = { menu() },
                        shoppingMode = shoppingMode,
                        afterSummary = {
                            if (shoppingMode) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { shopping.exit(); screen = "list"; listFilters.value = ListFilters(); focus.clearFocus(); keyboard?.hide() }, modifier = Modifier.testTag("exit-shopping")) { Text("Sair das compras") }
                            } else if (items.isNotEmpty()) {
                                Button(onClick = {
                                    if (editState.itemId == null) { focus.clearFocus(); keyboard?.hide(); shopping.start() }
                                    else editState.focusRequest++
                                }, colors = ButtonDefaults.buttonColors(containerColor = AlvaTheme.colors.accentSoft, contentColor = AlvaTheme.colors.accentStrong),
                                    shape = AlvaShapes.Control, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("start-shopping")
                                        .semantics { if (editState.itemId != null) disabled() }) { Text("Iniciar compras", style = AlvaTheme.type.button) }
                                if (editState.itemId != null) Text("Salve ou cancele a edição para iniciar as compras.", color = AlvaTheme.colors.warn,
                                    style = AlvaTheme.type.caption, modifier = Modifier.testTag("shopping-edit-hint"))
                            }
                        },
                        onMarked = { if (shoppingMode) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        actions = listActions,
                    )
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp)) {
                        BrandHeader(markSize = 28.dp, gap = 9.6.dp, markBox = 36.dp, trailing = menu)
                        notices(0.dp)
                        AddScreen(addState, ready.snapshot, Modifier.weight(1f).fillMaxWidth())
                }
                }
            }
        }
        // `#toast`: above the bottom navigation, centered, only on the device that acted.
        toast?.let { current ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 76.dp),
                contentAlignment = Alignment.Center) {
                UndoToast(current.text, current.actionLabel, onAction = {
                    toast = null
                    scope.launch { current.onAction() }
                })
            }
        }
    }
}
