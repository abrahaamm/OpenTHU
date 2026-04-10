package ai.opencray.app.domain.model

enum class AppDestination(
  val label: String,
) {
  Context(label = "Context"),
  Actions(label = "Actions"),
  Safety(label = "Safety"),
}
