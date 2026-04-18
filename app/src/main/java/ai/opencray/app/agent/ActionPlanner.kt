package ai.opencray.app.agent

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.SystemAction

class ActionPlanner {
  fun plan(goal: String, snapshot: RuntimeSnapshot): List<SystemAction> {
    val normalized = goal.lowercase()
    val actions = mutableListOf<SystemAction>()

    if (containsAny(normalized, listOf("课程", "课表", "class", "schedule", "考试", "ddl", "deadline", "作业"))) {
      actions +=
        SystemAction(
          id = "create_calendar_event",
          title = "Create Calendar Event",
          summary = "Create or update a campus schedule event from extracted course or deadline info.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 85,
          explain = "Goal mentions course/exam/deadline semantics.",
        )
      actions +=
        SystemAction(
          id = "set_alarm_reminder",
          title = "Set Reminder Alarm",
          summary = "Set a reminder alarm before the parsed campus event time.",
          riskLevel = "medium",
          requiresApproval = false,
          confidence = 80,
          explain = "Campus tasks usually require time-sensitive reminders.",
        )
    }

    if (containsAny(normalized, listOf("资讯", "新闻", "校园网", "活动", "news", "event"))) {
      actions +=
        SystemAction(
          id = "open_tsinghua_news",
          title = "Open Campus News",
          summary = "Open Tsinghua news or event page for user verification.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 90,
          explain = "Goal asks for campus information reading.",
        )
    }

    if (containsAny(normalized, listOf("微信", "支付", "验证码", "alipay", "wechat", "qq"))) {
      actions +=
        SystemAction(
          id = "cross_app_sensitive",
          title = "Sensitive Cross-App Automation",
          summary = "Run cross-app automation that may touch personal account operations.",
          riskLevel = "high",
          requiresApproval = true,
          confidence = 65,
          explain = "Contains account/payment-like signal and requires explicit confirmation.",
        )
    }

    if (actions.isEmpty()) {
      val fallbackAction =
        snapshot.systemActions.firstOrNull()?.copy(
          id = "fallback_contextual_action",
          title = "Fallback Contextual Action",
          summary = "No explicit domain intent detected. Keep context synced and suggest next step.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 55,
          explain = "Use safe fallback when intent confidence is low.",
          status = "planned",
          lastResult = null,
        ) ?: fallbackActionFromScratch()
      actions += fallbackAction
    }

    return actions
  }

  private fun fallbackActionFromScratch(): SystemAction =
    SystemAction(
      id = "fallback_contextual_action",
      title = "Fallback Contextual Action",
      summary = "Prepare a safe next suggestion based on current context signals.",
      riskLevel = "low",
      requiresApproval = false,
      confidence = 50,
      explain = "No high-confidence entity recognized.",
    )

  private fun containsAny(text: String, needles: List<String>): Boolean =
    needles.any { needle -> text.contains(needle) }
}
