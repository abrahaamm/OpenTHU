package ai.opencray.app.data.repository

import ai.opencray.app.domain.model.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRuntimeRepositoryTest {
  @Test
  fun replaceSnapshotPersistsMemoryRecordsToStore() {
    val store = InMemoryRuntimeMemoryStore()
    val repository = FakeRuntimeRepository(memoryStore = store)
    val record =
      MemoryRecord(
        id = "pref-1",
        scope = "long",
        key = "manual_preference",
        value = "少推荐晚间活动",
        weight = 90,
        updatedAtEpochMs = 1234L,
      )

    repository.replaceSnapshot(repository.getSnapshot().copy(memoryRecords = listOf(record)))

    val restored = FakeRuntimeRepository(memoryStore = store)
    assertEquals(listOf(record), restored.getSnapshot().memoryRecords)
  }

  @Test
  fun emptyStoreFallsBackToDefaultSeedMemory() {
    val repository = FakeRuntimeRepository(memoryStore = InMemoryRuntimeMemoryStore())

    assertTrue(repository.getSnapshot().memoryRecords.any { it.key == "default_preference" })
  }

  private class InMemoryRuntimeMemoryStore : RuntimeMemoryStore {
    private var records: List<MemoryRecord> = emptyList()

    override fun load(): List<MemoryRecord> = records

    override fun save(records: List<MemoryRecord>) {
      this.records = records
    }
  }
}
