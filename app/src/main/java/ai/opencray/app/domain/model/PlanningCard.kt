package ai.opencray.app.domain.model

data class PlanningCard(
  val id: String,
  val title: String,
  val body: String,
  val type: String,
  val source: String,
  val status: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val actionId: String? = null,
  val taskId: String? = null,
  val metadata: Map<String, String> = emptyMap(),
)
