package ai.opencray.app.domain.model

data class AuditEntry(
  val id: String,
  val taskId: String,
  val actionId: String,
  val stage: String,
  val message: String,
  val timestampEpochMs: Long,
)
