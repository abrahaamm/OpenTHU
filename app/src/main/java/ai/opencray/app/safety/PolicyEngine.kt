package ai.opencray.app.safety

import ai.opencray.app.domain.model.SystemAction

data class PolicyDecision(
  val status: String,
  val reason: String,
)

class PolicyEngine {
  fun review(action: SystemAction): PolicyDecision {
    if (action.riskLevel == "high" || action.requiresApproval) {
      return PolicyDecision(
        status = "Awaiting approval",
        reason = "High-risk action requires explicit user confirmation.",
      )
    }

    return PolicyDecision(
      status = "Auto-approved",
      reason = "Risk level is acceptable for automatic execution.",
    )
  }
}
