package ai.opencray.app

import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.system.AppLaunchController
import ai.opencray.app.system.CommonAppsRegistry

class MainActivity : AppCompatActivity() {
  private lateinit var viewModel: MainViewModel
  private lateinit var commonAppsRegistry: CommonAppsRegistry
  private lateinit var appLaunchController: AppLaunchController

  private lateinit var statusView: TextView
  private lateinit var transportView: TextView
  private lateinit var onboardingView: TextView
  private lateinit var contextSummaryView: TextView
  private lateinit var contextFeedView: TextView
  private lateinit var homeQuickActionsView: TextView
  private lateinit var taskFlowView: TextView
  private lateinit var actionFeedView: TextView
  private lateinit var commonAppsView: TextView
  private lateinit var safetyFeedView: TextView
  private lateinit var settingsPrivacyView: TextView
  private lateinit var eventsView: TextView
  private lateinit var hostInput: EditText
  private lateinit var portInput: EditText
  private lateinit var goalInput: EditText
  private lateinit var tlsToggle: CheckBox
  private lateinit var contextPanel: LinearLayout
  private lateinit var actionsPanel: LinearLayout
  private lateinit var safetyPanel: LinearLayout
  private lateinit var chatPanel: LinearLayout
  private lateinit var chatHistoryScroll: ScrollView
  private lateinit var chatHistoryContainer: LinearLayout
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
  private lateinit var contextTab: Button
  private lateinit var chatTab: Button
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
    decorateUi()
    bindActions()
    render()
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
    commonAppsView = findViewById(R.id.common_apps_text)
    safetyFeedView = findViewById(R.id.safety_feed_text)
    settingsPrivacyView = findViewById(R.id.settings_privacy_text)
    eventsView = findViewById(R.id.events_text)
    hostInput = findViewById(R.id.host_input)
    portInput = findViewById(R.id.port_input)
    goalInput = findViewById(R.id.goal_input)
    tlsToggle = findViewById(R.id.tls_toggle)
    contextPanel = findViewById(R.id.context_panel)
    actionsPanel = findViewById(R.id.actions_panel)
    safetyPanel = findViewById(R.id.safety_panel)
    chatPanel = findViewById(R.id.chat_panel)
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
    contextTab = findViewById(R.id.context_tab)
    chatTab = findViewById(R.id.chat_tab)
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
    findViewById<ScrollView>(R.id.quick_skills_scroll).clipToPadding = true
    findViewById<ScrollView>(R.id.quick_skills_scroll).clipChildren = true
  }

  private fun bindActions() {
    contextTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Context)
      render()
    }
    chatTab.setOnClickListener {
      viewModel.selectDestination(AppDestination.Chat)
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

    chatSendButton.setOnClickListener {
      val text = chatInput.text.toString()
      viewModel.sendChatMessage(text)
      chatInput.setText("")
      render()
    }

    bindSkillPlaceholder(skillCard1, "placeholder_study_assistant")
    bindSkillPlaceholder(skillCard2, "placeholder_schedule_planner")
    bindSkillPlaceholder(skillCard3, "placeholder_cross_app_executor")
    bindSkillPlaceholder(skillCard4, "placeholder_information_digest")

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

    planGoalButton.setOnClickListener {
      viewModel.updateGoalDraft(goalInput.text.toString())
      viewModel.submitGoal()
      render()
    }

    runAgentButton.setOnClickListener {
      viewModel.runAgentPlan()
      render()
    }

    actionPrimaryButton.setOnClickListener { executeActionOrShowFeedback(0, "已模拟加入日历，等待日历 skill 接入") }
    actionSecondaryButton.setOnClickListener { executeActionOrShowFeedback(1, "已标记为稍后提醒") }
    actionTertiaryButton.setOnClickListener { executeActionOrShowFeedback(2, "已忽略该建议，并保留可撤销记录") }

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
    chatPanel.visibility = if (state.currentDestination == AppDestination.Chat) View.VISIBLE else View.GONE
    actionsPanel.visibility = if (state.currentDestination == AppDestination.Actions) View.VISIBLE else View.GONE
    safetyPanel.visibility = if (state.currentDestination == AppDestination.Safety) View.VISIBLE else View.GONE

    contextTab.isEnabled = state.currentDestination != AppDestination.Context
    chatTab.isEnabled = state.currentDestination != AppDestination.Chat
    actionsTab.isEnabled = state.currentDestination != AppDestination.Actions
    safetyTab.isEnabled = state.currentDestination != AppDestination.Safety

    statusView.text = "Agent 状态：${state.snapshot.connectionStatus}"
    transportView.text = "数据链路：${state.snapshot.transportLabel}"
    renderChatHistory(state.chatMessages)

    if (hostInput.text.toString() != state.host) hostInput.setText(state.host)
    if (portInput.text.toString() != state.port) portInput.setText(state.port)
    if (goalInput.text.toString() != state.goalDraft) goalInput.setText(state.goalDraft)
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

    actionFeedView.text =
      state.systemActions.joinToString(separator = "\n\n") { action ->
        val approval = if (action.requiresApproval) "Approval required" else "Auto/instant"
        val result = action.lastResult ?: "Not executed yet"
        "${action.title}\n${action.summary}\nRisk: ${action.riskLevel} · $approval · Status: ${action.status}\nConfidence: ${action.confidence}%\nReason: ${action.explain}\nLast result: $result\n操作：加入日历 / 稍后提醒 / 忽略 / 修改后接受 / 回滚"
      }

    commonAppsView.text =
      state.commonApps.joinToString(separator = "\n") { app ->
        val status = if (app.installed) "installed" else "missing"
        "${app.label}: $status (${app.packageName})"
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

  private fun bindSkillPlaceholder(
    button: Button,
    skillId: String,
  ) {
    button.setOnClickListener {
      viewModel.invokeSkill(skillId)
      Toast.makeText(this, getString(R.string.skills_waiting_to_join), Toast.LENGTH_SHORT).show()
      render()
    }
  }

  private fun renderChatHistory(messages: List<ChatMessage>) {
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

    chatHistoryScroll.post {
      chatHistoryScroll.fullScroll(View.FOCUS_DOWN)
    }
  }

  private fun createMessageBubble(message: ChatMessage): TextView {
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

    viewModel.executeAction(action.id)
    render()
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

    viewModel.executeAction(action.id)
    Toast.makeText(this, "已提交：${action.title}", Toast.LENGTH_SHORT).show()
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
