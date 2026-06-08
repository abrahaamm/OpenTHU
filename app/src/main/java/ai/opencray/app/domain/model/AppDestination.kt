package ai.opencray.app.domain.model

enum class AppDestination(
  val label: String,
) {
  Chat(label = "Chat"),
  Planning(label = "Planning"),
  Settings(label = "Settings"),
}
