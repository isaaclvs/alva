package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.data.db.*
import net.ts.taild80ca0.atlas.twa.data.list.*
import net.ts.taild80ca0.atlas.twa.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Room + installed Compose app, with a transport that must never be called. */
class ShoppingScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = "screens-${UUID.randomUUID()}.db"
    private lateinit var db: AlvaDatabase
    private lateinit var store: RoomShoppingListStore
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    @Before fun prepare() {
        db = AlvaDatabaseFactory.create(context, file)
        store = RoomShoppingListStore(db)
        runBlocking {
            db.categories().upsert(listOf(CategoryEntity("produce", "Hortifruti", 1), CategoryEntity("other", "Outros", 2)))
        }
    }

    private fun launch(
        using: ShoppingListStore = store,
        sync: SyncController? = null,
        foreground: ((HouseAccess) -> ForegroundInvalidation)? = null,
        conflicts: ConflictResolver? = null,
    ) {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, RoomHouseholdBinding(db))
        AccessGraph.install(access, using, sync, foreground = foreground?.invoke(access), conflicts = conflicts)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        scenario?.close()
        AccessGraph.install(null)
        db.close()
        context.deleteDatabase(file)
    }

    private fun waitForText(tag: String, text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertMarked(id: String, checked: Boolean) {
        val value = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("item-$id") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, value,
            )).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Public emulator output survives the runner uninstalling the test APK.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun add(text: String) {
        compose.onNodeWithTag("add-input").performTextInput(text)
        compose.onNodeWithTag("save-add").assertIsDisplayed()
        // Exercise the real Done action without racing IME/layout animation coordinates.
        compose.onNodeWithTag("add-input").performImeAction()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("feedback") and hasText("Salvo neste aparelho", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun addsMultipleNamesMarksAndReopensFromDiskWithoutNetwork() {
        launch()
        compose.onNodeWithTag("add-input").assertIsFocused()
        add("Leite, Pão; Ovos\nLeite")
        val emptyInput = SemanticsMatcher("editable text is empty") {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("add-input") and emptyInput).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("add-input").assert(emptyInput)
        compose.onNodeWithText("Alva").assertIsDisplayed()
        compose.onNodeWithTag("save-add").assertIsDisplayed()
        capture("shopping-add.png")
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Pendentes: 3 · Comprados: 0")
        val id = runBlocking { db.listItems().live().first { it.name == "Leite" }.publicId }
        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, true)
        waitForText("list-summary", "Pendentes: 2 · Comprados: 1")
        capture("shopping-list.png")

        scenario!!.close()
        db.close()
        db = AlvaDatabaseFactory.create(context, file)
        store = RoomShoppingListStore(db)
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        assertMarked(id, true)
        assertEquals(2, runBlocking { db.commands().inOrder().size })
        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, false)
    }

    @Test fun warnsAboutDuplicatesAndPreservesInputWhenNothingIsAdded() {
        runBlocking { store.execute(ListIntent.AddText("Leite")) }
        launch()
        compose.onNodeWithTag("add-input").performTextInput("leite")
        waitForText("duplicate-hint", "Já na lista: Leite")
        compose.onNodeWithTag("duplicate-hint").assertTextContains("Já na lista: Leite")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Já está na lista")
        compose.onNodeWithTag("feedback").assertTextContains("Já está na lista: leite")
        compose.onNodeWithTag("add-input").assertTextContains("leite")
        assertEquals(1, runBlocking { db.commands().inOrder().size })
    }

    @Test fun storageFailureKeepsDraftAndNeverReportsSuccess() {
        val failing = object : ShoppingListStore by store {
            override suspend fun execute(intent: ListIntent) = IntentResult.Failed(IOException("test failure"))
        }
        launch(failing)
        compose.onNodeWithTag("add-input").performTextInput("Café")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Não foi possível salvar")
        compose.onNodeWithTag("feedback").assertTextContains("Não foi possível salvar", substring = true)
        compose.onNodeWithTag("add-input").assertTextContains("Café")
        assertTrue(runBlocking { db.commands().inOrder().isEmpty() })
        assertTrue(runBlocking { db.listItems().live().isEmpty() })
    }

    @Test fun typingDuringCommitIsPreservedAndRotationKeepsDraftAndScreen() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val delayed = object : ShoppingListStore by store {
            override suspend fun execute(intent: ListIntent): IntentResult {
                entered.complete(Unit)
                release.await()
                return store.execute(intent)
            }
        }
        launch(delayed)
        compose.onNodeWithTag("add-input").performTextInput("Leite")
        compose.onNodeWithTag("save-add").performClick()
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.onNodeWithTag("add-input").performTextReplacement("Pão")
        release.complete(Unit)
        waitForText("feedback", "Salvo neste aparelho")
        compose.onNodeWithTag("feedback").assertTextContains("Salvo neste aparelho", substring = true)
        compose.onNodeWithTag("add-input").assertTextContains("Pão")
        scenario!!.recreate()
        compose.onNodeWithTag("add-input").assertTextContains("Pão")
        compose.onNodeWithTag("nav-list").performClick()
        scenario!!.recreate()
        compose.onNodeWithTag("nav-list").assertIsSelected()
        compose.onNodeWithText("Leite").assertIsDisplayed()
        compose.onNodeWithTag("add-input").assertDoesNotExist()
    }

    @Test fun repeatedNavigationOnlyFocusesTheAttachedAddField() {
        launch()
        repeat(10) {
            // Exercise attachment, not touch coordinates moving with IME animation.
            // The main end-to-end flow above still navigates with real taps.
            compose.onNodeWithTag("nav-list").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick,
            ) { it() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("nav-list").assertIsSelected()
            compose.onNodeWithTag("add-input").assertDoesNotExist()
            compose.onNodeWithTag("nav-add").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick,
            ) { it() }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("add-input") and SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Focused, true,
                )).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertTrue(runBlocking { db.commands().inOrder().isEmpty() })
    }

    @Test fun observesChangesFromAnotherStoreAndGroupsByCategory() {
        runBlocking {
            db.listItems().upsert(listOf(ListItemEntity("fruit", "Banana", "produce", 0, null, null, 1, null, null)))
        }
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Pendentes: 1")
        compose.onNodeWithText("Hortifruti").assertIsDisplayed()
        compose.onNodeWithTag("item-fruit").assertIsOff()
        runBlocking { RoomShoppingListStore(db).execute(ListIntent.AddText("Café")) }
        waitForText("list-summary", "Pendentes: 2 · Comprados: 0")
        compose.onNodeWithText("Outros").assertIsDisplayed()
        compose.onNodeWithText("Café").assertIsDisplayed()
    }

    @Test fun opensWithOneSyncAndTheButtonAsksAgainShowingWhatHappened() {
        val results = java.util.concurrent.LinkedBlockingQueue(
            listOf(
                SyncReport(SyncOutcome.Offline(net.ts.taild80ca0.atlas.twa.sync.TransientCause.NETWORK), needsAttention = 1),
                SyncReport(SyncOutcome.Synced(7)),
            ),
        )
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val sync = SyncController(
            SyncRunner { runs.incrementAndGet(); results.take() },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default),
        )

        launch(sync = sync)

        // Opening asked for a cycle by itself; it did not get through, and the screen says so in plain words.
        waitForText("sync-status", "Sem conexão com o servidor")
        compose.onNodeWithTag("sync-status").assertTextContains("1 alteração precisa de atenção", substring = true)
        compose.onNodeWithTag("sync-now").assertTextEquals("Tentar novamente")
        assertEquals(1, runs.get())
        // Adding still works and never waits for the network.
        compose.onNodeWithTag("save-add").assertIsDisplayed()
        add("Leite")
        runBlocking { assertEquals("Leite", db.listItems().live().single().name) }

        compose.onNodeWithTag("sync-now").performScrollTo().assertIsDisplayed().performClick()
        waitForText("sync-status", "Sincronizado.")
        compose.onNodeWithTag("sync-now").assertTextEquals("Sincronizar agora")
        assertEquals(2, runs.get())
    }

    @Test fun liveSubscriptionClosesWhenPausedAndReopensOnResume() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        try {
            launch(foreground = { access ->
                val controller = SyncController(
                    SyncRunner {
                        SyncReport(SyncOutcome.Synced(0))
                    }, scope,
                )
                ForegroundInvalidation(
                    access, ServerEndpoint.parse("http://127.0.0.1:3010"), controller, scope,
                    CableConnector { _, _, _ ->
                        opened.incrementAndGet()
                        CableSocket { closed.incrementAndGet() }
                    },
                )
            })
            compose.waitUntil(10_000) { opened.get() == 1 }
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.waitUntil(10_000) { closed.get() == 1 }
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { opened.get() == 2 }
            assertEquals(1, closed.get())
        } finally {
            scope.cancel()
        }
    }


    private fun refusedAddition(name: String, uncertain: Boolean = false): IntentResult.Saved = runBlocking {
        val result = store.execute(ListIntent.AddText(name)) as IntentResult.Saved
        assertTrue(WirePreparer(db).prepare(result.commandId) is PreparationResult.Prepared)
        val attempt = WireOutbox(db).beginAttempt(result.commandId) as AttemptResult.Started
        if (!uncertain) db.commands().recordAnswer(result.commandId, attempt.attempt)
        db.commands().setStatus(result.commandId, "REJECTED", if (uncertain) "idempotency_conflict" else "not_found")
        result
    }

    private fun resolver() = ConflictResolver(db, IdGenerator { UUID.randomUUID().toString() }, Clock { 123L })

    private fun openReview(rootId: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("conflicts-expand").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conflicts-expand").performClick()
        compose.onNodeWithTag("conflict-review-$rootId").performClick()
    }

    @Test fun reviewsAndDiscardsOnlyTheRefusedGroupWhilePreservingTheAddDraft() {
        val root = refusedAddition("Leite")
        runBlocking {
            store.execute(ListIntent.SetItemState(root.itemIds.single(), ItemStatus.PURCHASED))
            store.execute(ListIntent.AddText("Pão"))
        }
        launch(conflicts = resolver())
        compose.onNodeWithTag("add-input").performTextInput("Rascunho")
        compose.onNodeWithTag("nav-list").performClick()
        openReview(root.commandId)
        compose.onNodeWithTag("conflict-discard").assertTextEquals("Descartar 2 alterações")
        compose.onNodeWithTag("conflict-cancel").performClick()
        assertEquals(3, runBlocking { db.commands().count() })
        compose.onNodeWithTag("nav-add").performClick()
        compose.onNodeWithTag("add-input").assertTextContains("Rascunho")
        compose.onNodeWithTag("nav-list").performClick()
        openReview(root.commandId)
        compose.onNodeWithTag("conflict-discard").performClick()
        compose.waitUntil(10_000) { runBlocking { db.commands().count() } == 1 }
        waitForText("conflict-feedback", "Alterações locais descartadas.")
        compose.onNodeWithTag("conflict-feedback").assertTextEquals("Alterações locais descartadas.")
        assertEquals(1, runBlocking { db.resolutions().all().size })
        assertEquals(listOf("Pão"), runBlocking { db.listItems().live().map { it.name } })
        compose.onNodeWithTag("nav-add").performClick()
        compose.onNodeWithTag("add-input").assertTextContains("Rascunho")
    }

    @Test fun aChangedGroupRequiresANewReviewAndNeverSilentlyUpdatesTheConfirmation() {
        val root = refusedAddition("Leite")
        launch(conflicts = resolver())
        compose.onNodeWithTag("nav-list").performClick()
        openReview(root.commandId)
        runBlocking { store.execute(ListIntent.SetItemState(root.itemIds.single(), ItemStatus.PURCHASED)) }
        compose.onNodeWithTag("conflict-discard").assertTextEquals("Descartar 1 alteração")
        compose.onNodeWithTag("conflict-discard").performClick()
        waitForText("conflict-feedback", "As alterações mudaram")
        assertEquals(2, runBlocking { db.commands().count() })
        assertTrue(runBlocking { db.resolutions().all().isEmpty() })
        compose.onNodeWithTag("conflict-review-${root.commandId}").performClick()
        compose.onNodeWithTag("conflict-discard").assertTextEquals("Descartar 2 alterações")
    }

    @Test fun anUnansweredIdempotencyConflictDoesNotOfferDiscard() {
        refusedAddition("Leite", uncertain = true)
        launch(conflicts = resolver())
        compose.onNodeWithTag("nav-list").performClick()
        compose.onNodeWithTag("list-summary").assertExists()
        compose.onNodeWithTag("conflict-discard").assertDoesNotExist()
        compose.onNodeWithTag("conflicts-panel").assertDoesNotExist()
        assertEquals(1, runBlocking { db.commands().count() })
        assertTrue(runBlocking { db.resolutions().all().isEmpty() })
    }


    @Test fun storageFailureKeepsTheGroupAndAllowsAnExplicitRetry() {
        val root = refusedAddition("Leite")
        runBlocking { store.execute(ListIntent.AddText("Pão")) }
        launch(conflicts = resolver())
        compose.onNodeWithTag("nav-list").performClick()
        openReview(root.commandId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_conflict_ui BEFORE INSERT ON list_items BEGIN SELECT RAISE(ABORT, 'storage failed'); END")
        try {
            compose.onNodeWithTag("conflict-discard").performClick()
            waitForText("conflict-feedback", "Não foi possível descartar")
            assertEquals(2, runBlocking { db.commands().count() })
            assertTrue(runBlocking { db.resolutions().all().isEmpty() })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_conflict_ui")
        }
        compose.onNodeWithTag("conflict-discard").performClick()
        waitForText("conflict-feedback", "Alterações locais descartadas.")
        assertEquals(1, runBlocking { db.commands().count() })
        assertEquals(1, runBlocking { db.resolutions().all().size })
    }

}
