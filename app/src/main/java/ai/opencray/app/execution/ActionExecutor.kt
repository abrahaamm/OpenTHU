package ai.opencray.app.execution

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.opencray.app.domain.model.SystemAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
  val semantic: String = if (success) "action_executed" else "action_failed",
  val metadata: Map<String, Any?> = emptyMap(),

)

private data class CalendarWindow(
  val startMs: Long,
  val endMs: Long,
)

private data class CalendarEventBrief(
  val id: Long,
  val title: String,
  val startMs: Long,
  val endMs: Long,
)

private data class CampusActivityRecord(
  val activityId: String = "",
  val title: String,
  val startTime: String = "",
  val location: String = "",
  val organizer: String = "",
  val category: String = "",
  val abstract: String = "",
  val url: String = "",
  val source: String = "",
) {
  fun toDataMap(): Map<String, String> =
    mapOf(
      "activity_id" to activityId,
      "title" to title,
      "start_time" to startTime,
      "time" to startTime,
      "location" to location,
      "organizer" to organizer,
      "category" to category,
      "abstract" to abstract,
      "url" to url,
      "source" to source,
    ).filterValues { it.isNotBlank() }
}

private data class CampusActivityLoadResult(
  val activities: List<CampusActivityRecord> = emptyList(),
  val source: String = "",
  val error: String = "",
  val status: String = "",
  val reason: String = "",
  val warnings: List<String> = emptyList(),
  val sourceDetails: List<Map<String, Any?>> = emptyList(),
)

private data class InfoHttpResponse(
  val statusCode: Int,
  val body: String,
  val setCookies: List<String> = emptyList(),
)

private data class InfoSession(
  val cookie: String,
  val csrf: String,
  val csrfSource: String,
)

private class InfoHttpException(
  val statusCode: Int,
  val responseExcerpt: String,
  message: String,
) : RuntimeException(message)

class ActionExecutor(
  private val appContext: Context,
) {
  private val homeworkSkillExecutor = HomeworkSkillExecutor(appContext)
  private val courseSkillExecutor = CourseSkillExecutor(appContext)

  private companion object {
    private const val INFO_PUBLIC_BASE_URL = "https://info.tsinghua.edu.cn"
    private const val INFO_ACTIVITY_INDEX_URL = "$INFO_PUBLIC_BASE_URL/f/info/hdrc_fg/teacher/index"
    private const val INFO_ACTIVITY_ALL_URL = "$INFO_PUBLIC_BASE_URL/b/hdrc_fg/index/all"
    private const val INFO_ACTIVITY_DETAIL_URL = "$INFO_PUBLIC_BASE_URL/f/info/hdrc_fg/common/detail?hdrc_id="
    private const val CAMPUS_ACTIVITY_CANDIDATE_LIMIT = 50
    private const val LEGACY_WEBVPN_COOKIE_URL =
      "https://webvpn.tsinghua.edu.cn/" +
        "wengine-vpn/cookie?method=get&host=info.tsinghua.edu.cn&scheme=https&path=/f/info/gxfw_fg/common/index"
    private const val LEGACY_INFO_NEWS_LIST_URL =
      "https://webvpn.tsinghua.edu.cn/https/" +
        "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/" +
        "b/info/xxfb_fg/xnzx/template/more?oType=xs&lydw="
    private const val LEGACY_INFO_SEARCH_URL =
      "https://webvpn.tsinghua.edu.cn/https/" +
        "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/" +
        "b/xnzx/search/info/xxfb_fg/teacher/getMobilePageList"
    private const val LEGACY_INFO_REDIRECT_URL =
      "https://webvpn.tsinghua.edu.cn/https/" +
        "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de"
    private const val DEFAULT_DUCKDUCKGO_ENDPOINT = "https://lite.duckduckgo.com/lite/"
    private val activityChannels =
      listOf("LM_HB", "LM_XJ_XSSQDT", "LM_JYGG", "LM_KYTZ", "LM_XJ_XTWBGTZ")
    private val defaultActivityKeywords =
      listOf("讲座", "活动", "论坛", "沙龙", "报名")
    private val cookieAttributeNames =
      setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")
  }

  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    val actionId = action.id.substringBefore("#")
    return when (actionId) {
      "get_current_time" -> executeGetCurrentTime()
      "create_calendar_event" -> executeCreateCalendarEvent(action, goal)
      "detect_calendar_conflicts" -> executeConflictDetection(action, goal)
      "delete_calendar_event" -> executeDeleteCalendarEvent(action)
      "get_homework_cookie",
      "get_assignments",
      "crawl_course_homeworks",
      "crawl_unsubmitted_homeworks",
      "preview_homework_attachments",
      "upload_homework_attachment",
      "submit_homework" -> homeworkSkillExecutor.execute(action)
      "get_semesters",
      "get_courses",
      "get_course_schedule" -> courseSkillExecutor.execute(action)
      "set_alarm_reminder", "set_alarm" -> executeAlarmIntent(action, goal)
      "create_reminder" -> executeCreateReminder(action, goal)
      "get_campus_activities" -> executeGetCampusActivities(action)
      "read_notifications" -> executeReadNotifications()
      "show_summary" -> executeShowSummary(action)
      "send_notification" -> executeSendNotification(action)
      "open_tsinghua_news" -> openWebPage("https://www.tsinghua.edu.cn")
      "open_url" -> {
        val url =
          (action.payload?.get("url") as? String)
            ?: action.params["url"]
        if (url.isNullOrBlank()) {
          ActionExecutionReport(
            success = false,
            message = "Missing required `url` for open_url.",
            recoverable = false,
            semantic = "invalid_param",
          )
        } else {
          openWebPage(url)
        }
      }
      "open_context_review" ->
        ActionExecutionReport(
          success = false,
          message = "open_context_review is not configured on this device.",
          recoverable = false,
          semantic = "not_configured",
        )
      else ->
        ActionExecutionReport(
          success = false,
          message = "Unsupported action: ${action.id}.",
          recoverable = false,
          semantic = "unsupported_action",
        )
    }
  }

  private fun executeGetCampusActivities(action: SystemAction): ActionExecutionReport {
    val query = firstNonBlank(
      readActionString(action, "query"),
      readActionString(action, "question"),
      readActionString(action, "keyword"),
    )
    val keywords =
      parseCsvOrJsonArray(readActionString(action, "keywords"))
        .ifEmpty { tokenizeCampusQuery(query) }
        .ifEmpty { defaultActivityKeywords }
    val limit = readActionString(action, "limit").toIntOrNull()?.coerceIn(1, 30) ?: 10
    val startDate = firstNonBlank(readActionString(action, "start_date"), readActionString(action, "startDate"))
    val endDate = firstNonBlank(readActionString(action, "end_date"), readActionString(action, "endDate"))

    val infoLoad = loadInfoCampusActivities(CAMPUS_ACTIVITY_CANDIDATE_LIMIT)
    val configuredLoad =
      if (infoLoad.activities.isEmpty()) {
        loadConfiguredCampusActivities()
      } else {
        CampusActivityLoadResult()
      }
    val load =
      listOf(infoLoad, configuredLoad)
        .firstOrNull { it.activities.isNotEmpty() }
        ?: CampusActivityLoadResult(
          source = firstNonBlank(infoLoad.source, configuredLoad.source, "not_configured"),
          status = campusActivitiesFailureStatus(infoLoad, configuredLoad),
          reason = campusActivitiesFailureReason(infoLoad, configuredLoad),
          warnings = infoLoad.warnings + configuredLoad.warnings,
          sourceDetails = infoLoad.sourceDetails + configuredLoad.sourceDetails,
          error = firstNonBlank(infoLoad.error, configuredLoad.error),
        )

    if (load.activities.isEmpty()) {
      val loadError = firstNonBlank(
        load.error,
        configuredLoad.error,
        "手机端未获取到校园活动数据。当前 get_campus_activities 仅使用 INFO/WebVPN 校内接口或本地校园活动文件。",
      )
      return ActionExecutionReport(
        success = false,
        message = loadError,
        recoverable = false,
        semantic = if (load.reason == "missing_webvpn_cookie") "not_configured" else "upstream_unavailable",
        metadata =
          mapOf(
            "status" to firstNonBlank(load.status, "source_unavailable"),
            "activities" to emptyList<Map<String, String>>(),
            "source" to firstNonBlank(load.source, configuredLoad.source, "not_configured"),
            "reason" to firstNonBlank(load.reason, "source_unavailable"),
            "warnings" to load.warnings,
            "sources" to load.sourceDetails,
          ),
      )
    }
    val dateFilteredCandidates =
      filterCampusActivitiesByDate(load.activities, startDate, endDate)
        .take(CAMPUS_ACTIVITY_CANDIDATE_LIMIT)
    val filtered =
      filterCampusActivities(dateFilteredCandidates, keywords)
        .take(limit)
    val result = if (filtered.isNotEmpty()) filtered else dateFilteredCandidates.take(limit)
    val summary =
      buildString {
        append("手机端校园活动检索完成：")
        append(if (filtered.isNotEmpty()) "匹配到 ${filtered.size} 条" else "未命中关键词或日期条件，返回前 ${result.size} 条候选")
        append("。")
        result.take(6).forEach { item ->
          append("\n- ").append(item.title)
          val details = listOf(item.startTime, item.location).filter { it.isNotBlank() }.joinToString("，")
          if (details.isNotBlank()) append("（").append(details).append("）")
        }
      }

    return ActionExecutionReport(
      success = true,
      message = summary,
      recoverable = false,
      semantic = "campus_activities_loaded_on_device",
      metadata =
        mapOf(
          "status" to "activities_loaded",
          "answer" to summary,
          "summary" to summary,
          "activities" to result.map { it.toDataMap() },
          "candidate_activities" to dateFilteredCandidates.map { it.toDataMap() },
          "count" to result.size,
          "candidate_count" to dateFilteredCandidates.size,
          "raw_count" to load.activities.size,
          "source" to load.source,
          "sources" to load.sourceDetails,
          "warnings" to load.warnings,
          "query" to query,
          "filter_input" to
            mapOf(
              "query" to query,
              "keywords" to keywords,
              "start_date" to startDate,
              "end_date" to endDate,
              "limit" to limit,
              "candidate_limit" to CAMPUS_ACTIVITY_CANDIDATE_LIMIT,
            ),
        ),
    )
  }

  private fun loadConfiguredCampusActivities(): CampusActivityLoadResult {
    val inlineJson = readSetting("campus_activities_json")
    if (inlineJson.isNotBlank()) {
      return parseCampusActivities(inlineJson, "settings:campus_activities_json")
    }

    val pathText = readSetting("campus_file")
    if (pathText.isBlank()) {
      return CampusActivityLoadResult()
    }
    val file = File(pathText)
    if (!file.isFile || !file.canRead()) {
      return CampusActivityLoadResult(
        source = pathText,
        error = "手机端无法读取校园活动文件：$pathText",
        status = "source_unavailable",
        reason = "configured_file_unavailable",
        sourceDetails = listOf(mapOf("type" to "configured_file", "source" to pathText, "status" to "failed", "reason" to "unreadable")),
      )
    }
    if (file.length() > 2L * 1024L * 1024L) {
      return CampusActivityLoadResult(
        source = pathText,
        error = "校园活动文件超过 2MB，暂不在手机端读取：$pathText",
        status = "source_unavailable",
        reason = "configured_file_unavailable",
        sourceDetails = listOf(mapOf("type" to "configured_file", "source" to pathText, "status" to "failed", "reason" to "too_large")),
      )
    }
    return runCatching {
      parseCampusActivities(file.readText(Charsets.UTF_8), pathText)
    }.getOrElse { throwable ->
      CampusActivityLoadResult(
        source = pathText,
        error = "校园活动文件解析失败：${throwable.message ?: throwable.javaClass.simpleName}",
        status = "source_unavailable",
        reason = "configured_file_unavailable",
        sourceDetails = listOf(mapOf("type" to "configured_file", "source" to pathText, "status" to "failed", "message" to safeErrorMessage(throwable))),
      )
    }
  }

  private fun parseCampusActivities(
    rawJson: String,
    source: String,
  ): CampusActivityLoadResult =
    runCatching {
      val trimmed = rawJson.trim()
      val array =
        if (trimmed.startsWith("[")) {
          JSONArray(trimmed)
        } else {
          val root = JSONObject(trimmed)
          root.optJSONArray("activities")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("data")
            ?: JSONArray()
        }
      val records =
        (0 until array.length())
          .mapNotNull { index -> array.optJSONObject(index)?.toCampusActivityRecord() }
          .filter { it.title.isNotBlank() }
      CampusActivityLoadResult(
        activities = records,
        source = source,
        status = if (records.isEmpty()) "source_unavailable" else "ok",
        reason = if (records.isEmpty()) "configured_file_unavailable" else "",
        sourceDetails = listOf(mapOf("type" to "configured_file", "source" to source, "status" to "ok", "count" to records.size)),
      )
    }.getOrElse { throwable ->
      CampusActivityLoadResult(
        source = source,
        error = "校园活动 JSON 解析失败：${throwable.message ?: throwable.javaClass.simpleName}",
        status = "source_unavailable",
        reason = "configured_file_unavailable",
        sourceDetails = listOf(mapOf("type" to "configured_file", "source" to source, "status" to "failed", "message" to safeErrorMessage(throwable))),
      )
    }

  private fun loadInfoCampusActivities(candidateLimit: Int): CampusActivityLoadResult {
    val warnings = mutableListOf<String>()
    val sources = mutableListOf<Map<String, Any?>>()
    val records = linkedMapOf<String, CampusActivityRecord>()
    val infoSession =
      runCatching { preparePublicInfoSession() }
        .getOrElse { throwable ->
          return CampusActivityLoadResult(
            source = "info_hdrc_public_api",
            error = "INFO 公开活动日程页面不可用：${throwable.message ?: throwable.javaClass.simpleName}",
            status = "info_failed",
            reason = "info_request_failed",
            sourceDetails = listOf(
              mapOf(
                "type" to "info_hdrc_public_api",
                "status" to "failed",
                "reason" to "info_request_failed",
                "message" to safeErrorMessage(throwable),
              ),
            ),
          )
        }

    val listResult =
      runCatching {
        val response = infoHttpRequest(
          urlText = INFO_ACTIVITY_ALL_URL,
          method = "POST",
          body = "page=1&cjf=-1&hdlxm=&fbdwnm=",
          cookie = infoSession.cookie,
          csrf = infoSession.csrf,
          referer = INFO_ACTIVITY_INDEX_URL,
          ajax = true,
        )
        val root = JSONObject(response.body)
        val resultList = root.optJSONObject("object")?.optJSONArray("resultList") ?: JSONArray()
        var rawCount = 0
        var filteredCount = 0
        for (i in 0 until resultList.length()) {
          val day = resultList.optJSONObject(i) ?: continue
          val activities = day.optJSONArray("hdrcList") ?: JSONArray()
          rawCount += activities.length()
          for (j in 0 until activities.length()) {
            val row = activities.optJSONObject(j) ?: continue
            val activity = row.toHdrcCampusActivityRecord()
            if (activity.title.isNotBlank()) {
              records[activity.url.ifBlank { "${activity.title}:${activity.startTime}" }] = activity
              filteredCount += 1
            }
          }
        }
        mapOf(
          "type" to "info_hdrc_public_list",
          "status" to "ok",
          "http_status" to response.statusCode,
          "raw_count" to rawCount,
          "filtered_count" to filteredCount,
          "count" to filteredCount,
          "response_excerpt_length" to response.body.take(512).length,
          "page" to 1,
        )
      }
    sources += listResult.getOrElse { throwable ->
      infoFailureDetail("info_hdrc_public_list", throwable, mapOf("page" to 1))
    }

    val sourceDetails =
      listOf(
        mapOf(
          "type" to "info_hdrc_public_api",
          "status" to "ready",
          "has_cookie" to infoSession.cookie.isNotBlank(),
          "cookie_names" to cookieNames(infoSession.cookie),
          "has_csrf" to infoSession.csrf.isNotBlank(),
          "csrf_source" to infoSession.csrfSource,
          "index_url" to INFO_ACTIVITY_INDEX_URL,
        ),
      ) + sources

    if (records.isEmpty()) {
      warnings += if (sources.any { it["status"] == "failed" }) {
        "INFO 公开活动日程接口请求失败或未返回可解析活动。"
      } else {
        "INFO 公开活动日程未返回匹配的校园活动。"
      }
    }
    val hasFailedSource = sources.any { it["status"] == "failed" }
    return CampusActivityLoadResult(
      activities = records.values.take(candidateLimit).toList(),
      source = "info_hdrc_public_api",
      status = if (records.isEmpty()) if (hasFailedSource) "info_failed" else "info_empty" else "ok",
      reason = if (records.isEmpty()) if (hasFailedSource) "info_request_failed" else "info_returned_no_records" else "",
      warnings = warnings,
      sourceDetails = sourceDetails,
    )
  }

  private fun loadLegacyWebvpnCampusActivities(
    keywords: List<String>,
    limit: Int,
  ): CampusActivityLoadResult {
    val cookie = normalizeCookieHeader(firstNonBlank(readSetting("webvpn_cookie"), readSetting("info_cookie")))
    val csrfSetting = firstNonBlank(readSetting("webvpn_csrf"), readSetting("csrf"), readSetting("csrf_token"))
    if (cookie.isBlank()) {
      return CampusActivityLoadResult(
        source = "info_webvpn_api",
        status = "not_configured",
        reason = "missing_webvpn_cookie",
        warnings = listOf("未配置 WebVPN Cookie，已跳过 INFO 校内资讯接口。"),
        sourceDetails = listOf(
          mapOf(
            "type" to "info_webvpn_api",
            "status" to "skipped",
            "reason" to "missing_webvpn_cookie",
            "has_cookie" to false,
            "has_csrf" to csrfSetting.isNotBlank(),
          ),
        ),
      )
    }

    val warnings = mutableListOf<String>()
    val sources = mutableListOf<Map<String, Any?>>()
    val records = linkedMapOf<String, CampusActivityRecord>()
    val infoSession =
      runCatching { prepareInfoSession(cookie, csrfSetting) }
        .getOrElse { throwable ->
          return CampusActivityLoadResult(
            source = "info_webvpn_api",
            error = "INFO/WebVPN 登录态不可用：${throwable.message ?: throwable.javaClass.simpleName}",
            status = "info_failed",
            reason = "csrf_unavailable",
            sourceDetails = listOf(
              mapOf(
                "type" to "info_webvpn_api",
                "status" to "failed",
                "reason" to "csrf_unavailable",
                "message" to safeErrorMessage(throwable),
                "has_cookie" to true,
                "cookie_names" to cookieNames(cookie),
                "has_csrf" to csrfSetting.isNotBlank(),
              ),
            ),
          )
        }

    activityChannels.forEach { channel ->
      val url =
        "$LEGACY_INFO_NEWS_LIST_URL&lmid=$channel&currentPage=1&length=20&_csrf=${encodeQueryValue(infoSession.csrf)}"
      val channelResult =
        runCatching {
          val response = infoHttpRequest(
            urlText = url,
            cookie = infoSession.cookie,
            csrf = infoSession.csrf,
            referer = LEGACY_INFO_REDIRECT_URL + "/f/info/gxfw_fg/common/index",
            ajax = true,
          )
          val rows = JSONObject(response.body).optJSONObject("object")
            ?.optJSONArray("dataList")
            ?: JSONArray()
          var filteredCount = 0
          for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val activity = row.toInfoCampusActivityRecord(channel)
            if (activity.title.isNotBlank() && looksCampusActivityRelated(activity, keywords)) {
              records[activity.url.ifBlank { "${activity.title}:${activity.startTime}" }] = activity
              filteredCount += 1
            }
          }
          mapOf(
            "type" to "info_channel",
            "channel" to channel,
            "status" to "ok",
            "http_status" to response.statusCode,
            "raw_count" to rows.length(),
            "filtered_count" to filteredCount,
            "count" to filteredCount,
            "response_excerpt_length" to response.body.take(512).length,
          )
        }
      sources += channelResult.getOrElse { throwable ->
        infoFailureDetail("info_channel", throwable, mapOf("channel" to channel))
      }
    }

    keywords.take(5).forEach { keyword ->
      val searchResult =
        runCatching {
          val response = infoHttpRequest(
            urlText = "$LEGACY_INFO_SEARCH_URL?_csrf=${encodeQueryValue(infoSession.csrf)}",
            method = "POST",
            body = "esParamClass=${encodeQueryValue(infoSearchPayload(keyword))}",
            cookie = infoSession.cookie,
            csrf = infoSession.csrf,
            referer = LEGACY_INFO_REDIRECT_URL + "/f/info/gxfw_fg/common/index",
            ajax = true,
          )
          val rows = JSONObject(response.body).optJSONObject("object")
            ?.optJSONArray("resultsList")
            ?: JSONArray()
          var filteredCount = 0
          for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val activity = row.toInfoCampusActivityRecord("")
            if (activity.title.isNotBlank()) {
              records[activity.url.ifBlank { "${activity.title}:${activity.startTime}" }] = activity
              filteredCount += 1
            }
          }
          mapOf(
            "type" to "info_search",
            "keyword" to keyword,
            "status" to "ok",
            "http_status" to response.statusCode,
            "raw_count" to rows.length(),
            "filtered_count" to filteredCount,
            "count" to filteredCount,
            "response_excerpt_length" to response.body.take(512).length,
          )
        }
      sources += searchResult.getOrElse { throwable ->
        infoFailureDetail("info_search", throwable, mapOf("keyword" to keyword))
      }
    }

    val sourceDetails =
      listOf(
        mapOf(
          "type" to "info_webvpn_api",
          "status" to "ready",
          "has_cookie" to true,
          "cookie_names" to cookieNames(infoSession.cookie),
          "has_csrf" to infoSession.csrf.isNotBlank(),
          "csrf_source" to infoSession.csrfSource,
        ),
      ) + sources

    if (records.isEmpty()) {
      warnings += if (sources.any { it["status"] == "failed" }) {
        "INFO/WebVPN 请求失败或未返回可解析活动。"
      } else {
        "INFO/WebVPN 未返回可用校园活动。"
      }
    }
    val hasFailedSource = sources.any { it["status"] == "failed" }
    return CampusActivityLoadResult(
      activities = records.values.take(limit * 2).toList(),
      source = "info_webvpn_api",
      status = if (records.isEmpty()) if (hasFailedSource) "info_failed" else "info_empty" else "ok",
      reason = if (records.isEmpty()) if (hasFailedSource) "info_request_failed" else "info_returned_no_records" else "",
      warnings = warnings,
      sourceDetails = sourceDetails,
    )
  }

  private fun loadPublicCampusSearchActivities(
    query: String,
    keywords: List<String>,
    limit: Int,
  ): CampusActivityLoadResult {
    val provider = readSetting("search_provider").ifBlank { "duckduckgo" }.lowercase(Locale.ROOT)
    if (provider == "mock") {
      return CampusActivityLoadResult(
        source = "public_campus_search",
        error = "mock 搜索提供方已移除，请配置 duckduckgo、searxng 或 brave。",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to provider, "status" to "failed")),
      )
    }
    val searchQuery = buildCampusSearchQuery(query, keywords)
    val result =
      when (provider) {
        "duckduckgo" -> searchDuckDuckGo(searchQuery, limit)
        "searxng" -> searchSearxng(searchQuery, limit)
        "brave" -> searchBrave(searchQuery, limit)
        else ->
          CampusActivityLoadResult(
            source = "public_campus_search",
            error = "不支持的搜索提供方：$provider。请使用 duckduckgo、searxng 或 brave。",
            sourceDetails = listOf(mapOf("type" to "public_search", "provider" to provider, "status" to "failed")),
          )
      }
    if (result.activities.isEmpty()) return result
    return result.copy(
      warnings = result.warnings + "INFO/WebVPN 和本地文件未返回可用数据，已使用公开校内网页搜索结果补充。",
      sourceDetails =
        result.sourceDetails.map { detail ->
          detail + ("query" to searchQuery)
        },
    )
  }

  private fun searchDuckDuckGo(
    query: String,
    limit: Int,
  ): CampusActivityLoadResult {
    val endpoint = normalizeDuckDuckGoEndpoint(readSetting("search_endpoint"))
    return runCatching {
      val html = httpRequest(urlWithQuery(endpoint, mapOf("q" to query)), cookie = "")
      val records = parseDuckDuckGoCampusResults(html).take(limit)
      val sourceDetails =
        listOf(
          mapOf("type" to "public_search", "provider" to "duckduckgo", "status" to "ok", "count" to records.size),
        )
      if (records.isEmpty()) {
        return@runCatching CampusActivityLoadResult(
          source = "public_campus_search",
          error = "DuckDuckGo 未返回可解析的校园活动搜索结果。",
          sourceDetails = sourceDetails,
        )
      }
      CampusActivityLoadResult(
        activities = records,
        source = "public_campus_search",
        sourceDetails = sourceDetails,
      )
    }.getOrElse { throwable ->
      CampusActivityLoadResult(
        source = "public_campus_search",
        error = "公开校园搜索不可用：${throwable.message ?: throwable.javaClass.simpleName}",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to "duckduckgo", "status" to "failed")),
      )
    }
  }

  private fun searchSearxng(
    query: String,
    limit: Int,
  ): CampusActivityLoadResult {
    val endpoint = readSetting("search_endpoint")
    if (endpoint.isBlank()) {
      return CampusActivityLoadResult(
        source = "public_campus_search",
        error = "searxng 需要配置搜索接口地址。",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to "searxng", "status" to "failed")),
      )
    }
    return runCatching {
      val json = JSONObject(httpRequest(urlWithQuery(endpoint, mapOf("q" to query, "format" to "json", "language" to "zh-CN"))))
      val rows = json.optJSONArray("results") ?: JSONArray()
      val records =
        (0 until rows.length())
          .mapNotNull { rows.optJSONObject(it) }
          .mapNotNull { row ->
            val title = cleanHtmlText(firstJsonString(row, "title"))
            val url = firstJsonString(row, "url")
            if (title.isBlank() || url.isBlank()) null else {
              CampusActivityRecord(
                title = title,
                abstract = cleanHtmlText(firstJsonString(row, "content", "snippet")),
                url = url,
                category = "campus",
                source = "searxng",
              )
            }
          }
          .take(limit)
      val sourceDetails =
        listOf(mapOf("type" to "public_search", "provider" to "searxng", "status" to "ok", "count" to records.size))
      if (records.isEmpty()) {
        return@runCatching CampusActivityLoadResult(
          source = "public_campus_search",
          error = "SearxNG 未返回可解析的校园活动搜索结果。",
          sourceDetails = sourceDetails,
        )
      }
      CampusActivityLoadResult(
        activities = records,
        source = "public_campus_search",
        sourceDetails = sourceDetails,
      )
    }.getOrElse { throwable ->
      CampusActivityLoadResult(
        source = "public_campus_search",
        error = "公开校园搜索不可用：${throwable.message ?: throwable.javaClass.simpleName}",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to "searxng", "status" to "failed")),
      )
    }
  }

  private fun searchBrave(
    query: String,
    limit: Int,
  ): CampusActivityLoadResult {
    val apiKey = readSetting("search_api_key")
    if (apiKey.isBlank()) {
      return CampusActivityLoadResult(
        source = "public_campus_search",
        error = "brave 搜索需要配置 API Key。",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to "brave", "status" to "failed")),
      )
    }
    val endpoint = readSetting("search_endpoint").ifBlank { "https://api.search.brave.com/res/v1/web/search" }
    return runCatching {
      val json =
        JSONObject(
          httpRequest(
            urlText = urlWithQuery(endpoint, mapOf("q" to query, "count" to limit.coerceAtMost(10).toString())),
            cookie = "",
            headers = mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json"),
          ),
        )
      val rows = json.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
      val records =
        (0 until rows.length())
          .mapNotNull { rows.optJSONObject(it) }
          .mapNotNull { row ->
            val title = cleanHtmlText(firstJsonString(row, "title"))
            val url = firstJsonString(row, "url")
            if (title.isBlank() || url.isBlank()) null else {
              CampusActivityRecord(
                title = title,
                abstract = cleanHtmlText(firstJsonString(row, "description", "snippet")),
                url = url,
                category = "campus",
                source = "brave",
              )
            }
          }
          .take(limit)
      val sourceDetails =
        listOf(mapOf("type" to "public_search", "provider" to "brave", "status" to "ok", "count" to records.size))
      if (records.isEmpty()) {
        return@runCatching CampusActivityLoadResult(
          source = "public_campus_search",
          error = "Brave 未返回可解析的校园活动搜索结果。",
          sourceDetails = sourceDetails,
        )
      }
      CampusActivityLoadResult(
        activities = records,
        source = "public_campus_search",
        sourceDetails = sourceDetails,
      )
    }.getOrElse { throwable ->
      CampusActivityLoadResult(
        source = "public_campus_search",
        error = "公开校园搜索不可用：${throwable.message ?: throwable.javaClass.simpleName}",
        sourceDetails = listOf(mapOf("type" to "public_search", "provider" to "brave", "status" to "failed")),
      )
    }
  }

  private fun JSONObject.toCampusActivityRecord(): CampusActivityRecord =
    CampusActivityRecord(
      activityId = firstJsonString(this, "activity_id", "id", "hdrcid"),
      title = firstJsonString(this, "title", "name", "activity_title"),
      startTime = firstJsonString(this, "start_time", "time", "date", "event_time", "begin_time"),
      location = firstJsonString(this, "location", "venue", "place", "address"),
      organizer = firstJsonString(this, "organizer", "host", "source"),
      category = firstJsonString(this, "category", "type", "channel"),
      abstract = firstJsonString(this, "abstract", "summary", "content", "description", "snippet"),
      url = firstJsonString(this, "url", "link", "detail_url"),
      source = firstJsonString(this, "source", "channel", "provider"),
    )

  private fun filterCampusActivities(
    activities: List<CampusActivityRecord>,
    keywords: List<String>,
  ): List<CampusActivityRecord> {
    if (keywords.isEmpty()) return activities
    val normalizedKeywords = keywords.map { it.lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
    return activities.filter { activity ->
      val haystack =
        listOf(
          activity.title,
          activity.abstract,
          activity.location,
          activity.organizer,
          activity.category,
          activity.source,
        ).joinToString(" ").lowercase(Locale.getDefault())
      normalizedKeywords.any { keyword -> haystack.contains(keyword) }
    }
  }

  private fun filterCampusActivitiesByDate(
    activities: List<CampusActivityRecord>,
    startDate: String,
    endDate: String,
  ): List<CampusActivityRecord> {
    val startBound = parseActivityDateBound(startDate)
    val endBound = parseActivityDateBound(endDate)
    if (startBound == null && endBound == null) return activities
    return activities.filter { activity ->
      val activityDate = parseActivityDate(activity.startTime) ?: return@filter true
      if (startBound != null && activityDate < startBound) return@filter false
      if (endBound != null && activityDate > endBound) return@filter false
      true
    }
  }

  private fun parseActivityDateBound(raw: String): LocalDate? =
    parseActivityDate(raw)

  private fun parseActivityDate(raw: String): LocalDate? {
    val text = raw.trim()
    if (text.isBlank()) return null
    Regex("(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日").find(text)?.let { match ->
      val year = match.groupValues[1].toIntOrNull()
      val month = match.groupValues[2].toIntOrNull()
      val day = match.groupValues[3].toIntOrNull()
      if (year != null && month != null && day != null) {
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
      }
    }
    Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(text)?.let { match ->
      val year = match.groupValues[1].toIntOrNull()
      val month = match.groupValues[2].toIntOrNull()
      val day = match.groupValues[3].toIntOrNull()
      if (year != null && month != null && day != null) {
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
      }
    }
    Regex("\\b(\\d{4})(\\d{2})(\\d{2})\\b").find(text)?.let { match ->
      val year = match.groupValues[1].toIntOrNull()
      val month = match.groupValues[2].toIntOrNull()
      val day = match.groupValues[3].toIntOrNull()
      if (year != null && month != null && day != null) {
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
      }
    }
    return null
  }

  private fun tokenizeCampusQuery(query: String): List<String> =
    query
      .replace(Regex("[，。；、,.!?！？:：\\[\\]（）()\\n\\t]"), " ")
      .split(Regex("\\s+"))
      .map { it.trim() }
      .filter { it.length >= 2 }
      .take(8)

  private fun parseCsvOrJsonArray(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim() }.filter { it.isNotEmpty() }
      }.getOrElse { emptyList() }
    }
    return text.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun readActionString(
    action: SystemAction,
    key: String,
  ): String {
    val fromParams = action.params[key]?.trim().orEmpty()
    if (fromParams.isNotBlank()) return fromParams
    return action.payload?.get(key)?.toString()?.trim().orEmpty()
  }

  private fun readSetting(key: String): String =
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
      .getString(key, "")
      .orEmpty()
      .trim()

  private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

  private fun firstJsonString(
    json: JSONObject,
    vararg keys: String,
  ): String {
    for (key in keys) {
      val value = json.optString(key, "").trim()
      if (value.isNotBlank() && value != "null") return value
    }
    return ""
  }

  private fun JSONObject.toInfoCampusActivityRecord(channel: String): CampusActivityRecord {
    val rawUrl = firstJsonString(this, "url", "link", "detail_url")
    return CampusActivityRecord(
      activityId = firstJsonString(this, "id", "xxid", "info_id"),
      title = cleanHtmlText(firstJsonString(this, "bt", "title", "name")),
      startTime = firstJsonString(this, "time", "publish_time", "date"),
      organizer = cleanHtmlText(firstJsonString(this, "dwmc_show", "organizer", "source", "dwmc")),
      category = firstNonBlank(channel, firstJsonString(this, "lmid", "channel", "category")),
      abstract = cleanHtmlText(firstJsonString(this, "zy", "abstract", "summary", "content", "snippet")),
      url = normalizeInfoUrl(rawUrl),
      source = "info_webvpn_api",
    )
  }

  private fun JSONObject.toHdrcCampusActivityRecord(): CampusActivityRecord {
    val activityId = firstJsonString(this, "hdrcid", "id")
    val date = firstJsonString(this, "hdrq", "date")
    val start = firstJsonString(this, "kssj", "start_time")
    val end = firstJsonString(this, "jssj", "end_time")
    val time = listOf(date, listOf(start, end).filter { it.isNotBlank() }.joinToString("-"))
      .filter { it.isNotBlank() }
      .joinToString(" ")
    val abstract = firstNonBlank(
      cleanHtmlText(firstJsonString(this, "jj_ch", "abstract", "summary")),
      cleanHtmlText(firstJsonString(this, "bgr", "speaker")),
    )
    return CampusActivityRecord(
      activityId = activityId,
      title = cleanHtmlText(firstJsonString(this, "bt", "title", "name")),
      startTime = time,
      location = cleanHtmlText(firstJsonString(this, "hddd", "location", "venue")),
      organizer = cleanHtmlText(firstJsonString(this, "zbdw", "fbdwmc", "organizer")),
      category = firstJsonString(this, "hdlxmc", "category", "type"),
      abstract = abstract,
      url = if (activityId.isBlank()) "" else INFO_ACTIVITY_DETAIL_URL + encodeQueryValue(activityId),
      source = "info_hdrc_public_api",
    )
  }

  private fun preparePublicInfoSession(): InfoSession {
    val response = infoHttpRequest(
      urlText = INFO_ACTIVITY_INDEX_URL,
      referer = INFO_PUBLIC_BASE_URL,
      ajax = false,
    )
    val cookie = mergeCookieHeaders("", response.setCookies)
    val csrf = extractCsrfFromText(response.body + ";" + response.setCookies.joinToString(";"))
      .ifBlank { throw IllegalStateException("Unable to locate XSRF-TOKEN from INFO activity index") }
    return InfoSession(cookie = cookie, csrf = csrf, csrfSource = "public_activity_index")
  }

  private fun prepareInfoSession(
    cookie: String,
    csrfSetting: String,
  ): InfoSession {
    val normalizedCookie = normalizeCookieHeader(cookie)
    val cookieResponse = infoHttpRequest(
      urlText = LEGACY_WEBVPN_COOKIE_URL,
      cookie = normalizedCookie,
      referer = "https://webvpn.tsinghua.edu.cn/",
      ajax = false,
    )
    val mergedCookie = mergeCookieHeaders(normalizedCookie, cookieResponse.setCookies)
    val csrfSource: String
    val csrf =
      if (csrfSetting.isNotBlank()) {
        csrfSource = "setting"
        csrfSetting
      } else {
        csrfSource = "webvpn_cookie_endpoint"
        extractCsrfFromText(cookieResponse.body + ";" + cookieResponse.setCookies.joinToString(";"))
          .ifBlank { throw IllegalStateException("Unable to locate XSRF-TOKEN from WebVPN cookie endpoint") }
      }
    warmUpInfoSession(mergedCookie, csrf)
    return InfoSession(cookie = mergedCookie, csrf = csrf, csrfSource = csrfSource)
  }

  private fun warmUpInfoSession(
    cookie: String,
    csrf: String,
  ) {
    infoHttpRequest(
      urlText = LEGACY_INFO_REDIRECT_URL + "/f/info/gxfw_fg/common/index",
      cookie = cookie,
      csrf = csrf,
      referer = "https://webvpn.tsinghua.edu.cn/",
      ajax = false,
    )
  }

  private fun extractCsrfFromText(text: String): String =
    Regex("XSRF-TOKEN=([^;]+)").find("$text;")?.groupValues?.getOrNull(1)?.trim().orEmpty()

  private fun infoSearchPayload(keyword: String): String {
    val params =
      JSONObject()
        .put("bt", keyword)
        .put("tag", keyword)
        .put("xxfl", keyword)
    return JSONObject()
      .put("params", params)
      .put("filterParams", JSONObject())
      .put("orderMap", JSONObject().put("sort", "time"))
      .put("matchExact", "否")
      .put("currentPage", 1)
      .toString()
  }

  private fun looksCampusActivityRelated(
    activity: CampusActivityRecord,
    keywords: List<String>,
  ): Boolean {
    val normalizedKeywords =
      (keywords + defaultActivityKeywords)
        .map { it.lowercase(Locale.getDefault()) }
        .filter { it.isNotBlank() }
        .distinct()
    val haystack =
      listOf(
        activity.title,
        activity.abstract,
        activity.location,
        activity.organizer,
        activity.category,
      ).joinToString(" ").lowercase(Locale.getDefault())
    return normalizedKeywords.any { keyword -> haystack.contains(keyword) }
  }

  private fun buildCampusSearchQuery(
    query: String,
    keywords: List<String>,
  ): String {
    val seed = query.ifBlank { keywords.take(4).joinToString(" ") }.ifBlank { "校园活动 讲座" }
    return if (listOf("清华", "校园", "活动", "讲座", "论坛").any { seed.contains(it) }) {
      seed
    } else {
      "清华 校园活动 $seed"
    }
  }

  private fun parseDuckDuckGoCampusResults(html: String): List<CampusActivityRecord> {
    val links =
      Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
        .findAll(html)
        .filter { match ->
          val attrs = match.groupValues[1]
          Regex("(?is)class=['\"][^'\"]*(result-link|result__a)[^'\"]*['\"]").containsMatchIn(attrs)
        }
        .toList()
    val records = mutableListOf<CampusActivityRecord>()
    val seenUrls = mutableSetOf<String>()
    links.forEachIndexed { index, match ->
      val attrs = match.groupValues[1]
      val href = Regex("(?is)href=['\"]([^'\"]+)['\"]").find(attrs)?.groupValues?.getOrNull(1).orEmpty()
      val url = unwrapDuckDuckGoRedirect(decodeHtmlEntities(href))
      val title = cleanHtmlText(match.groupValues[2])
      if (url.isBlank() || title.isBlank() || !seenUrls.add(url)) return@forEachIndexed
      val nextStart = links.getOrNull(index + 1)?.range?.first ?: html.length
      val block = html.substring(match.range.last.coerceAtMost(html.length - 1), nextStart)
      val snippet =
        Regex("(?is)<(td|a|div)[^>]+class=['\"][^'\"]*(result-snippet|result__snippet)[^'\"]*['\"][^>]*>(.*?)</\\1>")
          .find(block)
          ?.groupValues
          ?.getOrNull(3)
          .orEmpty()
      records +=
        CampusActivityRecord(
          title = title,
          abstract = cleanHtmlText(snippet),
          url = url,
          category = "campus",
          source = "duckduckgo",
        )
    }
    return records
  }

  private fun normalizeInfoUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    return when {
      url.isBlank() -> ""
      url.startsWith("http://") || url.startsWith("https://") -> url
      url.startsWith("/") -> LEGACY_INFO_REDIRECT_URL + url
      else -> url
    }
  }

  private fun normalizeDuckDuckGoEndpoint(rawEndpoint: String): String {
    val endpoint = rawEndpoint.trim().ifBlank { DEFAULT_DUCKDUCKGO_ENDPOINT }
    return when (endpoint) {
      "https://duckduckgo.com/html/",
      "https://html.duckduckgo.com/html/" -> DEFAULT_DUCKDUCKGO_ENDPOINT
      else -> endpoint
    }
  }

  private fun unwrapDuckDuckGoRedirect(rawUrl: String): String {
    val normalized = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
    if (!normalized.contains("duckduckgo.com") || !normalized.contains("uddg=")) return normalized
    val encoded = Regex("[?&]uddg=([^&]+)").find(normalized)?.groupValues?.getOrNull(1).orEmpty()
    return if (encoded.isBlank()) normalized else URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
  }

  private fun urlWithQuery(
    endpoint: String,
    params: Map<String, String>,
  ): String {
    val separator = if (endpoint.contains("?")) "&" else "?"
    return endpoint + separator + params.entries.joinToString("&") { (key, value) ->
      "${encodeQueryValue(key)}=${encodeQueryValue(value)}"
    }
  }

  private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

  private fun cleanHtmlText(value: String): String {
    val withoutScripts = value.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
    val withoutTags = withoutScripts.replace(Regex("(?s)<[^>]+>"), " ")
    return decodeHtmlEntities(withoutTags)
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun decodeHtmlEntities(value: String): String =
    Regex("&#(x?[0-9a-fA-F]+);").replace(
      value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " "),
    ) { match ->
      val raw = match.groupValues[1]
      val codePoint =
        if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
      codePoint?.let { String(Character.toChars(it)) } ?: match.value
    }

  private fun infoHttpRequest(
    urlText: String,
    method: String = "GET",
    body: String = "",
    cookie: String = "",
    csrf: String = "",
    referer: String = "",
    ajax: Boolean = true,
  ): InfoHttpResponse {
    val connection = URL(urlText).openConnection() as HttpURLConnection
    return try {
      connection.requestMethod = method
      connection.connectTimeout = 12_000
      connection.readTimeout = 24_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty(
        "Accept",
        if (ajax) "application/json, text/javascript, */*; q=0.01" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      )
      connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
      connection.setRequestProperty("Cache-Control", "no-cache")
      connection.setRequestProperty("Pragma", "no-cache")
      connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 OpenTHU/1.0",
      )
      if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
      if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
      if (ajax) connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
      if (ajax) connection.setRequestProperty("Origin", originFrom(urlText))
      setCsrfHeaders(connection, csrf)
      if (body.isNotBlank()) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { stream ->
          stream.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
      val text = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
      val cookies = connection.headerFields.orEmpty()
        .filterKeys { key -> key != null && key.equals("Set-Cookie", ignoreCase = true) }
        .values
        .flatten()
      if (code !in 200..299) {
        throw InfoHttpException(code, text.take(512), "HTTP $code for INFO request: ${text.take(160)}")
      }
      InfoHttpResponse(statusCode = code, body = text, setCookies = cookies)
    } finally {
      connection.disconnect()
    }
  }

  private fun setCsrfHeaders(
    connection: HttpURLConnection,
    csrf: String,
  ) {
    if (csrf.isBlank()) return
    connection.setRequestProperty("X-XSRF-TOKEN", csrf)
    connection.setRequestProperty("X-CSRF-TOKEN", csrf)
    connection.setRequestProperty("X-XSRFToken", csrf)
  }

  private fun originFrom(urlText: String): String =
    runCatching {
      val url = URL(urlText)
      "${url.protocol}://${url.host}"
    }.getOrDefault("")

  private fun normalizeCookieHeader(raw: String): String =
    raw.split(';')
      .map { it.trim() }
      .filter { it.contains('=') }
      .joinToString("; ")

  private fun mergeCookieHeaders(
    baseCookie: String,
    setCookies: List<String>,
  ): String {
    val merged = linkedMapOf<String, String>()
    fun add(header: String) {
      header.split(';')
        .map { it.trim() }
        .filter { it.contains('=') }
        .forEach { token ->
          val name = token.substringBefore('=').trim()
          val value = token.substringAfter('=').trim()
          if (name.isNotBlank() && value.isNotBlank() && !cookieAttributeNames.contains(name.lowercase(Locale.ROOT))) {
            merged[name] = value
          }
        }
    }
    add(baseCookie)
    setCookies.forEach { add(it.substringBefore(';')) }
    return merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
  }

  private fun cookieNames(cookieHeader: String): List<String> =
    cookieHeader.split(';')
      .map { it.trim().substringBefore('=').trim() }
      .filter { it.isNotBlank() }

  private fun safeErrorMessage(throwable: Throwable): String =
    (throwable.message ?: throwable.javaClass.simpleName).take(512)

  private fun infoFailureDetail(
    type: String,
    throwable: Throwable,
    extra: Map<String, Any?>,
  ): Map<String, Any?> {
    val base = mutableMapOf<String, Any?>(
      "type" to type,
      "status" to "failed",
      "reason" to "info_request_failed",
      "message" to safeErrorMessage(throwable),
    )
    if (throwable is InfoHttpException) {
      base["http_status"] = throwable.statusCode
      base["response_excerpt_length"] = throwable.responseExcerpt.length
    }
    base.putAll(extra)
    return base
  }

  private fun campusActivitiesFailureStatus(
    infoLoad: CampusActivityLoadResult,
    configuredLoad: CampusActivityLoadResult,
  ): String =
    firstNonBlank(
      configuredLoad.status.takeIf { configuredLoad.error.isNotBlank() }.orEmpty(),
      infoLoad.status,
      configuredLoad.status,
      "source_unavailable",
    )

  private fun campusActivitiesFailureReason(
    infoLoad: CampusActivityLoadResult,
    configuredLoad: CampusActivityLoadResult,
  ): String =
    firstNonBlank(
      configuredLoad.reason.takeIf { configuredLoad.error.isNotBlank() }.orEmpty(),
      infoLoad.reason,
      configuredLoad.reason,
      "source_unavailable",
    )

  private fun httpRequest(
    urlText: String,
    method: String = "GET",
    body: String = "",
    cookie: String = "",
    headers: Map<String, String> = emptyMap(),
  ): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection
    return try {
      connection.requestMethod = method
      connection.connectTimeout = 12_000
      connection.readTimeout = 12_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android) OpenTHU-Agent/1.0",
      )
      if (cookie.isNotBlank()) {
        connection.setRequestProperty("Cookie", cookie)
      }
      headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
      if (body.isNotBlank()) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { stream ->
          stream.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val code = connection.responseCode
      val stream =
        if (code in 200..299) {
          connection.inputStream
        } else {
          connection.errorStream ?: connection.inputStream
        }
      val text = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
      if (code !in 200..299) {
        throw IllegalStateException("HTTP $code: ${text.take(160)}")
      }
      text
    } finally {
      connection.disconnect()
    }
  }

  private fun executeShowSummary(action: SystemAction): ActionExecutionReport {
    val title =
      (action.payload?.get("title") as? String)
        ?: action.params["title"]
        ?: "OpenTHU 摘要"
    val content =
      (action.payload?.get("content") as? String)
        ?: action.params["content"]
        ?: action.summary
    return ActionExecutionReport(
      success = true,
      message = "$title\n$content",
      recoverable = false,
      semantic = "summary_shown",
      metadata = mapOf(
        "title" to title,
        "content" to content,
        "format" to ((action.payload?.get("format") as? String) ?: action.params["format"] ?: "plain"),
      ),
    )
  }

  private fun executeSendNotification(action: SystemAction): ActionExecutionReport {
    val title =
      (action.payload?.get("title") as? String)
        ?: action.params["title"]
        ?: "OpenTHU 通知"
    val body =
      (action.payload?.get("body") as? String)
        ?: action.params["body"]
        ?: action.summary

    Handler(Looper.getMainLooper()).post {
      Toast.makeText(appContext, "$title\n$body", Toast.LENGTH_LONG).show()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      return ActionExecutionReport(
        success = true,
        message = "Notification text shown in-app because POST_NOTIFICATIONS permission is not granted: $title",
        recoverable = false,
        semantic = "notification_shown_in_app",
        metadata = mapOf("title" to title, "body" to body, "system_notification_posted" to false),
      )
    }

    val channelId = "opencray_agent"
    val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(channelId, "OpenTHU Agent", NotificationManager.IMPORTANCE_DEFAULT),
      )
    }

    val notification =
      NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .build()

    return runCatching {
      manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
      ActionExecutionReport(
        success = true,
        message = "Notification posted: $title",
        recoverable = false,
        semantic = "notification_posted",
        metadata = mapOf("title" to title, "body" to body, "system_notification_posted" to true),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Notification failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        semantic = "notification_failed",
        metadata = mapOf("title" to title, "body" to body),
      )
    }
  }

  private fun executeCreateReminder(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    val dueTime =
      (action.payload?.get("due_time") as? String)
        ?: action.params["due_time"]
        ?: (action.payload?.get("time") as? String)
        ?: action.params["time"]
    if (dueTime.isNullOrBlank()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing required `due_time` or `time` for create_reminder.",
        recoverable = false,
        semantic = "invalid_param",
      )
    }
    val alarmAction = action.copy(
      id = "set_alarm",
      payload = mapOf(
        "time" to dueTime,
        "label" to ((action.payload?.get("title") as? String) ?: action.params["title"] ?: goal.take(40)),
        "vibrate" to true,
      ),
    )
    return executeAlarmIntent(alarmAction, goal).copy(
      semantic = "reminder_alarm_requested",
    )
  }

  private fun executeReadNotifications(): ActionExecutionReport {
    val enabledListeners = Settings.Secure.getString(appContext.contentResolver, "enabled_notification_listeners")
    val isEnabled = enabledListeners != null && enabledListeners.contains(appContext.packageName)

    if (!isEnabled) {
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      appContext.startActivity(intent)
      return ActionExecutionReport(
        success = false,
        message = "Missing notification access permission. Prompted user to enable it in Settings.",
        recoverable = false,
      )
    }

    val service = OpenTHUNotificationListenerService.instance
    if (service == null) {
      return ActionExecutionReport(
        success = false,
        message = "NotificationListenerService is active but not bound yet. Please wait or restart the app.",
        recoverable = true,
      )
    }

    val notifications = service.getUnreadNotifications()
    val parsedNotifications = mutableListOf<Map<String, Any>>()

    for (sbn in notifications) {
        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE)?.toString() ?: continue
        
        val textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
        val messages = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
        
        val content = if (textLines != null && textLines.isNotEmpty()) {
            textLines.joinToString("\n") { it.toString() }
        } else if (messages != null && messages.isNotEmpty()) {
            messages.mapNotNull { 
                if (it is android.os.Bundle) it.getCharSequence("text")?.toString() else null 
            }.joinToString("\n")
        } else {
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        }

        val pkg = sbn.packageName
        parsedNotifications.add(mapOf(
            "package" to pkg,
            "title" to title,
            "text" to content,
            "post_time_ms" to sbn.postTime
        ))
    }

    if (parsedNotifications.isEmpty()) {
      return ActionExecutionReport(
        success = true,
        message = "No unread notifications found.",
        recoverable = false,
        semantic = "notifications_read",
        metadata = mapOf(
            "notification_count" to 0,
            "notifications" to emptyList<Map<String, Any>>()
        )
      )
    }

    val displayMessage = "Found ${parsedNotifications.size} unread notifications:\n" + 
        parsedNotifications.joinToString("\n") { "[${it["package"]}] ${it["title"]}: ${it["text"]}" }

    return ActionExecutionReport(
      success = true,
      message = displayMessage,
      recoverable = false,
      semantic = "notifications_read",
      metadata = mapOf(
          "notification_count" to parsedNotifications.size,
          "notifications" to parsedNotifications
      )
    )
  }

  private fun executeCreateCalendarEvent(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    if (!hasCalendarWritePermission() || !hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
        recoverable = false,
      )
    }

    val title = action.params["title"]?.trim().orEmpty().ifEmpty { goal.take(40).ifEmpty { "OpenTHU Event" } }
    val description = action.params["description"]?.trim().orEmpty().ifEmpty { goal }
    val window = deriveWindow(action.params) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing calendar time. start_time and end_time must be ISO-8601 datetimes with explicit UTC offset, e.g. 2026-06-03T14:00:00+08:00.",
      recoverable = false,
      semantic = "invalid_calendar_time",
      metadata = mapOf(
        "reason" to "invalid_calendar_time",
        "start_time" to action.params["start_time"],
        "end_time" to action.params["end_time"],
      ),
    )
    val calendarZone = resolveCalendarZoneId(action.params["timezone"]) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid calendar timezone. Use an IANA timezone id such as Asia/Shanghai.",
      recoverable = false,
      semantic = "invalid_calendar_timezone",
      metadata = mapOf(
        "reason" to "invalid_calendar_timezone",
        "timezone" to action.params["timezone"],
      ),
    )
    val conflictDecision = action.params["conflict_decision"]?.trim()?.lowercase() ?: "prompt_user"
    val conflicts = queryConflicts(window.startMs, window.endMs)

    if (conflicts.isNotEmpty()) {
      when (conflictDecision) {
        "skip_write" -> {
          return ActionExecutionReport(
            success = true,
            message = "已跳过写入（skip_write）。${conflictSummaryMessage(conflicts)}",
            recoverable = false,
            metadata = mapOf(
              "skipped" to true,
              "conflict_count" to conflicts.size,
              "conflicts" to conflictsToDataList(conflicts),
            ),
          )
        }
        "coexist" -> {
          // Android calendar supports overlapping events; proceed with insert below.
        }
        "delete_conflicts" -> {
          val allowDelete = action.params["allow_conflict_delete"]?.toBooleanStrictOrNull() ?: false
          if (!allowDelete) {
            return ActionExecutionReport(
              success = false,
              message = "冲突删除需要明确授权（allow_conflict_delete=true）。${conflictSummaryMessage(conflicts)}",
              recoverable = false,
              metadata = mapOf(
                "reason" to "allow_conflict_delete_not_set",
                "conflict_count" to conflicts.size,
                "conflicts" to conflictsToDataList(conflicts),
              ),
            )
          }
          deleteEventsByIds(conflicts.map { it.id })
        }
        else -> {
          return ActionExecutionReport(
            success = false,
            message = "检测到冲突，请选择策略 skip_write / coexist / delete_conflicts。${conflictSummaryMessage(conflicts)}",
            recoverable = false,
            metadata = mapOf(
              "reason" to "conflict_strategy_required",
              "conflict_count" to conflicts.size,
              "conflicts" to conflictsToDataList(conflicts),
            ),
          )
        }
      }
    }

    val calendarId = resolveWritableCalendarId()
    if (calendarId == null) {
      return ActionExecutionReport(
        success = false,
        message = "No writable calendar found on this device.",
        recoverable = false,
      )
    }

    return runCatching {
      val values =
        ContentValues().apply {
          put(CalendarContract.Events.CALENDAR_ID, calendarId)
          put(CalendarContract.Events.TITLE, title)
          put(CalendarContract.Events.DESCRIPTION, description)
          put(CalendarContract.Events.DTSTART, window.startMs)
          put(CalendarContract.Events.DTEND, window.endMs)
          put(CalendarContract.Events.EVENT_TIMEZONE, calendarZone.id)
        }
      val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
      val eventId = uri?.lastPathSegment ?: "unknown"
      ActionExecutionReport(
        success = true,
        message = "日历事项已创建：「$title」${formatEpochMs(window.startMs)}-${formatEpochMs(window.endMs)}（event_id=$eventId）",
        recoverable = false,
        metadata = mapOf(
          "event_id" to eventId,
          "title" to title,
          "start" to formatEpochMs(window.startMs),
          "end" to formatEpochMs(window.endMs),
          "timezone" to calendarZone.id,
          "calendar_id" to calendarId,
          "deleted_conflicts" to if (conflicts.isNotEmpty()) conflictsToDataList(conflicts) else emptyList<Any>(),
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "日历写入失败：${throwable.message ?: "未知错误"}",
        recoverable = true,
        metadata = mapOf("exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executeGetCurrentTime(): ActionExecutionReport {
    val zone = resolveCalendarZoneId(null) ?: ZoneId.systemDefault()
    val now = OffsetDateTime.now(zone).withNano(0)
    val hour = now.hour
    val minute = now.minute
    val localTime = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    return ActionExecutionReport(
      success = true,
      message = "Current local time captured: $localTime (${zone.id})",
      recoverable = false,
      semantic = "current_time_captured",
      metadata = mapOf(
        "local_datetime" to now.toString(),
        "local_date" to now.toLocalDate().toString(),
        "local_time" to localTime,
        "timezone" to zone.id,
        "epoch_ms" to now.toInstant().toEpochMilli(),
      ),
    )
  }

  private fun executeConflictDetection(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    if (!hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing READ_CALENDAR permission for conflict detection.",
        recoverable = false,
      )
    }
    val window = deriveWindow(action.params) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing calendar time. start_time and end_time must be ISO-8601 datetimes with explicit UTC offset, e.g. 2026-06-03T14:00:00+08:00.",
      recoverable = false,
      semantic = "invalid_calendar_time",
      metadata = mapOf(
        "reason" to "invalid_calendar_time",
        "start_time" to action.params["start_time"],
        "end_time" to action.params["end_time"],
      ),
    )
    val conflicts = queryConflicts(window.startMs, window.endMs)
    return ActionExecutionReport(
      success = true,
      message = if (conflicts.isEmpty()) {
        "未检测到时间冲突（已排除全天事件）。"
      } else {
        conflictSummaryMessage(conflicts)
      },
      recoverable = false,
      metadata = mapOf(
        "conflict_count" to conflicts.size,
        "conflicts" to conflictsToDataList(conflicts),
        "check_window_start" to formatEpochMs(window.startMs),
        "check_window_end" to formatEpochMs(window.endMs),
        "all_day_excluded" to true,
      ),
    )
  }

  private fun executeDeleteCalendarEvent(action: SystemAction): ActionExecutionReport {
    if (!hasCalendarWritePermission() || !hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
        recoverable = false,
      )
    }

    val confirmed = action.params["confirm_delete"]?.toBooleanStrictOrNull() ?: false
    if (!confirmed) {
      return ActionExecutionReport(
        success = false,
        message = "Delete blocked: confirm_delete=true is required for high-risk deletion.",
        recoverable = false,
      )
    }

    val directIds = parseIdList(action.params["event_ids"]) + parseSingleId(action.params["event_id"])
    val idsToDelete =
      if (directIds.isNotEmpty()) {
        directIds
      } else {
        val keyword = action.params["title_keyword"]?.trim().orEmpty()
        if (keyword.isEmpty()) {
          return ActionExecutionReport(
            success = false,
            message = "Delete requires event_id/event_ids or title_keyword.",
            recoverable = false,
          )
        }
        findEventsByTitleKeyword(keyword, limit = 50).map { it.id }
      }

    if (idsToDelete.isEmpty()) {
      return ActionExecutionReport(
        success = true,
        message = "No matching calendar events to delete.",
        recoverable = false,
      )
    }

    return runCatching {
      val deleted = deleteEventsByIds(idsToDelete)
      ActionExecutionReport(
        success = true,
        message = "已删除 $deleted 个日历事项（目标 ${idsToDelete.size} 个）。",
        recoverable = false,
        metadata = mapOf(
          "deleted_count" to deleted,
          "requested_ids" to idsToDelete,
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "日历删除失败：${throwable.message ?: "未知错误"}",
        recoverable = true,
        metadata = mapOf(
          "exception" to (throwable.message ?: "unknown"),
          "requested_ids" to idsToDelete,
        ),
      )
    }
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    val timeStr = action.payload?.get("time") as? String
      ?: return ActionExecutionReport(
        success = false,
        message = "Missing 'time' in payload",
        recoverable = false,
        semantic = "alarm_invalid_args",
      )

    val parsed = parseAlarmTime(timeStr)
      ?: return ActionExecutionReport(
        success = false,
        message = "Invalid 'time' format (expected local HH:mm or local ISO8601 datetime)",
        recoverable = false,
        semantic = "alarm_invalid_time_format",
      )

    val hourArg = parsed.first
    val minArg = parsed.second

    val labelArg = action.payload["label"] as? String ?: ""
    val vibrateArg = action.payload["vibrate"] as? Boolean ?: false

    // Note: The 'repeat' parameter is safely ignored here.
    // Setting AlarmClock.EXTRA_DAYS in Android requires an ArrayList<Integer> of Calendar.DAY_OF_WEEK.
    // It can be implemented strictly once the Agent contract is standardized without fuzzy inference.

    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        if (labelArg.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, labelArg)
        putExtra(AlarmClock.EXTRA_HOUR, hourArg)
        putExtra(AlarmClock.EXTRA_MINUTES, minArg)
        if (vibrateArg) putExtra(AlarmClock.EXTRA_VIBRATE, true)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    val report =
      launchIntent(
        intent = intent,
        successMessage = "Alarm creation requested in background mode for ${hourArg}:${minArg.toString().padStart(2, '0')}",
      )
    val alarmMeta =
      mapOf(
        "alarm_hour" to hourArg,
        "alarm_minute" to minArg,
        "label" to labelArg,
        "skip_ui" to true,
      )
    return if (report.success) {
      report.copy(
        semantic = "alarm_created_background_requested",
        metadata = alarmMeta,
      )
    } else {
      report.copy(
        semantic = "alarm_creation_failed",
        metadata = report.metadata + alarmMeta,
      )
    }
  }

  private fun parseAlarmTime(timeRaw: String): Pair<Int, Int>? {
    val text = timeRaw.trim()
    if (text.isEmpty()) return null

    // Preferred local wall-clock format.
    Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(text)?.let { match ->
      val hour = match.groupValues[1].toIntOrNull() ?: return null
      val minute = match.groupValues[2].toIntOrNull() ?: return null
      return Pair(hour, minute)
    }

    // Local-time semantics for ISO-like strings:
    // keep the wall-clock hour/minute in text and do not apply timezone conversion.
    Regex(
      """^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?:\.\d+)?(?:Z|[+-][01]\d:[0-5]\d)?$""",
    ).matchEntire(text)?.let { match ->
      val hour = match.groupValues[1].toIntOrNull() ?: return null
      val minute = match.groupValues[2].toIntOrNull() ?: return null
      return Pair(hour, minute)
    }

    return null
  }

  private fun openWebPage(url: String): ActionExecutionReport {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return launchIntent(intent, "Campus website opened")
  }

  /** Format a timestamp (epochMs) to local ISO datetime string for display. */
  private fun formatEpochMs(epochMs: Long): String {
    return runCatching {
      val instant = java.time.Instant.ofEpochMilli(epochMs)
      val zdt = instant.atZone(ZoneId.systemDefault())
      zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse { epochMs.toString() }
  }

  /** Convert conflict list to structured map list suitable for JSON submission. */
  private fun conflictsToDataList(conflicts: List<CalendarEventBrief>): List<Map<String, Any>> =
    conflicts.map { event ->
      mapOf(
        "id" to event.id,
        "title" to event.title,
        "start" to formatEpochMs(event.startMs),
        "end" to formatEpochMs(event.endMs),
      )
    }

  /** Build a human-readable conflict summary for the message field. */
  private fun conflictSummaryMessage(conflicts: List<CalendarEventBrief>): String {
    val items = conflicts.joinToString("; ") { event ->
      "「${event.title}」${formatEpochMs(event.startMs)}-${formatEpochMs(event.endMs)}"
    }
    return "冲突事项(${conflicts.size}): $items"
  }

  private fun launchIntent(intent: Intent, successMessage: String): ActionExecutionReport {
    return runCatching {
      appContext.startActivity(intent)
      ActionExecutionReport(
        success = true,
        message = successMessage,
        recoverable = false,
        semantic = "intent_launched",
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Intent execution failed",
        recoverable = true,
        semantic = "intent_launch_failed",
        metadata = mapOf("error" to (throwable.message ?: "Intent execution failed")),
      )
    }
  }

  private fun hasCalendarReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private fun hasCalendarWritePermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private fun resolveWritableCalendarId(): Long? {
    val projection =
      arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.IS_PRIMARY,
      )
    val selection = "${CalendarContract.Calendars.VISIBLE}=1"
    val sort = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
    appContext.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      projection,
      selection,
      null,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
      val accessIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
      while (cursor.moveToNext()) {
        val accessLevel = cursor.getInt(accessIdx)
        if (accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
          return cursor.getLong(idIdx)
        }
      }
    }
    return null
  }

  private fun deriveWindow(params: Map<String, String>): CalendarWindow? {
    val startRaw = params["start_time"]?.trim().orEmpty()
    val endRaw = params["end_time"]?.trim().orEmpty()
    if (startRaw.isEmpty() || endRaw.isEmpty()) return null

    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val start = runCatching { OffsetDateTime.parse(startRaw, formatter) }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endRaw, formatter) }.getOrNull() ?: return null
    if (!end.isAfter(start)) return null
    return CalendarWindow(
      startMs = start.toInstant().toEpochMilli(),
      endMs = end.toInstant().toEpochMilli(),
    )
  }

  private fun resolveCalendarZoneId(rawTimezone: String?): ZoneId? {
    val requested = rawTimezone?.trim().orEmpty()
    val configured =
      appContext
        .getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
        .getString("timezone", "")
        .orEmpty()
        .trim()
    val zoneText = requested.ifEmpty { configured }
    if (zoneText.isEmpty()) return ZoneId.systemDefault()
    return runCatching { ZoneId.of(zoneText) }.getOrNull()
  }

  private fun queryConflicts(
    startMs: Long,
    endMs: Long,
  ): List<CalendarEventBrief> {
    val projection =
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
      )
    // Exclude all-day events (ALL_DAY=1) — their midnight UTC times cause false positives
    // against regular timed events.
    val selection =
      buildString {
        append("(")
        append("${CalendarContract.Events.DTSTART} < ?")
        append(" AND ")
        append("${CalendarContract.Events.DTEND} > ?")
        append(")")
        append(" AND ")
        append("(${CalendarContract.Events.DELETED} = 0 OR ${CalendarContract.Events.DELETED} IS NULL)")
        append(" AND ")
        append("(${CalendarContract.Events.ALL_DAY} = 0 OR ${CalendarContract.Events.ALL_DAY} IS NULL)")
      }
    val args = arrayOf(endMs.toString(), startMs.toString())
    val sort = "${CalendarContract.Events.DTSTART} ASC"
    val result = mutableListOf<CalendarEventBrief>()
    appContext.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      projection,
      selection,
      args,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
      val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
      val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
      val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
      while (cursor.moveToNext()) {
        val eventId = cursor.getLong(idIdx)
        val title = cursor.getString(titleIdx).orEmpty()
        val existingStart = cursor.getLong(startIdx)
        val existingEndRaw = cursor.getLong(endIdx)
        val existingEnd = max(existingEndRaw, existingStart + 60_000L)
        if (existingStart < endMs && startMs < existingEnd) {
          result += CalendarEventBrief(eventId, title, existingStart, existingEnd)
        }
      }
    }
    return result
  }

  private fun findEventsByTitleKeyword(
    keyword: String,
    limit: Int,
  ): List<CalendarEventBrief> {
    val projection =
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
      )
    val selection =
      "(${CalendarContract.Events.DELETED} = 0 OR ${CalendarContract.Events.DELETED} IS NULL) AND ${CalendarContract.Events.TITLE} LIKE ?"
    val args = arrayOf("%$keyword%")
    val sort = "${CalendarContract.Events.DTSTART} DESC"
    val result = mutableListOf<CalendarEventBrief>()
    appContext.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      projection,
      selection,
      args,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
      val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
      val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
      val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
      while (cursor.moveToNext() && result.size < limit) {
        result +=
          CalendarEventBrief(
            id = cursor.getLong(idIdx),
            title = cursor.getString(titleIdx).orEmpty(),
            startMs = cursor.getLong(startIdx),
            endMs = cursor.getLong(endIdx),
          )
      }
    }
    return result
  }

  private fun deleteEventsByIds(ids: List<Long>): Int {
    var deletedCount = 0
    ids.forEach { id ->
      val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
      deletedCount += appContext.contentResolver.delete(uri, null, null)
    }
    return deletedCount
  }

  private fun parseIdList(raw: String?): List<Long> {
    if (raw.isNullOrBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim()?.toLongOrNull() }
      }.getOrElse { emptyList() }
    }
    return text.split(",").mapNotNull { it.trim().toLongOrNull() }
  }

  private fun parseSingleId(raw: String?): List<Long> {
    val parsed = raw?.trim()?.toLongOrNull() ?: return emptyList()
    return listOf(parsed)
  }

  private fun extractIsoDateTime(text: String): List<String> =
    Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""")
      .findAll(text)
      .map { it.value }
      .toList()
}
