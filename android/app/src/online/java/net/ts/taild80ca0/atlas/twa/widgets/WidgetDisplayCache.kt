package net.ts.taild80ca0.atlas.twa.widgets

import android.content.Context
import android.util.AtomicFile
import net.ts.taild80ca0.atlas.twa.sync.*
import net.ts.taild80ca0.atlas.twa.data.list.Category
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only the subset needed to paint widgets, excluded from backup; never used as a command queue. */
internal object WidgetDisplayCache {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "widget_display.json"))
    @Synchronized fun clear(context: Context) { file(context).delete() }
    @Synchronized fun write(context: Context, snapshot: ServerSnapshot, session: String) {
        val json = JSONObject().put("session", session).put("house", snapshot.householdPublicId).put("name", snapshot.householdName)
            .put("revision", snapshot.revision).put("dictionary", snapshot.dictionaryRevision)
            .put("categories", JSONArray(snapshot.categories.map { JSONObject().put("id", it.publicId).put("name", it.name).put("position", it.position) }))
            .put("items", JSONArray(snapshot.listItems.map { JSONObject().put("id", it.publicId).put("name", it.name)
                .put("normalized", it.normalizedName).put("category", it.categoryPublicId ?: JSONObject.NULL)
                .put("status", it.status).put("version", it.lockVersion) }))
            .put("habituals", JSONArray(snapshot.habitualItems.map { JSONObject().put("id", it.publicId)
                .put("name", it.name).put("normalized", it.normalizedName) }))
        val target = file(context)
        var stream: java.io.FileOutputStream? = null
        try { stream = target.startWrite(); stream.write(json.toString().toByteArray()); target.finishWrite(stream) }
        catch (_: Exception) { if (stream != null) target.failWrite(stream) }
    }
    @Synchronized fun read(context: Context, session: String): ServerSnapshot? = try {
        val file = file(context)
        if (file.baseFile.length() > 2 * 1024 * 1024) { file.delete(); null } else {
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            require(json.getString("session") == session)
            fun objects(key: String): List<JSONObject> = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ServerSnapshot(1, json.getLong("revision"), json.getLong("dictionary"), json.getString("house"), json.getString("name"),
                objects("categories").map { Category(it.getString("id"), it.getString("name"), it.getInt("position")) }, emptyList(),
                objects("items").map { SnapshotItem(it.getString("id"), it.getString("name"), it.getString("normalized"),
                    if (it.isNull("category")) null else it.getString("category"), it.getInt("status"), null, null, 0, 0, it.getInt("version"), null) },
                emptyList(), emptyList(), objects("habituals").map { SnapshotHabitual(it.getString("id"), it.getString("name"), it.getString("normalized"), 0) }, emptyList())
        }
    } catch (_: Exception) { null }
}
