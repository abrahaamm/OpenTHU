package ai.opencray.app.data.model

import ai.opencray.app.domain.model.AgentCapability
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PendingConflictResolution
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction

data class RuntimeSnapshot(
  val appTitle: String,
  val nodeName: String,
  val connectionStatus: String,
  val transportLabel: String,
  val host: String,
  val port: Int,
  val tlsEnabled: Boolean,
  val featureFlags: List<String>,
  val capabilities: List<AgentCapability>,
  val contextSignals: List<ContextSignal>,
  val systemActions: List<SystemAction>,
  val planningCards: List<PlanningCard> = emptyList(),
  val dismissedPlanningCardIds: Set<String> = emptySet(),
  val safetyRecords: List<SafetyRecord>,
  val tasks: List<AgentTask>,
  val memoryRecords: List<MemoryRecord>,
  val auditTrail: List<AuditEntry>,
  val recentEvents: List<String>,
  val pendingConflict: PendingConflictResolution? = null,
)
