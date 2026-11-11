package net.ts.taild80ca0.atlas.twa.data

import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.PreparationRefusal
import net.ts.taild80ca0.atlas.twa.data.list.ReviveObservation
import net.ts.taild80ca0.atlas.twa.data.list.WireBuild
import net.ts.taild80ca0.atlas.twa.data.list.WireDocument
import net.ts.taild80ca0.atlas.twa.data.list.WireEnvelopeBuilder
import net.ts.taild80ca0.atlas.twa.data.list.WireItemLinks
import net.ts.taild80ca0.atlas.twa.data.list.WireSource
import net.ts.taild80ca0.atlas.twa.data.list.WireSubject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure conversion of a stored intent into a contract v1 document. The expected
 * documents are the ones in test/fixtures/files/android_wire_documents.json, which the
 * Rails suite also posts to the real endpoint.
 */
class WireEnvelopeBuilderTest {

    private val item = "9a1d0000-0000-4000-8000-000000000001"
    private val other = "9a1d0000-0000-4000-8000-000000000002"
    private val claim = "9a1d0000-0000-4000-8000-0000000000c1"

    private class Scripted(private vararg val ids: String) : IdGenerator {
        var calls = 0
        override fun newId(): String = ids.getOrElse(calls++) { "generated-$calls" }
    }

    private fun source(
        type: String,
        payload: String,
        subjects: List<WireSubject>,
        id: String = "cmd-1",
        dependsOn: String? = null,
        preconditions: String = "{}",
        observedRevision: Long = 0,
        dependencyTouches: Set<String>? = null,
    ) = WireSource(id, type, dependsOn, observedRevision, payload, preconditions, subjects, dependencyTouches)

    private fun add(
        names: List<String>,
        subjects: List<WireSubject>,
        dependsOn: String? = null,
        revives: List<ReviveObservation>? = null,
        touches: Set<String>? = null,
        observedRevision: Long = 0,
    ) = source(
        "add_items",
        JSONObject().put("names", JSONArray(names)).toString(),
        subjects,
        dependsOn = dependsOn,
        preconditions = revives?.let { ReviveObservation.toPreconditions(it).toString() } ?: "{}",
        dependencyTouches = touches,
        observedRevision = observedRevision,
    )

    private fun built(source: WireSource, ids: IdGenerator = Scripted(claim)): String =
        (WireEnvelopeBuilder.build(source, ids) as? WireBuild.Built)?.document ?: error("expected a document")

    private fun refusal(source: WireSource, ids: IdGenerator = Scripted(claim)): PreparationRefusal =
        (WireEnvelopeBuilder.build(source, ids) as? WireBuild.Refused)?.reason ?: error("expected a refusal")

    private fun create(publicId: String, ordinal: Int = 0) = WireSubject(publicId, SubjectRole.CREATES, ordinal)
    private fun target(publicId: String, ordinal: Int = 0) = WireSubject(publicId, SubjectRole.TARGETS, ordinal)

    // ---- the shared fixture -------------------------------------------------------------

    private val fixture: JSONArray by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("android_wire_documents.json"))
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONArray("cases")
    }

    private fun documents(name: String): List<JSONObject> {
        val case = (0 until fixture.length()).map { fixture.getJSONObject(it) }.first { it.getString("name") == name }
        val docs = case.getJSONArray("documents")
        return (0 until docs.length()).map { docs.getJSONObject(it) }
    }

    /** Key order and spacing are not part of the contract (Rails digests sorted keys), so compare trees. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        JSONObject.NULL, null -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun assertMatchesFixture(expected: JSONObject, document: String) =
        assertEquals(canonical(expected), canonical(JSONObject(document)))

    @Test
    fun offlineAddMarkThenTypedAgainMatchesTheDocumentsRailsAccepts() {
        val (a1, a2, a3) = documents("offline_add_mark_then_typed_again")

        assertMatchesFixture(a1, built(add(listOf("Tapioca"), listOf(create(item))).let {
            source("add_items", it.payload, it.subjects, id = a1.getString("id"))
        }))
        assertMatchesFixture(a2, built(source(
            "set_item_state", """{"public_id":"$item","desired":"purchased"}""", listOf(target(item)),
            id = a2.getString("id"), dependsOn = a1.getString("id"), dependencyTouches = setOf(item),
        )))
        // The revive was typed when the item existed only in the queue: no version seen, but depends_on lends one.
        val revive = add(
            listOf("Tapioca"), listOf(target(item)), dependsOn = a2.getString("id"), touches = setOf(item),
            revives = listOf(ReviveObservation(0, item, lockVersion = null, covered = true)),
        ).let { source("add_items", it.payload, it.subjects, a3.getString("id"), it.dependsOn, it.preconditions, 0, it.dependencyTouches) }
        assertMatchesFixture(a3, built(revive))
    }

    @Test
    fun aPurchaseSeenInASnapshotIsRevivedWithExactlyThatVersion() {
        val (b1) = documents("seen_purchase_is_revived_with_its_version")
        val purchase = "40000000-0000-4000-8000-000000000002"
        val seen = add(listOf("Detergente"), listOf(target(purchase)), revives = listOf(ReviveObservation(0, purchase, 0, covered = false)))

        assertMatchesFixture(b1, built(source("add_items", seen.payload, seen.subjects, b1.getString("id"), null, seen.preconditions), Scripted("9a1d0000-0000-4000-8000-0000000000c2")))
    }

    @Test
    fun severalCreatedNamesKeepTheirOrderAndTheirDeviceUuids() {
        val (d1) = documents("created_batch_keeps_device_uuids")
        val banana = "9a1d0000-0000-4000-8000-0000000000e1"
        val bread = "9a1d0000-0000-4000-8000-0000000000e2"
        val built = add(listOf("Banana", "Pão de forma"), listOf(create(banana, 0), create(bread, 1)), observedRevision = 7)

        assertMatchesFixture(d1, built(source("add_items", built.payload, built.subjects, d1.getString("id"), null, "{}", 7)))
    }

    @Test
    fun markingSendsTheDesiredStateAndTheVersionTheUserSaw() {
        val (f1) = documents("mark_with_the_version_the_user_saw")
        val milk = "40000000-0000-4000-8000-000000000001"

        assertMatchesFixture(f1, built(source(
            "set_item_state", """{"public_id":"$milk","desired":"purchased"}""", listOf(target(milk)),
            id = f1.getString("id"), preconditions = """{"lock_version":0}""",
        )))
    }

    // ---- creation, revive and claim -----------------------------------------------------

    @Test
    fun aReviveEntryCarriesANewClaimNeverThePurchaseItRevives() {
        val ids = Scripted(item, claim) // the generator "collides" with the purchase first
        val document = built(
            add(listOf("Leite"), listOf(target(item)), revives = listOf(ReviveObservation(0, item, 3, false))),
            ids,
        )

        val entry = JSONObject(document).getJSONObject("payload").getJSONArray("items").getJSONObject(0)
        assertEquals(claim, entry.getString("public_id"))
        assertEquals(item, entry.getJSONObject("revives").getString("public_id"))
        assertEquals(3, entry.getJSONObject("revives").getInt("lock_version"))
        assertNull(WireDocument.problemWith(document, "cmd-1", "add_items", null))
    }

    @Test
    fun theTypedNameTravelsUntouchedWhateverItContains() {
        val name = "Pão \"de\" açúcar\n\\ 🍞 \u0000x"
        val document = built(add(listOf(name), listOf(create(item))))

        assertEquals(name, JSONObject(document).getJSONObject("payload").getJSONArray("items").getJSONObject(0).getString("name"))
    }

    @Test
    fun theSameStoredIntentAlwaysGivesTheSameBytes() {
        val s = add(listOf("Leite", "Pão"), listOf(create(item, 0), target(other, 1)), dependsOn = "cmd-0", touches = setOf(other),
            revives = listOf(ReviveObservation(1, other, null, covered = true)), observedRevision = 12)

        assertEquals(built(s, Scripted(claim)), built(s, Scripted(claim)))
    }

    @Test
    fun aMixedCommandKeepsCreatesAndRevivesInTheirOrdinals() {
        val document = built(
            add(listOf("Banana", "Detergente"), listOf(create(item, 0), target(other, 1)),
                revives = listOf(ReviveObservation(1, other, 5, false))),
        )

        val items = JSONObject(document).getJSONObject("payload").getJSONArray("items")
        assertEquals(item, items.getJSONObject(0).getString("public_id"))
        assertTrue(!items.getJSONObject(0).has("revives"))
        assertEquals(claim, items.getJSONObject(1).getString("public_id"))
        assertEquals(other, items.getJSONObject(1).getJSONObject("revives").getString("public_id"))
    }

    @Test
    fun claimsLinkBackToTheirSubjectsByOrdinal() {
        val subjects = listOf(create(item, 0), target(other, 1))
        val document = built(add(listOf("Banana", "Detergente"), subjects, revives = listOf(ReviveObservation(1, other, 5, false))))

        val links = checkNotNull(WireItemLinks.of(document, subjects))

        assertEquals(listOf(item, claim), links.map { it.wirePublicId })
        assertEquals(listOf(item, other), links.map { it.subjectPublicId })
        assertEquals(listOf(null, other), links.map { it.revivedPublicId })
        // Not the same command: a document of another shape does not link.
        assertNull(WireItemLinks.of(document, listOf(create(item, 0))))
        assertNull(WireItemLinks.of(document, listOf(create(item, 0), target(item, 1))))
        assertNull(WireItemLinks.of("not json", subjects))
    }

    // ---- observations and versions ------------------------------------------------------

    @Test
    fun aVersionIsOnlyWhatTheIntentRecordedOrTheDependencyCanLend() {
        // Recorded and covered: both go; the server takes the greater.
        val both = built(add(listOf("Leite"), listOf(target(item)), dependsOn = "cmd-0", touches = setOf(item),
            revives = listOf(ReviveObservation(0, item, 4, covered = true))))
        assertEquals(4, JSONObject(both).getJSONObject("payload").getJSONArray("items").getJSONObject(0).getJSONObject("revives").getInt("lock_version"))

        // A legacy row whose dependency still touches the item: no version is claimed at all.
        val legacy = built(add(listOf("Leite"), listOf(target(item)), dependsOn = "cmd-0", touches = setOf(item)))
        assertTrue(!JSONObject(legacy).getJSONObject("payload").getJSONArray("items").getJSONObject(0).getJSONObject("revives").has("lock_version"))
    }

    @Test
    fun aReviveWithoutAnyRecoverableVersionIsRefusedForTheRightReason() {
        val ids = Scripted(claim)
        val lost = add(listOf("Leite"), listOf(target(item)))
        assertEquals(PreparationRefusal.REVIVE_OBSERVATION_LOST, refusal(lost, ids))
        // A legacy row with a dependency that does not touch the item is still lost, not guessed.
        assertEquals(PreparationRefusal.REVIVE_OBSERVATION_LOST, refusal(add(listOf("Leite"), listOf(target(item)), dependsOn = "c0", touches = setOf(other)), ids))
        // The dependency row is gone from the queue: it cannot be shown to touch the item.
        assertEquals(PreparationRefusal.REVIVE_OBSERVATION_LOST, refusal(add(listOf("Leite"), listOf(target(item)), dependsOn = "c0", touches = null), ids))

        val unseen = listOf(ReviveObservation(0, item, null, covered = false))
        assertEquals(PreparationRefusal.VERSION_UNKNOWN, refusal(add(listOf("Leite"), listOf(target(item)), revives = unseen), ids))
        assertEquals(PreparationRefusal.DEPENDENCY_INSUFFICIENT, refusal(add(listOf("Leite"), listOf(target(item)), dependsOn = "c0", touches = setOf(other), revives = unseen), ids))
        assertEquals("a refusal consumes no UUID", 0, ids.calls)
    }

    @Test
    fun oneUncoveredReviveRefusesTheWholeCommandInsteadOfSendingAPartialOne() {
        val revives = listOf(ReviveObservation(0, item, 1, false), ReviveObservation(1, other, null, covered = false))
        val ids = Scripted(claim)

        val s = add(listOf("Leite", "Pão"), listOf(target(item, 0), target(other, 1)), dependsOn = "c0", touches = setOf(item), revives = revives)

        assertEquals(PreparationRefusal.DEPENDENCY_INSUFFICIENT, refusal(s, ids))
        assertEquals(0, ids.calls)
    }

    @Test
    fun setItemStateNeedsAVersionOrADependencyThatTouchesTheItem() {
        val payload = """{"public_id":"$item","desired":"pending"}"""
        val subjects = listOf(target(item))

        assertEquals(PreparationRefusal.VERSION_UNKNOWN, refusal(source("set_item_state", payload, subjects)))
        assertEquals(PreparationRefusal.DEPENDENCY_INSUFFICIENT, refusal(source("set_item_state", payload, subjects, dependsOn = "c0", dependencyTouches = setOf(other))))
        // Dependency inherits; its row may already be gone from the queue (it was built from that very dependency).
        val inherits = built(source("set_item_state", payload, subjects, dependsOn = "c0", dependencyTouches = null))
        assertEquals("{}", JSONObject(inherits).getJSONObject("preconditions").toString())
        assertEquals("c0", JSONObject(inherits).getString("depends_on"))
        val explicit = built(source("set_item_state", payload, subjects, preconditions = """{"lock_version":9}"""))
        assertEquals(9, JSONObject(explicit).getJSONObject("preconditions").getInt("lock_version"))
    }

    // ---- size, shape and damage ---------------------------------------------------------

    @Test
    fun thirtyNamesGoOutAndThirtyOneAreRefusedWithoutDroppingAny() {
        fun many(count: Int): WireSource {
            val subjects = (0 until count).map { create("u-$it", it) }
            return add((0 until count).map { "Item $it" }, subjects)
        }

        val thirty = built(many(30))
        assertNull(WireDocument.problemWith(thirty, "cmd-1", "add_items", null))
        assertEquals(30, JSONObject(thirty).getJSONObject("payload").getJSONArray("items").length())

        val result = WireEnvelopeBuilder.build(many(31), Scripted()) as WireBuild.Refused
        assertEquals(PreparationRefusal.ENVELOPE_TOO_LARGE, result.reason)
        assertTrue(result.detail, "31" in result.detail && "none was dropped" in result.detail)
    }

    @Test
    fun damagedOrUnknownIntentsAreRefusedNotRepaired() {
        val ok = add(listOf("Leite"), listOf(create(item)))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", "not json", ok.subjects)))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", """{"names":[]}""", ok.subjects)))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", """{"names":["a",7]}""", listOf(create(item, 0), create(other, 1)))))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", """{"names":["a","b"]}""", ok.subjects)))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", """{"items":[]}""", ok.subjects)))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(add(listOf("a"), listOf(WireSubject(item, "watches", 0)))))
        // A recorded observation for something that is not a revived subject.
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(add(listOf("a"), listOf(create(item)), revives = listOf(ReviveObservation(0, item, 1, false)))))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("add_items", """{"names":["a"]}""", listOf(create(item)), preconditions = """{"revives":[{"ordinal":0}]}""")))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("set_item_state", """{"public_id":"$item","desired":"toggle"}""", listOf(target(item)))))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("set_item_state", """{"public_id":"$item","desired":"pending"}""", listOf(target(other)), preconditions = """{"lock_version":1}""")))
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(source("set_item_state", """{"public_id":"$item","desired":"pending"}""", listOf(target(item)), preconditions = """{"lock_version":-1}""")))
        assertEquals(PreparationRefusal.UNSUPPORTED_TYPE, refusal(source("remove_item", "{}", emptyList())))
    }

    @Test
    fun duplicateObservationsCannotStandInForAnotherRevivedItem() {
        val duplicated = listOf(
            ReviveObservation(0, item, 3, false),
            ReviveObservation(0, item, 3, false),
        )
        val command = add(
            listOf("Leite", "Pão"), listOf(target(item, 0), target(other, 1)),
            dependsOn = "prior", touches = setOf(other), revives = duplicated,
        )
        val ids = Scripted(claim)
        assertEquals(PreparationRefusal.INCONSISTENT_INTENT, refusal(command, ids))
        assertEquals(0, ids.calls)
    }

    @Test
    fun anUnknownSubjectRoleCannotBeUsedToApplyAResult() {
        val document = built(add(listOf("Leite"), listOf(create(item))))
        assertNull(WireItemLinks.of(document, listOf(WireSubject(item, "watches", 0))))
    }

    @Test
    fun everyBuiltDocumentPassesTheFreezeCheck() {
        val documents = listOf(
            add(listOf("Leite"), listOf(create(item))) to null,
            add(listOf("Leite"), listOf(target(item)), dependsOn = "c0", touches = setOf(item), revives = listOf(ReviveObservation(0, item, null, true))) to "c0",
            source("set_item_state", """{"public_id":"$item","desired":"purchased"}""", listOf(target(item)), preconditions = """{"lock_version":2}""") to null,
        )
        for ((s, dependsOn) in documents) {
            val document = built(s)
            assertNull(document, WireDocument.problemWith(document, s.id, s.type, dependsOn))
        }
        assertNotNull(documents)
        assertNotEquals(item, claim)
    }

    @Test
    fun observationsSurviveTheirJsonFormAndDamagedOnesAreNotLegacy() {
        val seen = listOf(ReviveObservation(0, item, 4, false), ReviveObservation(2, other, null, true))

        assertEquals(seen, ReviveObservation.fromPreconditions(ReviveObservation.toPreconditions(seen)))
        assertNull("a command without a record is a legacy one", ReviveObservation.fromPreconditions(JSONObject("{}")))
        assertEquals("{}", ReviveObservation.toPreconditions(emptyList()).toString())
        val failure = runCatching { ReviveObservation.fromPreconditions(JSONObject("""{"revives":"x"}""")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
