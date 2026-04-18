package ai.opencray.app.domain.model

data class MemoryRecord(
  val id: String,
  val scope: String,
  val key: String,
  val value: String,
  val weight: Int,
  val updatedAtEpochMs: Long,
)
