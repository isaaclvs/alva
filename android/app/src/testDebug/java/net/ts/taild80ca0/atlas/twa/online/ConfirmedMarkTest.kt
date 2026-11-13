package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.online.FakeHouse.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server said "applied" and then the list could not be read again (or came back from before the command).
 * What the server confirmed must stay on screen: the receipt is the server's own word, with the version it left.
 * Nothing optimistic is kept — an answer that was not a success never changes the list, and any snapshot that has
 * caught up with (or passed) the confirmed version replaces it.
 */
class ConfirmedMarkTest {
    private val house = FakeHouse()
    private val access = object : OnlineAccess {
        override suspend fun credential() = AccessCredential(1, "test-token")
        override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
    }
    private val repository = OnlineRepository(access, house, house, CoroutineScope(Dispatchers.Default), pause = { })

    private fun ready() = repository.state.value as OnlineState.Ready
    private fun shown(id: String) = ready().snapshot.listItems.single { it.publicId == id }

    @Test fun aMarkTheServerAppliedStaysMarkedWhenTheReloadFails() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown

        val result = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals("purchased", leite.status)
        assertEquals(1, shown(leite.id).status)
        assertEquals(1, shown(leite.id).lockVersion)
        assertNotNull("the receipt's purchase instant", shown(leite.id).purchasedAtMicros)
        assertEquals("the list still says it may be out of date", OnlineProblem.OFFLINE, ready().problem)
    }

    @Test fun aMarkTheServerAppliedStaysMarkedWhenTheReloadIsFromBeforeTheCommand() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.Stale

        assertTrue(repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)) is ActionResult.Success)

        assertEquals(1, shown(leite.id).status)
        assertEquals(1, shown(leite.id).lockVersion)
    }

    @Test fun anUnmarkIsConfirmedTheSameWay() = runBlocking {
        val leite = house.seed("Leite", status = "purchased", lock = 4)
        repository.refresh()
        house.snapshotSteps += Step.ServerError

        assertTrue(repository.execute(SetItemState(leite.id, ItemStatus.PENDING)) is ActionResult.Success)

        assertEquals(0, shown(leite.id).status)
        assertNull(shown(leite.id).purchasedAtMicros)
        assertEquals(5, shown(leite.id).lockVersion)
    }

    @Test fun theNextMarkOfAConfirmedItemUsesTheVersionTheReceiptLeft() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown
        repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        // Still no fresh list: the unmark must name version 1, not the 0 of the snapshot that was read before.
        house.snapshotSteps += Step.NetworkDown
        val result = repository.execute(SetItemState(leite.id, ItemStatus.PENDING))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals("pending", leite.status)
        assertEquals(2, leite.lock)
    }

    @Test fun anUndoTheServerAppliedStaysUndoneWhenTheReloadFails() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        val before = shown(leite.id)
        val mark = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)) as ActionResult.Success
        house.snapshotSteps += Step.NetworkDown

        val undone = repository.execute(UndoItemState(leite.id, ItemStatus.PENDING, before.purchasedAtMicros, mark.receipt.markedItem()!!.lockVersion))

        assertTrue("$undone", undone is ActionResult.Success)
        assertEquals(0, shown(leite.id).status)
        assertEquals(2, shown(leite.id).lockVersion)
    }

    // ---- what never counts as confirmed --------------------------------------------------------------------

    @Test fun aRefusedMarkLeavesTheListAsItWasEvenWhenTheReloadFails() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        leite.lock++ // another phone edited it: the mark will be refused
        house.snapshotSteps += Step.NetworkDown

        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)))

        assertEquals(0, shown(leite.id).status)
        assertEquals(0, shown(leite.id).lockVersion)
    }

    @Test fun noAnswerAtAllConfirmsNothing() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)

        assertEquals(ActionResult.Offline, repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED)))

        assertEquals(0, shown(leite.id).status)
        assertEquals("pending", leite.status)
    }

    // ---- later snapshots win --------------------------------------------------------------------------------

    @Test fun theNextGoodReadReplacesTheConfirmationAndNeverBringsItBack() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown
        repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        repository.refresh()
        assertNull(ready().problem)
        assertEquals(1, shown(leite.id).status)

        // Another phone unmarks it: the server's list says pending, and it stays so on every later read.
        leite.status = "pending"; leite.lock++; leite.purchasedAt = null
        repository.refresh()
        assertEquals(0, shown(leite.id).status)
        assertEquals(2, shown(leite.id).lockVersion)
        repository.refresh()
        assertEquals(0, shown(leite.id).status)
    }

    @Test fun aNewerVersionFromAnotherPhoneBeatsTheConfirmationAtOnce() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown
        repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))
        leite.status = "pending"; leite.lock++; leite.purchasedAt = null // the other phone unmarked it right after

        repository.requestInvalidation()
        withTimeout(5_000) { while (shown(leite.id).status != 0) delay(10) }

        assertEquals(2, shown(leite.id).lockVersion)
    }

    @Test fun anItemRemovedByAnotherPhoneDoesNotComeBackFromTheConfirmation() = runBlocking {
        val leite = house.seed("Leite")
        val pao = house.seed("Pão")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown
        repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))
        house.rows.remove(leite)

        repository.refresh()

        assertEquals(listOf(pao.id), ready().snapshot.listItems.map { it.publicId })
    }

    // ---- concurrency ----------------------------------------------------------------------------------------

    @Test fun aSlowReadFromBeforeTheCommandCannotTakeTheConfirmationAway() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        // The first read to come is from before the commit (it will answer late); the one after the answer fails.
        house.fetchDelayMillis = 300
        house.snapshotSteps += listOf(Step.Stale, Step.NetworkDown)
        val before = house.fetches
        val slowRead = async(Dispatchers.Default) { repository.refresh() }
        withTimeout(5_000) { while (house.fetches == before) delay(5) }

        val result = repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))
        slowRead.await()

        assertTrue("$result", result is ActionResult.Success)
        assertEquals(1, shown(leite.id).status)
    }

    @Test fun marksOfDifferentItemsAtTheSameTimeAreAllKept() = runBlocking {
        val rows = (1..6).map { house.seed("Item $it") }
        repository.refresh()
        house.snapshotSteps += List(6) { Step.NetworkDown }

        val results = rows.map { row -> async(Dispatchers.Default) { repository.execute(SetItemState(row.id, ItemStatus.PURCHASED)) } }.awaitAll()

        assertTrue("$results", results.all { it is ActionResult.Success })
        assertEquals(rows.map { 1 }, rows.map { shown(it.id).status })
        assertEquals(rows.map { 1 }, rows.map { shown(it.id).lockVersion })
    }

    @Test fun signingOutForgetsEveryConfirmation() = runBlocking {
        val leite = house.seed("Leite")
        repository.refresh()
        house.snapshotSteps += Step.NetworkDown
        repository.execute(SetItemState(leite.id, ItemStatus.PURCHASED))

        repository.forget()
        // (an older version than the confirmed one: only a forgotten confirmation lets this read through unpatched)
        leite.status = "pending"; leite.lock = 0; leite.purchasedAt = null
        repository.refresh()

        assertEquals(0, shown(leite.id).status)
        assertEquals(0, shown(leite.id).lockVersion)
    }
}
