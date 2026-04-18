package ai.opencray.app.domain.model

data class AgentTask(
  val id: String,
  val goal: String,
  val status: String,
  val attempt: Int,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val summary: String,
)
