package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoveItemTest {
    private val house = FakeHouse()
    private val access = object : OnlineAccess {
        override suspend fun credential() = AccessCredential(1, "test-only")
        override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
    }
    private val repository = OnlineRepository(access, house, house, CoroutineScope(Dispatchers.Default), pause = {})

    @Test fun removalAndUndoUseTheReceiptAndPreserveThePurchasedItem() = runBlocking {
        val item = house.seed("Leite", status = "purchased", lock = 8, category = null)
        repository.refresh()
        val removed = repository.execute(RemoveItem(item.id)) as ActionResult.Success
        assertTrue(house.rows.isEmpty())
        assertEquals(8, JSONObject(house.sent.first().second).getJSONObject("preconditions").getInt("lock_version"))
        assertTrue(repository.execute(UndoRemove(removed.receipt.commandId)) is ActionResult.Success)
        val restored = house.rows.single()
        assertEquals(item.id, restored.id)
        assertEquals(item.purchasedAt, restored.purchasedAt)
        assertEquals("purchased", restored.status)
        assertNull(restored.category)
        val undo = JSONObject(house.sent.last().second)
        assertEquals(removed.receipt.commandId, undo.getString("depends_on"))
        assertEquals(0, undo.getJSONObject("payload").length())
    }

    @Test fun staleRemovalAndUndoWithANewPendingNamesakeAreRefused() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        item.lock++
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(RemoveItem(item.id)))
        val removed = repository.execute(RemoveItem(item.id)) as ActionResult.Success
        house.seed("Leite")
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(UndoRemove(removed.receipt.commandId)))
        assertEquals(1, house.rows.size)
    }

    @Test fun aLostRemovalResponseIsRetriedWithoutRemovingTwice() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        house.commandSteps += FakeHouse.Step.LostAnswer
        assertTrue(repository.execute(RemoveItem(item.id)) is ActionResult.Success)
        assertEquals(house.sent[0], house.sent[1])
        assertTrue(house.rows.isEmpty())
    }

    @Test fun removalFromAnOpenFormKeepsItsObservedVersionAfterRefresh() = runBlocking {
        val item = house.seed("Leite", lock = 3)
        repository.refresh()
        item.lock++
        repository.refresh()
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(RemoveItem(item.id, observedLockVersion = 3)))
        assertEquals(1, house.rows.size)
    }
}
