package ai.opencray.app.safety

import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import java.util.UUID

class SafetyAuditor(
  private val policyEngine: PolicyEngine = PolicyEngine(),
) {
  fun auditPlannedActions(actions: List<SystemAction>): List<SafetyRecord> {
    val now = System.currentTimeMillis()
    return actions.map { action ->
      val decision = policyEngine.review(action)
      SafetyRecord(
        id = UUID.randomUUID().toString(),
        title = "Policy Review: ${action.title}",
        detail = decision.reason,
        status = decision.status,
        actionId = action.id,
        timestampEpochMs = now,
      )
    }
  }

  fun hasPendingApproval(records: List<SafetyRecord>): Boolean =
    records.any { it.status == "Awaiting approval" }
}
