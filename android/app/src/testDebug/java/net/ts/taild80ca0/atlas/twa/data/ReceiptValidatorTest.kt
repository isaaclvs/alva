package net.ts.taild80ca0.atlas.twa.data

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.list.AliasClaim
import net.ts.taild80ca0.atlas.twa.data.list.IsoInstant
import net.ts.taild80ca0.atlas.twa.data.list.ItemOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptCheck
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptRefusal
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptValidator
import net.ts.taild80ca0.atlas.twa.data.list.WireSubject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The business data of a receipt, checked against the command that was sent. The shapes below are
 * the ones Rails produces (`SyncCommands::ItemData.entry`); the Rails harness
 * (`script/android_rails_transport_check`) runs the same validator on real answers.
 */
class ReceiptValidatorTest {
    private val mine = "9a1d0000-0000-4000-8000-000000000001"
    private val theirs = "9a1d0000-0000-4000-8000-0000000000aa"
    private val purchase = "40000000-0000-4000-8000-000000000002"
    private val claim = "9a1d0000-0000-4000-8000-000000000003"

    private val none: (String) -> String? = { null }

    private fun addDocument(vararg entries: String) =
        """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[${entries.joinToString(",")}]}}"""

    private fun entry(id: String, name: String, revives: String? = null) =
        """{"public_id":"$id","name":"$name"${if (revives != null) ""","revives":{"public_id":"$revives","lock_version":3}""" else ""}}"""

    private fun item(
        cited: String,
        canonical: String = cited,
        name: String = "Leite",
        status: String = "pending",
        purchasedAt: String? = null,
        lock: Int = 0,
        outcome: String? = "created",
    ): String {
        val at = if (purchasedAt == null) "null" else "\"$purchasedAt\""
        val out = if (outcome == null) "" else ""","outcome":"$outcome""""
        return """{"public_id":"$cited","canonical_public_id":"$canonical","name":"$name","status":"$status","purchased_at":$at,"lock_version":$lock$out}"""
    }

    private fun addCheck(
        data: String,
        document: String = addDocument(entry(mine, "Leite")),
        subjects: List<WireSubject> = listOf(WireSubject(mine, SubjectRole.CREATES, 0)),
        applied: Boolean = true,
        known: (String) -> String? = none,
    ) = ReceiptValidator.check("add_items", document, subjects, applied, data, known)

    private fun refusal(check: ReceiptCheck): ReceiptRefusal = (check as ReceiptCheck.Invalid).reason

    @Test
    fun aCreatedItemIsAcceptedWithoutAnAlias() {
        val valid = addCheck("""{"items":[${item(mine)}]}""") as ReceiptCheck.Valid

        val created = valid.effect.items.single()
        assertEquals(ItemOutcome.CREATED, created.outcome)
        assertEquals(mine, created.canonicalPublicId)
        assertTrue(valid.effect.aliases.isEmpty())
    }

    @Test
    fun theServersSquishOfTheTypedNameIsNotAMismatch() {
        val document = addDocument(entry(mine, "  Leite   desnatado "))
        val check = addCheck("""{"items":[${item(mine, name = "Leite desnatado")}]}""", document = document)
        assertTrue(check is ReceiptCheck.Valid)
    }

    @Test
    fun aDuplicateNamesTheItemTheServerKeptAndRecordsTheAlias() {
        val valid = addCheck(
            """{"items":[${item(mine, theirs, outcome = "duplicate", lock = 4)}]}""",
            applied = false,
        ) as ReceiptCheck.Valid

        assertEquals(listOf(AliasClaim(mine, theirs)), valid.effect.aliases)
        assertEquals(theirs, valid.effect.items.single().canonicalPublicId)
        assertEquals(4, valid.effect.items.single().lockVersion)
    }

    @Test
    fun aReviveRecordsTheClaimAsAnAliasOfThePurchase() {
        val document = addDocument(entry(claim, "Detergente", revives = purchase))
        val subjects = listOf(WireSubject(purchase, SubjectRole.TARGETS, 0))
        val valid = addCheck(
            """{"items":[${item(claim, purchase, "Detergente", lock = 4, outcome = "revived")}]}""",
            document = document,
            subjects = subjects,
        ) as ReceiptCheck.Valid

        assertEquals(listOf(AliasClaim(claim, purchase)), valid.effect.aliases)
        assertEquals(ItemOutcome.REVIVED, valid.effect.items.single().outcome)
    }

    @Test
    fun aReviveThatDoesNotMoveTheVersionOrRevivesAnotherPurchaseIsRefused() {
        val document = addDocument(entry(claim, "Detergente", revives = purchase))
        val subjects = listOf(WireSubject(purchase, SubjectRole.TARGETS, 0))
        // The user saw version 3: a revive leaves a higher one.
        assertEquals(
            ReceiptRefusal.ITEM_MISMATCH,
            refusal(addCheck("""{"items":[${item(claim, purchase, "Detergente", lock = 3, outcome = "revived")}]}""", document, subjects)),
        )
        assertEquals(
            ReceiptRefusal.ITEM_MISMATCH,
            refusal(addCheck("""{"items":[${item(claim, theirs, "Detergente", lock = 4, outcome = "revived")}]}""", document, subjects)),
        )
        // "created" for an entry that revives, and "revived" for one that creates.
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(claim, claim, "Detergente")}]}""", document, subjects)))
        assertEquals(
            ReceiptRefusal.ITEM_MISMATCH,
            refusal(addCheck("""{"items":[${item(mine, purchase, lock = 4, outcome = "revived")}]}""")),
        )
    }

    @Test
    fun anAnswerThatCannotBeThisCommandsIsRefusedForEveryWayItCanDiffer() {
        // The answer of a set_item_state, or of a command with more or fewer items.
        assertEquals(
            ReceiptRefusal.WRONG_RESULT_SHAPE,
            refusal(addCheck("""{"item":${item(mine, outcome = null)}}""")),
        )
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine)},${item(theirs)}]}""")))
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[]}""")))
        // Another UUID than the one cited, a created item under another UUID or name, a state it cannot have.
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(theirs)}]}""")))
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine, theirs)}]}""")))
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine, name = "Café")}]}""")))
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine, lock = 2)}]}""")))
        assertEquals(
            ReceiptRefusal.ITEM_MISMATCH,
            refusal(addCheck("""{"items":[${item(mine, status = "purchased", purchasedAt = "2026-11-10T12:00:00.000000Z")}]}""")),
        )
        // Applied with nothing created, noop with something created.
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine, theirs, outcome = "duplicate")}]}""", applied = true)))
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(addCheck("""{"items":[${item(mine)}]}""", applied = false)))
    }

    @Test
    fun malformedDataIsRefusedFieldByField() {
        val bad = listOf(
            """{"items":[${item(mine).replace("\"lock_version\":0", "\"lock_version\":-1")}]}""",
            """{"items":[${item(mine).replace("\"lock_version\":0", "\"lock_version\":\"0\"")}]}""",
            """{"items":[${item(mine).replace("\"status\":\"pending\"", "\"status\":\"done\"")}]}""",
            """{"items":[${item(mine).replace("\"name\":\"Leite\"", "\"name\":\"  \"")}]}""",
            """{"items":[${item(mine, "not-a-uuid")}]}""",
            """{"items":[${item(mine).replace("\"outcome\":\"created\"", "\"outcome\":\"cloned\"")}]}""",
            """{"items":[${item(mine).replace("\"purchased_at\":null", "\"purchased_at\":\"yesterday\"")}]}""",
            """{"items":["x"]}""",
            """{"items":{}}""",
            """{}""",
            """[]""",
            """not json""",
            """{"items":[${item(mine)}]} trailing""",
        )
        for (data in bad) {
            assertEquals(data, ReceiptRefusal.MALFORMED_DATA, refusal(addCheck(data)))
        }
    }

    @Test
    fun anAliasThatContradictsOrChainsWithWhatIsKnownIsRefused() {
        val duplicate = """{"items":[${item(mine, theirs, outcome = "duplicate")}]}"""
        // The same alias already points somewhere else.
        assertEquals(
            ReceiptRefusal.ALIAS_CONFLICT,
            refusal(addCheck(duplicate, applied = false, known = { if (it == mine) purchase else null })),
        )
        // The same alias pointing at the same item again is the expected repetition.
        assertTrue(addCheck(duplicate, applied = false, known = { if (it == mine) theirs else null }) is ReceiptCheck.Valid)
        // The canonical item is itself an alias.
        assertEquals(
            ReceiptRefusal.ALIAS_CONFLICT,
            refusal(addCheck(duplicate, applied = false, known = { if (it == theirs) purchase else null })),
        )
        // Within one answer: an alias source that another entry names as canonical.
        val document = addDocument(entry(mine, "Leite"), entry(theirs, "Pão"))
        val subjects = listOf(WireSubject(mine, SubjectRole.CREATES, 0), WireSubject(theirs, SubjectRole.CREATES, 1))
        val chain = """{"items":[${item(mine, purchase, outcome = "duplicate")},${item(theirs, mine, "Pão", outcome = "duplicate")}]}"""
        assertEquals(ReceiptRefusal.ALIAS_CONFLICT, refusal(addCheck(chain, document, subjects, applied = false)))
    }

    private fun setDocument(id: String = mine, desired: String = "purchased") =
        """{"id":"c-2","type":"set_item_state","depends_on":"c-1","preconditions":{},"payload":{"public_id":"$id","desired":"$desired"}}"""

    private fun setCheck(
        data: String,
        id: String = mine,
        desired: String = "purchased",
        applied: Boolean = true,
        known: (String) -> String? = none,
        subjects: List<WireSubject> = listOf(WireSubject(id, SubjectRole.TARGETS, 0)),
    ) = ReceiptValidator.check("set_item_state", setDocument(id, desired), subjects, applied, data, known)

    private val bought = "2026-11-10T12:00:00.000000Z"

    @Test
    fun aMarkedItemIsAcceptedWithItsNewVersion() {
        val valid = setCheck("""{"item":${item(mine, status = "purchased", purchasedAt = bought, lock = 1, outcome = null)}}""") as ReceiptCheck.Valid

        val updated = valid.effect.items.single()
        assertEquals(ItemOutcome.UPDATED, updated.outcome)
        assertEquals(ItemStatusCodes.PURCHASED, updated.status)
        assertEquals(1, updated.lockVersion)
        assertEquals(1_794_312_000_000L, updated.purchasedAt)
    }

    @Test
    fun aNoopMayKeepTheVersionButAnAppliedChangeMustMoveIt() {
        val sameVersion = """{"item":${item(mine, status = "purchased", purchasedAt = bought, lock = 0, outcome = null)}}"""
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(setCheck(sameVersion, applied = true)))
        assertTrue(setCheck(sameVersion, applied = false) is ReceiptCheck.Valid)
    }

    @Test
    fun aMarkAnswerForTheWrongItemStateOrCommandIsRefused() {
        val pending = """{"item":${item(mine, outcome = null, lock = 1)}}"""
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(setCheck(pending)))
        val other = """{"item":${item(theirs, status = "purchased", purchasedAt = bought, lock = 1, outcome = null)}}"""
        assertEquals(ReceiptRefusal.ITEM_MISMATCH, refusal(setCheck(other)))
        assertEquals(
            ReceiptRefusal.WRONG_RESULT_SHAPE,
            refusal(setCheck("""{"items":[${item(mine)}]}""")),
        )
        val noDate = """{"item":${item(mine, status = "purchased", lock = 1, outcome = null)}}"""
        assertEquals(ReceiptRefusal.MALFORMED_DATA, refusal(setCheck(noDate)))
    }

    @Test
    fun aCitedAliasMustResolveToTheCanonicalItemTheServerReports() {
        val answer = """{"item":${item(mine, theirs, status = "purchased", purchasedAt = bought, lock = 2, outcome = null)}}"""
        // The device does not know the alias yet: the earlier receipt has to come first.
        assertEquals(ReceiptRefusal.ALIAS_UNKNOWN, refusal(setCheck(answer)))
        // Known and consistent.
        val valid = setCheck(answer, known = { if (it == mine) theirs else null }) as ReceiptCheck.Valid
        assertEquals(theirs, valid.effect.items.single().canonicalPublicId)
        // Known but pointing elsewhere.
        assertEquals(ReceiptRefusal.ALIAS_CONFLICT, refusal(setCheck(answer, known = { if (it == mine) purchase else null })))
        // The server reports the cited UUID while the device knows it as an alias.
        val direct = """{"item":${item(mine, status = "purchased", purchasedAt = bought, lock = 2, outcome = null)}}"""
        assertEquals(ReceiptRefusal.ALIAS_CONFLICT, refusal(setCheck(direct, known = { if (it == mine) theirs else null })))
    }

    @Test
    fun aDocumentAndSubjectsThatDoNotLineUpAreAnInconsistentIntentNotAGuess() {
        val answer = """{"items":[${item(mine)}]}"""
        assertEquals(ReceiptRefusal.INCONSISTENT_INTENT, refusal(addCheck(answer, subjects = emptyList())))
        assertEquals(
            ReceiptRefusal.INCONSISTENT_INTENT,
            refusal(setCheck("""{"item":${item(mine, status = "purchased", purchasedAt = bought, lock = 1, outcome = null)}}""", subjects = emptyList())),
        )
        assertEquals(ReceiptRefusal.UNSUPPORTED_TYPE, refusal(ReceiptValidator.check("remove_item", "{}", emptyList(), true, "{}", none)))
    }

    @Test
    fun isoInstantsAreParsedToEpochMillisAndImpossibleOnesAreNot() {
        assertEquals(0L, IsoInstant.parseMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_762_776_000_123L, IsoInstant.parseMillis("2025-11-10T12:00:00.123456Z"))
        assertEquals(951_782_400_000L, IsoInstant.parseMillis("2000-02-29T00:00:00.000000Z"))
        assertNull(IsoInstant.parseMillis("2001-02-29T00:00:00Z"))
        assertNull(IsoInstant.parseMillis("2026-13-01T00:00:00Z"))
        assertNull(IsoInstant.parseMillis("2026-11-10T24:00:00Z"))
        assertNull(IsoInstant.parseMillis("2026-11-10 12:00:00Z"))
        assertNull(IsoInstant.parseMillis("2026-11-10T12:00:00+00:00"))
        JSONObject(addDocument(entry(mine, "x"))) // the fixtures themselves are JSON
    }
}
