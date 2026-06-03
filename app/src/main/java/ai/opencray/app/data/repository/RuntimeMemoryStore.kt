package ai.opencray.app.data.repository

import android.content.Context
import ai.opencray.app.domain.model.MemoryRecord
import org.json.JSONArray
import org.json.JSONObject

interface RuntimeMemoryStore {
  fun load(): List<MemoryRecord>

  fun save(records: List<MemoryRecord>)
}

class SharedPreferencesRuntimeMemoryStore(
  context: Context,
) : RuntimeMemoryStore {
  private val preferences =
    context.applicationContext.getSharedPreferences("openthu_runtime_memory", Context.MODE_PRIVATE)

  override fun load(): List<MemoryRecord> {
    val raw = preferences.getString(KEY_MEMORY_RECORDS, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      val records = mutableListOf<MemoryRecord>()
      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val id = item.optString("id").trim()
        val value = item.optString("value").trim()
        if (id.isBlank() || value.isBlank()) continue
        records +=
          MemoryRecord(
            id = id,
            scope = item.optString("scope").trim(),
            key = item.optString("key").trim(),
            value = value,
            weight = item.optInt("weight", 0),
            updatedAtEpochMs = item.optLong("updated_at_epoch_ms", item.optLong("updatedAtEpochMs", 0L)),
          )
      }
      records
    }.getOrElse { emptyList() }
  }

  override fun save(records: List<MemoryRecord>) {
    val arr =
      JSONArray(
        records.map { record ->
          JSONObject()
            .put("id", record.id)
            .put("scope", record.scope)
            .put("key", record.key)
            .put("value", record.value)
            .put("weight", record.weight)
            .put("updated_at_epoch_ms", record.updatedAtEpochMs)
        },
      )
    preferences.edit().putString(KEY_MEMORY_RECORDS, arr.toString()).apply()
  }

  private companion object {
    private const val KEY_MEMORY_RECORDS = "memory_records"
  }
}
