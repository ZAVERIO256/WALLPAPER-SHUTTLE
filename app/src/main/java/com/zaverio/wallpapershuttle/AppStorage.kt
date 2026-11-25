package com.zaverio.wallpapershuttle

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class WallpaperHistoryEntry(
    val uri: Uri,
    val time: Long
)

object AppStorage {

    private const val PREFS = "shuffle_wallpaper"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getExcluded(context: Context): Set<String> =
        prefs(context).getStringSet("excluded_uris", emptySet())
            ?.toSet() ?: emptySet()

    fun saveExcluded(context: Context, values: Set<String>) {
        prefs(context).edit()
            .putStringSet("excluded_uris", HashSet(values))
            .commit()
    }

    fun getHistory(context: Context): List<WallpaperHistoryEntry> {
        return try {
            val data = JSONArray(
                prefs(context).getString("wallpaper_history", "[]")
            )

            (0 until data.length()).mapNotNull { index ->
                val item = data.getJSONObject(index)
                val uri = item.optString("uri")

                if (uri.isBlank()) null
                else WallpaperHistoryEntry(
                    Uri.parse(uri),
                    item.optLong("time")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(context: Context, uri: Uri) {
        val history = getHistory(context)
            .filterNot { it.uri == uri }
            .toMutableList()

        history.add(
            0,
            WallpaperHistoryEntry(uri, System.currentTimeMillis())
        )

        val json = JSONArray()

        history.take(50).forEach {
            json.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("time", it.time)
            )
        }

        prefs(context).edit()
            .putString("wallpaper_history", json.toString())
            .commit()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit()
            .remove("wallpaper_history")
            .commit()
    }
}
