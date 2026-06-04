package ai.opencray.app.data.repository

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AgentCapability
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import java.util.UUID

class FakeRuntimeRepository(
  private val memoryStore: RuntimeMemoryStore? = null,
) : RuntimeRepository {
  private var snapshot =
    RuntimeSnapshot(
      appTitle = "OpenTHU",
      nodeName = "Cray Node Alpha",
      connectionStatus = "Bootstrapping",
      transportLabel = "Android system agent prototype",
      host = "10.0.2.2",
      port = 18789,
      tlsEnabled = false,
      featureFlags = listOf("context-aware", "system-intents", "safety-audit", "action-loop", "memory"),
      capabilities =
        listOf(
          AgentCapability(
            id = "notification_context",
            title = "Notification Context",
            description = "读取通知和近期事件，给系统级 agent 提供现场上下文。",
          ),
          AgentCapability(
            id = "safety_guard",
            title = "Safety Guard",
            description = "高风险动作确认、审计记录和可回放执行轨迹。",
          ),
        ),
      contextSignals =
        listOf(
          ContextSignal(
            id = "campus_news",
            title = "Tsinghua campus feed",
            detail = "Detected campus information source with updates about lectures and events.",
            source = "CampusCrawler",
          ),
          ContextSignal(
            id = "schedule_hint",
            title = "Course schedule import",
            detail = "Course schedule and assignment timeline are available for planning reminders.",
            source = "ScheduleSync",
          ),
          ContextSignal(
            id = "notif_1",
            title = "Notification summary",
            detail = "New school notification and one event reminder were observed.",
            source = "NotificationListener",
          ),
        ),
      systemActions =
        listOf(
          SystemAction(
            id = "get_campus_activities",
            title = "Get Campus Activities",
            summary = "Fetch campus activity information and official source links.",
            riskLevel = "low",
            requiresApproval = false,
            confidence = 90,
            explain = "Seed action for campus info query.",
          ),
          SystemAction(
            id = "create_calendar_event",
            title = "Create Calendar Event",
            summary = "Create schedule item from parsed campus event/course info.",
            riskLevel = "medium",
            requiresApproval = true,
            params =
              mapOf(
                "title" to "OpenTHU campus task",
                "description" to "Seed event from prototype",
              ),
            confidence = 80,
            explain = "Seed action for schedule setup.",
          ),
          SystemAction(
            id = "set_alarm_reminder",
            title = "Set Alarm Reminder",
            summary = "Set alarm and reminder for important campus task.",
            riskLevel = "medium",
            requiresApproval = false,
            confidence = 78,
            explain = "Seed action for deadline follow-up.",
          ),
        ),
      safetyRecords =
        listOf(
          SafetyRecord(
            id = "safety_boot_1",
            title = "Safety guard online",
            detail = "Policy engine active. High-risk actions will require approval.",
            status = "Active",
          ),
        ),
      tasks = emptyList(),
      memoryRecords = initialMemoryRecords(),
      auditTrail = emptyList(),
      recentEvents =
        listOf(
          "System-agent scaffold prepared from openclaw-inspired runtime layout.",
          "Campus context feeds initialized.",
          "Action center waiting for user goal.",
        ),
    )

  override fun getSnapshot(): RuntimeSnapshot = snapshot

  override fun replaceSnapshot(snapshot: RuntimeSnapshot) {
    updateSnapshot(snapshot)
  }

  override fun markRuntimeBooted() {
    snapshot =
      snapshot.copy(
        connectionStatus = "Ready",
        recentEvents =
          listOf(
            "Application boot complete.",
            "Runtime coordinator attached.",
          ) + snapshot.recentEvents,
      )
  }

  override fun updateConnectionStatus(status: String) {
    snapshot = snapshot.copy(connectionStatus = status)
  }

  override fun updateConnectionConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
  ) {
    snapshot =
      snapshot.copy(
        host = host,
        port = port,
        tlsEnabled = tlsEnabled,
        transportLabel = if (tlsEnabled) "Secure agent link $host:$port" else "Agent link $host:$port",
      )
  }

  override fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  ) {
    snapshot =
      snapshot.copy(
        capabilities =
          snapshot.capabilities.map { capability ->
            if (capability.id == capabilityId) capability.copy(enabled = enabled) else capability
          },
      )
  }

  override fun markActionExecuted(actionId: String) {
    snapshot =
      snapshot.copy(
        systemActions =
          snapshot.systemActions.map { action ->
            if (action.id == actionId) {
              action.copy(
                status = "executed",
                lastResult = "Executed at runtime prototype",
              )
            } else {
              action
            }
          },
        safetyRecords =
          listOf(
            SafetyRecord(
              id = "log_${actionId}",
              title = "Action executed",
              detail = "Action $actionId ran through the Android system-agent prototype.",
              status = "Logged",
              actionId = actionId,
            ),
          ) + snapshot.safetyRecords,
      )
  }

  override fun approvePendingSafety() {
    snapshot =
      snapshot.copy(
        safetyRecords =
          snapshot.safetyRecords.map { record ->
            if (record.status == "Awaiting approval") {
              record.copy(status = "Approved")
            } else {
              record
            }
          },
      )
  }

  override fun appendEvent(event: String) {
    snapshot = snapshot.copy(recentEvents = listOf(event) + snapshot.recentEvents)
  }

  private fun updateSnapshot(next: RuntimeSnapshot) {
    val memoryChanged = snapshot.memoryRecords != next.memoryRecords
    snapshot = next
    if (memoryChanged) {
      memoryStore?.save(next.memoryRecords)
    }
  }

  private fun initialMemoryRecords(): List<MemoryRecord> =
    memoryStore
      ?.load()
      ?: listOf(
        MemoryRecord(
          id = UUID.randomUUID().toString(),
          scope = "long",
          key = "default_preference",
          value = "prefer_calendar_and_alarm",
          weight = 60,
          updatedAtEpochMs = System.currentTimeMillis(),
        ),
      )
}
