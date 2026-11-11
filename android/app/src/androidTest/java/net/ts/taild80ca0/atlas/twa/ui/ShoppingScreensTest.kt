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

    private fun launch(using: ShoppingListStore = store, sync: net.ts.taild80ca0.atlas.twa.sync.SyncController? = null) {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        AccessGraph.install(HouseAccess(vault, noNetwork, RoomHouseholdBinding(db)), using, sync)
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
        compose.onNodeWithTag("save-add").performClick()
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

    @Test fun openingAddingAndMarkingSyncAutomaticallyAndTheButtonStillWorks() {
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val sync = net.ts.taild80ca0.atlas.twa.sync.SyncController(
            net.ts.taild80ca0.atlas.twa.sync.SyncRunner {
                if (runs.incrementAndGet() == 1)
                    net.ts.taild80ca0.atlas.twa.sync.SyncReport(net.ts.taild80ca0.atlas.twa.sync.SyncOutcome.Offline(net.ts.taild80ca0.atlas.twa.sync.TransientCause.NETWORK), needsAttention = 1)
                else net.ts.taild80ca0.atlas.twa.sync.SyncReport(net.ts.taild80ca0.atlas.twa.sync.SyncOutcome.Synced(7))
            },
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

        waitForText("sync-status", "Sincronizado.")
        assertEquals(2, runs.get())
        // A duplicate does not enqueue anything and must not start another cycle.
        compose.onNodeWithTag("add-input").performTextInput("Leite")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Já está na lista")
        assertEquals(2, runs.get())
        compose.onNodeWithTag("nav-list").performClick()
        val id = runBlocking { db.listItems().live().single().publicId }
        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, true)
        compose.waitUntil(10_000) { runs.get() == 3 && sync.status.value is net.ts.taild80ca0.atlas.twa.sync.SyncStatus.Idle }
        compose.onNodeWithTag("sync-now").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runs.get() == 4 && sync.status.value is net.ts.taild80ca0.atlas.twa.sync.SyncStatus.Idle }
        waitForText("sync-status", "Sincronizado.")
        compose.onNodeWithTag("sync-now").assertTextEquals("Sincronizar agora")
        assertEquals(4, runs.get())
    }
}
