package net.ts.taild80ca0.atlas.twa.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer to a one-command request is accepted only when it is about that command and
 * matches the contract. Anything else confirms nothing.
 */
class CommandReceiptParserTest {
    private val id = "5f0c2f0e-0000-4000-8000-000000000001"

    private fun body(
        result: String,
        revision: String = "841",
        snapshotRequired: String = "false",
    ) = """{"revision":$revision,"results":[$result],"snapshot_required":$snapshotRequired}"""

    private fun applied(extra: String = "") =
        """{"id":"$id","status":"applied","revision":841,"data":{"items":[{"public_id":"a","canonical_public_id":"a","lock_version":0}]}$extra}"""

    private fun parse(text: String, expected: String = id) = CommandReceiptParser.parse(text.toByteArray(Charsets.UTF_8), expected)

    private fun receipt(text: String, expected: String = id) = (parse(text, expected) as ReceiptParse.Valid).receipt

    private fun invalid(text: String, expected: String = id) = (parse(text, expected) as ReceiptParse.Invalid).reason

    @Test
    fun anAppliedResultBecomesATypedReceiptWithItsDataUntouched() {
        val r = receipt(body(applied()))

        assertEquals(ReceiptStatus.APPLIED, r.status)
        assertEquals(id, r.commandId)
        assertEquals(841L, r.revision)
        assertEquals(841L, r.houseRevision)
        assertEquals(false, r.snapshotRequired)
        assertNull(r.error)
        assertEquals("a", JSONObject(r.dataJson!!).getJSONArray("items").getJSONObject(0).getString("public_id"))
    }

    @Test
    fun aDuplicateKeepsTheOriginalStatusRevisionAndData() {
        val duplicate = """{"id":"$id","status":"duplicate","original_status":"noop","revision":7,"data":{"item":{}}}"""

        val r = receipt(body(duplicate, revision = "900"))

        assertEquals(ReceiptStatus.DUPLICATE, r.status)
        assertEquals(ReceiptStatus.NOOP, r.originalStatus)
        assertEquals(7L, r.revision)
        assertEquals(900L, r.houseRevision)
        assertTrue(r.status.recordedByServer)
    }

    @Test
    fun conflictRejectedAndBlockedAreBusinessResultsWithTheirErrorCode() {
        val cases = mapOf(
            "conflict" to "precondition_failed",
            "rejected" to "gone",
            "blocked" to "dependency_not_applied",
        )
        for ((status, error) in cases) {
            val r = receipt(body("""{"id":"$id","status":"$status","error":"$error","data":{"reason":"x"}}""", snapshotRequired = "true"))

            assertEquals(ReceiptStatus.fromWire(status), r.status)
            assertEquals(error, r.error)
            assertNull(r.revision)
            assertTrue(r.snapshotRequired)
            assertTrue("$status is not recorded by the server", !r.status.recordedByServer)
        }
    }

    @Test
    fun theIdIsComparedWithoutCaseBecauseTheServerLowercasesIt() {
        val upper = id.uppercase()

        assertEquals(upper, receipt(body(applied()), expected = upper).commandId)
    }

    @Test
    fun aResultForAnotherCommandOrNoneOrSeveralIsNeverTheReceipt() {
        val other = applied().replace(id, "5f0c2f0e-0000-4000-8000-000000000002")

        assertEquals(InvalidResponse.WRONG_COMMAND, invalid(body(other)))
        assertEquals(InvalidResponse.WRONG_COMMAND, invalid("""{"revision":1,"results":[],"snapshot_required":false}"""))
        assertEquals(InvalidResponse.WRONG_COMMAND, invalid("""{"revision":841,"results":[${applied()},${applied()}],"snapshot_required":false}"""))
    }

    @Test
    fun anUnknownStatusConfirmsNothing() {
        assertEquals(InvalidResponse.UNKNOWN_STATUS, invalid(body("""{"id":"$id","status":"confirmed","revision":841,"data":{}}""")))
        assertEquals(InvalidResponse.UNKNOWN_STATUS, invalid(body("""{"id":"$id","status":"APPLIED","revision":841,"data":{}}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":1,"revision":841,"data":{}}""")))
    }

    @Test
    fun aTruncatedOrNonJsonBodyIsNotAnAnswer() {
        val whole = body(applied())

        assertEquals(InvalidResponse.NOT_JSON, invalid(whole.dropLast(5)))
        assertEquals(InvalidResponse.NOT_JSON, invalid(""))
        assertEquals(InvalidResponse.NOT_JSON, invalid("<html>502 Bad Gateway</html>"))
        assertEquals(InvalidResponse.NOT_JSON, invalid("$whole garbage"))
        assertEquals(InvalidResponse.NOT_JSON, invalid("[]"))
    }

    @Test
    fun missingOrMistypedFieldsAreAShapeError() {
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid("""{"revision":841,"results":[${applied()}]}"""))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid("""{"results":[${applied()}],"snapshot_required":false}"""))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body(applied(), revision = "\"841\"")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body(applied(), revision = "841.5")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body(applied(), revision = "-1")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body(applied(), snapshotRequired = "\"false\"")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid("""{"revision":841,"results":{},"snapshot_required":false}"""))
        // A recorded result needs its revision and data; a refusal needs its error.
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":"applied","data":{}}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":"applied","revision":841}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":"applied","revision":841,"data":[]}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":"rejected"}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"id":"$id","status":"conflict","error":""}""")))
        assertEquals(InvalidResponse.WRONG_SHAPE, invalid(body("""{"status":"applied","revision":841,"data":{}}""")))
    }

    @Test
    fun revisionsAndStatusesThatContradictEachOtherAreIncoherent() {
        // The house revision only grows: a result cannot be newer than the end of its own batch.
        assertEquals(InvalidResponse.INCOHERENT, invalid(body(applied(), revision = "840")))
        val noOriginal = """{"id":"$id","status":"duplicate","revision":7,"data":{}}"""
        assertEquals(InvalidResponse.INCOHERENT, invalid(body(noOriginal, revision = "900")))
        val badOriginal = """{"id":"$id","status":"duplicate","original_status":"conflict","revision":7,"data":{}}"""
        assertEquals(InvalidResponse.INCOHERENT, invalid(body(badOriginal, revision = "900")))
    }

    @Test
    fun fieldsTheClientDoesNotKnowAreIgnoredNotRejected() {
        val r = receipt("""{"revision":841,"future":true,"results":[{"id":"$id","status":"blocked","error":"dependency_not_applied","depends_on":"x","more":1}],"snapshot_required":false}""")

        assertEquals(ReceiptStatus.BLOCKED, r.status)
    }
}
