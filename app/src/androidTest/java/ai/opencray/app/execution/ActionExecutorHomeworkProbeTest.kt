package ai.opencray.app.execution

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencray.app.domain.model.SystemAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionExecutorHomeworkProbeTest {
  companion object {
    private const val TAG = "HW-PROBE-TEST"
  }

  class IntentInterceptingContext(base: Context) : ContextWrapper(base) {
    val interceptedIntents: MutableList<Intent> = mutableListOf()

    override fun startActivity(intent: Intent?) {
      if (intent != null) interceptedIntents += intent
    }
  }

  @Test
  fun probeHomeworkCookieCrawlAndSubmit() {
    val args = InstrumentationRegistry.getArguments()
    val cookie = args.getString("homework.cookie", "").orEmpty().trim()
    val csrfToken = args.getString("homework.csrf", "").orEmpty().trim()
    val baseUrl = args.getString("homework.baseUrl", "https://learn.tsinghua.edu.cn").orEmpty().trim()
    val submitKeyword = "\u51b3\u7b56\u6811\u4e0e\u63d0\u5347\u7b97\u6cd5"

    assertTrue(
      "Missing instrumentation arg homework.cookie",
      cookie.isNotBlank(),
    )

    val baseContext = ApplicationProvider.getApplicationContext<Context>()
    val testContext = IntentInterceptingContext(baseContext)
    val executor = ActionExecutor(testContext)

    val cookieAction =
      SystemAction(
        id = "get_homework_cookie",
        title = "get_homework_cookie",
        summary = "homework backend probe",
        riskLevel = "high",
        requiresApproval = false,
        payload =
          linkedMapOf(
            "cookies" to cookie,
            "csrf_token" to csrfToken,
            "learn_base_url" to baseUrl,
          ),
      )
    val cookieReport = executor.execute(cookieAction, "probe homework cookie")
    println("[HW-PROBE] step=get_homework_cookie success=${cookieReport.success}")
    println("[HW-PROBE] get_homework_cookie data=${toJson(cookieReport.data)}")
    assertTrue("get_homework_cookie failed: ${cookieReport.message}", cookieReport.success)

    val crawlAction =
      SystemAction(
        id = "crawl_unsubmitted_homeworks",
        title = "crawl_unsubmitted_homeworks",
        summary = "homework backend probe",
        riskLevel = "low",
        requiresApproval = false,
        payload =
          linkedMapOf(
            "learn_base_url" to baseUrl,
            "session_cookie" to cookie,
            "csrf_token" to csrfToken,
            "include_overdue" to false,
          ),
      )
    val crawlReport = executor.execute(crawlAction, "probe crawl unsubmitted")
    println("[HW-PROBE] step=crawl_unsubmitted_homeworks success=${crawlReport.success}")
    printLargeBlock(
      title = "[HW-PROBE] crawl_unsubmitted_homeworks full_data",
      content = toPrettyJson(crawlReport.data),
    )
    assertTrue("crawl_unsubmitted_homeworks failed: ${crawlReport.message}", crawlReport.success)
    persistCrawlResult(testContext, baseUrl, crawlReport.data)
    printCrawlVisualization(crawlReport.data)

    val homeworks = crawlReport.data["homeworks"] as? List<*> ?: emptyList<Any>()
    val filteredHomeworks = homeworks.filterIsInstance<Map<*, *>>()
    assertTrue("No unsubmitted homework found from crawl result.", filteredHomeworks.isNotEmpty())

    val targetHomework =
      filteredHomeworks.firstOrNull { row ->
        val title = row["title"]?.toString().orEmpty()
        val hwId = row["homework_id"]?.toString().orEmpty().trim()
        val studentHwId = row["student_homework_id"]?.toString().orEmpty().trim()
        val validId =
          (hwId.isNotBlank() && !hwId.equals("null", ignoreCase = true)) ||
            (studentHwId.isNotBlank() && !studentHwId.equals("null", ignoreCase = true))
        title.contains(submitKeyword) && validId
      }
    assertTrue(
      buildString {
        append("No unsubmitted homework matched keyword: $submitKeyword")
        append("\nfound_titles=")
        append(
          filteredHomeworks
            .take(30)
            .joinToString(" | ") { it["title"]?.toString().orEmpty() },
        )
        append("\nfound_homework_ids=")
        append(
          filteredHomeworks
            .take(30)
            .joinToString(" | ") { it["homework_id"]?.toString().orEmpty() },
        )
      },
      targetHomework != null,
    )
    val homeworkIdRaw = targetHomework?.get("homework_id")?.toString().orEmpty().trim()
    val studentHomeworkIdRaw = targetHomework?.get("student_homework_id")?.toString().orEmpty().trim()
    val homeworkId =
      when {
        studentHomeworkIdRaw.isNotBlank() && !studentHomeworkIdRaw.equals("null", ignoreCase = true) -> studentHomeworkIdRaw
        homeworkIdRaw.isNotBlank() && !homeworkIdRaw.equals("null", ignoreCase = true) -> homeworkIdRaw
        else -> ""
      }
    assertTrue(
      "Matched homework has invalid ids: homework_id='$homeworkIdRaw', student_homework_id='$studentHomeworkIdRaw'",
      homeworkId.isNotBlank(),
    )

    val submitFile = File(testContext.cacheDir, "hw_probe_submit.txt")
    submitFile.writeText(
      "OpenTHU android instrumentation submit probe.\nkeyword=$submitKeyword\n",
      Charsets.UTF_8,
    )
    val submitAction =
      SystemAction(
        id = "submit_homework",
        title = "submit_homework",
        summary = "homework backend submit probe",
        riskLevel = "high",
        requiresApproval = true,
        params =
          linkedMapOf(
            "homework_id" to homeworkId,
            "learn_base_url" to baseUrl,
            "session_cookie" to cookie,
            "csrf_token" to csrfToken,
            "confirm_submit" to "true",
            "file_path" to submitFile.absolutePath,
            "file_name" to "hw_probe_submit.txt",
            "submission_text" to "Automated instrumentation submit probe.",
          ),
      )
    printLargeBlock(
      title = "[HW-PROBE] submit_homework request_params",
      content = toPrettyJson(submitAction.params),
    )
    val submitReport = executor.execute(submitAction, "probe submit homework")
    printLargeBlock(
      title = "[HW-PROBE] submit_homework full_data",
      content = toPrettyJson(submitReport.data),
    )
    println("[HW-PROBE] submit_homework success=${submitReport.success} message=${submitReport.message}")
    assertTrue(
      buildString {
        append("submit_homework failed for homework_id=$homeworkId")
        append("\nmessage=")
        append(submitReport.message)
        append("\nsubmit_data=")
        append(toPrettyJson(submitReport.data))
      },
      submitReport.success,
    )
  }

  private fun persistCrawlResult(
    context: Context,
    baseUrl: String,
    crawlData: Map<String, Any>,
  ) {
    val dir = File(context.getExternalFilesDir(null), "probe_results")
    if (!dir.exists()) dir.mkdirs()
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val output = File(dir, "crawl_unsubmitted_homeworks_$ts.json")
    val payload =
      JSONObject()
        .put("generated_at", ts)
        .put("base_url", baseUrl)
        .put("crawl_result", toJsonValue(crawlData))
    output.writeText(payload.toString(2), Charsets.UTF_8)
    println("[HW-PROBE] crawl_result_persisted=${output.absolutePath}")
  }

  private fun printCrawlVisualization(crawlData: Map<String, Any>) {
    val count = (crawlData["count"] as? Number)?.toInt() ?: 0
    val scope = crawlData["course_scope"]?.toString().orEmpty()
    println("[HW-PROBE][VIS] unsubmitted_count=$count course_scope=$scope")
    val homeworks = crawlData["homeworks"] as? List<*> ?: emptyList<Any>()
    homeworks
      .filterIsInstance<Map<*, *>>()
      .take(8)
      .forEachIndexed { index, row ->
        val title = row["title"]?.toString().orEmpty()
        val hwId = row["homework_id"]?.toString().orEmpty()
        val ddl = row["deadline"]?.toString().orEmpty()
        val course = row["course_name"]?.toString().orEmpty()
        println("[HW-PROBE][VIS][$index] hw=$hwId title=$title course=$course deadline=$ddl")
      }
  }

  private fun toJson(value: Any?): String =
    when (value) {
      null -> "null"
      is Map<*, *> -> mapToJson(value).toString()
      is List<*> -> listToJson(value).toString()
      else -> value.toString()
    }

  private fun toPrettyJson(value: Any?): String =
    when (value) {
      null -> "null"
      is Map<*, *> -> mapToJson(value).toString(2)
      is List<*> -> listToJson(value).toString(2)
      else -> value.toString()
    }

  private fun printLargeBlock(
    title: String,
    content: String,
  ) {
    Log.i(TAG, "$title BEGIN")
    println("$title BEGIN")
    if (content.isEmpty()) {
      Log.i(TAG, "$title <empty>")
      println("$title <empty>")
      Log.i(TAG, "$title END")
      println("$title END")
      return
    }
    val chunkSize = 1800
    var start = 0
    var index = 0
    while (start < content.length) {
      val end = minOf(start + chunkSize, content.length)
      Log.i(TAG, "$title CHUNK[$index]: ${content.substring(start, end)}")
      println("$title CHUNK[$index]: ${content.substring(start, end)}")
      start = end
      index += 1
    }
    Log.i(TAG, "$title END")
    println("$title END")
  }

  private fun mapToJson(map: Map<*, *>): JSONObject {
    val json = JSONObject()
    map.forEach { (key, value) ->
      if (key is String) {
        json.put(key, toJsonValue(value))
      }
    }
    return json
  }

  private fun listToJson(list: List<*>): JSONArray {
    val array = JSONArray()
    list.forEach { item -> array.put(toJsonValue(item)) }
    return array
  }

  private fun toJsonValue(value: Any?): Any? =
    when (value) {
      null -> JSONObject.NULL
      is Map<*, *> -> mapToJson(value)
      is List<*> -> listToJson(value)
      else -> value
    }
}

