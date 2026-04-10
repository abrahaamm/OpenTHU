package ai.opencray.app.data.repository

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AgentCapability
import ai.opencray.app.domain.model.CommonApp
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction

class FakeRuntimeRepository : RuntimeRepository {
  private var snapshot =
      RuntimeSnapshot(
        appTitle = "OpenCray",
        nodeName = "Cray Node Alpha",
        connectionStatus = "Bootstrapping",
        transportLabel = "Android system agent prototype",
        host = "192.168.0.88",
        port = 18789,
        tlsEnabled = false,
        featureFlags = listOf("context-aware", "cross-app", "safety-audit", "action-loop"),
        capabilities =
          listOf(
            AgentCapability(
              id = "notification_context",
              title = "Notification Context",
              description = "读取通知和近期事件，给系统级 agent 提供现场上下文。",
            ),
            AgentCapability(
              id = "cross_app_actions",
              title = "Cross-App Actions",
              description = "预留跨 App 操作、Intent 跳转和 UI 自动化执行链路。",
            ),
            AgentCapability(
              id = "safety_guard",
              title = "Safety Guard",
              description = "高风险动作确认、审计记录和可回放执行轨迹。",
            ),
          ),
        commonApps =
          listOf(
            CommonApp(id = "wechat", label = "WeChat", packageName = "com.tencent.mm"),
            CommonApp(id = "alipay", label = "Alipay", packageName = "com.eg.android.AlipayGphone"),
            CommonApp(id = "amap", label = "Amap", packageName = "com.autonavi.minimap"),
            CommonApp(id = "taobao", label = "Taobao", packageName = "com.taobao.taobao"),
            CommonApp(id = "meituan", label = "Meituan", packageName = "com.sankuai.meituan"),
            CommonApp(id = "qq", label = "QQ", packageName = "com.tencent.mobileqq"),
          ),
        contextSignals =
          listOf(
            ContextSignal(
              id = "notif_1",
              title = "Payment app notification",
              detail = "A verification code just arrived and can continue the sign-in flow.",
              source = "NotificationListener",
            ),
            ContextSignal(
              id = "share_1",
              title = "Shared text captured",
              detail = "User shared a dorm address from chat and may want to open navigation.",
              source = "ShareSheet",
            ),
            ContextSignal(
              id = "fg_1",
              title = "Foreground app observed",
              detail = "Maps app is on screen; agent can continue a route planning task.",
              source = "UsageState",
            ),
          ),
        systemActions =
          listOf(
            SystemAction(
              id = "open_map_route",
              title = "Continue route in Maps",
              summary = "Take the shared address and jump to navigation in the current map app.",
              riskLevel = "medium",
              requiresApproval = false,
            ),
            SystemAction(
              id = "fill_verification_code",
              title = "Fill verification code",
              summary = "Read the latest code notification and paste it into the active app.",
              riskLevel = "high",
              requiresApproval = true,
            ),
            SystemAction(
              id = "launch_food_order",
              title = "Prepare food ordering flow",
              summary = "Open the food delivery app and preload the dorm address workflow.",
              riskLevel = "medium",
              requiresApproval = true,
            ),
          ),
        safetyRecords =
          listOf(
            SafetyRecord(
              id = "safety_1",
              title = "Pending approval",
              detail = "Cross-app code fill requires explicit approval before execution.",
              status = "Awaiting approval",
            ),
            SafetyRecord(
              id = "safety_2",
              title = "Execution log",
              detail = "Every action is written into an audit trail for replay and rollback review.",
              status = "Active",
            ),
          ),
        recentEvents =
          listOf(
            "System-agent scaffold prepared from openclaw-inspired runtime layout.",
            "Context feed initialized with notification/share/foreground signals.",
            "Action center waiting for user approval or task trigger.",
          ),
      )

  override fun getSnapshot(): RuntimeSnapshot = snapshot

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

  override fun updateCommonApps(apps: List<CommonApp>) {
    snapshot = snapshot.copy(commonApps = apps)
  }

  override fun appendEvent(event: String) {
    snapshot = snapshot.copy(recentEvents = listOf(event) + snapshot.recentEvents)
  }
}
