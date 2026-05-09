package ai.opencray.app.runtime

import android.content.Context
import android.provider.Settings
import ai.opencray.app.agent.ActionPlanner
import ai.opencray.app.agent.TaskReplanner
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.execution.ActionExecutor
import ai.opencray.app.execution.ActionExecutionReport
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.gateway.AgentCoreHttpClient
import ai.opencray.app.gateway.DispatchSkillInvocation
import ai.opencray.app.gateway.GatewayConfig
import ai.opencray.app.gateway.PlanTaskData
import ai.opencray.app.gateway.PlannedSkillInvocation
import ai.opencray.app.memory.MemoryManager
import ai.opencray.app.safety.SafetyAuditor
import kotlin.concurrent.thread
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
  private val gatewayClient = AgentCoreHttpClient()
  private val deviceId =
    Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
      ?.takeIf { it.isNotBlank() }
      ?.let { "android-$it" }
      ?: "android-${UUID.randomUUID().toString().take(12)}"

  @Volatile private var gatewayRegistered = false
  @Volatile private var dispatchLoopRunning = false

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
    runtimeRepository.updateConnectionStatus("Connecting to $host:$port ...")
    runtimeRepository.appendEvent("Gateway pairing started: $host:$port")

    thread(name = "opencray-gateway-register", isDaemon = true) {
      val result =
        gatewayClient.registerDevice(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          capabilities = supportedGatewayCapabilities(),
        )
      if (result.success) {
        gatewayRegistered = true
        runtimeRepository.updateConnectionStatus("Connected to $host:$port")
        runtimeRepository.appendEvent("Gateway registered device_id=$deviceId")
        chatRepository.appendMessage(ChatRole.System, "Agent-Core 连接成功，后续将走服务端任务分发。")
      } else {
        gatewayRegistered = false
        runtimeRepository.updateConnectionStatus("Gateway connection failed")
        runtimeRepository.appendEvent("Gateway register failed: ${result.code} ${result.message}")
        chatRepository.appendMessage(
          ChatRole.System,
          "Agent-Core 连接失败（${result.code}），将继续使用本地链路。",
        )
      }
    }
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

  fun planGoal(goal: String) {
    val normalizedGoal = goal.trim()
    if (normalizedGoal.isEmpty()) return

    chatRepository.sendMessage(normalizedGoal)

    if (gatewayRegistered) {
      planGoalViaGateway(normalizedGoal)
    } else {
      planGoalLocally(normalizedGoal)
    }
  }

  fun runActions(actionIds: Set<String>? = null) {
    if (actionIds == null && gatewayRegistered) {
      runGatewayDispatchLoop()
      return
    }
    runActionsLocally(actionIds = actionIds, submitGatewayResult = gatewayRegistered)
  }

  fun clearChat() {
    chatRepository.clearMessages()
    runtimeRepository.appendEvent("Chat history cleared from prototype UI.")
  }

  private fun supportedGatewayCapabilities(): List<String> =
    listOf(
      "get_current_time",
      "set_alarm",
      "get_campus_activities",
      "create_calendar_event",
      "detect_calendar_conflicts",
      "delete_calendar_event",
      "open_url",
    )

  private fun currentGatewayConfig(): GatewayConfig {
    val current = snapshot()
    return GatewayConfig(
      host = current.host,
      port = current.port,
      tlsEnabled = current.tlsEnabled,
    )
  }

  private fun planGoalViaGateway(goal: String) {
    runtimeRepository.updateConnectionStatus("Planning on Agent-Core server ...")
    thread(name = "opencray-gateway-plan", isDaemon = true) {
      val result =
        gatewayClient.planTask(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          goal = goal,
          approveSensitive = true,
        )
      if (!result.success || result.data == null) {
        gatewayRegistered = false
        runtimeRepository.updateConnectionStatus("Gateway planning failed")
        runtimeRepository.appendEvent("Gateway plan failed: ${result.code} ${result.message}")
        chatRepository.appendMessage(ChatRole.System, "服务端规划失败，回落到本地规划。")
        planGoalLocally(goal)
        return@thread
      }
      applyGatewayPlan(goal = goal, data = result.data)
    }
  }

  private fun applyGatewayPlan(
    goal: String,
    data: PlanTaskData,
  ) {
    val now = System.currentTimeMillis()
    val current = snapshot()
    val approvedActions = data.approvedSkills.map { it.toSystemAction(status = "approved") }
    val blockedActions = data.blockedSkills.map { it.toSystemAction(status = "pending_approval") }
    val allActions = (approvedActions + blockedActions).ifEmpty { current.systemActions }
    val safetyRecords =
      buildList {
        addAll(approvedActions.map { it.toSafetyRecord(status = "Approved") })
        addAll(blockedActions.map { it.toSafetyRecord(status = "Awaiting approval") })
      }
    val taskStatus =
      when (data.taskStatus) {
        "ready_for_device_execution" -> "planned"
        "approval_required" -> "planned"
        else -> data.taskStatus
      }
    val task =
      AgentTask(
        id = if (data.taskId.isBlank()) UUID.randomUUID().toString() else data.taskId,
        goal = goal,
        status = taskStatus,
        attempt = 1,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        summary = "Gateway planned ${approvedActions.size} approved, ${blockedActions.size} blocked actions.",
      )
    val memory = memoryManager.updateFromGoal(current.memoryRecords, goal)
    val audit =
      allActions.map { action ->
        AuditEntry(
          id = UUID.randomUUID().toString(),
          taskId = task.id,
          actionId = action.id,
          stage = "plan_remote",
          message = "Gateway planned action ${action.id} (${action.status}).",
          timestampEpochMs = now,
        )
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        connectionStatus = "Server plan ready",
        systemActions = allActions,
        safetyRecords = safetyRecords + current.safetyRecords,
        tasks = listOf(task) + current.tasks.filterNot { it.id == task.id }.take(9),
        memoryRecords = memory,
        auditTrail = audit + current.auditTrail.take(99),
        recentEvents = listOf("Gateway planned task ${task.id}.") + current.recentEvents,
      ),
    )
    chatRepository.appendMessage(
      ChatRole.Assistant,
      "服务端规划完成：${approvedActions.size} 个可执行动作，${blockedActions.size} 个被拦截动作。",
    )
  }

  private fun planGoalLocally(goal: String) {
    val current = snapshot()
    val now = System.currentTimeMillis()
    val taskId = UUID.randomUUID().toString()

    val plannedActions = actionPlanner.plan(goal, current)
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
        goal = goal,
        status = "planned",
        attempt = 1,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        summary = taskSummary,
      )

    val memory = memoryManager.updateFromGoal(current.memoryRecords, goal)

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
        recentEvents = listOf("Goal planned: ${goal.take(48)}") + current.recentEvents,
      ),
    )

    chatRepository.appendMessage(
      ChatRole.Assistant,
      "已完成任务规划：${actionsWithReviewStatus.size} 个动作，${safetyRecords.count { it.status == "Awaiting approval" }} 个需确认。",
    )
  }

  private fun runGatewayDispatchLoop() {
    if (dispatchLoopRunning) {
      runtimeRepository.appendEvent("Gateway dispatch loop is already running.")
      return
    }
    dispatchLoopRunning = true
    runtimeRepository.updateConnectionStatus("Polling Agent-Core queue ...")

    thread(name = "opencray-gateway-dispatch", isDaemon = true) {
      try {
        var dispatchCount = 0
        var iteration = 0
        var stop = false
        while (iteration < 20 && !stop) {
          iteration += 1
          val next = gatewayClient.pullNext(config = currentGatewayConfig(), deviceId = deviceId)
          if (!next.success) {
            runtimeRepository.appendEvent("Gateway pull failed: ${next.code} ${next.message}")
            runtimeRepository.updateConnectionStatus("Gateway pull failed")
            stop = true
            continue
          }
          if (next.code == "NO_TASK" || next.data == null) {
            stop = true
            continue
          }

          val dispatch = next.data
          dispatchCount += 1
          executeDispatchedSkill(dispatch)
        }
        runtimeRepository.updateConnectionStatus("Gateway queue idle")
        runtimeRepository.appendEvent("Gateway dispatch loop completed, executed $dispatchCount action(s).")
      } finally {
        dispatchLoopRunning = false
      }
    }
  }

  private fun executeDispatchedSkill(dispatch: DispatchSkillInvocation) {
    val now = System.currentTimeMillis()
    val before = snapshot()
    val task =
      before.tasks.firstOrNull { it.id == dispatch.taskId }
        ?: AgentTask(
          id = dispatch.taskId.ifBlank { UUID.randomUUID().toString() },
          goal = "Server dispatched task",
          status = "in_progress",
          attempt = 1,
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
          summary = "Task received from Agent-Core queue.",
        )
    val action = dispatch.toSystemAction(status = "approved")

    runtimeRepository.replaceSnapshot(
      before.copy(
        tasks = listOf(task) + before.tasks.filterNot { it.id == task.id }.take(9),
        systemActions = upsertAction(before.systemActions, action),
        recentEvents = listOf("Dispatched ${action.id} (${dispatch.requestId}).") + before.recentEvents,
      ),
    )

    val goal = snapshot().tasks.firstOrNull { it.id == task.id }?.goal ?: "Server dispatched task"
    val report = actionExecutor.execute(action, goal)
    applyExecutionReport(task = task, action = action, report = report, submitGatewayResult = true)
  }

  private fun runActionsLocally(
    actionIds: Set<String>? = null,
    submitGatewayResult: Boolean,
  ) {
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

    targetActions.forEach { action ->
      val report = actionExecutor.execute(action, latestTask.goal)
      applyExecutionReport(task = latestTask, action = action, report = report, submitGatewayResult = submitGatewayResult)

      if (!report.success && report.recoverable) {
        val fallbackActions = taskReplanner.replan(action)
        val fallbackSafety = safetyAuditor.auditPlannedActions(fallbackActions)
        val after = snapshot()
        val replanned =
          fallbackActions.map { fallback ->
            val review = fallbackSafety.firstOrNull { it.actionId == fallback.id }
            fallback.copy(
              status = if (review?.status == "Awaiting approval") "pending_approval" else "approved",
            )
          }
        val replanAudit =
          replanned.map { fallback ->
            AuditEntry(
              id = UUID.randomUUID().toString(),
              taskId = latestTask.id,
              actionId = fallback.id,
              stage = "replan",
              message = "Fallback action created from failed ${action.id}.",
              timestampEpochMs = System.currentTimeMillis(),
            )
          }
        runtimeRepository.replaceSnapshot(
          after.copy(
            systemActions = after.systemActions + replanned,
            safetyRecords = fallbackSafety + after.safetyRecords,
            auditTrail = replanAudit + after.auditTrail,
            recentEvents = listOf("Replanned from ${action.id} with ${replanned.size} action(s).") + after.recentEvents,
          ),
        )
      }
    }

    val after = snapshot()
    val completed = after.systemActions.none { it.status == "approved" || it.status == "planned" }
    val updatedTasks =
      after.tasks.map { task ->
        if (task.id == latestTask.id) {
          task.copy(
            status = if (completed) "completed" else "in_progress",
            updatedAtEpochMs = System.currentTimeMillis(),
            summary =
              if (completed) {
                "Task executed with ${after.systemActions.count { it.status == "executed" }} successful actions."
              } else {
                "Task still has pending or approval-gated actions."
              },
          )
        } else {
          task
        }
      }
    runtimeRepository.replaceSnapshot(
      after.copy(
        connectionStatus = if (completed) "Task completed" else "Task in progress",
        tasks = updatedTasks,
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

  private fun applyExecutionReport(
    task: AgentTask,
    action: SystemAction,
    report: ActionExecutionReport,
    submitGatewayResult: Boolean,
  ) {
    val now = System.currentTimeMillis()
    val nextStatus = if (report.success) "executed" else "failed"
    val current = snapshot()
    val updatedActions =
      current.systemActions.map { candidate ->
        if (isSameAction(candidate, action)) {
          candidate.copy(status = nextStatus, lastResult = report.message)
        } else {
          candidate
        }
      }
    val audit =
      AuditEntry(
        id = UUID.randomUUID().toString(),
        taskId = task.id,
        actionId = action.id,
        stage = "execute",
        message = report.message,
        timestampEpochMs = now,
      )

    runtimeRepository.replaceSnapshot(
      current.copy(
        systemActions = updatedActions,
        auditTrail = listOf(audit) + current.auditTrail.take(149),
        recentEvents = listOf("Action ${action.id}: ${if (report.success) "success" else "failed"}") + current.recentEvents,
      ),
    )

    if (submitGatewayResult && !action.requestId.isNullOrBlank()) {
      submitGatewayResultAsync(taskId = task.id, action = action, report = report)
    }
  }

  private fun submitGatewayResultAsync(
    taskId: String,
    action: SystemAction,
    report: ActionExecutionReport,
  ) {
    val requestId = action.requestId ?: return
    thread(name = "opencray-gateway-submit", isDaemon = true) {
      val code = if (report.success) "OK" else "SKILL_EXECUTION_FAILED"
      val result =
        gatewayClient.submitResult(
          config = currentGatewayConfig(),
          taskId = taskId,
          deviceId = deviceId,
          requestId = requestId,
          skillName = action.id,
          code = code,
          message = report.message,
          data =
            mapOf(
              "status" to if (report.success) "executed" else "failed",
              "recoverable" to report.recoverable,
              "semantic" to report.semantic,
              "metadata" to report.metadata,
              "action_id" to action.id,
            ),
        )
      val current = snapshot()
      if (result.success) {
        val taskStatus = result.data?.taskStatus.orEmpty()
        val updatedTasks =
          if (taskStatus.isBlank()) {
            current.tasks
          } else {
            current.tasks.map { task ->
              if (task.id == taskId) {
                task.copy(status = taskStatus, updatedAtEpochMs = System.currentTimeMillis())
              } else {
                task
              }
            }
          }
        runtimeRepository.replaceSnapshot(
          current.copy(
            tasks = updatedTasks,
            recentEvents = listOf("Result submitted for ${action.id} ($requestId).") + current.recentEvents,
          ),
        )
      } else {
        runtimeRepository.replaceSnapshot(
          current.copy(
            recentEvents = listOf("Result submit failed for ${action.id}: ${result.code}") + current.recentEvents,
          ),
        )
      }
    }
  }

  private fun upsertAction(
    existing: List<SystemAction>,
    incoming: SystemAction,
  ): List<SystemAction> {
    val index =
      existing.indexOfFirst { candidate ->
        isSameAction(candidate, incoming)
      }
    return if (index >= 0) {
      existing.mapIndexed { idx, item -> if (idx == index) incoming else item }
    } else {
      listOf(incoming) + existing
    }
  }

  private fun isSameAction(
    left: SystemAction,
    right: SystemAction,
  ): Boolean {
    val leftReq = left.requestId
    val rightReq = right.requestId
    return if (!leftReq.isNullOrBlank() && !rightReq.isNullOrBlank()) {
      leftReq == rightReq
    } else {
      left.id == right.id
    }
  }
}

private fun PlannedSkillInvocation.toSystemAction(status: String): SystemAction {
  val paramMap = args.entries.associate { it.key to (it.value?.toString() ?: "") }
  val title = if (description.isNotBlank()) description else skillName
  return SystemAction(
    id = skillName,
    requestId = requestId.ifBlank { null },
    title = title,
    summary = "Gateway planned skill: $skillName",
    riskLevel = riskLevel,
    requiresApproval = requiresApproval,
    params = paramMap,
    confidence = 86,
    explain = "Generated by Agent-Core server planning.",
    status = status,
    payload = args,
  )
}

private fun DispatchSkillInvocation.toSystemAction(status: String): SystemAction {
  val paramMap = args.entries.associate { it.key to (it.value?.toString() ?: "") }
  val title = if (description.isNotBlank()) description else skillName
  return SystemAction(
    id = skillName,
    requestId = requestId.ifBlank { null },
    title = title,
    summary = "Gateway dispatched skill: $skillName",
    riskLevel = riskLevel,
    requiresApproval = requiresApproval,
    params = paramMap,
    confidence = 90,
    explain = "Pulled from Agent-Core queue.",
    status = status,
    payload = args,
  )
}

private fun SystemAction.toSafetyRecord(status: String): SafetyRecord =
  SafetyRecord(
    id = UUID.randomUUID().toString(),
    title = "Safety review: $id",
    detail = "Risk=$riskLevel, requiresApproval=$requiresApproval, decision=$status",
    status = status,
    actionId = id,
  )
