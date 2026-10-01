package com.kyzer.ytdl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val title: String,
    val fileName: String,
    val uri: String,
    val mime: String,
    val kind: String,      // VIDEO | MP3
    val quality: String,
    val sizeBytes: Long,
    val time: Long
)

/** Download history, stored as JSON in SharedPreferences. */
object HistoryStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<HistoryItem> = try {
        val arr = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(
                o.getString("title"), o.getString("fileName"), o.getString("uri"),
                o.getString("mime"), o.getString("kind"), o.getString("quality"),
                o.getLong("size"), o.getLong("time")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("title", it.title); put("fileName", it.fileName); put("uri", it.uri)
                put("mime", it.mime); put("kind", it.kind); put("quality", it.quality)
                put("size", it.sizeBytes); put("time", it.time)
            })
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun add(ctx: Context, item: HistoryItem) = save(ctx, (listOf(item) + load(ctx)).take(200))
    fun remove(ctx: Context, item: HistoryItem) = save(ctx, load(ctx).filter { it != item })
    fun clear(ctx: Context) = save(ctx, emptyList())
}
