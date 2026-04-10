package ai.opencray.app.domain.model

data class SystemAction(
  val id: String,
  val title: String,
  val summary: String,
  val riskLevel: String,
  val requiresApproval: Boolean,
  val lastResult: String? = null,
)
