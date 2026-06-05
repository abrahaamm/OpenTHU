package ai.opencray.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.AgentEvent
import ai.opencray.app.feature.chat.AgentEventOption
import ai.opencray.app.feature.chat.AgentEventType
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import java.time.ZoneId

class MainActivity : AppCompatActivity() {
  companion object {
    private const val CALENDAR_PERMISSION_REQUEST = 1201
    private const val PREF_SHOW_PLANNING_DETAILS = "show_planning_details"
    private const val PREF_HOST = "host"
    private const val PREF_PORT = "port"
    private const val PREF_TLS_ENABLED = "tls_enabled"
    private const val PREF_TIMEZONE = "timezone"
    private const val PREF_TIMEZONE_FOLLOW_SYSTEM = "timezone_follow_system"
  }

  private lateinit var viewModel: MainViewModel
  private lateinit var markwon: Markwon
  private var pendingCalendarActionId: String? = null

  private lateinit var drawerLayout: DrawerLayout
  private lateinit var drawerToggleButton: Button
  private lateinit var pageTitleView: TextView
  private lateinit var statusView: TextView
  private lateinit var transportView: TextView
  private lateinit var onboardingView: TextView
  private lateinit var contextSummaryView: TextView
  private lateinit var contextFeedView: TextView
  private lateinit var homeQuickActionsView: TextView
  private lateinit var taskFlowView: TextView
  private lateinit var actionSummaryView: TextView
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
  private lateinit var planningPage: ScrollView
  private lateinit var planningCardsContainer: LinearLayout
  private lateinit var settingsPage: ScrollView
  private lateinit var chatHistoryScroll: ScrollView
  private lateinit var chatHistoryContainer: LinearLayout
  private lateinit var conversationSection: LinearLayout
  private lateinit var conversationListScroll: ScrollView
  private lateinit var conversationTabsContainer: LinearLayout
  private lateinit var newConversationButton: Button
  private lateinit var chatInput: EditText
  private lateinit var chatAttachButton: Button
  private lateinit var chatAttachmentRow: LinearLayout
  private lateinit var chatAttachmentView: TextView
  private lateinit var chatAttachmentClearButton: Button
  private lateinit var chatSendButton: Button
  private lateinit var preferenceInput: EditText
  private lateinit var preferenceAddButton: Button
  private lateinit var preferenceListContainer: LinearLayout
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
  private lateinit var planningDetailsToggle: CheckBox
  private lateinit var connectButton: Button
  private lateinit var saveSettingsButton: Button
  private lateinit var testSettingsButton: Button
  private lateinit var runAgentButton: Button
  private lateinit var actionPrimaryButton: Button
  private lateinit var actionSecondaryButton: Button
  private lateinit var actionTertiaryButton: Button
  private lateinit var approveButton: Button
  private lateinit var planningDeveloperContextSection: LinearLayout
  private lateinit var planningDeveloperFlowSection: LinearLayout
  private lateinit var planningDeveloperToggleButton: Button
  private lateinit var openAiKeyInput: EditText
  private lateinit var llmModelInput: EditText
  private lateinit var llmBaseUrlInput: EditText
  private lateinit var userIdInput: EditText
  private lateinit var webvpnCookieInput: EditText
  private lateinit var webvpnCsrfInput: EditText
  private lateinit var learnBaseUrlInput: EditText
  private lateinit var homeworkCookieInput: EditText
  private lateinit var homeworkCsrfInput: EditText
  private lateinit var learnCookieLoginButton: Button
  private lateinit var campusFileInput: EditText
  private lateinit var searchProviderInput: EditText
  private lateinit var searchEndpointInput: EditText
  private lateinit var searchApiKeyInput: EditText
  private lateinit var searchSceneInput: EditText
  private lateinit var searchTtlInput: EditText
  private lateinit var memoryFileInput: EditText
  private lateinit var memoryLongTtlInput: EditText
  private lateinit var memoryMidTtlInput: EditText
  private lateinit var memoryShortTtlInput: EditText
  private lateinit var memoryHalfLifeInput: EditText
  private lateinit var memoryClearAllButton: Button
  private lateinit var adbBinInput: EditText
  private lateinit var adbSerialInput: EditText
  private lateinit var timezoneFollowSystemToggle: CheckBox
  private lateinit var timezoneInput: EditText
  private var showPlanningDetails: Boolean = false
  private var selectedChatFileUri: Uri? = null
  private var selectedChatFileName: String = ""
  private var editingPreferenceIndex: Int? = null
  private var suppressSettingsAutosave: Boolean = false
  private var pendingSettingsSave: Runnable? = null
  private var lastConnectionUiState: String = ""
  private var connectionPulseActive: Boolean = false

  private val learnCookieLoginLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        val data = result.data
        val cookie = data?.getStringExtra(LearnCookieLoginActivity.EXTRA_COOKIE).orEmpty()
        val csrf = data?.getStringExtra(LearnCookieLoginActivity.EXTRA_CSRF).orEmpty()
        val webvpnCookie = data?.getStringExtra(LearnCookieLoginActivity.EXTRA_WEBVPN_COOKIE).orEmpty()
        val baseUrl = data?.getStringExtra(LearnCookieLoginActivity.EXTRA_LEARN_BASE_URL).orEmpty()
        if (baseUrl.isNotBlank()) learnBaseUrlInput.setText(baseUrl)
        if (cookie.isNotBlank()) homeworkCookieInput.setText(cookie)
        if (csrf.isNotBlank()) homeworkCsrfInput.setText(csrf)
        if (webvpnCookie.isNotBlank()) webvpnCookieInput.setText(webvpnCookie)
        Toast.makeText(this, getString(R.string.setting_learn_cookie_login_success), Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this, getString(R.string.setting_learn_cookie_login_failed), Toast.LENGTH_SHORT).show()
      }
    }

  private val chatFilePickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@registerForActivityResult
      runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      selectedChatFileUri = uri
      selectedChatFileName = resolveDisplayName(uri)
      renderChatAttachment()
    }

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
    markwon =
      Markwon.builder(this)
        .usePlugin(TablePlugin.create(this))
        .usePlugin(ImagesPlugin.create())
        .build()

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

  override fun onStart() {
    super.onStart()
    viewModel.setAppInForeground(true)
  }

  override fun onPause() {
    flushPendingSettingsSave()
    super.onPause()
  }

  override fun onStop() {
    viewModel.setAppInForeground(false)
    super.onStop()
  }

  override fun onDestroy() {
    flushPendingSettingsSave()
    uiRefreshHandler.removeCallbacks(uiRefreshTicker)
    super.onDestroy()
  }

  private fun bindViews() {
    drawerLayout = findViewById(R.id.drawer_layout)
    drawerToggleButton = findViewById(R.id.drawer_toggle_button)
    pageTitleView = findViewById(R.id.page_title_text)
    statusView = findViewById(R.id.status_text)
    transportView = findViewById(R.id.transport_text)
    onboardingView = findViewById(R.id.onboarding_text)
    contextSummaryView = findViewById(R.id.context_summary_text)
    contextFeedView = findViewById(R.id.context_feed_text)
    homeQuickActionsView = findViewById(R.id.home_quick_actions_text)
    taskFlowView = findViewById(R.id.task_flow_text)
    actionSummaryView = findViewById(R.id.action_summary_text)
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
    planningPage = findViewById(R.id.planning_page)
    planningCardsContainer = findViewById(R.id.planning_cards_container)
    settingsPage = findViewById(R.id.settings_page)
    chatHistoryScroll = findViewById(R.id.chat_history_scroll)
    chatHistoryContainer = findViewById(R.id.chat_history_container)
    conversationSection = findViewById(R.id.conversation_section)
    conversationListScroll = findViewById(R.id.conversation_list_scroll)
    conversationTabsContainer = findViewById(R.id.conversation_tabs_container)
    newConversationButton = findViewById(R.id.new_conversation_button)
    chatInput = findViewById(R.id.chat_input)
    chatAttachButton = findViewById(R.id.chat_attach_button)
    chatAttachmentRow = findViewById(R.id.chat_attachment_row)
    chatAttachmentView = findViewById(R.id.chat_attachment_text)
    chatAttachmentClearButton = findViewById(R.id.chat_attachment_clear_button)
    chatSendButton = findViewById(R.id.chat_send_button)
    preferenceInput = findViewById(R.id.preference_input)
    preferenceAddButton = findViewById(R.id.preference_add_button)
    preferenceListContainer = findViewById(R.id.preference_list_container)
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
    notificationToggle = findViewById(R.id.capability_notification_toggle)
    safetyGuardToggle = findViewById(R.id.capability_safety_toggle)
    planningDetailsToggle = findViewById(R.id.capability_planning_details_toggle)
    connectButton = findViewById(R.id.connect_button)
    saveSettingsButton = findViewById(R.id.save_settings_button)
    testSettingsButton = findViewById(R.id.test_settings_button)
    runAgentButton = findViewById(R.id.run_agent_button)
    actionPrimaryButton = findViewById(R.id.action_primary_button)
    actionSecondaryButton = findViewById(R.id.action_secondary_button)
    actionTertiaryButton = findViewById(R.id.action_tertiary_button)
    approveButton = findViewById(R.id.approve_button)
    planningDeveloperContextSection = findViewById(R.id.planning_developer_context_section)
    planningDeveloperFlowSection = findViewById(R.id.planning_developer_flow_section)
    planningDeveloperToggleButton = findViewById(R.id.planning_dev_toggle_button)
    openAiKeyInput = findViewById(R.id.setting_openai_key_input)
    llmModelInput = findViewById(R.id.setting_llm_model_input)
    llmBaseUrlInput = findViewById(R.id.setting_llm_base_url_input)
    userIdInput = findViewById(R.id.setting_user_id_input)
    webvpnCookieInput = findViewById(R.id.setting_webvpn_cookie_input)
    webvpnCsrfInput = findViewById(R.id.setting_webvpn_csrf_input)
    learnBaseUrlInput = findViewById(R.id.setting_learn_base_url_input)
    homeworkCookieInput = findViewById(R.id.setting_homework_cookie_input)
    homeworkCsrfInput = findViewById(R.id.setting_homework_csrf_input)
    learnCookieLoginButton = findViewById(R.id.setting_learn_cookie_login_button)
    campusFileInput = findViewById(R.id.setting_campus_file_input)
    searchProviderInput = findViewById(R.id.setting_search_provider_input)
    searchEndpointInput = findViewById(R.id.setting_search_endpoint_input)
    searchApiKeyInput = findViewById(R.id.setting_search_api_key_input)
    searchSceneInput = findViewById(R.id.setting_search_scene_input)
    searchTtlInput = findViewById(R.id.setting_search_ttl_input)
    memoryFileInput = findViewById(R.id.setting_memory_file_input)
    memoryLongTtlInput = findViewById(R.id.setting_memory_long_ttl_input)
    memoryMidTtlInput = findViewById(R.id.setting_memory_mid_ttl_input)
    memoryShortTtlInput = findViewById(R.id.setting_memory_short_ttl_input)
    memoryHalfLifeInput = findViewById(R.id.setting_memory_half_life_input)
    memoryClearAllButton = findViewById(R.id.memory_clear_all_button)
    adbBinInput = findViewById(R.id.setting_adb_bin_input)
    adbSerialInput = findViewById(R.id.setting_adb_serial_input)
    timezoneFollowSystemToggle = findViewById(R.id.setting_timezone_follow_system_toggle)
    timezoneInput = findViewById(R.id.setting_timezone_input)
    loadSettingsInputs()
  }

  private fun decorateUi() {
    val root = findViewById<ViewGroup>(R.id.drawer_layout)
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
    conversationSection.clipToPadding = true
    conversationSection.clipChildren = true
    conversationListScroll.clipToPadding = true
    conversationListScroll.clipChildren = true
    conversationTabsContainer.clipToPadding = true
    conversationTabsContainer.clipChildren = true
    installNestedScrollLock(chatHistoryScroll)
    installNestedScrollLock(conversationListScroll)
  }

  private fun installNestedScrollLock(innerScroll: ScrollView) {
    innerScroll.setOnTouchListener { view, event ->
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

  private fun installSettingsAutoSave() {
    val settingInputs =
      listOf(
        openAiKeyInput,
        llmModelInput,
        llmBaseUrlInput,
        userIdInput,
        webvpnCookieInput,
        webvpnCsrfInput,
        learnBaseUrlInput,
        homeworkCookieInput,
        homeworkCsrfInput,
        campusFileInput,
        searchProviderInput,
        searchEndpointInput,
        searchApiKeyInput,
        searchSceneInput,
        searchTtlInput,
        memoryFileInput,
        memoryLongTtlInput,
        memoryMidTtlInput,
        memoryShortTtlInput,
        memoryHalfLifeInput,
        adbBinInput,
        adbSerialInput,
        timezoneInput,
      )
    settingInputs.forEach { input ->
      input.syncToViewModel { scheduleSettingsAutoSave() }
    }
    tlsToggle.setOnCheckedChangeListener { _, isChecked ->
      viewModel.updateTlsEnabled(isChecked)
      scheduleSettingsAutoSave()
      render()
    }
    timezoneFollowSystemToggle.setOnCheckedChangeListener { _, isChecked ->
      if (isChecked) {
        timezoneInput.setText(systemTimezoneId())
      }
      syncTimezoneInputState()
      scheduleSettingsAutoSave()
    }
  }

  private fun scheduleSettingsAutoSave() {
    if (suppressSettingsAutosave) return
    pendingSettingsSave?.let { uiRefreshHandler.removeCallbacks(it) }
    val task =
      Runnable {
        pendingSettingsSave = null
        if (persistSettings()) {
          viewModel.updateConfiguredModel(selectedModel())
        }
      }
    pendingSettingsSave = task
    uiRefreshHandler.postDelayed(task, 450L)
  }

  private fun flushPendingSettingsSave() {
    if (suppressSettingsAutosave || !::openAiKeyInput.isInitialized) return
    val pending = pendingSettingsSave ?: return
    uiRefreshHandler.removeCallbacks(pending)
    pendingSettingsSave = null
    if (persistSettings()) {
      viewModel.updateConfiguredModel(selectedModel())
    }
  }

  private fun EditText.syncToViewModel(onChanged: (String) -> Unit) {
    addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(
          s: CharSequence?,
          start: Int,
          count: Int,
          after: Int,
        ) = Unit

        override fun onTextChanged(
          s: CharSequence?,
          start: Int,
          before: Int,
          count: Int,
        ) {
          onChanged(s?.toString().orEmpty())
        }

        override fun afterTextChanged(s: Editable?) = Unit
      },
    )
  }

  private fun bindActions() {
    drawerToggleButton.setOnClickListener {
      if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
        drawerLayout.closeDrawer(GravityCompat.START)
      } else {
        drawerLayout.openDrawer(GravityCompat.START)
      }
    }

    chatTab.setOnClickListener {
      flushPendingSettingsSave()
      viewModel.selectDestination(AppDestination.Chat)
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }
    planningTab.setOnClickListener {
      flushPendingSettingsSave()
      viewModel.selectDestination(AppDestination.Planning)
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }
    settingsTab.setOnClickListener {
      flushPendingSettingsSave()
      viewModel.selectDestination(AppDestination.Settings)
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }
    newConversationButton.setOnClickListener {
      viewModel.createConversation()
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }

    chatAttachButton.setOnClickListener {
      chatFilePickerLauncher.launch(arrayOf("*/*"))
    }
    chatAttachmentView.setOnClickListener {
      clearChatAttachment()
    }
    chatAttachmentView.setOnLongClickListener {
      clearChatAttachment()
      true
    }
    chatAttachmentClearButton.setOnClickListener {
      clearChatAttachment()
    }
    chatSendButton.setOnClickListener {
      val text = chatInput.text.toString()
      viewModel.sendChatMessage(
        text = text,
        attachedFileUri = selectedChatFileUri?.toString(),
        attachedFileName = selectedChatFileName,
      )
      chatInput.setText("")
      clearChatAttachment()
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }

    bindSkillPlaceholder(skillCard1, "get_assignments")
    bindSkillPlaceholder(skillCard2, "get_campus_activities")
    bindSkillPlaceholder(skillCard3, "create_calendar_event")
    bindSkillPlaceholder(skillCard4, "read_notifications")
    quickSkillsToggle.setOnClickListener {
      quickSkillsContainer.visibility =
        if (quickSkillsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
      syncQuickSkillsToggle()
    }
    planningDeveloperToggleButton.setOnClickListener {
      setPlanningDetailsVisible(!showPlanningDetails)
      render()
    }
    planningDetailsToggle.setOnCheckedChangeListener { _, isChecked ->
      setPlanningDetailsVisible(isChecked)
      render()
    }

    preferenceAddButton.setOnClickListener {
      val editingIndex = editingPreferenceIndex
      val saved =
        if (editingIndex == null) {
          viewModel.addPreference(preferenceInput.text.toString())
        } else {
          viewModel.updatePreference(editingIndex, preferenceInput.text.toString())
        }
      if (saved) {
        preferenceInput.setText("")
        editingPreferenceIndex = null
        preferenceAddButton.text = getString(R.string.preference_add)
        Toast.makeText(
          this,
          getString(if (editingIndex == null) R.string.preference_saved else R.string.preference_updated),
          Toast.LENGTH_SHORT,
        ).show()
        render()
      } else {
        Toast.makeText(this, getString(R.string.preference_empty), Toast.LENGTH_SHORT).show()
      }
    }
    listOf(preferenceDelete1Button, preferenceDelete2Button, preferenceDelete3Button).forEach {
      it.visibility = View.GONE
    }
    preferenceDelete1Button.setOnClickListener { deletePreferenceAt(0) }
    preferenceDelete2Button.setOnClickListener { deletePreferenceAt(1) }
    preferenceDelete3Button.setOnClickListener { deletePreferenceAt(2) }
    memoryClearAllButton.setOnClickListener { confirmClearAllMemory() }

    statusView.setOnClickListener {
      viewModel.reconnectGateway()
      Toast.makeText(this, "正在重新连接服务器…", Toast.LENGTH_SHORT).show()
      render()
    }
    transportView.visibility = View.GONE

    connectButton.setOnClickListener {
      flushPendingSettingsSave()
      viewModel.updateHost(hostInput.text.toString())
      viewModel.updatePort(portInput.text.toString())
      viewModel.connectToGateway()
      render()
    }
    saveSettingsButton.setOnClickListener {
      pendingSettingsSave?.let { uiRefreshHandler.removeCallbacks(it) }
      pendingSettingsSave = null
      if (persistSettings()) {
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this, getString(R.string.settings_invalid), Toast.LENGTH_SHORT).show()
      }
    }
    testSettingsButton.setOnClickListener {
      flushPendingSettingsSave()
      val warnings = buildSettingsWarnings()
      val message =
        if (warnings.isEmpty()) {
          getString(R.string.settings_health_ok)
        } else {
          "${getString(R.string.settings_health_warn)}：${warnings.joinToString("；")}"
        }
      Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    learnCookieLoginButton.setOnClickListener {
      val learnBaseUrl = learnBaseUrlInput.text.toString().trim().ifEmpty { "https://learn.tsinghua.edu.cn" }
      getSharedPreferences("openthu_settings", MODE_PRIVATE)
        .edit()
        .putString("learn_base_url", learnBaseUrl)
        .apply()
      learnCookieLoginLauncher.launch(
        Intent(this, LearnCookieLoginActivity::class.java)
          .putExtra(LearnCookieLoginActivity.EXTRA_LEARN_BASE_URL, learnBaseUrl),
      )
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
        executeFocusedAction()
      }
    }
    actionSecondaryButton.setOnClickListener {
      if (viewModel.getUiState().pendingConflict != null) {
        viewModel.resolveConflict("coexist")
        render()
      } else {
        markFocusedActionForLater()
      }
    }
    actionTertiaryButton.setOnClickListener {
      if (viewModel.getUiState().pendingConflict != null) {
        viewModel.resolveConflict("delete_conflicts")
        render()
      } else {
        ignoreFocusedAction()
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

    hostInput.syncToViewModel { value ->
      viewModel.updateHost(value)
      scheduleSettingsAutoSave()
    }
    portInput.syncToViewModel { value ->
      viewModel.updatePort(value)
      scheduleSettingsAutoSave()
    }
    hostInput.setOnFocusChangeListener { _, hasFocus ->
      if (!hasFocus) uiRefreshHandler.post { applyConnectionDraftIfIdle() }
    }
    portInput.setOnFocusChangeListener { _, hasFocus ->
      if (!hasFocus) uiRefreshHandler.post { applyConnectionDraftIfIdle() }
    }
    installSettingsAutoSave()
  }

  private fun renderChatAttachment() {
    val name =
      selectedChatFileName
        .takeIf { it.isNotBlank() }
        ?: getString(R.string.chat_attachment_empty_file)
    chatAttachmentView.text = getString(R.string.chat_attachment_selected, name)
    chatAttachmentRow.visibility = View.VISIBLE
  }

  private fun clearChatAttachment() {
    selectedChatFileUri = null
    selectedChatFileName = ""
    chatAttachmentView.text = ""
    chatAttachmentRow.visibility = View.GONE
  }

  private fun resolveDisplayName(uri: Uri): String {
    val queried =
      runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index).orEmpty() else ""
          } else {
            ""
          }
        }.orEmpty()
      }.getOrDefault("")
    return queried.ifBlank {
      uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: getString(R.string.chat_attachment_empty_file)
    }
  }

  private fun render() {
    val state = viewModel.getUiState()
    val isPlanning = state.currentDestination == AppDestination.Planning
    val isSettings = state.currentDestination == AppDestination.Settings
    val isChat = state.currentDestination == AppDestination.Chat

    chatPanel.visibility = if (isChat) View.VISIBLE else View.GONE
    planningPage.visibility = if (isPlanning) View.VISIBLE else View.GONE
    settingsPage.visibility = if (isSettings) View.VISIBLE else View.GONE
    contextPanel.visibility = View.VISIBLE
    actionsPanel.visibility = View.VISIBLE
    safetyPanel.visibility = View.VISIBLE

    chatTab.isEnabled = state.currentDestination != AppDestination.Chat
    planningTab.isEnabled = state.currentDestination != AppDestination.Planning
    settingsTab.isEnabled = state.currentDestination != AppDestination.Settings
    syncQuickSkillsToggle()
    planningDeveloperContextSection.visibility = if (showPlanningDetails) View.VISIBLE else View.GONE
    planningDeveloperFlowSection.visibility = if (showPlanningDetails) View.VISIBLE else View.GONE
    actionFeedView.visibility = if (showPlanningDetails) View.VISIBLE else View.GONE
    if (planningDetailsToggle.isChecked != showPlanningDetails) {
      planningDetailsToggle.isChecked = showPlanningDetails
    }
    planningDeveloperToggleButton.text =
      if (showPlanningDetails) {
        getString(R.string.planning_details_hide)
      } else {
        getString(R.string.planning_details_show)
      }

    val connectionStatus = state.snapshot.connectionStatus
    statusView.text = connectionStatusText(connectionStatus)
    statusView.setTextColor(ContextCompat.getColor(this, connectionStatusTextColor(connectionStatus)))
    statusView.setBackgroundResource(connectionStatusBackgroundResource(connectionStatus))
    syncConnectionStatusEffects(connectionStatus)
    transportView.visibility = View.GONE
    pageTitleView.text =
      when (state.currentDestination) {
        AppDestination.Chat -> "Agent 对话"
        AppDestination.Planning -> "规划中心"
        AppDestination.Settings -> "设置中心"
      }
    onboardingView.text =
      when (state.currentDestination) {
        AppDestination.Chat -> "像 ChatGPT 一样自然地发起任务、提问和追踪执行结果。"
        AppDestination.Planning -> "把线索、日程、待办和执行建议收拢成一个真正可读的计划工作台。"
        AppDestination.Settings -> "管理能力开关、数据源、模型配置与运行参数。"
      }
    onboardingView.visibility = View.GONE

    renderChatHistory(state.chatMessages)
    renderConversationTabs(state.conversationSummaries)

    if (!hostInput.hasFocus() && hostInput.text.toString() != state.host) hostInput.setText(state.host)
    if (!portInput.hasFocus() && portInput.text.toString() != state.port) portInput.setText(state.port)
    tlsToggle.isChecked = state.tlsEnabled
    syncTimezoneInputState()

    contextSummaryView.text =
      buildString {
        append("校园资讯\n")
        append("• 教务通知：课程 DDL 与考试安排会在这里汇总。\n")
        append("• 校园活动：讲座、社团与志愿活动会按兴趣整理。\n")
        append("• 课程与日程：当前共有 ${state.tasks.size} 个任务，${state.memoryRecords.size} 条记忆。\n")
        append("• 待处理动作：${state.systemActions.count { it.status != "executed" }} 个。")
      }

    contextFeedView.text =
      buildString {
        append("规划说明\n")
        append("• 新收到的通知和网页内容会先进入结构化解析。\n")
        append("• 涉及日历或跨应用操作时，会先解释原因再等待确认。\n")
        append("• 执行结果、失败记录和回滚入口都会同步到这里。")

        if (state.contextSignals.isNotEmpty()) {
          append("\n\n最近上下文\n")
          append(
            state.contextSignals.take(6).joinToString(separator = "\n\n") { signal ->
              "${signal.title}\n${signal.detail}\n来源：${signal.source}"
            },
          )
        }
      }

    homeQuickActionsView.text =
      buildString {
        append("快捷动作建议\n")
        append("• 把课程、活动、考试转成日历事项。\n")
        append("• 为重要节点添加多层级提醒。\n")
        append("• 对不需要的信息做稍后提醒或忽略处理。\n")
        append("• 把你的偏好沉淀到记忆层，减少重复设置。")
      }

    taskFlowView.text =
      buildString {
        append("任务流转\n")
        append("1. 输入目标、通知或网页内容。\n")
        append("2. Planner 生成候选动作并进行策略审查。\n")
        append("3. 高风险动作等待确认后执行。\n")
        append("4. 结果回写到对话、规划和记忆模块。")
      }

    val planningSnapshotView = findViewById<TextView>(R.id.planning_snapshot_text)
    val planningMetricTasksView = findViewById<TextView>(R.id.planning_metric_tasks)
    val planningMetricActionsView = findViewById<TextView>(R.id.planning_metric_actions)
    val planningMetricSafetyView = findViewById<TextView>(R.id.planning_metric_safety)
    val planningMetricMemoryView = findViewById<TextView>(R.id.planning_metric_memory)
    val planningFocusView = findViewById<TextView>(R.id.planning_focus_text)
    val planningScheduleView = findViewById<TextView>(R.id.planning_schedule_text)
    val planningAlarmView = findViewById<TextView>(R.id.planning_alarm_text)
    val planningTodoView = findViewById<TextView>(R.id.planning_todo_text)

    planningSnapshotView.text =
      buildString {
        append("这里优先展示能帮助你决定下一步的计划项。\n")
        append("先看待行动与待确认内容，再决定提前、后排、执行或继续补充信息。")
      }
    planningMetricTasksView.text = state.tasks.size.toString()
    planningMetricActionsView.text = state.systemActions.count { planningActionNeedsAttention(it.status) }.toString()
    planningMetricSafetyView.text =
      state.systemActions.count { it.status == "pending_approval" || it.status == "conflict_pending" }.toString()
    planningMetricMemoryView.text = state.memoryRecords.size.toString()

    planningFocusView.text =
      when {
        state.planningCards.isNotEmpty() -> "建议先看：${state.planningCards.first().title}，确认它的下一步是否清楚。"
        state.tasks.isNotEmpty() -> "建议先拆解：${state.tasks.first().goal.take(42)}"
        state.systemActions.isNotEmpty() -> "建议先确认：${state.systemActions.first().title}"
        state.contextSignals.isNotEmpty() -> "可参考线索：${state.contextSignals.first().title}"
        else -> "当前还没有明确焦点，可以先回到对话页描述你的目标。"
      }

    planningScheduleView.text =
      buildString {
        append("课程线索\n")
        val content =
          state.contextSignals
            .filter { it.title.contains("课程") || it.title.contains("课表") || it.detail.contains("课程") }
            .take(5)
            .joinToString("\n") { "• ${it.title}" }
            .ifBlank { "• 暂无课程数据，等待课程或校历能力返回。" }
        append(content)
      }

    planningAlarmView.text =
      buildString {
        append("提醒状态\n")
        val content =
          state.systemActions
            .filter { it.id.contains("alarm") || it.title.contains("提醒") || it.title.contains("闹钟") }
            .take(5)
            .joinToString("\n") { "• ${it.title} · ${planningCardStatusLabel(it.status)}" }
            .ifBlank { "• 暂无提醒任务，可从规划动作中添加。" }
        append(content)
      }

    planningTodoView.text =
      buildString {
        append("待办摘要\n")
        val content =
          state.tasks
            .take(6)
            .joinToString("\n") { "• ${it.goal.take(34)}\n  下一步：查看计划项并确认待行动内容。" }
            .ifBlank { "• 暂无待办任务，先在对话页提交目标。" }
        append(content)
      }
    renderPlanningCards(state.planningCards)

    val conflict = state.pendingConflict
    if (conflict != null) {
      actionSummaryView.text = "当前有一个需要你立即决定的日历冲突。"
      actionFeedView.text =
        buildString {
          append("检测到日历冲突，请选择处理策略。\n\n")
          append(conflict.conflictMessage)
          append("\n\n• 跳过创建：保留现有事项。\n")
          append("• 共存：忽略冲突，直接写入。\n")
          append("• 删除冲突事项：清理后再写入。")
        }
      actionPrimaryButton.text = "跳过创建"
      actionSecondaryButton.text = "共存"
      actionTertiaryButton.text = "删除冲突事项"
      actionPrimaryButton.isEnabled = true
      actionSecondaryButton.isEnabled = true
      actionTertiaryButton.isEnabled = true
    } else {
      actionSummaryView.text =
        when {
          state.systemActions.isNotEmpty() -> "当前共有 ${state.systemActions.size} 个候选动作，优先查看第一个建议并决定是否执行。"
          else -> "当前还没有候选动作。先在对话页描述目标，规划页会在这里生成建议。"
        }
      actionFeedView.text =
        state.systemActions.joinToString(separator = "\n\n") { action ->
          val approval = if (action.requiresApproval) "需要确认" else "可自动执行"
          val result = action.lastResult ?: "尚未执行"
          val priority = recommendationTier(action)
          "${action.title}\n${action.summary}\n推荐：$priority · 风险：${action.riskLevel} · $approval · 状态：${action.status}\n原因：${action.explain}\n结果：$result"
        }.ifBlank { "当前还没有可执行动作。先在对话页描述你的目标，我们会在这里生成规划建议。" }

      val focusedAction = focusedAction(state.systemActions)
      configureActionButton(actionPrimaryButton, focusedAction?.title ?: "暂无可执行建议", focusedAction != null)
      configureActionButton(actionSecondaryButton, "稍后处理", focusedAction != null)
      configureActionButton(actionTertiaryButton, "忽略建议", focusedAction != null)
    }

    safetyFeedView.text =
      buildString {
        append("安全审查与解释记录\n")
        val content =
          state.safetyRecords
            .take(12)
            .joinToString(separator = "\n\n") { record ->
              "${record.title}\n${record.detail}\n状态：${record.status}"
            }
            .ifBlank { "暂无安全记录。" }
        append(content)
      }

    settingsPrivacyView.text =
      buildString {
        append("在这里统一管理 OpenTHU 的能力开关、连接方式、校园数据源、记忆策略与设备参数。\n")
        append("建议先完成连接与模型配置，再按需补充校园与搜索、记忆策略和设备选项。")
        val preferences =
          state.memoryRecords
            .filter { it.scope == "long" }
            .sortedByDescending { it.updatedAtEpochMs }
            .take(3)
        if (preferences.isNotEmpty()) {
          append("\n\n长期偏好\n")
          append(preferences.joinToString("\n") { "• ${it.value}" })
        }
      }
    preferenceAddButton.text =
      getString(if (editingPreferenceIndex == null) R.string.preference_add else R.string.preference_update)
    renderPreferenceList(state.memoryRecords)
    configurePreferenceDeleteButtons(state.memoryRecords)
    configureMemoryClearButton(state.memoryRecords)

    eventsView.text =
      state.snapshot.recentEvents
        .take(20)
        .joinToString(separator = "\n• ", prefix = "• ")
        .ifBlank { "• 暂无最近事件。" }

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
  }

  private fun renderConversationTabs(conversations: List<ConversationSummary>) {
    conversationTabsContainer.removeAllViews()
    conversations.forEach { summary ->
      val row =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(14), dp(12), dp(14), dp(12))
          setBackgroundResource(
            if (summary.selected) {
              R.drawable.button_primary_selector
            } else {
              R.drawable.button_secondary_selector
            },
          )
          isClickable = true
          isFocusable = true
          alpha = if (summary.selected) 1f else 0.92f
          setOnClickListener {
            viewModel.selectConversation(summary.id)
            drawerLayout.closeDrawer(GravityCompat.START)
            render()
          }
        }

      val textColumn =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams =
            LinearLayout.LayoutParams(
              0,
              LinearLayout.LayoutParams.WRAP_CONTENT,
              1f,
            )
        }

      val titleView =
        TextView(this).apply {
          text = summary.title
          textSize = 13f
          setTextColor(
            ContextCompat.getColor(
              this@MainActivity,
              if (summary.selected) R.color.white else R.color.opencray_ink,
            ),
          )
          setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

      val subtitleView =
        TextView(this).apply {
          text = summary.subtitle
          textSize = 12f
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
          alpha = 0.9f
          setTextColor(
            ContextCompat.getColor(
              this@MainActivity,
              if (summary.selected) R.color.white else R.color.opencray_muted,
            ),
          )
        }

      val deleteButton =
        Button(this).apply {
          text = getString(R.string.chat_delete_conversation)
          textSize = 11f
          minWidth = 0
          minimumWidth = 0
          minHeight = dp(34)
          minimumHeight = dp(34)
          setPadding(dp(10), dp(2), dp(10), dp(2))
          setBackgroundResource(R.drawable.button_secondary_selector)
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
          setOnClickListener {
            viewModel.deleteConversation(summary.id)
            render()
          }
          layoutParams =
            LinearLayout.LayoutParams(
              dp(56),
              dp(34),
            ).apply {
              marginStart = dp(8)
            }
        }

      textColumn.addView(titleView)
      textColumn.addView(subtitleView)
      row.addView(textColumn)
      row.addView(deleteButton)

      val params =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          bottomMargin = dp(8)
        }
      conversationTabsContainer.addView(row, params)
    }
  }

  private data class PlanningCardSection(
    val label: String,
    val value: String,
    val emphasized: Boolean = false,
  )

  private fun renderPlanningCards(cards: List<PlanningCard>) {
    planningCardsContainer.removeAllViews()
    if (cards.isEmpty()) {
      planningCardsContainer.addView(
        TextView(this).apply {
          text = getString(R.string.planning_cards_empty)
          textSize = 13f
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
          setBackgroundResource(R.drawable.chat_section_surface)
          setPadding(dp(14), dp(12), dp(14), dp(12))
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        },
      )
      return
    }

    cards.forEachIndexed { index, card ->
      val stepNumber = (index + 1).toString().padStart(2, '0')
      val cardView =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setBackgroundResource(R.drawable.planning_card_surface)
          setPadding(dp(14), dp(14), dp(14), dp(12))
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              if (index > 0) topMargin = dp(10)
            }
        }

      val headerRow =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

      headerRow.addView(
        TextView(this).apply {
          text = stepNumber
          textSize = 12f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          gravity = Gravity.CENTER
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_accent))
          setBackgroundResource(R.drawable.chat_status_chip_surface)
          layoutParams =
            LinearLayout.LayoutParams(
              dp(42),
              dp(34),
            )
        },
      )

      val titleColumn =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams =
            LinearLayout.LayoutParams(
              0,
              LinearLayout.LayoutParams.WRAP_CONTENT,
              1f,
            ).apply {
              marginStart = dp(10)
              marginEnd = dp(8)
            }
        }
      titleColumn.addView(
        TextView(this).apply {
          text = card.title
          textSize = 16f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
        },
      )
      titleColumn.addView(
        TextView(this).apply {
          text = planningCardSubtitle(card)
          textSize = 12f
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = dp(3)
            }
        },
      )
      headerRow.addView(titleColumn)
      headerRow.addView(
        createPlanningPill(planningRecommendationLabel(card), planningRecommendationColor(card)).apply {
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              marginEnd = dp(6)
            }
        },
      )
      headerRow.addView(createPlanningPill(planningCardStatusLabel(card.status), planningStatusColor(card.status)))
      cardView.addView(headerRow)

      planningCardSections(card).forEach { section ->
        cardView.addView(createPlanningSection(section))
      }

      cardView.addView(createPlanningFooter(card))

      val buttonRow =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = dp(12)
            }
        }
      buttonRow.addView(planningCardButton(getString(R.string.planning_card_move_up), index > 0) {
        viewModel.movePlanningCard(card.id, -1)
        render()
      })
      buttonRow.addView(planningCardButton(getString(R.string.planning_card_move_down), index < cards.lastIndex) {
        viewModel.movePlanningCard(card.id, 1)
        render()
      })
      buttonRow.addView(planningCardButton(getString(R.string.planning_card_delete), true) {
        viewModel.deletePlanningCard(card.id)
        render()
      })
      cardView.addView(buttonRow)
      planningCardsContainer.addView(cardView)
    }
  }

  private fun planningCardSections(card: PlanningCard): List<PlanningCardSection> {
    val sections = mutableListOf<PlanningCardSection>()
    val nextStep = planningBodySection(card.body, "下一步") ?: planningNextStepText(card)
    sections.add(PlanningCardSection("下一步", nextStep, emphasized = true))

    val details = planningBodySection(card.body, "计划要点") ?: planningMetadataDetails(card)
    if (details.isNotBlank()) {
      sections.add(PlanningCardSection("计划要点", details))
    }

    val reference = planningBodySection(card.body, "计划依据") ?: planningReferenceText(card)
    if (reference.isNotBlank()) {
      sections.add(PlanningCardSection("参考信息", reference))
    }

    val progress =
      planningBodySection(card.body, "进展备注")
        ?: planningBodySection(card.body, "结果")
        ?: planningBodySection(card.body, "反馈")
    if (!progress.isNullOrBlank()) {
      sections.add(PlanningCardSection("进展备注", progress))
    }
    return sections
  }

  private fun createPlanningSection(section: PlanningCardSection): LinearLayout =
    LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = if (section.emphasized) dp(14) else dp(10)
        }

      addView(
        TextView(this@MainActivity).apply {
          text = section.label
          textSize = 11f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          setTextColor(
            ContextCompat.getColor(
              this@MainActivity,
              if (section.emphasized) R.color.opencray_accent else R.color.opencray_primary_dark,
            ),
          )
        },
      )
      addView(
        TextView(this@MainActivity).apply {
          text = section.value
          textSize = if (section.emphasized) 14f else 13f
          setLineSpacing(2f, 1.0f)
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
          if (section.emphasized) {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
          }
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = dp(4)
            }
        },
      )
    }

  private fun createPlanningFooter(card: PlanningCard): TextView =
    TextView(this).apply {
      val source = card.source.ifBlank { "规划页" }
      text = listOf(planningCardTypeLabel(card.type), source).joinToString(" · ")
      textSize = 12f
      maxLines = 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = dp(12)
        }
    }

  private fun planningCardSubtitle(card: PlanningCard): String {
    val time = card.metadata["time"].orEmpty()
    val target = card.metadata["target"].orEmpty()
    return listOf(
      planningCardTypeLabel(card.type),
      time.ifBlank { target },
    ).filter { it.isNotBlank() }.joinToString(" · ")
  }

  private fun planningBodySection(
    body: String,
    label: String,
  ): String? {
    val chinesePrefix = "$label："
    val asciiPrefix = "$label:"
    return body
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { line -> line.startsWith(chinesePrefix) || line.startsWith(asciiPrefix) }
      ?.removePrefix(chinesePrefix)
      ?.removePrefix(asciiPrefix)
      ?.trim()
      ?.takeIf { it.isNotBlank() }
  }

  private fun planningMetadataDetails(card: PlanningCard): String =
    listOf(
      card.metadata["target"]?.takeIf { it.isNotBlank() }?.let { "对象：$it" },
      card.metadata["time"]?.takeIf { it.isNotBlank() }?.let { "时间：$it" },
      card.metadata["confirmation"]?.takeIf { it.isNotBlank() }?.let { "确认：$it" },
      card.metadata["confidence"]?.takeIf { it.isNotBlank() }?.let { "把握度：$it" },
      card.metadata["risk"]?.takeIf { it.isNotBlank() }?.let { "风险：$it" },
      card.metadata["recommendation"]?.takeIf { it.isNotBlank() }?.let { "推荐：$it" },
    ).filterNotNull().joinToString("；")

  private fun planningReferenceText(card: PlanningCard): String {
    val ignoredLabels = listOf("目标", "下一步", "计划要点", "计划依据", "进展备注", "结果", "反馈")
    val cleaned =
      card.body
        .lineSequence()
        .map { it.trim() }
        .filter { line ->
          line.isNotBlank() &&
            ignoredLabels.none { label -> line.startsWith("$label：") || line.startsWith("$label:") } &&
            !line.startsWith("Gateway planned skill:") &&
            !line.startsWith("Gateway dispatched skill:")
        }
        .joinToString("\n")
    if (cleaned.isNotBlank()) return cleaned.take(220)
    return card.metadata["goal"]?.takeIf { it.isNotBlank() }?.let { "目标：${it.take(120)}" }.orEmpty()
  }

  private fun planningNextStepText(card: PlanningCard): String =
    when (card.status) {
      "pending_approval" -> "先确认这个计划项是否可以执行；如果信息不完整，补充时间、地点或范围。"
      "queued" -> "等待端侧接收任务；如长时间无响应，可回到对话页补充上下文后重试。"
      "running" -> "等待当前能力返回结果，然后把结果拆成可执行的下一步。"
      "failed" -> "查看进展备注中的失败原因，调整参数后重试，或把这项后排。"
      "conflict_pending" -> "先处理冲突选择，再决定是写入日历、改期，还是保留原安排。"
      "snoozed" -> "稍后重新检查这项，如果仍然重要就提前到列表上方。"
      "ignored" -> "这项已搁置；保留它只用于参考，后续可以删除。"
      "ok", "executed", "completed" -> "复核产出是否满足目标；如果还需要行动，继续拆成新的提醒、日历或待办。"
      else ->
        when (card.type) {
          "alarm" -> "确认提醒时间和提醒内容，必要时补充提前量。"
          "todo" -> "明确截止时间、提交材料和第一步动作。"
          "course" -> "核对课程范围，把关键时间点转成复习或日历安排。"
          "calendar" -> "确认标题、时间、地点和冲突情况，再加入日历。"
          "homework" -> "核对课程、作业标题和截止时间，优先处理临近截止项。"
          "notification" -> "筛出需要行动的信息，转成待办、提醒或日历事项。"
          "search" -> "确认搜索范围，把有用结果整理成下一步行动。"
          else -> "补充目标、时间、地点和优先级，然后决定是否执行。"
        }
    }

  private fun planningCardButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
  ): Button =
    Button(this).apply {
      this.text = text
      isEnabled = enabled
      alpha = if (enabled) 1f else 0.45f
      minWidth = 0
      minimumWidth = 0
      minHeight = dp(36)
      minimumHeight = dp(36)
      textSize = 12f
      setAllCaps(false)
      setBackgroundResource(R.drawable.button_secondary_selector)
      setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
      stateListAnimator = null
      elevation = 0f
      setPadding(dp(8), dp(2), dp(8), dp(2))
      setOnClickListener { onClick() }
      layoutParams =
        LinearLayout.LayoutParams(
          0,
          dp(38),
          1f,
        ).apply {
          marginEnd = dp(8)
        }
    }

  private fun createPlanningMetaRow(card: PlanningCard): LinearLayout =
    LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = dp(8)
        }

      addView(createPlanningPill(planningCardTypeLabel(card.type), R.color.opencray_primary_dark))
      addView(
        createPlanningPill(planningCardStatusLabel(card.status), planningStatusColor(card.status)).apply {
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              marginStart = dp(6)
            }
        },
      )
      addView(
        TextView(this@MainActivity).apply {
          text = card.source.ifBlank { "规划页" }
          textSize = 12f
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
          layoutParams =
            LinearLayout.LayoutParams(
              0,
              LinearLayout.LayoutParams.WRAP_CONTENT,
              1f,
            ).apply {
              marginStart = dp(8)
            }
        },
      )
    }

  private fun createPlanningPill(
    text: String,
    colorRes: Int,
  ): TextView =
    TextView(this).apply {
      this.text = text
      textSize = 11f
      maxLines = 1
      setTypeface(typeface, android.graphics.Typeface.BOLD)
      setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
      setBackgroundResource(R.drawable.chat_status_chip_surface)
      setPadding(dp(8), dp(4), dp(8), dp(4))
    }

  private fun planningStatusColor(status: String): Int =
    when (status) {
      "ok", "executed", "completed" -> R.color.opencray_success
      "failed", "conflict_pending" -> R.color.opencray_danger
      "pending_approval", "queued", "running", "snoozed" -> R.color.opencray_warning
      "ignored" -> R.color.opencray_muted
      else -> R.color.opencray_primary_dark
    }

  private fun planningActionNeedsAttention(status: String): Boolean =
    status !in setOf("ok", "executed", "completed", "ignored")

  private fun planningCardTypeLabel(type: String): String =
    when (type) {
      "alarm" -> "闹钟"
      "todo" -> "待办"
      "course" -> "课表"
      "calendar" -> "日历"
      "homework" -> "作业"
      "notification" -> "通知"
      "search" -> "搜索"
      else -> "规划"
    }

  private fun planningCardStatusLabel(status: String): String =
    when (status) {
      "planned" -> "待行动"
      "approved" -> "待行动"
      "pending_approval" -> "待确认"
      "queued" -> "等待端侧"
      "ok" -> "可复核"
      "running" -> "处理中"
      "executed" -> "可复核"
      "completed" -> "可复核"
      "failed" -> "需调整"
      "conflict_pending" -> "需决策"
      "snoozed" -> "稍后处理"
      "ignored" -> "已忽略"
      else -> status.ifBlank { "待处理" }
    }

  private fun planningRecommendationLabel(card: PlanningCard): String =
    card.metadata["recommendation"]?.takeIf { it.isNotBlank() } ?: when {
      card.status == "ignored" -> "已忽略"
      card.status == "snoozed" -> "稍后"
      card.status == "pending_approval" || card.status == "conflict_pending" -> "强提醒"
      card.metadata["risk"].orEmpty().equals("high", ignoreCase = true) -> "强提醒"
      (card.metadata["confidence"]?.toIntOrNull() ?: 0) >= 80 -> "强推荐"
      (card.metadata["confidence"]?.toIntOrNull() ?: 0) >= 60 -> "中推荐"
      else -> "弱推荐"
    }

  private fun planningRecommendationColor(card: PlanningCard): Int =
    when (planningRecommendationLabel(card)) {
      "强提醒" -> R.color.opencray_danger
      "强推荐" -> R.color.opencray_success
      "中推荐" -> R.color.opencray_warning
      "已忽略", "稍后" -> R.color.opencray_muted
      else -> R.color.opencray_primary_dark
    }

  private fun syncQuickSkillsToggle() {
    quickSkillsToggle.text =
      getString(
        if (quickSkillsContainer.visibility == View.VISIBLE) {
          R.string.chat_quick_skills_toggle_collapse
        } else {
          R.string.chat_quick_skills_toggle_expand
        },
      )
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
      drawerLayout.closeDrawer(GravityCompat.START)
      render()
    }
  }

  private fun renderChatHistory(messages: List<ChatMessage>) {
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
      chatHistoryScroll.post { chatHistoryScroll.fullScroll(View.FOCUS_DOWN) }
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
    val shouldRenderMarkdown = message.role == ChatRole.Assistant || message.role == ChatRole.System
    val markdownText = "**$label**\n\n${message.text}"

    return TextView(this).apply {
      textSize = 14f
      setLineSpacing(2f, 1.0f)
      setPadding(dp(14), dp(10), dp(14), dp(10))
      if (shouldRenderMarkdown) {
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        markwon.setMarkdown(this, markdownText)
      } else {
        text = "$label\n${message.text}"
      }
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
          (resources.displayMetrics.widthPixels * 0.72f).toInt(),
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
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
      setPadding(dp(12), dp(10), dp(12), dp(10))
      setBackgroundResource(R.drawable.event_card_surface)
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
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          setTextColor(ContextCompat.getColor(this@MainActivity, eventToneColor(event)))
        },
      )
      addView(
        TextView(this@MainActivity).apply {
          text = body
          textSize = 12f
          setLineSpacing(2f, 1.0f)
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = dp(4)
            }
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

  private fun eventToneColor(event: AgentEvent): Int =
    when {
      event.type == AgentEventType.Error || event.status == "failed" -> R.color.opencray_danger
      event.type == AgentEventType.ConfirmationRequired -> R.color.opencray_warning
      event.type == AgentEventType.ToolResult -> R.color.opencray_success
      else -> R.color.opencray_primary_dark
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
        val isAffirmative = normalizedValue == "approve" || normalizedValue == "approved"
        val button =
          Button(this@MainActivity).apply {
            text = option.label.ifBlank { value }
            textSize = 12f
            setAllCaps(false)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundResource(
              if (isAffirmative) {
                R.drawable.button_primary_selector
              } else {
                R.drawable.button_secondary_selector
              },
            )
            setTextColor(
              ContextCompat.getColor(
                this@MainActivity,
                if (isAffirmative) R.color.white else R.color.opencray_ink,
              ),
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
    text: String,
    enabled: Boolean,
  ) {
    button.text = text
    button.isEnabled = enabled
  }

  private fun connectionStatusText(status: String): String =
    when {
      status.contains("未连接") || status.contains("failed", ignoreCase = true) -> "服务器未连接，点击重试"
      status.contains("连接到服务器") || status.contains("Connecting", ignoreCase = true) -> "连接到服务器..."
      status.contains("已连接") || status.contains("Connected", ignoreCase = true) -> "服务器已连接"
      else -> "服务器状态：$status"
    }

  private fun connectionStatusBackgroundResource(status: String): Int =
    when {
      connectionStatusUiState(status) == "disconnected" -> R.drawable.connection_status_disconnected
      connectionStatusUiState(status) == "connecting" -> R.drawable.connection_status_connecting
      connectionStatusUiState(status) == "connected" -> R.drawable.connection_status_connected
      else -> R.drawable.chat_status_chip_surface
    }

  private fun connectionStatusTextColor(status: String): Int =
    when {
      connectionStatusUiState(status) == "disconnected" -> R.color.opencray_danger
      connectionStatusUiState(status) == "connected" -> R.color.opencray_success
      else -> R.color.opencray_primary_dark
    }

  private fun connectionStatusUiState(status: String): String =
    when {
      status.contains("未连接") || status.contains("failed", ignoreCase = true) -> "disconnected"
      status.contains("连接到服务器") || status.contains("Connecting", ignoreCase = true) -> "connecting"
      status.contains("已连接") || status.contains("Connected", ignoreCase = true) -> "connected"
      else -> "unknown"
    }

  private fun syncConnectionStatusEffects(status: String) {
    val state = connectionStatusUiState(status)
    if (state != lastConnectionUiState) {
      when (state) {
        "connecting" -> Toast.makeText(this, "连接到服务器", Toast.LENGTH_SHORT).show()
        "disconnected" -> Toast.makeText(this, "服务器未连接", Toast.LENGTH_SHORT).show()
      }
      lastConnectionUiState = state
    }
    if (state == "connecting") {
      startConnectionPulse()
    } else {
      stopConnectionPulse()
    }
  }

  private fun startConnectionPulse() {
    if (connectionPulseActive) return
    connectionPulseActive = true
    pulseConnectionChip(toAlpha = 0.58f)
  }

  private fun pulseConnectionChip(toAlpha: Float) {
    if (!connectionPulseActive) return
    statusView.animate()
      .alpha(toAlpha)
      .setDuration(520L)
      .withEndAction {
        pulseConnectionChip(if (toAlpha < 1f) 1f else 0.58f)
      }
      .start()
  }

  private fun stopConnectionPulse() {
    if (!connectionPulseActive && statusView.alpha == 1f) return
    connectionPulseActive = false
    statusView.animate().cancel()
    statusView.alpha = 1f
  }

  private fun configurePreferenceDeleteButtons(memoryRecords: List<MemoryRecord>) {
    val count = memoryRecords.count { it.scope == "long" }
    listOf(preferenceDelete1Button, preferenceDelete2Button, preferenceDelete3Button)
      .forEachIndexed { index, button ->
        button.visibility = View.GONE
        button.isEnabled = index < count
        button.alpha = if (button.isEnabled) 1f else 0.45f
        button.text = if (button.isEnabled) "删${index + 1}" else "X"
      }
  }

  private fun configureMemoryClearButton(memoryRecords: List<MemoryRecord>) {
    memoryClearAllButton.isEnabled = memoryRecords.isNotEmpty()
    memoryClearAllButton.alpha = if (memoryClearAllButton.isEnabled) 1f else 0.45f
  }

  private fun renderPreferenceList(memoryRecords: List<MemoryRecord>) {
    preferenceListContainer.removeAllViews()
    val preferences =
      memoryRecords
        .filter { it.scope == "long" }
        .sortedByDescending { it.updatedAtEpochMs }
    if (preferences.isEmpty()) {
      preferenceListContainer.addView(
        TextView(this).apply {
          text = "还没有长期偏好。"
          textSize = 13f
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_muted))
        },
      )
      return
    }

    preferences.forEachIndexed { index, record ->
      val row =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setBackgroundResource(R.drawable.chat_section_surface)
          setPadding(dp(10), dp(8), dp(8), dp(8))
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              bottomMargin = dp(8)
            }
        }
      row.addView(
        TextView(this).apply {
          text = record.value
          textSize = 13f
          setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
          layoutParams =
            LinearLayout.LayoutParams(
              0,
              LinearLayout.LayoutParams.WRAP_CONTENT,
              1f,
            ).apply {
              marginEnd = dp(8)
            }
        },
      )
      row.addView(
        compactPreferenceButton("修改") {
          editingPreferenceIndex = index
          preferenceInput.setText(record.value)
          preferenceInput.setSelection(preferenceInput.text.length)
          preferenceAddButton.text = getString(R.string.preference_update)
          preferenceInput.requestFocus()
        },
      )
      row.addView(
        compactPreferenceButton("删除") {
          deletePreferenceAt(index)
        },
      )
      preferenceListContainer.addView(row)
    }
  }

  private fun compactPreferenceButton(
    label: String,
    onClick: () -> Unit,
  ): Button =
    Button(this).apply {
      text = label
      textSize = 12f
      minWidth = 0
      minimumWidth = 0
      minHeight = dp(34)
      minimumHeight = dp(34)
      setAllCaps(false)
      setBackgroundResource(R.drawable.button_secondary_selector)
      setTextColor(ContextCompat.getColor(this@MainActivity, R.color.opencray_ink))
      stateListAnimator = null
      elevation = 0f
      setPadding(dp(8), dp(2), dp(8), dp(2))
      setOnClickListener { onClick() }
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT,
          dp(36),
        ).apply {
          marginStart = dp(6)
        }
    }

  private fun focusedAction(actions: List<SystemAction>): SystemAction? =
    actions.firstOrNull { it.status in setOf("planned", "approved", "pending_approval", "conflict_pending") }
      ?: actions.firstOrNull { it.status !in setOf("executed", "failed", "ignored", "snoozed") }

  private fun recommendationTier(action: SystemAction): String =
    when {
      action.status == "ignored" -> "已忽略"
      action.status == "snoozed" -> "稍后处理"
      action.requiresApproval || action.riskLevel.equals("high", ignoreCase = true) -> "强提醒"
      action.confidence >= 80 -> "强推荐"
      action.confidence >= 60 -> "中推荐"
      else -> "弱推荐"
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

  private fun executeFocusedAction() {
    val action = focusedAction(viewModel.getUiState().systemActions)
    if (action == null) {
      Toast.makeText(this, "当前没有可执行建议。", Toast.LENGTH_SHORT).show()
      return
    }
    executeActionOrShowFeedback(action)
  }

  private fun markFocusedActionForLater() {
    val action = focusedAction(viewModel.getUiState().systemActions)
    if (action == null) {
      Toast.makeText(this, "当前没有可稍后处理的建议。", Toast.LENGTH_SHORT).show()
      return
    }
    val ok = viewModel.snoozeAction(action.id)
    Toast.makeText(this, if (ok) "已标记为稍后处理。" else "没有找到这条建议。", Toast.LENGTH_SHORT).show()
    render()
  }

  private fun ignoreFocusedAction() {
    val action = focusedAction(viewModel.getUiState().systemActions)
    if (action == null) {
      Toast.makeText(this, "当前没有可忽略的建议。", Toast.LENGTH_SHORT).show()
      return
    }
    val ok = viewModel.ignoreAction(action.id)
    Toast.makeText(this, if (ok) "已忽略该建议。偏好会用于后续排序。" else "没有找到这条建议。", Toast.LENGTH_SHORT).show()
    render()
  }

  private fun deletePreferenceAt(index: Int) {
    val deleted = viewModel.deletePreference(index)
    if (deleted && editingPreferenceIndex != null) {
      editingPreferenceIndex = null
      preferenceInput.setText("")
      preferenceAddButton.text = getString(R.string.preference_add)
    }
    Toast.makeText(
      this,
      if (deleted) getString(R.string.preference_deleted) else getString(R.string.preference_delete_empty),
      Toast.LENGTH_SHORT,
    ).show()
    render()
  }

  private fun confirmClearAllMemory() {
    AlertDialog.Builder(this)
      .setTitle(R.string.memory_clear_confirm_title)
      .setMessage(R.string.memory_clear_confirm_message)
      .setNegativeButton(R.string.memory_clear_cancel, null)
      .setPositiveButton(R.string.memory_clear_confirm_action) { _, _ ->
        val cleared = viewModel.clearAllMemory()
        editingPreferenceIndex = null
        preferenceInput.setText("")
        preferenceAddButton.text = getString(R.string.preference_add)
        Toast.makeText(
          this,
          getString(if (cleared) R.string.memory_clear_all_done else R.string.memory_clear_all_empty),
          Toast.LENGTH_SHORT,
        ).show()
        render()
      }
      .show()
  }

  private fun executeActionOrShowFeedback(action: SystemAction) {
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
    drawerLayout.openDrawer(GravityCompat.START)
    render()
  }

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
    suppressSettingsAutosave = true
    val pref = getSharedPreferences("openthu_settings", MODE_PRIVATE)
    val state = viewModel.getUiState()
    hostInput.setText(pref.getString(PREF_HOST, state.host).orEmpty().ifBlank { state.host })
    portInput.setText(
      (
        if (pref.contains(PREF_PORT)) {
          pref.getInt(PREF_PORT, state.port.toIntOrNull() ?: 18789)
        } else {
          state.port.toIntOrNull() ?: 18789
        }
      ).toString(),
    )
    tlsToggle.isChecked = pref.getBoolean(PREF_TLS_ENABLED, state.tlsEnabled)
    openAiKeyInput.setText(pref.getString("openai_api_key", ""))
    setModelInputText(pref.getString("llm_model", "gpt-4.1-mini").orEmpty())
    llmBaseUrlInput.setText(pref.getString("llm_base_url", ""))
    userIdInput.setText(pref.getString("user_id", "demo_user"))
    webvpnCookieInput.setText(pref.getString("webvpn_cookie", ""))
    webvpnCsrfInput.setText(pref.getString("webvpn_csrf", ""))
    learnBaseUrlInput.setText(pref.getString("learn_base_url", "https://learn.tsinghua.edu.cn"))
    homeworkCookieInput.setText(pref.getString("homework_cookie", ""))
    homeworkCsrfInput.setText(pref.getString("homework_csrf", ""))
    campusFileInput.setText(pref.getString("campus_file", ""))
    searchProviderInput.setText(pref.getString("search_provider", "duckduckgo"))
    searchEndpointInput.setText(pref.getString("search_endpoint", "https://lite.duckduckgo.com/lite/"))
    searchApiKeyInput.setText(pref.getString("search_api_key", ""))
    searchSceneInput.setText(pref.getString("search_scene", "hybrid"))
    searchTtlInput.setText(pref.getString("search_ttl", "3600"))
    showPlanningDetails = pref.getBoolean(PREF_SHOW_PLANNING_DETAILS, false)
    planningDetailsToggle.isChecked = showPlanningDetails
    memoryFileInput.setText(pref.getString("memory_file", "agent/langgraph/memory_store.json"))
    memoryLongTtlInput.setText(pref.getString("memory_long_ttl", "365"))
    memoryMidTtlInput.setText(pref.getString("memory_mid_ttl", "30"))
    memoryShortTtlInput.setText(pref.getString("memory_short_ttl", "7"))
    memoryHalfLifeInput.setText(pref.getString("memory_half_life", "30"))
    adbBinInput.setText(pref.getString("adb_bin", "adb"))
    adbSerialInput.setText(pref.getString("adb_serial", ""))
    val followSystemTimezone = pref.getBoolean(PREF_TIMEZONE_FOLLOW_SYSTEM, !pref.contains(PREF_TIMEZONE))
    timezoneFollowSystemToggle.isChecked = followSystemTimezone
    timezoneInput.setText(
      if (followSystemTimezone) {
        systemTimezoneId()
      } else {
        pref.getString(PREF_TIMEZONE, "UTC").orEmpty().ifBlank { systemTimezoneId() }
      },
    )
    syncTimezoneInputState()
    suppressSettingsAutosave = false
  }

  private fun persistSettings(): Boolean {
    val pref = getSharedPreferences("openthu_settings", MODE_PRIVATE)
    val host = hostInput.text.toString().trim()
    val port = portInput.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val hasValidConnectionConfig = host.isNotBlank() && port != null
    val timezone = timezoneInput.text.toString().trim().ifBlank { systemTimezoneId() }
    pref.edit()
      .apply {
        if (hasValidConnectionConfig) {
          putString(PREF_HOST, host)
          putInt(PREF_PORT, port!!)
        }
        putBoolean(PREF_TLS_ENABLED, tlsToggle.isChecked)
      }
      .putString("openai_api_key", openAiKeyInput.text.toString().trim())
      .putString("llm_model", selectedModel())
      .putString("llm_base_url", llmBaseUrlInput.text.toString().trim())
      .putString("user_id", userIdInput.text.toString().trim())
      .putString("webvpn_cookie", webvpnCookieInput.text.toString().trim())
      .putString("webvpn_csrf", webvpnCsrfInput.text.toString().trim())
      .putString("learn_base_url", learnBaseUrlInput.text.toString().trim().ifEmpty { "https://learn.tsinghua.edu.cn" })
      .putString("homework_cookie", homeworkCookieInput.text.toString().trim())
      .putString("homework_csrf", homeworkCsrfInput.text.toString().trim())
      .putString("campus_file", campusFileInput.text.toString().trim())
      .putString("search_provider", searchProviderInput.text.toString().trim().ifEmpty { "duckduckgo" })
      .putString("search_endpoint", normalizedSearchEndpoint())
      .putString("search_api_key", searchApiKeyInput.text.toString().trim())
      .putString("search_scene", searchSceneInput.text.toString().trim().lowercase().ifEmpty { "hybrid" })
      .putString("search_ttl", searchTtlInput.text.toString().trim())
      .putBoolean(PREF_SHOW_PLANNING_DETAILS, showPlanningDetails)
      .putString("memory_file", memoryFileInput.text.toString().trim())
      .putString("memory_long_ttl", memoryLongTtlInput.text.toString().trim())
      .putString("memory_mid_ttl", memoryMidTtlInput.text.toString().trim())
      .putString("memory_short_ttl", memoryShortTtlInput.text.toString().trim())
      .putString("memory_half_life", memoryHalfLifeInput.text.toString().trim())
      .putString("adb_bin", adbBinInput.text.toString().trim())
      .putString("adb_serial", adbSerialInput.text.toString().trim())
      .putBoolean(PREF_TIMEZONE_FOLLOW_SYSTEM, timezoneFollowSystemToggle.isChecked)
      .putString(PREF_TIMEZONE, timezone)
      .apply()
    return hasValidConnectionConfig
  }

  private fun applyConnectionDraftIfIdle() {
    if (hostInput.hasFocus() || portInput.hasFocus()) return
    viewModel.updateHost(hostInput.text.toString())
    viewModel.updatePort(portInput.text.toString())
    if (persistSettings() && viewModel.applyConnectionDraft(reconnectIfRegistered = true)) {
      render()
    }
  }

  private fun syncTimezoneInputState() {
    val followSystemTimezone = timezoneFollowSystemToggle.isChecked
    timezoneInput.isEnabled = !followSystemTimezone
    timezoneInput.alpha = if (followSystemTimezone) 0.65f else 1f
    if (followSystemTimezone && !timezoneInput.hasFocus() && timezoneInput.text.toString() != systemTimezoneId()) {
      timezoneInput.setText(systemTimezoneId())
    }
  }

  private fun systemTimezoneId(): String = ZoneId.systemDefault().id

  private fun selectedModel(): String =
    llmModelInput.text.toString().trim().ifBlank { "gpt-4.1-mini" }

  private fun setModelInputText(model: String) {
    val normalized = model.trim().ifBlank { "gpt-4.1-mini" }
    llmModelInput.setText(normalized)
  }

  private fun setPlanningDetailsVisible(visible: Boolean) {
    showPlanningDetails = visible
    getSharedPreferences("openthu_settings", MODE_PRIVATE)
      .edit()
      .putBoolean(PREF_SHOW_PLANNING_DETAILS, visible)
      .apply()
  }

  private fun normalizedSearchEndpoint(): String {
    val endpoint = searchEndpointInput.text.toString().trim()
    if (endpoint == "https://duckduckgo.com/html/" || endpoint == "https://html.duckduckgo.com/html/") {
      return "https://lite.duckduckgo.com/lite/"
    }
    return endpoint
  }

  private fun buildSettingsWarnings(): List<String> {
    val warnings = mutableListOf<String>()
    if (openAiKeyInput.text.toString().trim().isEmpty()) warnings += "缺少 OPENAI_API_KEY"
    if (webvpnCookieInput.text.toString().trim().isEmpty()) warnings += "缺少 WebVPN Cookie（校园资讯真实抓取会降级）"
    if (homeworkCookieInput.text.toString().trim().isEmpty()) warnings += "缺少网络学堂 Cookie（作业 skill 需要手动提供 Cookie）"
    if (
      searchProviderInput.text.toString().trim().equals("brave", ignoreCase = true) &&
      searchApiKeyInput.text.toString().trim().isEmpty()
    ) {
      warnings += "Brave provider 缺少 API Key"
    }
    return warnings
  }
}
