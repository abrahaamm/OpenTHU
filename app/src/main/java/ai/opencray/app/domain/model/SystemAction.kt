package ai.opencray.app.domain.model

data class SystemAction(
  val id: String,
  val requestId: String? = null,
  val title: String,
  val summary: String,
  val riskLevel: String,
  val requiresApproval: Boolean,
  val params: Map<String, String> = emptyMap(),
  val confidence: Int = 60,
  val explain: String = "",
  val status: String = "planned",
  val lastResult: String? = null,
  val payload: Map<String, Any?>? = null, // Used for JSON-driven args from Agent
)
