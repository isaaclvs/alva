package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.*
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import org.junit.Assert.*
import org.junit.Test

class ShoppingActionsTest {
    private val house = FakeHouse()
    private val access = object : OnlineAccess {
        override suspend fun credential() = AccessCredential(1, "test-only")
        override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
    }
    private val repository = OnlineRepository(access, house, house, CoroutineScope(Dispatchers.Default), pause = {})
    private fun snapshot() = (repository.state.value as OnlineState.Ready).snapshot

    @Test fun finishArchivesOnlyObservedPurchasedItemsAndUndoKeepsOriginalAttributes() = runBlocking {
        val bought = house.seed("Leite", status = "purchased", lock = 3)
        val pending = house.seed("Pão")
        repository.refresh()
        val observed = snapshot().listItems
        house.seed("Ovos", status = "purchased") // not seen when the button was pressed
        repository.refresh()
        val result = repository.execute(FinishShopping(observed)) as ActionResult.Success
        assertEquals(setOf("Pão", "Ovos"), house.rows.map { it.name }.toSet())
        assertEquals(bought.id, snapshot().archivedItems.single().publicId)
        assertTrue(repository.execute(UndoShopping(result.receipt.shoppingTripPublicId()!!)) is ActionResult.Success)
        assertEquals("purchased", bought.status)
        assertEquals("2026-11-10T12:00:00.000000Z", bought.purchasedAt)
        assertEquals("pending", pending.status)
        assertTrue(snapshot().shoppingTrips.isEmpty())
    }

    @Test fun aStalePurchasedItemRefusesTheWholeFinishAndRefreshes() = runBlocking {
        val changed = house.seed("Leite", status = "purchased")
        house.seed("Ovos", status = "purchased")
        repository.refresh()
        val observed = snapshot().listItems
        changed.lock++
        changed.status = "pending"
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(FinishShopping(observed)))
        assertEquals(2, house.rows.size)
        assertTrue(house.trips.isEmpty())
        assertEquals(0, snapshot().listItems.single { it.publicId == changed.id }.status)
    }

    @Test fun aLostFinishAnswerCreatesOneTripAndOnlyTheLatestTripCanBeUndone() = runBlocking {
        house.seed("Leite", status = "purchased")
        repository.refresh()
        house.commandSteps += FakeHouse.Step.LostAnswer
        val first = repository.execute(FinishShopping()) as ActionResult.Success
        assertEquals(1, house.trips.size)
        assertEquals(house.sent[0], house.sent[1])
        house.seed("Pão", status = "purchased")
        repository.refresh()
        val latest = repository.execute(FinishShopping()) as ActionResult.Success
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(UndoShopping(first.receipt.shoppingTripPublicId()!!)))
        assertTrue(repository.execute(UndoShopping(latest.receipt.shoppingTripPublicId()!!)) is ActionResult.Success)
        assertEquals(listOf("Pão"), house.rows.map { it.name })
    }

    @Test fun noPurchasedItemsMeansNoCommand() = runBlocking {
        house.seed("Leite")
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.NO_PURCHASED_ITEMS), repository.execute(FinishShopping()))
        assertTrue(house.sent.isEmpty())
    }
}
