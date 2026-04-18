package ai.opencray.app.agent

import ai.opencray.app.domain.model.SystemAction

class TaskReplanner {
  fun replan(failedAction: SystemAction): List<SystemAction> {
    if (failedAction.id == "cross_app_sensitive") {
      return listOf(
        SystemAction(
          id = "manual_confirm_flow",
          title = "Manual Confirmation Flow",
          summary = "Fallback to user-guided manual step-by-step flow for sensitive operation.",
          riskLevel = "medium",
          requiresApproval = true,
          confidence = 70,
          explain = "Sensitive action failed; switch to safer human-in-the-loop plan.",
        ),
      )
    }

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
