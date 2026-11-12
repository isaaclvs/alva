package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CorrectItemTest {
    private val house = FakeHouse()
    private val access = object : OnlineAccess {
        override suspend fun credential() = AccessCredential(1, "test-only")
        override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
    }
    private val repository = OnlineRepository(access, house, house, CoroutineScope(Dispatchers.Default), pause = {})

    @Test fun renameWithoutChoosingACategoryAsksRailsToRecategorize() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        assertTrue(repository.execute(CorrectItem(item.id, name = "Café", categoryPublicId = null)) is ActionResult.Success)
        assertEquals("Café", house.rows.single().name)
        assertEquals(net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.OTHERS, house.rows.single().category)
        assertTrue(JSONObject(house.sent.single().second).getJSONObject("payload").isNull("category_id"))
    }

    @Test fun choosingACategoryNamesTheDictionaryVersionAndTeachesIt() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        val category = FakeHouse.PANTRY
        assertTrue(repository.execute(CorrectItem(item.id, name = "Café", categoryPublicId = category)) is ActionResult.Success)
        assertEquals(category, house.learned["café"])
        assertEquals(12, JSONObject(house.sent.single().second).getJSONObject("preconditions").getInt("dictionary_revision"))
    }

    @Test fun aRefreshDuringEditingDoesNotReplaceTheObservedVersion() = runBlocking {
        val item = house.seed("Leite", lock = 4)
        repository.refresh()
        item.lock++
        repository.refresh()
        assertEquals(ActionResult.Refused("precondition_failed"), repository.execute(CorrectItem(item.id,
            name = "Café", categoryPublicId = null, observedLockVersion = 4, observedDictionaryRevision = 12)))
        assertEquals("Leite", house.rows.single().name)
    }

    @Test fun blankNameAndUnknownCategorySendNothing() = runBlocking {
        val item = house.seed("Leite")
        repository.refresh()
        assertEquals(ActionResult.Invalid(InvalidAction.INVALID_NAME), repository.execute(CorrectItem(item.id, name = " ", categoryPublicId = null)))
        assertEquals(ActionResult.Invalid(InvalidAction.CATEGORY_NOT_FOUND), repository.execute(CorrectItem(item.id, name = "Café", categoryPublicId = "gone")))
        assertTrue(house.sent.isEmpty())
    }
}
