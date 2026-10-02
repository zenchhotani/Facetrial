package com.zen.facetrial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves names + face embeddings on the phone only (private app storage). */
class FaceStore(context: Context) {

    data class Match(val name: String?, val score: Float)

    private val prefs = context.getSharedPreferences("faces", Context.MODE_PRIVATE)
    private val entries = mutableListOf<Pair<String, FloatArray>>()

    init {
        load()
    }

    @Synchronized
    fun add(name: String, embedding: FloatArray) {
        entries.add(name to embedding)
        save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        prefs.edit().clear().apply()
    }

    @Synchronized
    fun names(): List<String> = entries.map { it.first }.distinct()

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Best cosine similarity against everything saved (embeddings are L2-normalised). */
    @Synchronized
    fun match(embedding: FloatArray, threshold: Float): Match {
        var bestName: String? = null
        var best = -1f
        for ((name, saved) in entries) {
            if (saved.size != embedding.size) continue
            var dot = 0f
            for (i in saved.indices) dot += saved[i] * embedding[i]
            if (dot > best) {
                best = dot
                bestName = name
            }
        }
        return Match(if (best >= threshold) bestName else null, best)
    }

    private fun save() {
        val arr = JSONArray()
        for ((name, emb) in entries) {
            val values = JSONArray()
            emb.forEach { values.put(it.toDouble()) }
            arr.put(JSONObject().put("n", name).put("e", values))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("entries", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val values = o.getJSONArray("e")
                val emb = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                entries.add(o.getString("n") to emb)
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }
}
