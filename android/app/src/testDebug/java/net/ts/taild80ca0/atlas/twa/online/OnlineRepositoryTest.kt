package net.ts.taild80ca0.atlas.twa.online

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.WireDocument
import net.ts.taild80ca0.atlas.twa.online.FakeHouse.Step
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRepositoryTest {
    private val house = FakeHouse()
    private val reported = ArrayList<AccessCredential>()
    private var credential: AccessCredential? = AccessCredential(1, "test-token")
    private val access = object : OnlineAccess {
        override suspend fun credential() = credential
        override suspend fun reportUnauthorized(credential: AccessCredential) { reported += credential }
    }
    private val counter = AtomicInteger()
    private val pauses = ArrayList<Long>()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val repository = OnlineRepository(
        access, house, house, scope,
        ids = IdGenerator { "00000000-0000-4000-8000-%012d".format(counter.incrementAndGet()) },
        pause = { pauses += it },
    )

    private fun ready() = repository.state.value as OnlineState.Ready
    private fun names() = ready().snapshot.listItems.map { it.name }

    @Test fun habitualSelectionHasNoThirtyItemLimitAndRemovalKeepsTheList() = runBlocking {
        repeat(35) { house.habituals += SnapshotFixtures.habitual(java.util.UUID.randomUUID().toString(), "Produto $it") }
        repository.refresh()
        val ids = ready().snapshot.habitualItems.map { it.publicId }
        assertTrue(repository.execute(AddHabituals(ids)) is ActionResult.Success)
        assertEquals(35, JSONObject(house.sent.last().second).getJSONObject("payload").getJSONArray("public_ids").length())
        assertEquals(35, names().size)
        assertTrue(repository.execute(RemoveHabitual(ids.first())) is ActionResult.Success)
        assertEquals(35, names().size)
        assertEquals(34, ready().snapshot.habitualItems.size)
    }

    @Test fun habitualCreateLostAnswerIsIdempotentAndAllUsesTheServersSavedNames() = runBlocking {
        repository.refresh()
        house.commandSteps += Step.LostAnswer
        assertTrue(repository.execute(CreateHabitual(" Café ")) is ActionResult.Success)
        assertEquals(1, house.habituals.size)
        assertTrue(repository.execute(AddHabituals()) is ActionResult.Success)
        assertEquals(listOf("Café"), names())
        val duplicate = repository.execute(AddHabituals()) as ActionResult.Success
        assertEquals(AddOutcome.DUPLICATE, duplicate.receipt.addedItems().single().outcome)
    }

    // ---- reading the list ----------------------------------------------------------------------------------

    @Test fun startsLoadingAndReadsTheServersList() = runBlocking {
        house.seed("Leite")
        assertEquals(OnlineState.Loading, repository.state.value)

        repository.refresh()

        assertEquals(listOf("Leite"), names())
        assertNull(ready().problem)
        assertEquals(840L, ready().snapshot.revision)
    }

    @Test fun aFirstReadThatFailsSaysSoInPortugueseAndTheNextOneRecovers() = runBlocking {
        house.snapshotSteps += Step.NetworkDown
        repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.OFFLINE), repository.state.value)
        assertEquals("Sem conexão com o servidor. Confira a internet e tente de novo.", OnlineProblem.OFFLINE.message)

        house.seed("Leite")
        repository.refresh()
        assertEquals(listOf("Leite"), names())
    }

    @Test fun aReloadThatFailsKeepsTheListOnScreenAndFlagsIt() = runBlocking {
        house.seed("Leite")
        repository.refresh()

        house.snapshotSteps += Step.ServerError
        repository.refresh()

        assertEquals(listOf("Leite"), names())
        assertEquals(OnlineProblem.SERVER, ready().problem)
        repository.refresh()
        assertNull(ready().problem)
    }

    @Test fun aRefusedCredentialIsReportedAndAskedToReconnect() = runBlocking {
        house.snapshotSteps += Step.Unauthorized
        repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.NO_ACCESS), repository.state.value)
        assertEquals(listOf(credential), reported)
    }

    @Test fun noCredentialMeansNoAccessAndNoRequest() = runBlocking {
        credential = null
        repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.NO_ACCESS), repository.state.value)
        assertEquals(0, house.fetches)
    }

    @Test fun anUnexpectedAnswerIsNotAList() = runBlocking {
        house.snapshotSteps += Step.Refuse(404, "not_found")
        repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.UNEXPECTED), repository.state.value)
    }

    @Test fun aBurstOfReadsSharesTheNextRead() = runBlocking {
        house.seed("Leite")
        repository.refresh()
        val before = house.fetches
        house.fetchDelayMillis = 100

        (1..10).map { async(Dispatchers.Default) { repository.refresh() } }.awaitAll()

        assertTrue("${house.fetches - before} reads for 10 requests", house.fetches - before in 1..3)
    }

    @Test fun theLiveSignalReadsTheListAgain() = runBlocking {
        house.seed("Leite")
        repository.refresh()
        house.seed("Pão")

        repository.requestInvalidation()

        withTimeout(5_000) { while (names().size < 2) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("Leite", "Pão"), names())
    }

    // ---- adding --------------------------------------------------------------------------------------------

    @Test fun anAddSendsOneNewCommandThenReadsTheListAgain() = runBlocking {
        repository.refresh()
        val reads = house.fetches

        val result = repository.execute(AddItems.fromText("Leite, Pão"))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals(listOf("Leite", "Pão"), (result as ActionResult.Success).receipt.addedItems().map { it.name })
        assertEquals(listOf(AddOutcome.CREATED, AddOutcome.CREATED), result.receipt.addedItems().map { it.outcome })
        assertEquals(1, house.sent.size)
        val (id, document) = house.sent.single()
        assertNull("a valid contract document", WireDocument.problemWith(document, id, "add_items", null))
        assertEquals(840, JSONObject(document).getLong("observed_revision"))
        assertEquals(reads + 1, house.fetches)
        assertEquals(listOf("Leite", "Pão"), names())
        assertEquals("the screen sees the server's revision", 841L, ready().snapshot.revision)
    }

    @Test fun everyActionHasItsOwnCommandId() = runBlocking {
        repository.refresh()
        repository.execute(AddItems.fromText("Leite"))
        repository.execute(AddItems.fromText("Pão"))
        val ids = house.sent.map { it.first }
        assertEquals(2, ids.distinct().size)
        assertEquals(2, house.sent.map { JSONObject(it.second).getJSONObject("payload").getJSONArray("items").getJSONObject(0).getString("public_id") }.distinct().size)
    }

    @Test fun aNameAlreadyPendingIsReportedAsAlreadyThere() = runBlocking {
        house.seed("Leite")
        repository.refresh()

        val result = repository.execute(AddItems.fromText("leite, Pão")) as ActionResult.Success

        assertEquals(listOf(AddOutcome.DUPLICATE, AddOutcome.CREATED), result.receipt.addedItems().map { it.outcome })
        assertEquals(1, house.named("Leite").size)
        assertEquals("1 item adicionado · Leite já estava na lista", OnlineMessages.added(result.receipt.addedItems()))
    }

    @Test fun aPurchasedNameComesBackCarryingThePurchaseItSaw() = runBlocking {
        val bought = house.seed("Leite", status = "purchased", lock = 3)
        repository.refresh()

        val result = repository.execute(AddItems.fromText("Leite")) as ActionResult.Success

        val item = JSONObject(house.sent.single().second).getJSONObject("payload").getJSONArray("items").getJSONObject(0)
        assertEquals(bought.id, item.getJSONObject("revives").getString("public_id"))
        assertEquals(3, item.getJSONObject("revives").getInt("lock_version"))
        assertNotEquals("a new claim, never the purchase's own UUID", bought.id, item.getString("public_id"))
        assertEquals(AddOutcome.REVIVED, result.receipt.addedItems().single().outcome)
        assertEquals("pending", house.named("Leite").single().status)
    }

    @Test fun aPurchaseThatChangedElsewhereIsRefusedAndTheListIsReloaded() = runBlocking {
        val bought = house.seed("Leite", status = "purchased", lock = 3)
        repository.refresh()
        bought.lock = 4 // someone else touched it after this phone read the list

        val result = repository.execute(AddItems.fromText("Leite"))

        assertEquals(ActionResult.Refused("precondition_failed"), result)
        assertEquals(4, ready().snapshot.listItems.single().lockVersion)
        assertEquals("A lista mudou em outro aparelho. Ela foi atualizada; confira e tente de novo.", OnlineMessages.failure(result))
    }

    @Test fun nothingTypedOrTooManyNamesSendsNothing() = runBlocking {
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.NO_NAMES), repository.execute(AddItems.fromText("  , ; ")))
        assertEquals(ActionResult.Invalid(InvalidAction.TOO_MANY_NAMES), repository.execute(AddItems((1..31).map { "Item $it" })))
        assertTrue(house.sent.isEmpty())
    }

    // ---- marking -------------------------------------------------------------------------------------------

    @Test fun markingSendsTheDesiredStateWithTheVersionTheListShowed() = runBlocking {
        val leite = house.seed("Leite", lock = 2)
        repository.refresh()

        val result = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        assertTrue("$result", result is ActionResult.Success)
        val (id, document) = house.sent.single()
        assertNull(WireDocument.problemWith(document, id, "set_item_state", null))
        assertEquals(2, JSONObject(document).getJSONObject("preconditions").getInt("lock_version"))
        assertEquals("purchased", JSONObject(document).getJSONObject("payload").getString("desired"))
        assertEquals(1, ready().snapshot.listItems.single().status)
        assertEquals(3, ready().snapshot.listItems.single().lockVersion)
    }

    @Test fun markingAnItemThatLeftTheListSendsNothing() = runBlocking {
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.ITEM_NOT_FOUND), repository.execute(SetItemState("nope", ItemStatus.PURCHASED)))
        assertTrue(house.sent.isEmpty())
    }

    @Test fun markingAnItemChangedElsewhereIsRefusedAndNothingIsOverwritten() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        leite.status = "purchased"; leite.lock++ // the other phone marked it first, with a different outcome for us

        val result = repository.execute(SetItemState(leite.id, ItemStatus.PENDING))

        assertEquals(ActionResult.Refused("precondition_failed"), result)
        assertEquals("purchased", house.named("Leite").single().status)
        assertEquals("the screen now shows what the server holds", 1, ready().snapshot.listItems.single().status)
    }

    @Test fun undoingAMarkRestoresWhatWasSeenBeforeAndNamesTheVersionTheMarkLeft() = runBlocking {
        val leite = house.seed("Leite", lock = 2)
        repository.refresh()
        val before = ready().snapshot.listItems.single()
        val mark = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)) as ActionResult.Success
        val marked = mark.receipt.markedItem()!!
        assertEquals(MarkedItem(leite.id, 3), marked)

        val result = repository.execute(UndoItemState(marked.publicId, ItemStatus.PENDING, before.purchasedAtMicros, marked.lockVersion))

        assertTrue("$result", result is ActionResult.Success)
        val document = JSONObject(house.sent.last().second)
        assertEquals("undo_item_state", document.getString("type"))
        assertEquals(3, document.getJSONObject("preconditions").getInt("lock_version"))
        assertEquals("pending", document.getJSONObject("payload").getString("status"))
        assertTrue(document.getJSONObject("payload").isNull("purchased_at"))
        assertEquals(0, ready().snapshot.listItems.single().status)
    }

    @Test fun undoingAnUnmarkBringsBackTheOriginalPurchaseInstant() = runBlocking {
        val leite = house.seed("Leite", status = "purchased", lock = 1)
        repository.refresh()
        val before = ready().snapshot.listItems.single()
        val mark = repository.execute(SetItemState(leite.id, ItemStatus.PENDING)) as ActionResult.Success

        repository.execute(UndoItemState(leite.id, ItemStatus.PURCHASED, before.purchasedAtMicros, mark.receipt.markedItem()!!.lockVersion))

        assertEquals("2026-11-10T12:00:00.000000Z", JSONObject(house.sent.last().second).getJSONObject("payload").getString("purchased_at"))
        assertEquals("purchased", house.named("Leite").single().status)
    }

    @Test fun anUndoAfterAnotherPhoneTouchedTheItemIsRefusedAndNothingIsOverwritten() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        val mark = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)) as ActionResult.Success
        leite.status = "pending"; leite.lock++ // the other phone unmarked, then someone else edited it further
        leite.lock++

        val result = repository.execute(UndoItemState(leite.id, ItemStatus.PENDING, null, mark.receipt.markedItem()!!.lockVersion))

        assertEquals(ActionResult.Refused("precondition_failed"), result)
        assertEquals(3, leite.lock)
    }

    @Test fun undoingAnItemThatLeftTheListSendsNothing() = runBlocking {
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.ITEM_NOT_FOUND), repository.execute(UndoItemState("nope", ItemStatus.PENDING, null, 1)))
        assertTrue(house.sent.isEmpty())
    }

    // ---- failures ------------------------------------------------------------------------------------------

    @Test fun aFailureToReachTheServerIsRetriedWithTheSameBodyAndSucceeds() = runBlocking {
        repository.refresh()
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown)

        val result = repository.execute(AddItems.fromText("Leite"))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals(3, house.sent.size)
        assertEquals("one command id", 1, house.sent.map { it.first }.distinct().size)
        assertEquals("the very same bytes", 1, house.sent.map { it.second }.distinct().size)
        assertEquals(2, pauses.size)
        assertEquals(1, house.named("Leite").size)
    }

    @Test fun anAnswerLostAfterTheServerCommittedDoesNotDuplicateOnTheRetry() = runBlocking {
        repository.refresh()
        house.commandSteps += Step.LostAnswer

        val result = repository.execute(AddItems.fromText("Leite")) as ActionResult.Success

        assertEquals("the server answered the second try with its first result", 2, house.sent.size)
        assertEquals(ReceiptStatus.DUPLICATE, result.receipt.status)
        assertEquals(1, house.named("Leite").size)
        assertEquals(841L, house.revision)
        assertEquals(listOf("Leite"), names())
    }

    @Test fun givingUpAfterTheRetriesSaysOfflineKeepsTheScreenAsItWasAndStoresNothing() = runBlocking {
        house.seed("Pão")
        repository.refresh()
        val reads = house.fetches
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)

        val result = repository.execute(AddItems.fromText("Leite"))

        assertEquals(ActionResult.Offline, result)
        assertEquals("three attempts, no more", 3, house.sent.size)
        assertEquals(1, house.sent.map { it.second }.distinct().size)
        assertEquals("no point in reading while the network is down", reads, house.fetches)
        assertEquals(listOf("Pão"), names())
        assertEquals(1, house.commandSteps.size)
        assertEquals("Sem conexão com o servidor. Nada foi salvo. Seu texto foi mantido; tente de novo.", OnlineMessages.failure(result, keepsText = true))
        assertTrue(house.named("Leite").isEmpty())
    }

    @Test fun retypingAfterALostAnswerThatNeverRecoveredStillAddsTheItemOnce() = runBlocking {
        repository.refresh()
        house.commandSteps += listOf(Step.LostAnswer, Step.NetworkDown, Step.NetworkDown)
        assertEquals(ActionResult.Offline, repository.execute(AddItems.fromText("Leite")))
        assertEquals("the server did add it", 1, house.named("Leite").size)

        repository.refresh()
        val again = repository.execute(AddItems.fromText("Leite")) as ActionResult.Success

        assertEquals(1, house.named("Leite").size)
        assertEquals(AddOutcome.DUPLICATE, again.receipt.addedItems().single().outcome)
    }

    @Test fun aServerErrorIsNotRetriedButTheListIsReloaded() = runBlocking {
        repository.refresh()
        val reads = house.fetches
        house.commandSteps += Step.ServerError

        val result = repository.execute(AddItems.fromText("Leite"))

        assertEquals(ActionResult.ServerUnavailable, result)
        assertEquals(1, house.sent.size)
        assertEquals(reads + 1, house.fetches)
        assertEquals("O servidor não está disponível agora. Nada foi salvo. Tente de novo.", OnlineMessages.failure(result))
    }

    @Test fun aRefusedCredentialOnACommandIsReportedAndNothingIsRetried() = runBlocking {
        repository.refresh()
        house.commandSteps += Step.Unauthorized

        assertEquals(ActionResult.NoAccess, repository.execute(AddItems.fromText("Leite")))

        assertEquals(1, house.sent.size)
        assertEquals(listOf(credential), reported)
    }

    @Test fun noCredentialMeansNoAccessAndNothingIsSent() = runBlocking {
        repository.refresh()
        credential = null
        assertEquals(ActionResult.NoAccess, repository.execute(AddItems.fromText("Leite")))
        assertTrue(house.sent.isEmpty())
    }

    @Test fun aWholeRequestRefusedCarriesTheContractCodeAndReloads() = runBlocking {
        repository.refresh()
        val reads = house.fetches
        house.commandSteps += Step.Refuse(422, "malformed_command")

        assertEquals(ActionResult.Refused("malformed_command"), repository.execute(AddItems.fromText("Leite")))

        assertEquals(1, house.sent.size)
        assertEquals(reads + 1, house.fetches)
    }

    @Test fun anActionBeforeTheFirstReadReadsTheListFirst() = runBlocking {
        val leite = house.seed("Leite")
        assertEquals(OnlineState.Loading, repository.state.value)

        val result = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        assertTrue("$result", result is ActionResult.Success)
    }

    @Test fun anActionWhenTheListCannotBeReadSaysWhy() = runBlocking {
        house.snapshotSteps += Step.NetworkDown
        assertEquals(ActionResult.Offline, repository.execute(AddItems.fromText("Leite")))
        assertTrue(house.sent.isEmpty())
    }

    @Test fun messagesNeverCarryContractCodes() {
        val all = listOf(
            ActionResult.Offline, ActionResult.ServerUnavailable, ActionResult.NoAccess,
            ActionResult.Refused("gone"), ActionResult.Refused("public_id_conflict"), ActionResult.Refused("whatever_new"),
            ActionResult.Invalid(InvalidAction.NO_NAMES), ActionResult.Invalid(InvalidAction.ITEM_NOT_FOUND),
        )
        all.forEach { result ->
            val text = OnlineMessages.failure(result)
            assertTrue(text, text.isNotBlank() && !text.contains('_') && text.first().isUpperCase())
        }
    }

    // ---- correct_item and the add messages of the PWA ------------------------------------------------------

    @Test fun correctingACategorySendsTheItemVersionAndTheDictionaryRevisionSeen() = runBlocking {
        val cafe = house.seed("Café", category = SnapshotFixtures.OTHERS, lock = 2)
        repository.refresh()

        val result = repository.execute(CorrectItem(cafe.id, categoryPublicId = FakeHouse.PANTRY))

        assertTrue("$result", result is ActionResult.Success)
        val command = JSONObject(house.sent.single().second)
        assertEquals("correct_item", command.getString("type"))
        assertEquals(cafe.id, command.getJSONObject("payload").getString("public_id"))
        assertEquals("the name stays as the list shows it", "Café", command.getJSONObject("payload").getString("name"))
        assertEquals(FakeHouse.PANTRY, command.getJSONObject("payload").getString("category_id"))
        assertEquals(2, command.getJSONObject("preconditions").getInt("lock_version"))
        assertEquals(12, command.getJSONObject("preconditions").getInt("dictionary_revision"))
        assertEquals("the list was read again with the new category", FakeHouse.PANTRY, ready().snapshot.listItems.single().categoryPublicId)
        assertEquals(FakeHouse.PANTRY, house.learned["café"])
    }

    @Test fun correctingAnItemThatLeftTheListSendsNothing() = runBlocking {
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.ITEM_NOT_FOUND), repository.execute(CorrectItem("nope", FakeHouse.PANTRY)))
        assertTrue(house.sent.isEmpty())
    }

    @Test fun aCorrectionOnAStaleVersionIsRefusedAndTheListIsReloaded() = runBlocking {
        val cafe = house.seed("Café", category = SnapshotFixtures.OTHERS)
        repository.refresh()
        cafe.lock++ // another phone changed it meanwhile

        val result = repository.execute(CorrectItem(cafe.id, FakeHouse.PANTRY))

        assertEquals(ActionResult.Refused("precondition_failed"), result)
        assertEquals(SnapshotFixtures.OTHERS, cafe.category)
        assertEquals(cafe.lock, ready().snapshot.listItems.single().lockVersion)
    }

    private fun added(name: String, outcome: AddOutcome) = AddedItem(name, "id-$name", outcome)

    @Test fun oneNameGetsItsOwnSentenceLikeThePwa() {
        val label = { _: AddedItem -> "Laticínios" }
        assertEquals("Leite adicionado em Laticínios", OnlineMessages.addFeedback(listOf(added("Leite", AddOutcome.CREATED)), label))
        assertEquals("Leite voltou para a lista", OnlineMessages.addFeedback(listOf(added("Leite", AddOutcome.REVIVED)), label))
        assertEquals("Leite já está na lista", OnlineMessages.addFeedback(listOf(added("Leite", AddOutcome.DUPLICATE)), label))
    }

    @Test fun severalNamesGetTheSummary() {
        val label = { _: AddedItem -> "Laticínios" }
        assertEquals("2 itens adicionados · Leite já estava na lista", OnlineMessages.addFeedback(
            listOf(added("Pão", AddOutcome.CREATED), added("Café", AddOutcome.REVIVED), added("Leite", AddOutcome.DUPLICATE)), label))
        assertEquals("Nada adicionado · Leite e Pão já estavam na lista", OnlineMessages.addFeedback(
            listOf(added("Leite", AddOutcome.DUPLICATE), added("Pão", AddOutcome.DUPLICATE)), label))
    }

    @Test fun theDuplicateHintReadsLikeThePwa() {
        assertEquals("Leite já está na lista", OnlineMessages.alreadyOnList(listOf("Leite")))
        assertEquals("Leite e Pão já estão na lista", OnlineMessages.alreadyOnList(listOf("Leite", "Pão")))
        assertEquals("Leite, Pão e Café já estão na lista", OnlineMessages.alreadyOnList(listOf("Leite", "Pão", "Café")))
    }
}

