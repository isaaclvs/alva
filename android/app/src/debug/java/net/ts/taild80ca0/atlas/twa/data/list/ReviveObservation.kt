package net.ts.taild80ca0.atlas.twa.data.list

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the device knew about one purchased item at the moment the user typed its name
 * again (a revive), kept with the local intent so that preparing the request later never
 * has to guess what was seen.
 *
 * - [lockVersion] is the item's confirmed `lock_version` in the projection *when the user
 *   acted*; null when the device had never seen the item on the server (created offline).
 *   A snapshot that arrives afterwards must not change it: the server decides whether the
 *   purchase is still the one the user saw by comparing exactly this.
 * - [covered] says whether the command's own `depends_on` is the last queued command that
 *   touched this item, so the server can inherit the version that command left. It is a fact
 *   about the queue at intent time; `depends_on` never changes after the insert.
 *
 * Stored in the local `add_items` command's `preconditions` JSON as
 * `{"revives":[{"ordinal":1,"public_id":"…","lock_version":3,"covered":false}]}`.
 * `ordinal` is the same index `command_subjects` and `payload.names` use. This is intent
 * metadata, never part of the wire document; `ProjectionReducer` ignores it, so replaying
 * the queue is unchanged. Commands queued before this existed have no such record.
 */
data class ReviveObservation(
    val ordinal: Int,
    val publicId: String,
    val lockVersion: Int?,
    val covered: Boolean,
) {
    companion object {
        const val KEY = "revives"

        /** `{}` when there is nothing to remember, so commands without revives keep their old form. */
        fun toPreconditions(observations: List<ReviveObservation>): JSONObject =
            JSONObject().apply {
                if (observations.isEmpty()) return@apply
                put(
                    KEY,
                    JSONArray().also { array ->
                        observations.forEach { seen ->
                            array.put(
                                JSONObject()
                                    .put("ordinal", seen.ordinal)
                                    .put("public_id", seen.publicId)
                                    .put("lock_version", seen.lockVersion ?: JSONObject.NULL)
                                    .put("covered", seen.covered),
                            )
                        }
                    },
                )
            }

        /**
         * The recorded observations, or null when [preconditions] has none (a command queued
         * before they were kept). Throws [IllegalArgumentException] for a record that is there
         * but not understandable: that is a damaged intent, not a legacy one.
         */
        fun fromPreconditions(preconditions: JSONObject): List<ReviveObservation>? {
            if (!preconditions.has(KEY)) return null
            val array = preconditions.opt(KEY) as? JSONArray ?: throw IllegalArgumentException("revives must be an array")
            return (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: throw IllegalArgumentException("revives[$index] must be an object")
                val ordinal = (entry.opt("ordinal") as? Int)?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("revives[$index].ordinal must be an integer >= 0")
                val publicId = (entry.opt("public_id") as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("revives[$index].public_id must be a string")
                val version = entry.opt("lock_version")
                val lockVersion = when {
                    version == null || version == JSONObject.NULL -> null
                    version is Int && version >= 0 -> version
                    else -> throw IllegalArgumentException("revives[$index].lock_version must be null or an integer >= 0")
                }
                val covered = entry.opt("covered") as? Boolean
                    ?: throw IllegalArgumentException("revives[$index].covered must be a boolean")
                ReviveObservation(ordinal, publicId, lockVersion, covered)
            }
        }
    }
}
