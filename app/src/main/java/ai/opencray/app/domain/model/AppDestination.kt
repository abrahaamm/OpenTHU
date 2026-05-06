package ai.opencray.app.domain.model

enum class AppDestination(
  val label: String,
) {
  Chat(label = "Chat"),
  Context(label = "Context"),
  Actions(label = "Actions"),
  Safety(label = "Safety"),
}
