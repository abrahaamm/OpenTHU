package ai.opencray.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.system.AppLaunchController
import ai.opencray.app.system.CommonAppsRegistry

class MainActivity : AppCompatActivity() {
  private lateinit var viewModel: MainViewModel
  private lateinit var commonAppsRegistry: CommonAppsRegistry
  private lateinit var appLaunchController: AppLaunchController

  private lateinit var statusView: TextView
  private lateinit var transportView: TextView
  private lateinit var contextSummaryView: TextView
  private lateinit var contextFeedView: TextView
  private lateinit var actionFeedView: TextView
  private lateinit var commonAppsView: TextView
  private lateinit var safetyFeedView: TextView
  private lateinit var eventsView: TextView
  private lateinit var hostInput: EditText
  private lateinit var portInput: EditText
  private lateinit var goalInput: EditText
  private lateinit var tlsToggle: CheckBox
  private lateinit var contextPanel: LinearLayout
  private lateinit var actionsPanel: LinearLayout
  private lateinit var safetyPanel: LinearLayout
  private lateinit var contextTab: Button
  private lateinit var actionsTab: Button
  private lateinit var safetyTab: Button
  private lateinit var notificationToggle: CheckBox
  private lateinit var crossAppToggle: CheckBox
  private lateinit var safetyGuardToggle: CheckBox
  private lateinit var connectButton: Button
  private lateinit var planGoalButton: Button
  private lateinit var runAgentButton: Button
  private lateinit var actionPrimaryButton: Button
  private lateinit var actionSecondaryButton: Button
  private lateinit var actionTertiaryButton: Button
  private lateinit var launchWeChatButton: Button
  private lateinit var launchAlipayButton: Button
  private lateinit var launchAmapButton: Button
  private lateinit var launchTaobaoButton: Button
  private lateinit var launchMeituanButton: Button
  private lateinit var launchQqButton: Button
  private lateinit var approveButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)
    viewModel = ViewModelProvider(this)[MainViewModel::class.java]
    commonAppsRegistry = CommonAppsRegistry(this)
    appLaunchController = AppLaunchController(this)

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
    }

    bindViews()
    bindActions()
    render()
  }

  private fun bindViews() {
    statusView = findViewById(R.id.status_text)
    transportView = findViewById(R.id.transport_text)
    contextSummaryView = findViewById(R.id.context_summary_text)
    contextFeedView = findViewById(R.id.context_feed_text)
    actionFeedView = findViewById(R.id.action_feed_text)
    commonAppsView = findViewById(R.id.common_apps_text)
    safetyFeedView = findViewById(R.id.safety_feed_text)
    eventsView = findViewById(R.id.events_text)
    hostInput = findViewById(R.id.host_input)
    portInput = findViewById(R.id.port_input)
    goalInput = findViewById(R.id.goal_input)
    tlsToggle = findViewById(R.id.tls_toggle)
    contextPanel = findViewById(R.id.context_panel)
    actionsPanel = findViewById(R.id.actions_panel)
    safetyPanel = findViewById(R.id.safety_panel)
    contextTab = findViewById(R.id.context_tab)
    actionsTab = findViewById(R.id.actions_tab)
    safetyTab = findViewById(R.id.safety_tab)
    notificationToggle = findViewById(R.id.capability_notification_toggle)
    crossAppToggle = findViewById(R.id.capability_cross_app_toggle)
    safetyGuardToggle = findViewById(R.id.capability_safety_toggle)
    connectButton = findViewById(R.id.connect_button)
    planGoalButton = findViewById(R.id.plan_goal_button)
    runAgentButton = findViewById(R.id.run_agent_button)
    actionPrimaryButton = findViewById(R.id.action_primary_button)
    actionSecondaryButton = findViewById(R.id.action_secondary_button)
    actionTertiaryButton = findViewById(R.id.action_tertiary_button)
    launchWeChatButton = findViewById(R.id.launch_wechat_button)
    launchAlipayButton = findViewById(R.id.launch_alipay_button)
    launchAmapButton = findViewById(R.id.launch_amap_button)
    launchTaobaoButton = findViewById(R.id.launch_taobao_button)
    launchMeituanButton = findViewById(R.id.launch_meituan_button)
    launchQqButton = findViewById(R.id.launch_qq_button)
    approveButton = findViewById(R.id.approve_button)
  }

  private fun bindActions() {
    contextTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Context)
      render()
    }
    actionsTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Actions)
      render()
    }
    safetyTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Safety)
      render()
    }

    connectButton.setOnClickListener {
      viewModel.updateHost(hostInput.text.toString())
      viewModel.updatePort(portInput.text.toString())
      viewModel.updateTlsEnabled(tlsToggle.isChecked)
      viewModel.connectToGateway()
      render()
    }

    planGoalButton.setOnClickListener {
      viewModel.updateGoalDraft(goalInput.text.toString())
      viewModel.submitGoal()
      render()
    }

    runAgentButton.setOnClickListener {
      viewModel.runAgentPlan()
      render()
    }

    actionPrimaryButton.setOnClickListener { executeActionByIndex(0) }
    actionSecondaryButton.setOnClickListener { executeActionByIndex(1) }
    actionTertiaryButton.setOnClickListener { executeActionByIndex(2) }

    approveButton.setOnClickListener {
      viewModel.approvePendingActions()
      render()
    }

    launchWeChatButton.setOnClickListener { launchCommonApp("wechat") }
    launchAlipayButton.setOnClickListener { launchCommonApp("alipay") }
    launchAmapButton.setOnClickListener { launchCommonApp("amap") }
    launchTaobaoButton.setOnClickListener { launchCommonApp("taobao") }
    launchMeituanButton.setOnClickListener { launchCommonApp("meituan") }
    launchQqButton.setOnClickListener { launchCommonApp("qq") }

    notificationToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.toggleCapability("notification_context", isChecked)
      render()
    }
    crossAppToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.toggleCapability("cross_app_actions", isChecked)
      render()
    }
    safetyGuardToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.toggleCapability("safety_guard", isChecked)
      render()
    }
  }

  private fun render() {
    refreshCommonApps()
    val state = viewModel.getUiState()

    contextPanel.visibility = if (state.currentDestination == AppDestination.Context) View.VISIBLE else View.GONE
    actionsPanel.visibility = if (state.currentDestination == AppDestination.Actions) View.VISIBLE else View.GONE
    safetyPanel.visibility = if (state.currentDestination == AppDestination.Safety) View.VISIBLE else View.GONE

    contextTab.isEnabled = state.currentDestination != AppDestination.Context
    actionsTab.isEnabled = state.currentDestination != AppDestination.Actions
    safetyTab.isEnabled = state.currentDestination != AppDestination.Safety

    statusView.text = "Status: ${state.snapshot.connectionStatus}"
    transportView.text = "Transport: ${state.snapshot.transportLabel}"

    if (hostInput.text.toString() != state.host) hostInput.setText(state.host)
    if (portInput.text.toString() != state.port) portInput.setText(state.port)
    if (goalInput.text.toString() != state.goalDraft) goalInput.setText(state.goalDraft)
    tlsToggle.isChecked = state.tlsEnabled

    contextSummaryView.text =
      buildString {
        append("Node: ${state.snapshot.nodeName}\n")
        append("Signals: ${state.contextSignals.size}\n")
        append("Tasks: ${state.tasks.size}\n")
        append("Memory: ${state.memoryRecords.size}\n")
        append("Audit entries: ${state.auditTrail.size}\n")
        append("Flags: ${state.snapshot.featureFlags.joinToString()}")
      }

    contextFeedView.text =
      buildString {
        append("Context Signals\n")
        append(
          state.contextSignals.joinToString(separator = "\n\n") { signal ->
            "${signal.title}\n${signal.detail}\nSource: ${signal.source}"
          },
        )

        val memoryPreview = state.memoryRecords.take(5)
        if (memoryPreview.isNotEmpty()) {
          append("\n\nMemory Preview\n")
          append(
            memoryPreview.joinToString(separator = "\n") { memory ->
              "[${memory.scope}] ${memory.key}: ${memory.value} (w=${memory.weight})"
            },
          )
        }
      }

    actionFeedView.text =
      state.systemActions.joinToString(separator = "\n\n") { action ->
        val approval = if (action.requiresApproval) "Approval required" else "Auto/instant"
        val result = action.lastResult ?: "Not executed yet"
        "${action.title}\n${action.summary}\nRisk: ${action.riskLevel} · $approval · Status: ${action.status}\nConfidence: ${action.confidence}%\nReason: ${action.explain}\nLast result: $result"
      }

    commonAppsView.text =
      state.commonApps.joinToString(separator = "\n") { app ->
        val status = if (app.installed) "installed" else "missing"
        "${app.label}: $status (${app.packageName})"
      }

    safetyFeedView.text =
      buildString {
        append("Safety Records\n")
        append(
          state.safetyRecords.take(12).joinToString(separator = "\n\n") { record ->
            "${record.title}\n${record.detail}\nStatus: ${record.status}"
          },
        )

        val auditPreview = state.auditTrail.take(12)
        if (auditPreview.isNotEmpty()) {
          append("\n\nAudit Trail\n")
          append(
            auditPreview.joinToString(separator = "\n") { entry ->
              "[${entry.stage}] ${entry.actionId}: ${entry.message}"
            },
          )
        }
      }

    eventsView.text = state.snapshot.recentEvents.take(20).joinToString(separator = "\n• ", prefix = "• ")

    state.snapshot.capabilities.associateBy { it.id }.let { capabilities ->
      notificationToggle.setOnCheckedChangeListener(null)
      crossAppToggle.setOnCheckedChangeListener(null)
      safetyGuardToggle.setOnCheckedChangeListener(null)

      notificationToggle.isChecked = capabilities["notification_context"]?.enabled == true
      crossAppToggle.isChecked = capabilities["cross_app_actions"]?.enabled == true
      safetyGuardToggle.isChecked = capabilities["safety_guard"]?.enabled == true

      notificationToggle.setOnCheckedChangeListener { _, isChecked ->
        viewModel.toggleCapability("notification_context", isChecked)
        render()
      }
      crossAppToggle.setOnCheckedChangeListener { _, isChecked ->
        viewModel.toggleCapability("cross_app_actions", isChecked)
        render()
      }
      safetyGuardToggle.setOnCheckedChangeListener { _, isChecked ->
        viewModel.toggleCapability("safety_guard", isChecked)
        render()
      }
    }

    configureActionButton(actionPrimaryButton, state.systemActions, 0, "No action #1")
    configureActionButton(actionSecondaryButton, state.systemActions, 1, "No action #2")
    configureActionButton(actionTertiaryButton, state.systemActions, 2, "No action #3")
  }

  private fun configureActionButton(
    button: Button,
    actions: List<SystemAction>,
    index: Int,
    fallbackText: String,
  ) {
    val action = actions.getOrNull(index)
    if (action == null) {
      button.text = fallbackText
      button.isEnabled = false
      return
    }

    button.text = "Execute: ${action.title}"
    button.isEnabled = true
  }

  private fun executeActionByIndex(index: Int) {
    val action = viewModel.getUiState().systemActions.getOrNull(index)
    if (action == null) {
      Toast.makeText(this, "No action at slot ${index + 1}", Toast.LENGTH_SHORT).show()
      return
    }

    viewModel.executeAction(action.id)
    render()
  }

  private fun refreshCommonApps() {
    val state = viewModel.getUiState()
    val refreshedApps = commonAppsRegistry.resolveInstalledApps(state.commonApps)
    viewModel.updateCommonApps(refreshedApps)
  }

  private fun launchCommonApp(appId: String) {
    val state = viewModel.getUiState()
    val app = state.commonApps.firstOrNull { it.id == appId } ?: return
    val succeeded = appLaunchController.launch(app)
    viewModel.noteAppLaunch(app.label, succeeded)
    Toast.makeText(
      this,
      if (succeeded) "${app.label} opened" else "${app.label} not installed",
      Toast.LENGTH_SHORT,
    ).show()
    render()
  }
}
