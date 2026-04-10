package ai.opencray.app.domain.model

data class CommonApp(
  val id: String,
  val label: String,
  val packageName: String,
  val installed: Boolean = false,
)
