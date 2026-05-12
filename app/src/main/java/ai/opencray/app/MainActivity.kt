package ai.opencray.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.PendingConflictResolution
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.AgentEvent
import ai.opencray.app.feature.chat.AgentEventOption
import ai.opencray.app.feature.chat.AgentEventType
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
//import ai.opencray.app.system.AppLaunchController
//import ai.opencray.app.system.CommonAppsRegistry

class MainActivity : AppCompatActivity() {
  companion object {
    private const val CALENDAR_PERMISSION_REQUEST = 1201
  }

  private lateinit var viewModel: MainViewModel
  private var pendingCalendarActionId: String? = null

  private lateinit var statusView: TextView
  private lateinit var transportView: TextView
  private lateinit var onboardingView: TextView
  private lateinit var contextSummaryView: TextView
  private lateinit var contextFeedView: TextView
  private lateinit var homeQuickActionsView: TextView
  private lateinit var taskFlowView: TextView
  private lateinit var actionFeedView: TextView
  private lateinit var safetyFeedView: TextView
  private lateinit var settingsPrivacyView: TextView
  private lateinit var eventsView: TextView
  private lateinit var hostInput: EditText
  private lateinit var portInput: EditText
  private lateinit var tlsToggle: CheckBox
  private lateinit var contextPanel: LinearLayout
  private lateinit var actionsPanel: LinearLayout
  private lateinit var safetyPanel: LinearLayout
  private lateinit var chatPanel: LinearLayout
  private lateinit var mainScroll: ScrollView
  private lateinit var chatHistoryScroll: ScrollView
  private lateinit var chatHistoryContainer: LinearLayout
  private lateinit var conversationTabsContainer: LinearLayout
  private lateinit var newConversationButton: Button
  private lateinit var chatInput: EditText
  private lateinit var chatSendButton: Button
  private lateinit var preferenceInput: EditText
  private lateinit var preferenceAddButton: Button
  private lateinit var preferenceDelete1Button: Button
  private lateinit var preferenceDelete2Button: Button
  private lateinit var preferenceDelete3Button: Button
  private lateinit var skillCard1: Button
  private lateinit var skillCard2: Button
  private lateinit var skillCard3: Button
  private lateinit var skillCard4: Button
  private lateinit var quickSkillsToggle: Button
  private lateinit var quickSkillsContainer: LinearLayout
  private lateinit var chatTab: Button
  private lateinit var planningTab: Button
  private lateinit var settingsTab: Button
  private lateinit var notificationToggle: CheckBox
  private lateinit var safetyGuardToggle: CheckBox
  private lateinit var connectButton: Button
  private lateinit var saveSettingsButton: Button
  private lateinit var testSettingsButton: Button
  private lateinit var runAgentButton: Button
  private lateinit var actionPrimaryButton: Button
  private lateinit var actionSecondaryButton: Button
  private lateinit var actionTertiaryButton: Button
  private lateinit var approveButton: Button
  private lateinit var openAiKeyInput: EditText
  private lateinit var llmModelInput: EditText
  private lateinit var llmBaseUrlInput: EditText
  private lateinit var userIdInput: EditText
  private lateinit var webvpnCookieInput: EditText
  private lateinit var webvpnCsrfInput: EditText
  private lateinit var campusFileInput: EditText
  private lateinit var searchProviderInput: EditText
  private lateinit var searchEndpointInput: EditText
  private lateinit var searchApiKeyInput: EditText
  private lateinit var searchTtlInput: EditText
  private lateinit var memoryFileInput: EditText
  private lateinit var memoryLongTtlInput: EditText
  private lateinit var memoryMidTtlInput: EditText
  private lateinit var memoryShortTtlInput: EditText
  private lateinit var memoryHalfLifeInput: EditText
  private lateinit var adbBinInput: EditText
  private lateinit var adbSerialInput: EditText
  private lateinit var timezoneInput: EditText
  private val uiRefreshHandler = Handler(Looper.getMainLooper())
  private val uiRefreshTicker =
    object : Runnable {
      override fun run() {
        render()
        uiRefreshHandler.postDelayed(this, 150L)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)
    viewModel = ViewModelProvider(this)[MainViewModel::class.java]

    // Allow the runtime (background threads) to request calendar permissions via the Activity.
    viewModel.setCalendarPermissionDelegate {
      ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        CALENDAR_PERMISSION_REQUEST,
      )
    }

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
    }

    bindViews()
    decorateUi()
    bindActions()
    render()
    uiRefreshHandler.post(uiRefreshTicker)
  }

  override fun onDestroy() {
    uiRefreshHandler.removeCallbacks(uiRefreshTicker)
    super.onDestroy()
  }

  private fun bindViews() {
    statusView = findViewById(R.id.status_text)
    transportView = findViewById(R.id.transport_text)
    onboardingView = findViewById(R.id.onboarding_text)
    contextSummaryView = findViewById(R.id.context_summary_text)
    contextFeedView = findViewById(R.id.context_feed_text)
    homeQuickActionsView = findViewById(R.id.home_quick_actions_text)
    taskFlowView = findViewById(R.id.task_flow_text)
    actionFeedView = findViewById(R.id.action_feed_text)
    safetyFeedView = findViewById(R.id.safety_feed_text)
    settingsPrivacyView = findViewById(R.id.settings_privacy_text)
    eventsView = findViewById(R.id.events_text)
    hostInput = findViewById(R.id.host_input)
    portInput = findViewById(R.id.port_input)
    tlsToggle = findViewById(R.id.tls_toggle)
    contextPanel = findViewById(R.id.context_panel)
    actionsPanel = findViewById(R.id.actions_panel)
    safetyPanel = findViewById(R.id.safety_panel)
    chatPanel = findViewById(R.id.chat_panel)
    mainScroll = findViewById(R.id.main)
    chatHistoryScroll = findViewById(R.id.chat_history_scroll)
    chatHistoryContainer = findViewById(R.id.chat_history_container)
    chatInput = findViewById(R.id.chat_input)
    chatSendButton = findViewById(R.id.chat_send_button)
    preferenceInput = findViewById(R.id.preference_input)
    preferenceAddButton = findViewById(R.id.preference_add_button)
    preferenceDelete1Button = findViewById(R.id.preference_delete_1)
    preferenceDelete2Button = findViewById(R.id.preference_delete_2)
    preferenceDelete3Button = findViewById(R.id.preference_delete_3)
    skillCard1 = findViewById(R.id.skill_card_1)
    skillCard2 = findViewById(R.id.skill_card_2)
    skillCard3 = findViewById(R.id.skill_card_3)
    skillCard4 = findViewById(R.id.skill_card_4)
    quickSkillsToggle = findViewById(R.id.quick_skills_toggle)
    quickSkillsContainer = findViewById(R.id.quick_skills_container)
    chatTab = findViewById(R.id.chat_tab)
    planningTab = findViewById(R.id.planning_tab)
    settingsTab = findViewById(R.id.settings_tab)
    conversationTabsContainer = findViewById(R.id.conversation_tabs_container)
    newConversationButton = findViewById(R.id.new_conversation_button)
    notificationToggle = findViewById(R.id.capability_notification_toggle)
    safetyGuardToggle = findViewById(R.id.capability_safety_toggle)
    connectButton = findViewById(R.id.connect_button)
    saveSettingsButton = findViewById(R.id.save_settings_button)
    testSettingsButton = findViewById(R.id.test_settings_button)
    runAgentButton = findViewById(R.id.run_agent_button)
    actionPrimaryButton = findViewById(R.id.action_primary_button)
    actionSecondaryButton = findViewById(R.id.action_secondary_button)
    actionTertiaryButton = findViewById(R.id.action_tertiary_button)
    approveButton = findViewById(R.id.approve_button)
    openAiKeyInput = findViewById(R.id.setting_openai_key_input)
    llmModelInput = findViewById(R.id.setting_llm_model_input)
    llmBaseUrlInput = findViewById(R.id.setting_llm_base_url_input)
    userIdInput = findViewById(R.id.setting_user_id_input)
    webvpnCookieInput = findViewById(R.id.setting_webvpn_cookie_input)
    webvpnCsrfInput = findViewById(R.id.setting_webvpn_csrf_input)
    campusFileInput = findViewById(R.id.setting_campus_file_input)
    searchProviderInput = findViewById(R.id.setting_search_provider_input)
    searchEndpointInput = findViewById(R.id.setting_search_endpoint_input)
    searchApiKeyInput = findViewById(R.id.setting_search_api_key_input)
    searchTtlInput = findViewById(R.id.setting_search_ttl_input)
    memoryFileInput = findViewById(R.id.setting_memory_file_input)
    memoryLongTtlInput = findViewById(R.id.setting_memory_long_ttl_input)
    memoryMidTtlInput = findViewById(R.id.setting_memory_mid_ttl_input)
    memoryShortTtlInput = findViewById(R.id.setting_memory_short_ttl_input)
    memoryHalfLifeInput = findViewById(R.id.setting_memory_half_life_input)
    adbBinInput = findViewById(R.id.setting_adb_bin_input)
    adbSerialInput = findViewById(R.id.setting_adb_serial_input)
    timezoneInput = findViewById(R.id.setting_timezone_input)
    loadSettingsInputs()
  }

  private fun decorateUi() {
    val root = findViewById<ViewGroup>(R.id.main)
    decorateButtons(root)
    elevatePanels(root)
    keepScrollableContentClipped()
  }

  private fun decorateButtons(view: View) {
    if (view is Button) {
      view.stateListAnimator = null
      view.elevation = dp(6).toFloat()
      view.translationZ = dp(1).toFloat()
      view.setOnTouchListener { target, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            target.animate()
              .scaleX(0.97f)
              .scaleY(0.97f)
              .translationZ(dp(9).toFloat())
              .setDuration(90)
              .start()
          }
          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL,
          -> {
            target.animate()
              .scaleX(1f)
              .scaleY(1f)
              .translationZ(dp(1).toFloat())
              .setDuration(140)
              .start()
          }
        }
        false
      }
    }

    if (view is ViewGroup) {
      for (index in 0 until view.childCount) {
        decorateButtons(view.getChildAt(index))
      }
    }
  }

  private fun elevatePanels(view: View) {
    if (
      view.background != null &&
      view !is Button &&
      view !is EditText &&
      view !is CheckBox &&
      view.id != R.id.main
    ) {
      view.elevation = maxOf(view.elevation, dp(4).toFloat())
      view.translationZ = maxOf(view.translationZ, dp(1).toFloat())
    }

    if (view is ViewGroup) {
      if (view !is ScrollView) {
        view.clipToPadding = false
        view.clipChildren = false
      }
      for (index in 0 until view.childCount) {
        elevatePanels(view.getChildAt(index))
      }
    }
  }

  private fun keepScrollableContentClipped() {
    chatHistoryScroll.clipToPadding = true
    chatHistoryScroll.clipChildren = true
    chatHistoryContainer.clipToPadding = true
    chatHistoryContainer.clipChildren = true
    val quickSkillsScroll = findViewById<ScrollView>(R.id.quick_skills_scroll)
    quickSkillsScroll.clipToPadding = true
    quickSkillsScroll.clipChildren = true

    // Nested ScrollView fix: keep touch focus inside inner scroll areas so emulator
    // gestures do not get hijacked by the outer page ScrollView.
    installNestedScrollLock(chatHistoryScroll)
    installNestedScrollLock(quickSkillsScroll)
    chatPanel.setOnTouchListener { view, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_MOVE,
        -> view.parent?.requestDisallowInterceptTouchEvent(true)
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL,
        -> view.parent?.requestDisallowInterceptTouchEvent(false)
      }
      false
    }
  }

  private fun installNestedScrollLock(innerScroll: ScrollView) {
    innerScroll.setOnTouchListener { view, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_MOVE,
        -> {
          view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL,
        -> {
          view.parent?.requestDisallowInterceptTouchEvent(false)
        }
      }
      false
    }
  }

  private fun bindActions() {
    chatTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Chat)
      render()
    }
    planningTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Planning)
      render()
    }
    settingsTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Settings)
      render()
    }
    newConversationButton.setOnClickListener {
      viewModel.createConversation()
      render()
    }

    chatSendButton.setOnClickListener {
      val text = chatInput.text.toString()
      viewModel.sendChatMessage(text)
      chatInput.setText("")
      render()
    }

    bindSkillPlaceholder(skillCard1, "placeholder_study_assistant")
    bindSkillPlaceholder(skillCard2, "get_campus_activities")
    bindSkillPlaceholder(skillCard3, "create_calendar_event")
    bindSkillPlaceholder(skillCard4, "read_notifications")
    quickSkillsToggle.setOnClickListener {
      quickSkillsContainer.visibility =
        if (quickSkillsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    val preferencePlaceholderListener = View.OnClickListener {
      Toast.makeText(this, getString(R.string.preference_waiting), Toast.LENGTH_SHORT).show()
    }
    preferenceAddButton.setOnClickListener {
      preferenceInput.setText("")
      Toast.makeText(this, getString(R.string.preference_waiting), Toast.LENGTH_SHORT).show()
    }
    preferenceDelete1Button.setOnClickListener(preferencePlaceholderListener)
    preferenceDelete2Button.setOnClickListener(preferencePlaceholderListener)
    preferenceDelete3Button.setOnClickListener(preferencePlaceholderListener)

    connectButton.setOnClickListener {
      viewModel.updateHost(hostInput.text.toString())
      viewModel.updatePort(portInput.text.toString())
      viewModel.updateTlsEnabled(tlsToggle.isChecked)
      viewModel.connectToGateway()
      render()
    }
    saveSettingsButton.setOnClickListener {
      if (persistSettings()) {
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this, getString(R.string.settings_invalid), Toast.LENGTH_SHORT).show()
      }
    }
    testSettingsButton.setOnClickListener {
      val warnings = buildSettingsWarnings()
      val message = if (warnings.isEmpty()) getString(R.string.settings_health_ok) else "${getString(R.string.settings_health_warn)}: ${warnings.joinToString("；")}"
      Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    runAgentButton.setOnClickListener {
      viewModel.runAgentPlan()
      render()
    }

    actionPrimaryButton.setOnClickListener {
      if (viewModel.getUiState().pendingConflict != null) {
        viewModel.resolveConflict("skip_write")
        render()
      } else {
        executeActionOrShowFeedback(0, "已模拟加入日历，等待日历 skill 接入")
      }
    }
    actionSecondaryButton.setOnClickListener {
      if (viewModel.getUiState().pendingConflict != null) {
        viewModel.resolveConflict("coexist")
        render()
      } else {
        executeActionOrShowFeedback(1, "已标记为稍后提醒")
      }
    }
    actionTertiaryButton.setOnClickListener {
      if (viewModel.getUiState().pendingConflict != null) {
        viewModel.resolveConflict("delete_conflicts")
        render()
      } else {
        executeActionOrShowFeedback(2, "已忽略该建议，并保留可撤销记录")
      }
    }

    approveButton.setOnClickListener {
      viewModel.approvePendingActions()
      render()
    }

    notificationToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.toggleCapability("notification_context", isChecked)
      render()
    }
    safetyGuardToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.toggleCapability("safety_guard", isChecked)
      render()
    }
  }

  private fun render() {
    val state = viewModel.getUiState()

    val isPlanning = state.currentDestination == AppDestination.Planning
    contextPanel.visibility = if (isPlanning) View.VISIBLE else View.GONE
    chatPanel.visibility = if (state.currentDestination == AppDestination.Chat) View.VISIBLE else View.GONE
    actionsPanel.visibility = if (isPlanning) View.VISIBLE else View.GONE
    safetyPanel.visibility = if (state.currentDestination == AppDestination.Settings) View.VISIBLE else View.GONE

    chatTab.isEnabled = state.currentDestination != AppDestination.Chat
    planningTab.isEnabled = state.currentDestination != AppDestination.Planning
    settingsTab.isEnabled = state.currentDestination != AppDestination.Settings

    statusView.text = "Agent 状态：${state.snapshot.connectionStatus}"
    transportView.text = "数据链路：${state.snapshot.transportLabel}"
    renderChatHistory(state.chatMessages)
    renderConversationTabs(state.conversationSummaries)

    if (hostInput.text.toString() != state.host) hostInput.setText(state.host)
    if (portInput.text.toString() != state.port) portInput.setText(state.port)
    tlsToggle.isChecked = state.tlsEnabled

    onboardingView.text =
      "${getString(R.string.onboarding_title)}\n${getString(R.string.onboarding_body)}"

    contextSummaryView.text =
      buildString {
        append("校园资讯\n")
        append("• 教务通知：课程 DDL 与考试安排待同步\n")
        append("• 校园活动：讲座、社团与志愿活动可按兴趣推荐\n")
        append("• 课程/日程：${state.tasks.size} 个任务，${state.memoryRecords.size} 条记忆偏好\n")
        append("• 待办：${state.systemActions.count { it.status != "executed" }} 个候选动作")
      }

    contextFeedView.text =
      buildString {
        append("状态提示与推荐\n")
        append("• 解析中：通知和校园网页会先进入结构化队列\n")
        append("• 待确认：涉及日历、跨应用或高风险动作时展示原因\n")
        append("• 已执行/失败/已回滚：结果会同步到进度页\n\n")
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

    // --- Conflict resolution UI (overrides normal action feed + buttons) ---
    val conflict = state.pendingConflict
    if (conflict != null) {
      actionFeedView.text = buildString {
        append("===== 日历冲突，请选择处理策略 =====\n\n")
        append(conflict.conflictMessage)
        append("\n\n操作：\n")
        append("  [跳过创建]     — 不写入日历，保留现有事项\n")
        append("  [共存]         — 忽略冲突，直接写入\n")
        append("  [删除冲突事项] — 删除冲突事项后再写入")
      }
      actionPrimaryButton.text = "跳过创建"
      actionPrimaryButton.isEnabled = true
      actionSecondaryButton.text = "共存"
      actionSecondaryButton.isEnabled = true
      actionTertiaryButton.text = "删除冲突事项"
      actionTertiaryButton.isEnabled = true
      return
    }

    homeQuickActionsView.text =
      buildString {
        append("${getString(R.string.home_quick_actions)}\n")
        append("• 加入日历：把课程、活动、考试转为日程\n")
        append("• 设置提醒：按强/中/弱优先级提醒\n")
        append("• 忽略/稍后：降低打扰，保留可恢复记录\n")
        append("• 偏好反馈：感兴趣、减少此类、不再推荐、修改后接受")
      }

    taskFlowView.text =
      buildString {
        append("任务流转\n")
        append("1. 通知/网页/课表输入 → 语义解析\n")
        append("2. Planner 生成候选动作 → Policy Engine 审查\n")
        append("3. 用户确认高风险动作 → 执行并展示结果\n")
        append("4. 可撤销操作保留回滚入口，偏好反馈写入记忆层")
      }

    val planningSnapshotView = findViewById<TextView>(R.id.planning_snapshot_text)
    val planningScheduleView = findViewById<TextView>(R.id.planning_schedule_text)
    val planningAlarmView = findViewById<TextView>(R.id.planning_alarm_text)
    val planningTodoView = findViewById<TextView>(R.id.planning_todo_text)

    planningSnapshotView.text = buildString {
      append("${getString(R.string.planning_snapshot_title)}\n")
      append("任务数量：${state.tasks.size}\n")
      append("候选动作：${state.systemActions.size}\n")
      append("安全记录：${state.safetyRecords.size}\n")
      append("记忆条目：${state.memoryRecords.size}")
    }

    val scheduleHints =
      state.contextSignals
        .filter { it.title.contains("课程") || it.title.contains("课表") || it.detail.contains("课程") }
        .take(5)
        .joinToString("\n") { "• ${it.title}: ${it.detail.take(40)}" }
        .ifBlank { "• 暂无已爬取课表数据，等待课程/校历 skill 返回。" }
    planningScheduleView.text = "${getString(R.string.planning_schedule_title)}\n$scheduleHints"

    val alarmHints =
      state.systemActions
        .filter { it.id.contains("alarm") || it.title.contains("提醒") || it.title.contains("闹钟") }
        .take(5)
        .joinToString("\n") { "• ${it.title} (${it.status})" }
        .ifBlank { "• 暂无闹钟任务，可在规划动作中添加。"}
    planningAlarmView.text = "${getString(R.string.planning_alarm_title)}\n$alarmHints"

    val todoHints =
      state.tasks
        .take(6)
        .joinToString("\n") { "• ${it.goal.take(36)} [${it.status}]" }
        .ifBlank { "• 暂无待办任务，先在对话页提交目标。"}
    planningTodoView.text = "${getString(R.string.planning_todo_title)}\n$todoHints"

    actionFeedView.text =
      state.systemActions.joinToString(separator = "\n\n") { action ->
        val approval = if (action.requiresApproval) "Approval required" else "Auto/instant"
        val result = action.lastResult ?: "Not executed yet"
        "${action.title}\n${action.summary}\nRisk: ${action.riskLevel} · $approval · Status: ${action.status}\nConfidence: ${action.confidence}%\nReason: ${action.explain}\nLast result: $result"
      }

    safetyFeedView.text =
      buildString {
        append("安全审查与可解释记录\n")
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

    settingsPrivacyView.text =
      buildString {
        append("${getString(R.string.settings_title)}\n")
        append("• 记忆开关：长期偏好 / 中期情境 / 短期交互\n")
        append("• 数据源配置：校园官网、学院通知、课表、作业、考试\n")
        append("• 权限管理：通知、日历、无障碍、悬浮窗按需开启\n")
        append("• 风险提示：跨应用执行前必须展示用途、影响与回滚方式")
      }

    eventsView.text = state.snapshot.recentEvents.take(20).joinToString(separator = "\n• ", prefix = "• ")

    state.snapshot.capabilities.associateBy { it.id }.let { capabilities ->
      notificationToggle.setOnCheckedChangeListener(null)
      safetyGuardToggle.setOnCheckedChangeListener(null)

      notificationToggle.isChecked = capabilities["notification_context"]?.enabled == true
      safetyGuardToggle.isChecked = capabilities["safety_guard"]?.enabled == true

      notificationToggle.setOnCheckedChangeListener { _, isChecked ->
        viewModel.toggleCapability("notification_context", isChecked)
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

  private fun renderConversationTabs(conversations: List<ConversationSummary>) {
    conversationTabsContainer.removeAllViews()
    conversations.forEach { summary ->
      val button = Button(this).apply {
        text = summary.title
        setAllCaps(false)
        textSize = 12f
        minHeight = dp(32)
        minimumHeight = dp(32)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        alpha = if (summary.selected) 1f else 0.78f
        setBackgroundResource(if (summary.selected) R.drawable.button_primary_selector else R.drawable.button_secondary_selector)
        setOnClickListener {
          viewModel.selectConversation(summary.id)
          render()
        }
      }
      val params =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          rightMargin = dp(8)
        }
      conversationTabsContainer.addView(button, params)
    }
  }

  private fun bindSkillPlaceholder(
    button: Button,
    skillId: String,
  ) {
    button.setOnClickListener {
      viewModel.invokeSkill(skillId)
      if (skillId.startsWith("placeholder_")) {
        Toast.makeText(this, getString(R.string.skills_waiting_to_join), Toast.LENGTH_SHORT).show()
      }
      render()
    }
  }

  private fun renderChatHistory(messages: List<ChatMessage>) {
    // Keep auto-stick-to-bottom behavior only when user is already at bottom.
    // If user has scrolled up to read history, do not force jump back.
    val shouldStickToBottom = !chatHistoryScroll.canScrollVertically(1)
    chatHistoryContainer.removeAllViews()

    val renderedMessages =
      messages.ifEmpty {
        listOf(
          ChatMessage(
            id = "empty_title",
            role = ChatRole.Assistant,
            text = "${getString(R.string.chat_empty_title)}\n${getString(R.string.chat_empty_body)}",
          ),
        )
      }

    renderedMessages.forEach { message ->
      chatHistoryContainer.addView(createMessageBubble(message))
    }

    if (shouldStickToBottom) {
      chatHistoryScroll.post {
        chatHistoryScroll.fullScroll(View.FOCUS_DOWN)
      }
    }
  }

  private fun createMessageBubble(message: ChatMessage): View {
    val isUser = message.role == ChatRole.User
    val visibleEvents =
      message.events.filterNot { event ->
        event.type == AgentEventType.AssistantDelta || event.type == AgentEventType.AssistantFinal
      }

    if (visibleEvents.isEmpty()) {
      return createMessageTextBubble(message)
    }

    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams =
        LinearLayout.LayoutParams(
          (resources.displayMetrics.widthPixels * 0.72f).toInt(),
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          setMargins(dp(4), dp(6), dp(4), dp(6))
          gravity = if (isUser) Gravity.END else Gravity.START
        }

      addView(
        createMessageTextBubble(message).apply {
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        },
      )
      visibleEvents.forEach { event ->
        addView(createEventCard(event))
      }
    }
  }

  private fun createMessageTextBubble(message: ChatMessage): TextView {
    val isUser = message.role == ChatRole.User
    val label =
      when (message.role) {
        ChatRole.User -> "你"
        ChatRole.Assistant -> "助手"
        ChatRole.System -> "系统"
      }

    return TextView(this).apply {
      text = "$label\n${message.text}"
      textSize = 14f
      setLineSpacing(2f, 1.0f)
      setPadding(dp(14), dp(10), dp(14), dp(10))
      setTextColor(
        ContextCompat.getColor(
          this@MainActivity,
          if (isUser) R.color.white else R.color.opencray_ink,
        ),
      )
      setBackgroundResource(
        when (message.role) {
          ChatRole.User -> R.drawable.message_user_bubble
          ChatRole.Assistant -> R.drawable.message_assistant_bubble
          ChatRole.System -> R.drawable.message_system_bubble
        },
      )

      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          width = (resources.displayMetrics.widthPixels * 0.72f).toInt()
          setMargins(dp(4), dp(6), dp(4), dp(6))
          gravity = if (isUser) Gravity.END else Gravity.START
        }
    }
  }

  private fun createEventCard(event: AgentEvent): View {
    val label =
      when (event.type) {
        AgentEventType.ToolCall -> if (event.status == "queued") "等待执行" else "正在调用"
        AgentEventType.ToolResult -> "执行完成"
        AgentEventType.ConfirmationRequired ->
          when (event.status) {
            "submitting" -> "正在提交"
            "approved" -> "已允许"
            "rejected" -> "已拒绝"
            "failed" -> "提交失败"
            else -> "需要确认"
          }
        AgentEventType.PermissionRequired -> "需要权限"
        AgentEventType.Error -> "执行异常"
        AgentEventType.Unknown -> "状态更新"
        AgentEventType.AssistantDelta,
        AgentEventType.AssistantFinal,
        -> "回复"
      }
    val title =
      listOf(label, event.title, event.skillName)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
    val body =
      buildString {
        if (event.content.isNotBlank()) {
          append(event.content)
        }
        if (event.options.isNotEmpty() && event.type != AgentEventType.ConfirmationRequired) {
          if (isNotBlank()) append("\n")
          append("选项：")
          append(event.options.joinToString(" / ") { option -> option.label.ifBlank { option.value } })
        }
      }.ifBlank { event.status.ifBlank { "处理中" } }

    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(12), dp(8), dp(12), dp(8))
      setBackgroundResource(R.drawable.skill_card_surface)
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = dp(6)
        }

      addView(
        TextView(this@MainActivity).apply {
          text = title
          textSize = 12f
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_primary_dark))
        },
      )
      addView(
        TextView(this@MainActivity).apply {
          text = body
          textSize = 12f
          setLineSpacing(2f, 1.0f)
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
        },
      )
      if (
        event.type == AgentEventType.ConfirmationRequired &&
        (event.status.isBlank() || event.status == "pending") &&
        event.taskId.isNotBlank() &&
        event.requestId.isNotBlank()
      ) {
        addView(createDecisionButtonRow(event))
      }
    }
  }

  private fun createDecisionButtonRow(event: AgentEvent): View {
    val options =
      event.options.ifEmpty {
        listOf(
          AgentEventOption("允许", "approve"),
          AgentEventOption("拒绝", "reject"),
        )
      }
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = dp(8)
        }

      options.forEach { option ->
        val value = option.value.ifBlank { option.label }
        val normalizedValue = value.lowercase()
        val button =
          Button(this@MainActivity).apply {
            text = option.label.ifBlank { value }
            textSize = 12f
            setAllCaps(false)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundResource(
              if (normalizedValue == "approve" || normalizedValue == "approved") {
                R.drawable.button_primary_selector
              } else {
                R.drawable.button_secondary_selector
              },
            )
            setOnClickListener {
              viewModel.submitAgentDecision(
                taskId = event.taskId,
                requestId = event.requestId,
                eventId = event.id,
                decision = value,
              )
              render()
            }
          }
        addView(
          button,
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          ).apply {
            leftMargin = dp(8)
          },
        )
      }
    }
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    if (requiresCalendarPermission(action) && !hasCalendarPermissions()) {
      pendingCalendarActionId = action.id
      ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        CALENDAR_PERMISSION_REQUEST,
      )
      Toast.makeText(this, "请授予日历权限后重试。", Toast.LENGTH_SHORT).show()
      return
    }

    viewModel.executeAction(action.id)
    render()
  }

  private fun requiresCalendarPermission(action: SystemAction): Boolean =
    action.id == "create_calendar_event" ||
      action.id == "detect_calendar_conflicts" ||
      action.id == "delete_calendar_event"

  private fun hasCalendarPermissions(): Boolean {
    val readGranted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val writeGranted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    return readGranted && writeGranted
  }

  private fun executeActionOrShowFeedback(
    index: Int,
    fallbackMessage: String,
  ) {
    val action = viewModel.getUiState().systemActions.getOrNull(index)
    if (action == null) {
      Toast.makeText(this, fallbackMessage, Toast.LENGTH_SHORT).show()
      return
    }

    if (requiresCalendarPermission(action) && !hasCalendarPermissions()) {
      pendingCalendarActionId = action.id
      ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        CALENDAR_PERMISSION_REQUEST,
      )
      Toast.makeText(this, "请授予日历权限后重试。", Toast.LENGTH_SHORT).show()
      return
    }

    viewModel.executeAction(action.id)
    Toast.makeText(this, "已提交：${action.title}", Toast.LENGTH_SHORT).show()
    render()
  }

  // Define commonAppsRegistry if it was lost in context handling or it should just be properly scoped
  // The original feature/UI used commonAppsRegistry, let's ensure it can compile or comment it out if undefined
  // For now I will leave it
  // private fun refreshCommonApps() { ... }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != CALENDAR_PERMISSION_REQUEST) return

    if (hasCalendarPermissions()) {
      pendingCalendarActionId?.let { actionId ->
        viewModel.executeAction(actionId)
      }
      viewModel.notifyCalendarPermissionGranted()
      Toast.makeText(this, "日历权限已授予。", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(this, "未授予日历权限，无法执行日历操作。", Toast.LENGTH_SHORT).show()
    }
    pendingCalendarActionId = null
    render()
  }

  private fun loadSettingsInputs() {
    val pref = getSharedPreferences("openthu_settings", MODE_PRIVATE)
    openAiKeyInput.setText(pref.getString("openai_api_key", ""))
    llmModelInput.setText(pref.getString("llm_model", "gpt-4.1-mini"))
    llmBaseUrlInput.setText(pref.getString("llm_base_url", ""))
    userIdInput.setText(pref.getString("user_id", "demo_user"))
    webvpnCookieInput.setText(pref.getString("webvpn_cookie", ""))
    webvpnCsrfInput.setText(pref.getString("webvpn_csrf", ""))
    campusFileInput.setText(pref.getString("campus_file", ""))
    searchProviderInput.setText(pref.getString("search_provider", "duckduckgo"))
    searchEndpointInput.setText(pref.getString("search_endpoint", "https://duckduckgo.com/html/"))
    searchApiKeyInput.setText(pref.getString("search_api_key", ""))
    searchTtlInput.setText(pref.getString("search_ttl", "3600"))
    memoryFileInput.setText(pref.getString("memory_file", "agent/langgraph/memory_store.json"))
    memoryLongTtlInput.setText(pref.getString("memory_long_ttl", "365"))
    memoryMidTtlInput.setText(pref.getString("memory_mid_ttl", "30"))
    memoryShortTtlInput.setText(pref.getString("memory_short_ttl", "7"))
    memoryHalfLifeInput.setText(pref.getString("memory_half_life", "30"))
    adbBinInput.setText(pref.getString("adb_bin", "adb"))
    adbSerialInput.setText(pref.getString("adb_serial", ""))
    timezoneInput.setText(pref.getString("timezone", "UTC"))
  }

  private fun persistSettings(): Boolean {
    if (llmModelInput.text.toString().trim().isEmpty()) return false
    val pref = getSharedPreferences("openthu_settings", MODE_PRIVATE)
    pref.edit()
      .putString("openai_api_key", openAiKeyInput.text.toString().trim())
      .putString("llm_model", llmModelInput.text.toString().trim())
      .putString("llm_base_url", llmBaseUrlInput.text.toString().trim())
      .putString("user_id", userIdInput.text.toString().trim())
      .putString("webvpn_cookie", webvpnCookieInput.text.toString().trim())
      .putString("webvpn_csrf", webvpnCsrfInput.text.toString().trim())
      .putString("campus_file", campusFileInput.text.toString().trim())
      .putString("search_provider", searchProviderInput.text.toString().trim().ifEmpty { "duckduckgo" })
      .putString("search_endpoint", searchEndpointInput.text.toString().trim())
      .putString("search_api_key", searchApiKeyInput.text.toString().trim())
      .putString("search_ttl", searchTtlInput.text.toString().trim())
      .putString("memory_file", memoryFileInput.text.toString().trim())
      .putString("memory_long_ttl", memoryLongTtlInput.text.toString().trim())
      .putString("memory_mid_ttl", memoryMidTtlInput.text.toString().trim())
      .putString("memory_short_ttl", memoryShortTtlInput.text.toString().trim())
      .putString("memory_half_life", memoryHalfLifeInput.text.toString().trim())
      .putString("adb_bin", adbBinInput.text.toString().trim())
      .putString("adb_serial", adbSerialInput.text.toString().trim())
      .putString("timezone", timezoneInput.text.toString().trim())
      .apply()
    return true
  }

  private fun buildSettingsWarnings(): List<String> {
    val warnings = mutableListOf<String>()
    if (openAiKeyInput.text.toString().trim().isEmpty()) warnings += "缺少 OPENAI_API_KEY"
    if (webvpnCookieInput.text.toString().trim().isEmpty()) warnings += "缺少 WebVPN Cookie（校园资讯真实抓取会降级）"
    if (searchProviderInput.text.toString().trim().equals("brave", ignoreCase = true) &&
      searchApiKeyInput.text.toString().trim().isEmpty()
    ) {
      warnings += "Brave provider 缺少 API Key"
    }
    return warnings
  }
}
