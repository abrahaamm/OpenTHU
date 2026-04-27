package ai.opencray.app.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencray.app.data.repository.FakeChatRepository
import ai.opencray.app.data.repository.FakeRuntimeRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AgentCoreGatewayFlowTest {
  companion object {
    private const val TEST_GATEWAY_PORT = 28789
  }

  class IntentInterceptingContext(
    base: Context,
  ) : ContextWrapper(base) {
    val launchedIntents: MutableList<Intent> = mutableListOf()

    override fun startActivity(intent: Intent?) {
      if (intent != null) {
        launchedIntents += intent
      }
    }

    override fun getApplicationContext(): Context = this
  }

  @Test
  fun gatewayFlow_connect_plan_poll_execute_and_callback() {
    val baseContext = ApplicationProvider.getApplicationContext<Context>()
    val testContext = IntentInterceptingContext(baseContext)
    val runtime =
      OpenCrayRuntime(
        appContext = testContext,
        runtimeRepository = FakeRuntimeRepository(),
        chatRepository = FakeChatRepository(),
      )

    runtime.boot()
    runtime.connectToGateway(host = "10.0.2.2", port = TEST_GATEWAY_PORT, tlsEnabled = false)

    waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(15)) {
      val status = runtime.snapshot().connectionStatus.lowercase()
      status.contains("connected") || status.contains("failed")
    }
    assertTrue(
      "Gateway should be connected for integration test. Ensure agent_core_server is running on host:$TEST_GATEWAY_PORT",
      runtime.snapshot().connectionStatus.lowercase().contains("connected"),
    )

    runtime.planGoal("帮我设置一个明天早上7点的闹钟")
    waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(15)) {
      runtime.snapshot().systemActions.any { it.id == "set_alarm" }
    }

    runtime.runActions()

    waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(20)) {
      val snapshot = runtime.snapshot()
      val executed = snapshot.systemActions.any { it.id == "set_alarm" && it.status == "executed" }
      val callbackSent = snapshot.recentEvents.any { it.contains("Result submitted for set_alarm") }
      executed && callbackSent
    }

    val snapshot = runtime.snapshot()
    assertTrue(
      "Expected ACTION_SET_ALARM intent launched by ActionExecutor",
      testContext.launchedIntents.any { it.action == AlarmClock.ACTION_SET_ALARM },
    )
    assertTrue(
      "Expected set_alarm action status=executed",
      snapshot.systemActions.any { it.id == "set_alarm" && it.status == "executed" },
    )
    assertTrue(
      "Expected callback submission event in recent events",
      snapshot.recentEvents.any { it.contains("Result submitted for set_alarm") },
    )
  }

  private fun waitUntil(
    timeoutMs: Long,
    intervalMs: Long = 250L,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(intervalMs)
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
  }
}
