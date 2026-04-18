package ai.opencray.app.domain.model

data class SystemAction(
  val id: String,
  val title: String,
  val summary: String,
  val riskLevel: String,
  val requiresApproval: Boolean,
  val confidence: Int = 60,
  val explain: String = "",
  val status: String = "planned",
  val lastResult: String? = null,
)
