package ai.opencray.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PendingConflictResolution
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.runtime.CalendarPermissionDelegate
import ai.opencray.app.runtime.OpenCrayRuntime

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val runtime: OpenCrayRuntime =
    (app as OpenCrayApplication).appContainer.runtime

  private var selectedDestination: AppDestination = AppDestination.Context
  private var goalDraft: String = ""
  private var hostText: String = runtime.snapshot().host
  private var portText: String = runtime.snapshot().port.toString()
  private var tlsEnabled: Boolean = runtime.snapshot().tlsEnabled

  private fun buildUiState(): MainUiState {
    val snapshot = runtime.snapshot()
    return MainUiState(
      currentDestination = selectedDestination,
      goalDraft = goalDraft,
      host = hostText,
      port = portText,
      tlsEnabled = tlsEnabled,
      snapshot = snapshot,
      contextSignals = snapshot.contextSignals,
      systemActions = snapshot.systemActions,
      safetyRecords = snapshot.safetyRecords,
      tasks = snapshot.tasks,
      memoryRecords = snapshot.memoryRecords,
      auditTrail = snapshot.auditTrail,
      chatMessages = runtime.chatMessages(),
      pendingConflict = snapshot.pendingConflict,
    )
  }

  init {
    runtime.boot()
  }

  fun getUiState(): MainUiState = buildUiState()

  fun selectDestination(destination: AppDestination) {
    selectedDestination = destination
  }

  fun updateGoalDraft(value: String) {
    goalDraft = value
  }

  fun updateHost(value: String) {
    hostText = value
  }

  fun updatePort(value: String) {
    portText = value
  }

  fun updateTlsEnabled(enabled: Boolean) {
    tlsEnabled = enabled
  }

  fun connectToGateway() {
    val host = hostText.trim().ifEmpty { "10.0.2.2" }
    val port = portText.toIntOrNull() ?: 18789
    runtime.connectToGateway(host = host, port = port, tlsEnabled = tlsEnabled)
    selectedDestination = AppDestination.Actions
  }

  fun toggleCapability(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtime.setCapabilityEnabled(capabilityId = capabilityId, enabled = enabled)
  }

  fun submitGoal() {
    val text = goalDraft.trim()
    if (text.isEmpty()) return
    goalDraft = ""
    runtime.planGoal(text)
    selectedDestination = AppDestination.Actions
  }

  fun runAgentPlan() {
    runtime.runActions()
    selectedDestination = AppDestination.Safety
  }

  fun clearChat() {
    runtime.clearChat()
  }

  fun executeAction(actionId: String) {
    runtime.executeAction(actionId)
    selectedDestination = AppDestination.Actions
  }

  fun approvePendingActions() {
    runtime.approvePendingActions()
    selectedDestination = AppDestination.Safety
  }

  fun setCalendarPermissionDelegate(delegate: CalendarPermissionDelegate?) {
    runtime.setCalendarPermissionDelegate(delegate)
  }

  fun notifyCalendarPermissionGranted() {
    runtime.notifyCalendarPermissionGranted()
  }

  fun resolveConflict(strategy: String) {
    runtime.resolveConflict(strategy)
  }
}

data class MainUiState(
  val currentDestination: AppDestination,
  val goalDraft: String,
  val host: String,
  val port: String,
  val tlsEnabled: Boolean,
  val snapshot: RuntimeSnapshot,
  val contextSignals: List<ContextSignal>,
  val systemActions: List<SystemAction>,
  val safetyRecords: List<SafetyRecord>,
  val tasks: List<AgentTask>,
  val memoryRecords: List<MemoryRecord>,
  val auditTrail: List<AuditEntry>,
  val chatMessages: List<ChatMessage>,
  val pendingConflict: PendingConflictResolution? = null,
)
