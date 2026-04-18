package ai.opencray.app.runtime

import android.content.Context
import ai.opencray.app.agent.ActionPlanner
import ai.opencray.app.agent.TaskReplanner
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.CommonApp
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.execution.ActionExecutor
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.memory.MemoryManager
import ai.opencray.app.safety.SafetyAuditor
import java.util.UUID

class OpenCrayRuntime(
  appContext: Context,
  private val runtimeRepository: RuntimeRepository,
  private val chatRepository: ChatRepository,
) {
  private val actionPlanner = ActionPlanner()
  private val safetyAuditor = SafetyAuditor()
  private val memoryManager = MemoryManager()
  private val taskReplanner = TaskReplanner()
  private val actionExecutor = ActionExecutor(appContext.applicationContext)

  fun snapshot(): RuntimeSnapshot = runtimeRepository.getSnapshot()

  fun chatMessages(): List<ChatMessage> = chatRepository.getMessages()

  fun boot() {
    runtimeRepository.markRuntimeBooted()
    chatRepository.appendMessage(ChatRole.System, "Runtime booted. Agent pipeline ready.")
  }

  fun connectToGateway(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
  ) {
    runtimeRepository.updateConnectionConfig(host = host, port = port, tlsEnabled = tlsEnabled)
    val tlsLabel = if (tlsEnabled) "with TLS" else "without TLS"
    runtimeRepository.updateConnectionStatus("Connected to $host:$port $tlsLabel")
    runtimeRepository.appendEvent("Gateway paired to $host:$port ($tlsLabel).")
  }

  fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtimeRepository.setCapabilityEnabled(capabilityId, enabled)
    val stateLabel = if (enabled) "enabled" else "disabled"
    runtimeRepository.appendEvent("Capability $capabilityId $stateLabel.")
  }

  fun executeAction(actionId: String) {
    val current = snapshot()
    val action = current.systemActions.firstOrNull { it.id == actionId }
    if (action == null) {
      runtimeRepository.appendEvent("Action $actionId is not available.")
      return
    }

    val reviewedStatus =
      current.safetyRecords.firstOrNull { it.actionId == action.id }?.status
        ?: if (action.requiresApproval) "Awaiting approval" else "Auto-approved"

    if (reviewedStatus == "Awaiting approval") {
      val blockedRecord =
        SafetyRecord(
          id = UUID.randomUUID().toString(),
          title = "Execution blocked",
          detail = "Action ${action.title} is awaiting approval.",
          status = "Blocked",
          actionId = action.id,
        )
      runtimeRepository.replaceSnapshot(
        current.copy(
          safetyRecords = listOf(blockedRecord) + current.safetyRecords,
          recentEvents = listOf("Blocked execution for ${action.id} due to approval gate.") + current.recentEvents,
        ),
      )
      return
    }

    runActions(actionIds = setOf(actionId))
  }

  fun approvePendingActions() {
    val current = snapshot()
    val updatedSafety =
      current.safetyRecords.map { record ->
        if (record.status == "Awaiting approval") {
          record.copy(status = "Approved")
        } else {
          record
        }
      }

    val updatedActions =
      current.systemActions.map { action ->
        val record = updatedSafety.firstOrNull { it.actionId == action.id }
        if (record?.status == "Approved" && action.status == "pending_approval") {
          action.copy(status = "approved")
        } else {
          action
        }
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        safetyRecords = updatedSafety,
        systemActions = updatedActions,
        recentEvents = listOf("Pending actions approved.") + current.recentEvents,
      ),
    )

    runActions()
  }

  fun updateCommonApps(apps: List<CommonApp>) {
    runtimeRepository.updateCommonApps(apps)
  }

  fun noteAppLaunch(
    appLabel: String,
    succeeded: Boolean,
  ) {
    val result = if (succeeded) "opened" else "not installed or not launchable"
    runtimeRepository.updateConnectionStatus("App action: $appLabel $result")
    runtimeRepository.appendEvent("Common app launch requested: $appLabel -> $result")
  }

  fun planGoal(goal: String) {
    val normalizedGoal = goal.trim()
    if (normalizedGoal.isEmpty()) return

    chatRepository.sendMessage(normalizedGoal)

    val current = snapshot()
    val now = System.currentTimeMillis()
    val taskId = UUID.randomUUID().toString()

    val plannedActions = actionPlanner.plan(normalizedGoal, current)
    val safetyRecords = safetyAuditor.auditPlannedActions(plannedActions)

    val actionsWithReviewStatus =
      plannedActions.map { action ->
        val review = safetyRecords.firstOrNull { it.actionId == action.id }
        val status =
          when (review?.status) {
            "Awaiting approval" -> "pending_approval"
            "Auto-approved" -> "approved"
            else -> "planned"
          }
        action.copy(status = status)
      }

    val taskSummary =
      buildString {
        append("Goal planned with ${actionsWithReviewStatus.size} actions")
        if (safetyAuditor.hasPendingApproval(safetyRecords)) {
          append(", includes approval-required actions")
        }
      }

    val task =
      AgentTask(
        id = taskId,
        goal = normalizedGoal,
        status = "planned",
        attempt = 1,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        summary = taskSummary,
      )

    val memory = memoryManager.updateFromGoal(current.memoryRecords, normalizedGoal)

    val audit =
      actionsWithReviewStatus.map { action ->
        AuditEntry(
          id = UUID.randomUUID().toString(),
          taskId = taskId,
          actionId = action.id,
          stage = "plan",
          message = "Action planned with status ${action.status} and confidence ${action.confidence}.",
          timestampEpochMs = now,
        )
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        connectionStatus = "Plan ready",
        systemActions = actionsWithReviewStatus,
        safetyRecords = safetyRecords + current.safetyRecords,
        tasks = listOf(task) + current.tasks.take(9),
        memoryRecords = memory,
        auditTrail = audit + current.auditTrail.take(99),
        recentEvents = listOf("Goal planned: ${normalizedGoal.take(48)}") + current.recentEvents,
      ),
    )

    chatRepository.appendMessage(
      ChatRole.Assistant,
      "已完成任务规划：${actionsWithReviewStatus.size} 个动作，${safetyRecords.count { it.status == "Awaiting approval" }} 个需确认。",
    )
  }

  fun runActions(actionIds: Set<String>? = null) {
    val current = snapshot()
    val latestTask = current.tasks.firstOrNull()
    if (latestTask == null) {
      runtimeRepository.appendEvent("No active task to execute.")
      return
    }

    val targetActions =
      current.systemActions.filter { action ->
        val selected = actionIds?.contains(action.id) ?: true
        val runnable = action.status == "approved" || action.status == "planned"
        selected && runnable
      }

    if (targetActions.isEmpty()) {
      runtimeRepository.appendEvent("No executable actions found.")
      return
    }

    var updatedActions = current.systemActions
    var updatedTasks = current.tasks
    val updatedSafety = current.safetyRecords.toMutableList()
    val updatedAudit = current.auditTrail.toMutableList()
    val newEvents = mutableListOf<String>()

    targetActions.forEach { action ->
      val report = actionExecutor.execute(action, latestTask.goal)
      val now = System.currentTimeMillis()
      val nextStatus = if (report.success) "executed" else "failed"

      updatedActions =
        updatedActions.map { candidate ->
          if (candidate.id == action.id) {
            candidate.copy(status = nextStatus, lastResult = report.message)
          } else {
            candidate
          }
        }

      updatedAudit.add(
        0,
        AuditEntry(
          id = UUID.randomUUID().toString(),
          taskId = latestTask.id,
          actionId = action.id,
          stage = "execute",
          message = report.message,
          timestampEpochMs = now,
        ),
      )
      newEvents += "Action ${action.id}: ${if (report.success) "success" else "failed"}"

      if (!report.success && report.recoverable) {
        val fallbackActions = taskReplanner.replan(action)
        val fallbackSafety = safetyAuditor.auditPlannedActions(fallbackActions)
        updatedActions =
          updatedActions.map { existing ->
            if (existing.id == action.id) existing.copy(status = "failed") else existing
          } +
            fallbackActions.map { fallback ->
              val review = fallbackSafety.firstOrNull { it.actionId == fallback.id }
              fallback.copy(
                status = if (review?.status == "Awaiting approval") "pending_approval" else "approved",
              )
            }

        updatedSafety.addAll(0, fallbackSafety)

        fallbackActions.forEach { fallback ->
          updatedAudit.add(
            0,
            AuditEntry(
              id = UUID.randomUUID().toString(),
              taskId = latestTask.id,
              actionId = fallback.id,
              stage = "replan",
              message = "Fallback action created from failed ${action.id}.",
              timestampEpochMs = now,
            ),
          )
        }

        newEvents += "Replanned from ${action.id} with ${fallbackActions.size} fallback actions"
      }
    }

    val completed = updatedActions.none { it.status == "approved" || it.status == "planned" }
    updatedTasks =
      updatedTasks.map { task ->
        if (task.id == latestTask.id) {
          task.copy(
            status = if (completed) "completed" else "in_progress",
            updatedAtEpochMs = System.currentTimeMillis(),
            summary =
              if (completed) {
                "Task executed with ${updatedActions.count { it.status == "executed" }} successful actions."
              } else {
                "Task still has pending or approval-gated actions."
              },
          )
        } else {
          task
        }
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        connectionStatus = if (completed) "Task completed" else "Task in progress",
        systemActions = updatedActions,
        safetyRecords = updatedSafety.take(60),
        tasks = updatedTasks,
        auditTrail = updatedAudit.take(150),
        recentEvents = newEvents + current.recentEvents,
      ),
    )

    chatRepository.appendMessage(
      ChatRole.System,
      if (completed) {
        "任务执行完成，可在 Safety 面板查看审计轨迹。"
      } else {
        "任务部分完成，仍有待确认或待执行动作。"
      },
    )
  }

  fun clearChat() {
    chatRepository.clearMessages()
    runtimeRepository.appendEvent("Chat history cleared from prototype UI.")
  }
}
