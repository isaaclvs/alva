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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
) {
    val state by repository.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf("add") }
    val addState = rememberAddScreenState(repository)
    var feedback by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(screen) {
        if (screen == "list") keyboard?.hide()
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

    val pending = items.count { it.status == ItemStatus.PENDING }
    val menu: @Composable () -> Unit = { HouseMenu(accessActions) }
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
                BottomNavigation(
                    current = if (screen == "list") AppScreen.List else AppScreen.Add, pending = pending,
                    onSelect = { screen = it.tag },
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
                    screen == "list" -> ListScreen(
                        repository = repository, snapshot = ready.snapshot,
                        onAddItems = { screen = "add" },
                        onFailure = { feedback = it },
                        showToast = { toast = it },
                        notice = { notices(20.dp) },
                        headerActions = { menu() },
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

/**
 * The ⋯ button of the PWA headers. The menu itself (theme, color, PDF, sair) is #109; until then it only hosts the
 * house connection actions the access screen provides.
 */
@Composable
private fun HouseMenu(actions: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton(onClick = { open = true }, modifier = Modifier.testTag("house-menu"))
        if (actions != null) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 280.dp).padding(12.dp)) { actions() }
        }
    }
}
