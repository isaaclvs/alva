package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incident of 2026-11-13: the Atlas ran Rails at 6702909, whose `SyncCommandProcessor::EXECUTORS` held only
 * `add_items` and `set_item_state`. Removing, editing and finishing a purchase were answered `rejected` /
 * `unsupported_command`, and the app said "Não foi possível fazer isso agora. ... tente de novo." as if retrying
 * could help. The server is the source of truth, so the app must say what is wrong and must not change the list.
 */
class UnsupportedServerTest {
    private val house = FakeHouse().apply {
        unsupportedTypes = setOf("remove_item", "correct_item", "undo_item_state", "undo_remove", "finish_shopping", "undo_shopping")
    }
    private val access = object : OnlineAccess {
        override suspend fun credential() = AccessCredential(1, "test-only")
        override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
    }
    private val repository = OnlineRepository(access, house, house, CoroutineScope(Dispatchers.Default), pause = {})

    @Test fun removeAndEditAreRefusedAsUnsupportedAndLeaveTheListUntouched() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()

        val removed = repository.execute(RemoveItem(item.id))
        val edited = repository.execute(CorrectItem(item.id, name = "Leite integral"))

        assertEquals(ActionResult.Refused("unsupported_command"), removed)
        assertEquals(ActionResult.Refused("unsupported_command"), edited)
        assertEquals(listOf("Leite"), house.rows.map { it.name })
    }

    @Test fun markingStillWorksOnTheOlderServer() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        assertTrue(repository.execute(SetItemState(item.id, ItemStatus.PURCHASED)) is ActionResult.Success)
        assertEquals("purchased", house.rows.single().status)
    }

    @Test fun theMessageSaysTheServerLacksTheFeatureInsteadOfAskingToRetry() {
        val refused = ActionResult.Refused("unsupported_command")
        listOf(false, true).forEach { keepsText ->
            val text = OnlineMessages.failure(refused, keepsText)
            assertEquals("O servidor da casa ainda não tem esta função. Nada foi alterado. Atualize o Alva no servidor.", text)
            assertFalse("retrying cannot help: $text", text.contains("tente de novo", ignoreCase = true))
        }
    }

    @Test fun anUnknownCodeStillGetsTheGenericRetryMessage() {
        assertEquals("Não foi possível fazer isso agora. Tente de novo.", OnlineMessages.failure(ActionResult.Refused("something_new")))
    }
}
