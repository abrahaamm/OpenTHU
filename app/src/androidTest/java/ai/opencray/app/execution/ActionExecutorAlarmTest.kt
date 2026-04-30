package ai.opencray.app.execution

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencray.app.domain.model.SystemAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ActionExecutorAlarmTest {

    // A custom context built to intercept startActivity without launching actual apps
    class IntentInterceptingContext(base: Context) : ContextWrapper(base) {
        var interceptedIntent: Intent? = null
        
        override fun startActivity(intent: Intent?) {
            // intercept the intent instead of actually launching the activity, which avoids disrupting the test runner
            interceptedIntent = intent
        }
    }

    @Test
    fun testSetAlarmIntentCreation() {
        // 1. Prepare testing environment
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = IntentInterceptingContext(baseContext)
        val executor = ActionExecutor(testContext)

        // 2. Prepare mock SystemAction from Agent. Note: The time must be an ISO8601 UTC string.
        // Let's create an exact UTC time and figure out what its local equivalent would be
        val mockInstant = Instant.parse("2026-04-26T14:30:00Z")
        val zdt = mockInstant.atZone(ZoneId.systemDefault())
        val expectedHour = zdt.hour
        val expectedMinute = zdt.minute

        val dummyAction = SystemAction(
            id = "set_alarm",
            title = "Set Python Alarm",
            summary = "E2E Skill testing",
            riskLevel = "low",
            requiresApproval = false,
            payload = mapOf(
                "time" to "2026-04-26T14:30:00Z",
                "label" to "Agent Reminder",
                "vibrate" to true
            )
        )

        // 3. Execute the action
        val report = executor.execute(dummyAction, "Test Setting Alarm")

        // 4. Verify Executor reporting success
        assertTrue("Executor should return success report", report.success)

        // 5. Verify the intent captured in ContextWrapper
        val capturedIntent = testContext.interceptedIntent
        assertNotNull("startActivity should have been called with an Intent", capturedIntent)
        
        capturedIntent?.let { intent ->
            assertEquals("Action must be exactly set_alarm", AlarmClock.ACTION_SET_ALARM, intent.action)
            assertEquals("Parsed hour doesn't match expected local time", expectedHour, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
            assertEquals("Parsed minute doesn't match expected local time", expectedMinute, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
            assertEquals("Message/label extra doesn't match", "Agent Reminder", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
            assertTrue("Vibrate extra should be true", intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, false))
            assertFalse("Skip UI should be explicit false per your ActionExecutor spec", intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
            
            // Verify FLAG_ACTIVITY_NEW_TASK was added
            assertTrue("Intent flag should include FLAG_ACTIVITY_NEW_TASK", (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        }
    }
    
    @Test
    fun testSetAlarmFailsGracefullyWithBadTimeFormat() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = IntentInterceptingContext(baseContext)
        val executor = ActionExecutor(testContext)

        val badAction = SystemAction(
            id = "set_alarm",
            title = "Set Python Alarm",
            summary = "E2E bad date formatted payload",
            riskLevel = "low",
            requiresApproval = false,
            payload = mapOf(
                "time" to "2026-04-26-NOT-ISO-FORMAT" 
            )
        )

        val report = executor.execute(badAction, "Test Bad Payload")
        
        assertFalse("Executor should fail due to invalid time format", report.success)
        assertNull("Intent should not be launched for bad payload", testContext.interceptedIntent)
    }
}
