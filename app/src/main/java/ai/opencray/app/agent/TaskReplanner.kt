package ai.opencray.app.agent

import ai.opencray.app.domain.model.SystemAction

class TaskReplanner {
  fun replan(failedAction: SystemAction): List<SystemAction> {
    return listOf(
      SystemAction(
        id = "open_context_review",
        title = "Open Context Review",
        summary = "Show parsed context and ask the user to adjust extracted entities.",
        riskLevel = "low",
        requiresApproval = false,
        confidence = 75,
        explain = "Execution failed; replan to verification-first path.",
      ),
    )
  }
}
