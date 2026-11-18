package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    var screen by rememberSaveable { mutableStateOf(preferences.screen) }
    // The read-only history is a screen of its own over the two tabs, opened from the ⋯ menu.
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val historyState = rememberHistoryScreenState()
    // The host's own failure line (#146): dismissible, in a card, and never older than the action that caused it.
    val feedback = rememberFeedbackState()
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    // The add screen's own toast (the undo of a removal from "Acabou de adicionar") is the host's undo toast too.
    val addState = rememberAddScreenState(repository, showToast = { toast = it }, onActionStart = feedback::dismiss)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val shopping = remember { ShoppingMode(context.getSharedPreferences(SHOPPING_PREFERENCES, android.content.Context.MODE_PRIVATE)) }
    val shoppingMode = shopping.active
    // The checklist starts with the bought items out of the way (also when the saved mode is restored after a
    // restart); the chip in the footer says how many are hidden and brings them back.
    val listFilters = rememberListFilterState(ListFilters(pendingOnly = shoppingMode))
    val focus = LocalFocusManager.current
    val shownScreen = if (shoppingMode) "list" else screen
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current
    var shoppingFooterHeight by remember { mutableIntStateOf(0) }
    // Hosted here, with this scope and this state, so a mark, removal or correction sent from the Lista tab goes on
    // (and keeps its "sending" hint and its undo) when the person switches to Adicionar before the server answers.
    val listActions = rememberListActions(repository, editState,
        // A success is the clearest sign the failure is over: the toast only comes from an answer that was taken.
        showToast = { toast = it; feedback.dismiss() },
        onFailure = { message, retry -> feedback.show(message, retry) }, onActionStart = feedback::dismiss,
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
        feedback.dismiss()
        menuWorking = true
        scope.launch {
            try {
                action()?.let { problem -> feedback.show(problem) { menuAction(action) } }
            } catch (_: IOException) {
                feedback.show("Não foi possível salvar a preferência. Tente de novo.") { menuAction(action) }
            } finally { menuWorking = false }
        }
    }
    val menu: @Composable () -> Unit = {
        HouseMenu(preferences, shownScreen == "list", menuWorking,
            toggleTheme = { menuAction { saveDark(!preferences.dark); null } },
            selectColor = { color -> menuAction { saveColor(color); null } },
            exportPdf = exportPdf?.let { { menuAction(it) } },
            history = { historyState.forget(); historyOpen = true },
            signOut = signOut?.let { { menuAction(it) } }, actions = accessActions)
    }
    // A command that is taking longer than a moment says so (the transport waits up to 10 s to connect and 30 s for
    // the answer, and retries twice): the hint goes away with the answer, whichever tab is open.
    val sending = listActions.anyBusy || listActions.finishing || addState.saving || addState.habituals.busy ||
        editState.saving || menuWorking
    val slowSending = rememberSlowCommand(sending)
    // The notices under the header of either screen, from the least to the most specific: the list may be out of
    // date (warn), a command is still on its way, and the last action failed (danger, dismissible).
    val notices: @Composable (Dp) -> Unit = { gutter ->
        val problem = ready?.problem
        val failure = feedback.current
        if (problem != null || failure != null || slowSending) {
            Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (problem != null) NoticeCard(problem.message, NoticeTone.Warn, "connection-notice") {
                    NoticeAction("Atualizar", AlvaTheme.colors.inkSoft, Modifier.testTag("reload")) {
                        scope.launch { repository.refresh() }
                    }
                }
                if (slowSending) SendingHint()
                if (failure != null) FeedbackBanner(failure, onDismiss = { feedback.dismiss(failure) })
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            bottomBar = {
                if (!shoppingMode && !historyOpen)
                BottomNavigation(
                    current = if (screen == "list") AppScreen.List else AppScreen.Add, pending = pending,
                    onSelect = { selected ->
                        screen = selected.tag
                        scope.launch {
                            try { saveScreen(selected.tag) }
                            catch (_: IOException) { feedback.show("Não foi possível lembrar a tela. Tente de novo.") }
                        }
                    },
                )
            },
        ) { padding ->
            // The Scaffold's padding already ends above the floating capsule (and above the keyboard), so neither the
            // content nor the toast below can sit under the navigation.
            Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().testTag("shopping-home")) {
                when {
                    state is OnlineState.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        BrandHeader(trailing = menu)
                        Text((state as OnlineState.Failed).problem.message, modifier = Modifier.testTag("load-failed")
                            .padding(vertical = 8.dp))
                        Button(onClick = { scope.launch { repository.refresh() } }, modifier = Modifier.testTag("reload")) { Text("Tentar de novo") }
                    }
                    historyOpen -> HistoryScreen(
                        repository = repository, snapshot = ready?.snapshot, state = historyState,
                        onBack = { historyOpen = false },
                        onReadd = { result ->
                            // The same summary the add field shows: what the repeat did, only on this device.
                            addState.notice(historyReaddToast(result))
                        },
                    )
                    ready == null -> Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        BrandHeader(trailing = menu)
                        Text("Abrindo sua lista…", modifier = Modifier.padding(top = 16.dp).testTag("loading"))
                    }
                    shownScreen == "list" -> ListScreen(
                        repository = repository, snapshot = ready.snapshot, editState = editState, filterState = listFilters,
                        onAddItems = { if (!shoppingMode) screen = "add" },
                        onFailure = { feedback.show(it) },
                        showToast = { toast = it; feedback.dismiss() },
                        notice = { notices(20.dp) },
                        headerActions = { menu() },
                        shoppingMode = shoppingMode,
                        onExitShopping = if (shoppingMode) { {
                            shopping.exit(); screen = "list"; listFilters.value = ListFilters(); focus.clearFocus(); keyboard?.hide()
                        } } else null,
                        afterSummary = {
                            if (!shoppingMode && items.isNotEmpty()) {
                                Button(onClick = {
                                    if (editState.itemId == null) {
                                        focus.clearFocus(); keyboard?.hide()
                                        // The checklist starts with the bought items out of the way: the chip in the
                                        // footer says how many are hidden and brings them back.
                                        listFilters.value = ListFilters(pendingOnly = true)
                                        shopping.start()
                                    }
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
                        onShoppingFooterHeightChanged = { shoppingFooterHeight = it },
                    )
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp)) {
                        BrandHeader(markSize = 28.dp, gap = 9.6.dp, markBox = 36.dp, trailing = menu)
                        notices(0.dp)
                        AddScreen(addState, ready.snapshot, Modifier.weight(1f).fillMaxWidth())
                }
                }
            }
            // `#toast`: above the capsule, or above the measured shopping footer (including search and filters),
            // centered, only on the device that acted.
            toast?.let { current ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = if (shoppingMode) with(density) { shoppingFooterHeight.toDp() } + 12.dp else 12.dp),
                    contentAlignment = Alignment.Center) {
                    UndoToast(current.text, current.actionLabel, onAction = {
                        toast = null
                        scope.launch { current.onAction() }
                    })
                }
            }
            }
        }
    }
}
