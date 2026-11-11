package net.ts.taild80ca0.atlas.twa.data

import net.ts.taild80ca0.atlas.twa.data.list.WireDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WireDocumentTest {
    private val add = """
        {"id":"c-1","type":"add_items","depends_on":null,"observed_revision":840,"preconditions":{},
         "payload":{"items":[{"public_id":"9a1d0000-0000-4000-8000-000000000001","name":"Leite"}]}}
    """.trimIndent()

    private val setState = """
        {"id":"c-2","type":"set_item_state","depends_on":"c-1","preconditions":{"lock_version":3},
         "payload":{"public_id":"i-1","desired":"purchased"}}
    """.trimIndent()

    private fun problem(doc: String, id: String = "c-1", type: String = "add_items", dependsOn: String? = null) =
        WireDocument.problemWith(doc, id, type, dependsOn)

    @Test
    fun aWellFormedDocumentOfTheLocalCommandIsAccepted() {
        assertNull(problem(add))
        assertNull(problem(setState, "c-2", "set_item_state", "c-1"))
        assertNull("surrounding whitespace is part of the bytes, not a defect", problem("\n$add\n"))
    }

    @Test
    fun identityMustMatchTheLocalCommand() {
        assertEquals("id does not match the local command", problem(add, id = "other"))
        assertEquals("type does not match the local command", problem(add, type = "set_item_state"))
        assertEquals("depends_on does not match the local command", problem(add, dependsOn = "c-0"))
        assertEquals("depends_on does not match the local command", problem(setState, "c-2", "set_item_state", null))
    }

    @Test
    fun theLegacyNamesPayloadIsNotAWireForm() {
        val legacy = """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"names":["Leite"]}}"""
        assertNotNull(problem(legacy))
        val both = """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":{},
            "payload":{"names":["Leite"],"items":[{"public_id":"u-1","name":"Leite"}]}}"""
        assertEquals("payload.names is the legacy local form, not a wire payload", problem(both))
    }

    @Test
    fun malformedOrForeignDocumentsAreRefused() {
        assertNotNull(problem(""))
        assertNotNull(problem("[]"))
        assertNotNull(problem("not json"))
        assertNotNull("trailing text", problem("$add trailing"))
        assertNotNull("second document", problem("$add $add"))
        assertEquals("unknown field extra", problem(add.replace("\"id\"", "\"extra\":1,\"id\"")))
        assertEquals("missing depends_on", problem(add.replace("\"depends_on\":null,", "")))
        assertEquals("missing preconditions", problem(add.replace("\"preconditions\":{},", "")))
        assertEquals("observed_revision must be an integer >= 0", problem(add.replace("840", "-1")))
        assertEquals("observed_revision must be an integer >= 0", problem(add.replace("840", "1.5")))
        assertEquals("payload must be an object", problem(add.replace(Regex("\"payload\":.*\\}\\s*$"), "\"payload\":[]}")))
    }

    @Test
    fun addItemsRulesFollowTheContract() {
        fun doc(items: String, preconditions: String = "{}") =
            """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":$preconditions,"payload":{"items":$items}}"""
        assertEquals("payload.items takes 1 to 30 entries", problem(doc("[]")))
        val thirtyOne = (1..31).joinToString(",", "[", "]") { """{"public_id":"u$it","name":"n$it"}""" }
        assertEquals("payload.items takes 1 to 30 entries", problem(doc(thirtyOne)))
        val thirty = (1..30).joinToString(",", "[", "]") { """{"public_id":"u$it","name":"n$it"}""" }
        assertNull(problem(doc(thirty)))
        assertEquals("items[1].public_id repeats", problem(doc("""[{"public_id":"U","name":"a"},{"public_id":"u","name":"b"}]""")))
        assertEquals("items[0].name must be a string", problem(doc("""[{"public_id":"u","name":7}]""")))
        assertEquals("items[0].public_id must be a string", problem(doc("""[{"name":"a"}]""")))
        assertNull(problem(doc("""[{"public_id":"u","name":"a","revives":{"public_id":"r","lock_version":0}}]""")))
        assertEquals("items[0].revives must carry a public_id", problem(doc("""[{"public_id":"u","name":"a","revives":{}}]""")))
        assertEquals("add_items takes no preconditions", problem(doc("""[{"public_id":"u","name":"a"}]""", """{"lock_version":1}""")))
    }

    @Test
    fun setItemStateNeedsAnItemAndADesiredState() {
        fun doc(payload: String) =
            """{"id":"c-2","type":"set_item_state","depends_on":"c-1","preconditions":{},"payload":$payload}"""
        assertNull(problem(doc("""{"public_id":"i","desired":"pending"}"""), "c-2", "set_item_state", "c-1"))
        assertEquals(
            "payload.desired must be pending or purchased",
            problem(doc("""{"public_id":"i","desired":"toggle"}"""), "c-2", "set_item_state", "c-1"),
        )
        assertEquals(
            "payload.public_id must be a string",
            problem(doc("""{"desired":"pending"}"""), "c-2", "set_item_state", "c-1"),
        )
    }

    @Test
    fun aTypeWithoutAWireFormYetCannotBeFrozen() {
        val other = """{"id":"c-9","type":"remove_item","depends_on":null,"preconditions":{},"payload":{}}"""
        assertEquals("type remove_item has no wire form yet", problem(other, "c-9", "remove_item"))
    }
}
