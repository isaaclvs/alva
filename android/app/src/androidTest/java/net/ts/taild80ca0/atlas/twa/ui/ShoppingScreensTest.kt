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

    private fun launch(using: ShoppingListStore = store) {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        AccessGraph.install(HouseAccess(vault, noNetwork, RoomHouseholdBinding(db)), using)
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
        compose.onNodeWithTag("add-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
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
}
