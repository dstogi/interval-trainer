package com.dstogi.intervaltrainer

class CardsStore {
}
package com.dstogi.intervaltrainer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "interval_trainer_store")

class CardsStore(context: Context) {

    private val appContext = context.applicationContext
    private val KEY_CARDS_JSON = stringPreferencesKey("cards_json_v1")

    val cardsFlow: Flow<List<IntervalCard>> =
        appContext.dataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                val json = prefs[KEY_CARDS_JSON]
                if (json.isNullOrBlank()) emptyList() else decodeCards(json)
            }

    suspend fun ensureSeeded(sample: IntervalCard) {
        appContext.dataStore.edit { prefs ->
            if (prefs[KEY_CARDS_JSON].isNullOrBlank()) {
                prefs[KEY_CARDS_JSON] = encodeCards(listOf(sample))
            }
        }
    }

    suspend fun upsert(card: IntervalCard) {
        appContext.dataStore.edit { prefs ->
            val existing = prefs[KEY_CARDS_JSON]?.let(::decodeCards) ?: emptyList()
            val mutable = existing.toMutableList()
            val idx = mutable.indexOfFirst { it.id == card.id }
            if (idx >= 0) mutable[idx] = card else mutable.add(0, card)
            prefs[KEY_CARDS_JSON] = encodeCards(mutable)
        }
    }

    suspend fun delete(cardId: String) {
        appContext.dataStore.edit { prefs ->
            val existing = prefs[KEY_CARDS_JSON]?.let(::decodeCards) ?: emptyList()
            val filtered = existing.filterNot { it.id == cardId }
            prefs[KEY_CARDS_JSON] = encodeCards(filtered)
        }
    }

    suspend fun clearAll() {
        appContext.dataStore.edit { prefs ->
            prefs.remove(KEY_CARDS_JSON)
        }
    }
}

// ---------- JSON (ohne extra Libs) ----------

private fun encodeCards(cards: List<IntervalCard>): String {
    val arr = JSONArray()
    for (c in cards) arr.put(cardToJson(c))
    return arr.toString()
}

private fun decodeCards(json: String): List<IntervalCard> {
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val card = cardFromJson(obj) ?: continue
                add(card)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun cardToJson(c: IntervalCard): JSONObject = JSONObject().apply {
    put("id", c.id)
    put("title", c.title)
    put("timing", timingToJson(c.timing))
    if (c.exercise == null) put("exercise", JSONObject.NULL) else put("exercise", exerciseToJson(c.exercise))
}

private fun cardFromJson(obj: JSONObject): IntervalCard? {
    val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: return null
    val title = obj.optString("title", "").takeIf { it.isNotBlank() } ?: return null

    val timingObj = obj.optJSONObject("timing") ?: return null
    val timing = timingFromJson(timingObj) ?: return null

    val exObj = obj.optJSONObject("exercise")
    val exercise = exObj?.let { exerciseFromJson(it) }

    return IntervalCard(
        id = id,
        title = title,
        timing = timing,
        exercise = exercise
    )
}

private fun timingToJson(t: TimingConfig): JSONObject = JSONObject().apply {
    put("warmupSec", t.warmupSec)
    put("workSec", t.workSec)
    put("restBetweenRepsSec", t.restBetweenRepsSec)
    put("repsPerSet", t.repsPerSet)
    put("restBetweenSetsSec", t.restBetweenSetsSec)
    put("sets", t.sets)
    put("cooldownSec", t.cooldownSec)
}

private fun timingFromJson(obj: JSONObject): TimingConfig? {
    val work = obj.optInt("workSec", -1)
    val sets = obj.optInt("sets", -1)
    val reps = obj.optInt("repsPerSet", 1)

    if (work <= 0 || sets <= 0 || reps <= 0) return null

    return TimingConfig(
        warmupSec = obj.optInt("warmupSec", 0).coerceAtLeast(0),
        workSec = work,
        restBetweenRepsSec = obj.optInt("restBetweenRepsSec", 0).coerceAtLeast(0),
        repsPerSet = reps,
        restBetweenSetsSec = obj.optInt("restBetweenSetsSec", 0).coerceAtLeast(0),
        sets = sets,
        cooldownSec = obj.optInt("cooldownSec", 0).coerceAtLeast(0)
    )
}

private fun exerciseToJson(ex: Exercise): JSONObject = JSONObject().apply {
    put("name", ex.name)
    put("notes", ex.notes)
}

private fun exerciseFromJson(obj: JSONObject): Exercise? {
    val name = obj.optString("name", "").takeIf { it.isNotBlank() } ?: return null
    val notes = obj.optString("notes", "")
    return Exercise(name = name, notes = notes)
}
