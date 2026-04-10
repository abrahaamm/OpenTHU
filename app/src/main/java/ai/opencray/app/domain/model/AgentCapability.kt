package ai.opencray.app.domain.model

data class AgentCapability(
  val id: String,
  val title: String,
  val description: String,
  val enabled: Boolean = true,
)
