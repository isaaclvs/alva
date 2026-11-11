package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import org.json.JSONException
import org.json.JSONObject

/**
 * How one entry of a frozen `add_items` document maps back to the local subject it came
 * from, for whoever applies the server's answer later. Entry `i` of `payload.items` belongs
 * to the subject with `ordinal == i`.
 *
 * - [wirePublicId] is the `public_id` the entry was sent with, i.e. the one the result cites
 *   (`data.items[i].public_id`). For a created item it is the subject's own UUID. For a
 *   revive it is the **claim**: a UUID made for this request, which the server turns into an
 *   alias of the purchase; [revivedPublicId] is that purchase and equals the subject's UUID.
 */
data class WireItemLink(
    val ordinal: Int,
    val wirePublicId: String,
    val subjectPublicId: String,
    val role: String,
    val revivedPublicId: String?,
)

object WireItemLinks {
    /**
     * The links of a frozen `add_items` [document] against its [subjects], or null when they
     * do not line up (wrong count, wrong order of identities): then nothing may be applied
     * from an answer, since the mapping cannot be trusted.
     */
    fun of(document: String, subjects: List<WireSubject>): List<WireItemLink>? {
        val items = try {
            JSONObject(document).getJSONObject("payload").getJSONArray("items")
        } catch (e: JSONException) {
            return null
        }
        val ordered = subjects.sortedBy { it.ordinal }
        if (items.length() != ordered.size || ordered.indices.any { ordered[it].ordinal != it }) return null
        return ordered.map { subject ->
            if (subject.role != SubjectRole.CREATES && subject.role != SubjectRole.TARGETS) return null
            val item = items.optJSONObject(subject.ordinal) ?: return null
            val wireId = item.optString("public_id").takeIf { it.isNotEmpty() } ?: return null
            val revived = item.optJSONObject("revives")?.optString("public_id")?.takeIf { it.isNotEmpty() }
            val coherent = if (subject.role == SubjectRole.TARGETS) {
                revived == subject.publicId && wireId != revived
            } else {
                revived == null && wireId == subject.publicId
            }
            if (!coherent) return null
            WireItemLink(subject.ordinal, wireId, subject.publicId, subject.role, revived)
        }
    }
}
