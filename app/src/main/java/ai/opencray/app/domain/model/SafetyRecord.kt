package ai.opencray.app.domain.model

data class SafetyRecord(
  val id: String,
  val title: String,
  val detail: String,
  val status: String,
  val actionId: String? = null,
  val timestampEpochMs: Long = System.currentTimeMillis(),
)
