package ai.opencray.app.safety

import ai.opencray.app.data.model.RuntimeSnapshot

class SafetyAuditor {
  fun hasPendingApproval(snapshot: RuntimeSnapshot): Boolean =
    snapshot.safetyRecords.any { it.status == "Awaiting approval" }
}
