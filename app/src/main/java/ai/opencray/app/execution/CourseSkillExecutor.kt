package ai.opencray.app.execution

import android.content.Context
import ai.opencray.app.domain.model.SystemAction
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

private const val LEARN_BASE_URL = "https://learn.tsinghua.edu.cn"
private const val WEBVPN_JXRL_BKS_PREFIX =
  "https://webvpn.tsinghua.edu.cn/http/" +
    "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290/" +
    "jxmh_out.do?m=bks_jxrl_all&p_start_date="
private const val WEBVPN_JXRL_YJS_PREFIX =
  "https://webvpn.tsinghua.edu.cn/http/" +
    "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290/" +
    "jxmh_out.do?m=yjs_jxrl_all&p_start_date="
private const val WEBVPN_JXRL_MIDDLE = "&p_end_date="
private const val WEBVPN_JXRL_SUFFIX = "&jsoncallback=m"
private const val LEARN_COURSE_INDEX_PATH = "/f/wlxt/index/course/student/"
private const val LEARN_COURSE_HOME_PATH = "/f/wlxt/kc/zhjw_v_code_xnxq/index.html"
private const val LEARN_COURSE_CURRENT_PATH = "/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester"

private data class CourseAuth(
  val learnBaseUrl: String,
  val learnCookie: String,
  val csrf: String,
  val webvpnCookie: String,
)

private data class SemesterInfo(
  val semesterId: String,
  val semesterName: String,
  val startDate: String,
  val endDate: String,
  val firstDay: String,
  val weekCount: Int,
) {
  fun toDataMap(): Map<String, Any?> =
    mapOf(
      "semester_id" to semesterId,
      "semester_name" to semesterName,
      "start_date" to startDate,
      "end_date" to endDate,
      "first_day" to firstDay,
      "week_count" to weekCount,
    )
}

private data class CourseItem(
  val courseId: String,
  val courseName: String,
  val teacherName: String = "",
  val timeLocation: String = "",
  val courseNo: String = "",
  val classNo: String = "",
) {
  fun toDataMap(): Map<String, Any?> =
    mapOf(
      "course_id" to courseId,
      "wlkcid" to courseId,
      "course_name" to courseName,
      "name" to courseName,
      "teacher_name" to teacherName,
      "time_location" to timeLocation,
      "course_no" to courseNo,
      "class_no" to classNo,
    )
}

private data class ScheduleEntry(
  val name: String,
  val location: String = "",
  val category: String = "",
  val date: String = "",
  val weekday: Int = 0,
  val week: Int = 0,
  val period: List<Int> = emptyList(),
) {
  fun toDataMap(): Map<String, Any?> =
    mapOf(
      "name" to name,
      "location" to location,
      "category" to category,
      "date" to date,
      "weekday" to weekday,
      "week" to week,
      "period" to period,
    )
}

class CourseSkillExecutor(
  private val appContext: Context,
) {
  fun execute(action: SystemAction): ActionExecutionReport {
    return when (action.id.substringBefore("#")) {
      "get_semesters" -> executeGetSemesters(action)
      "get_courses" -> executeGetCourses(action)
      "get_course_schedule" -> executeGetCourseSchedule(action)
      else ->
        ActionExecutionReport(
          success = false,
          message = "Unsupported course action: ${action.id}.",
          recoverable = false,
          semantic = "unsupported_action",
        )
    }
  }

  private fun executeGetSemesters(action: SystemAction): ActionExecutionReport {
    val auth = resolveAuth(action)
    if (auth.learnCookie.isBlank()) {
      return authFailure("Learn cookie is not configured. Open Settings > 清华统一登录 to sign in, then retry.")
    }

    return runCatching {
      warmUpLearnSession(auth)
      val currentRaw = getLearnJson(auth, LEARN_COURSE_CURRENT_PATH, referer = courseIndexUrl(auth), ajax = true)
      val current = parseSemester((currentRaw as? JSONObject)?.opt("result"))
      val nextSemesters =
        (currentRaw as? JSONObject)
          ?.optJSONArray("resultList")
          ?.let { array ->
            (0 until array.length()).mapNotNull { index -> parseSemester(array.opt(index)) }
          }
          .orEmpty()
      val allRaw = runCatching { getLearnJson(auth, "/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq", referer = courseIndexUrl(auth), ajax = true) }.getOrNull()
      val allSemesters = extractList(allRaw).mapNotNull { parseSemester(it) }

      val merged = mutableListOf<SemesterInfo>()
      val seen = linkedSetOf<String>()
      for (semester in listOfNotNull(current) + nextSemesters + allSemesters) {
        if (seen.add(semester.semesterId)) merged.add(semester)
      }

      ActionExecutionReport(
        success = true,
        message = "学期列表已获取，共 ${merged.size} 个学期。",
        recoverable = false,
        semantic = "course_semesters_loaded",
        metadata = mapOf(
          "status" to "ok",
          "current_semester" to (current?.semesterId.orEmpty()),
          "semesters" to merged.map { it.toDataMap() },
          "semester_count" to merged.size,
          "learn_base_url" to auth.learnBaseUrl,
        ),
      )
    }.getOrElse { throwable ->
      genericFailure(
        message = "Failed to fetch semesters: ${throwable.message ?: throwable.javaClass.simpleName}",
        reason = if (isAuthFailure(throwable.message.orEmpty())) "login_required" else "crawl_failed",
        recoverable = !isAuthFailure(throwable.message.orEmpty()),
      )
    }
  }

  private fun executeGetCourses(action: SystemAction): ActionExecutionReport {
    val auth = resolveAuth(action)
    if (auth.learnCookie.isBlank()) {
      return authFailure("Learn cookie is not configured. Open Settings > 清华统一登录 to sign in, then retry.")
    }

    return runCatching {
      val semesterId = firstNonBlank(readActionString(action, "semester_id"), currentSemesterId(auth))
      if (semesterId.isBlank()) {
        return@runCatching genericFailure(
          message = "semester_id is required and current semester could not be resolved.",
          reason = "missing_semester_id",
          recoverable = false,
        )
      }

      val lang = firstNonBlank(readActionString(action, "lang"), "zh_CN")
      warmUpLearnSession(auth)
      val raw = getLearnJson(
        auth,
        "/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/$semesterId/$lang",
        mapOf("timestamp" to System.currentTimeMillis()),
        referer = courseIndexUrl(auth),
        ajax = true,
      )
      val items = extractList(raw, "resultList", "result", "data", listOf("object", "aaData"))
      val courses = items.mapNotNull { normalizeCourse(it as? JSONObject) }

      ActionExecutionReport(
        success = true,
        message = "课程列表已获取，共 ${courses.size} 门课程。",
        recoverable = false,
        semantic = "course_list_loaded",
        metadata = mapOf(
          "status" to "ok",
          "semester_id" to semesterId,
          "courses" to courses.map { it.toDataMap() },
          "course_count" to courses.size,
          "learn_base_url" to auth.learnBaseUrl,
        ),
      )
    }.getOrElse { throwable ->
      genericFailure(
        message = "Failed to fetch courses: ${throwable.message ?: throwable.javaClass.simpleName}",
        reason = if (isAuthFailure(throwable.message.orEmpty())) "login_required" else "crawl_failed",
        recoverable = !isAuthFailure(throwable.message.orEmpty()),
      )
    }
  }

  private fun executeGetCourseSchedule(action: SystemAction): ActionExecutionReport {
    val auth = resolveAuth(action)
    val warnings = mutableListOf<String>()

    if (auth.learnCookie.isBlank()) {
      return authFailure(
        "Learn cookie is not configured. Open Settings > 清华统一登录 to sign in, then retry.",
        extra = mapOf("warnings" to warnings),
      )
    }

    return runCatching {
      val semesterId = firstNonBlank(readActionString(action, "semester_id"), currentSemesterId(auth))
      if (semesterId.isBlank()) {
        return@runCatching genericFailure(
          message = "semester_id is required for Learn course schedule.",
          reason = "missing_semester_id",
          recoverable = false,
          extra = mapOf("warnings" to warnings),
        )
      }
      val lang = firstNonBlank(readActionString(action, "lang"), "zh_CN")
      warmUpLearnSession(auth)
      val raw = getLearnJson(
        auth,
        "/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/$semesterId/$lang",
        mapOf("timestamp" to System.currentTimeMillis()),
        referer = courseIndexUrl(auth),
        ajax = true,
      )
      val courses = extractList(raw, "resultList", "result", "data", listOf("object", "aaData")).mapNotNull {
        normalizeCourse(it as? JSONObject)
      }
      val entries = buildEntriesFromCourses(courses)

      ActionExecutionReport(
        success = true,
        message = "课表已获取，共 ${entries.size} 条课表条目。",
        recoverable = false,
        semantic = "course_schedule_loaded",
        metadata = mapOf(
          "status" to "ok",
          "source" to "learn_course_list",
          "semester_id" to semesterId,
          "courses" to courses.map { it.toDataMap() },
          "schedule_entries" to entries.map { it.toDataMap() },
          "schedule_count" to entries.size,
          "course_count" to courses.size,
          "warnings" to warnings,
        ),
      )
    }.getOrElse { throwable ->
      genericFailure(
        message = "Failed to fetch course schedule: ${throwable.message ?: throwable.javaClass.simpleName}",
        reason = if (isAuthFailure(throwable.message.orEmpty())) "login_required" else "crawl_failed",
        recoverable = !isAuthFailure(throwable.message.orEmpty()),
        extra = mapOf("warnings" to warnings),
      )
    }
  }

  private fun resolveAuth(action: SystemAction): CourseAuth =
    CourseAuth(
      learnBaseUrl = firstNonBlank(readActionString(action, "learn_base_url"), readSetting("learn_base_url"), LEARN_BASE_URL),
      learnCookie = firstNonBlank(
        readActionString(action, "session_cookie"),
        readActionString(action, "cookies"),
        readActionString(action, "learn_cookie"),
        readActionString(action, "homework_cookie"),
        readSetting("homework_cookie"),
        readSetting("learn_cookie"),
      ).normalizeCookieHeader(),
      csrf = firstNonBlank(
        readActionString(action, "csrf_token"),
        readActionString(action, "homework_csrf"),
        readActionString(action, "learn_csrf"),
        extractCsrfFromCookie(firstNonBlank(readActionString(action, "session_cookie"), readActionString(action, "cookies"), readActionString(action, "learn_cookie"), readActionString(action, "homework_cookie"))),
        readSetting("homework_csrf"),
        readSetting("learn_csrf"),
      ),
      webvpnCookie = firstNonBlank(
        readActionString(action, "webvpn_cookie"),
        readActionString(action, "cookie"),
        readSetting("webvpn_cookie"),
      ).normalizeCookieHeader(),
    )

  private fun readSetting(key: String): String =
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE).getString(key, "").orEmpty().trim()

  private fun readActionString(action: SystemAction, key: String): String =
    (action.payload?.get(key) as? String)?.trim().orEmpty().ifBlank { action.params[key].orEmpty().trim() }

  private fun readActionBoolean(action: SystemAction, key: String, default: Boolean): Boolean {
    val value = readActionString(action, key)
    if (value.isBlank()) return default
    return value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
  }

  private fun currentSemesterId(auth: CourseAuth, today: LocalDate = LocalDate.now()): String {
    return runCatching {
      warmUpLearnSession(auth)
      val raw = getLearnJson(auth, LEARN_COURSE_CURRENT_PATH, referer = courseIndexUrl(auth), ajax = true)
      parseSemester((raw as? JSONObject)?.opt("result"))?.semesterId.orEmpty()
    }.getOrDefault("").ifBlank { inferCurrentSemesterId(today) }
  }

  private fun resolveScheduleSemester(auth: CourseAuth, action: SystemAction): SemesterInfo? {
    val semesterId = firstNonBlank(readActionString(action, "semester_id"), currentSemesterId(auth))
    val firstDay = readActionString(action, "first_day")
    val weekCount = readActionString(action, "week_count").toIntOrNull()
    if (semesterId.isBlank()) return null
    if (firstDay.isNotBlank() && weekCount != null) {
      return SemesterInfo(semesterId, semesterId, "", "", firstDay, weekCount)
    }

    return runCatching {
      warmUpLearnSession(auth)
      val raw = getLearnJson(auth, LEARN_COURSE_CURRENT_PATH, referer = courseIndexUrl(auth), ajax = true)
      val root = raw as? JSONObject ?: return null
      parseSemester(root.opt("result"))
        ?: root.optJSONArray("resultList")?.let { array ->
          for (index in 0 until array.length()) {
            val parsed = parseSemester(array.opt(index))
            if (parsed != null && parsed.semesterId == semesterId) return parsed
          }
          null
        }
    }.getOrNull()
  }

  private fun fetchWebvpnPrimarySchedule(
    auth: CourseAuth,
    semester: SemesterInfo,
    graduate: Boolean,
  ): List<ScheduleEntry> {
    val firstDay = parseIsoDate(semester.firstDay) ?: return emptyList()
    val prefix = if (graduate) WEBVPN_JXRL_YJS_PREFIX else WEBVPN_JXRL_BKS_PREFIX
    val groupSize = 3
    val groups = max((semester.weekCount + groupSize - 1) / groupSize, 1)
    val entries = mutableListOf<ScheduleEntry>()

    for (index in 0 until groups) {
      val start = firstDay.plusDays((index * groupSize * 7).toLong())
      val end = firstDay.plusDays((((index + 1) * groupSize - 1) * 7L) + 6L)
      val url = "$prefix${start.format(DateTimeFormatter.BASIC_ISO_DATE)}$WEBVPN_JXRL_MIDDLE${end.format(DateTimeFormatter.BASIC_ISO_DATE)}$WEBVPN_JXRL_SUFFIX"
      val text = getWebvpnText(auth, url)
      for (node in parseJsonpList(text)) {
        val raw = node as? JSONObject ?: continue
        parsePrimaryEntry(raw, semester.firstDay)?.let { entries.add(it) }
      }
    }

    return dedupeScheduleEntries(entries)
  }

  private fun parsePrimaryEntry(raw: JSONObject, firstDay: String): ScheduleEntry? {
    val name = normalizeText(raw.optString("nr"))
    val day = normalizeText(raw.optString("nq"))
    val startTime = normalizeText(raw.optString("kssj")).replace("：", ":").take(5)
    val endTime = normalizeText(raw.optString("jssj")).replace("：", ":").take(5)
    if (name.isBlank() || day.isBlank() || startTime.isBlank() || endTime.isBlank()) return null

    val dayDate = parseDate(day)
    val firstDate = parseDate(firstDay)
    val week = if (dayDate != null && firstDate != null) ((dayDate.toEpochDay() - firstDate.toEpochDay()) / 7).toInt() + 1 else 0

    return ScheduleEntry(
      name = name,
      location = normalizeText(raw.optString("dd")),
      category = normalizeText(raw.optString("fl")),
      date = day,
      weekday = dayDate?.dayOfWeek?.value ?: 0,
      week = week,
      period = timePeriodPair(startTime, endTime),
    )
  }

  private fun buildEntriesFromCourses(courses: List<CourseItem>): List<ScheduleEntry> {
    val entries = mutableListOf<ScheduleEntry>()
    for (course in courses) {
      val blocks = course.timeLocation.split(";", "\n").map { it.trim() }.filter { it.isNotBlank() }
      for (block in blocks) {
        entries += parseTimeLocationText(block, course.courseName)
      }
    }
    return dedupeScheduleEntries(entries)
  }

  private fun parseTimeLocationText(text: String, courseName: String): List<ScheduleEntry> {
    val cleaned = normalizeText(text)
    if (cleaned.isBlank()) return emptyList()
    val weekday = cleaned.toWeekday()
    val period = periodPair("", "", cleaned)
    val location = when {
      cleaned.contains("@") -> cleaned.substringAfterLast("@").trim()
      cleaned.contains("地点：") -> cleaned.substringAfter("地点：").trim().split(',', '，', ';', '；').firstOrNull().orEmpty()
      cleaned.contains("教室：") -> cleaned.substringAfter("教室：").trim().split(',', '，', ';', '；').firstOrNull().orEmpty()
      else -> ""
    }

    return listOf(
      ScheduleEntry(
        name = courseName,
        location = location,
        category = "learn",
        weekday = weekday,
        period = period,
      ),
    )
  }

  private fun scheduleSummary(entries: List<ScheduleEntry>): List<Map<String, Any?>> {
    val grouped = linkedMapOf<String, MutableMap<String, Any?>>()
    for (entry in entries) {
      val key = listOf(entry.name, entry.location, entry.category).joinToString("\u0000")
      val group = grouped.getOrPut(key) {
        mutableMapOf(
          "name" to entry.name,
          "location" to entry.location,
          "category" to entry.category,
          "occurrence_count" to 0,
          "weeks" to mutableListOf<Int>(),
          "time_and_location" to mutableListOf<Map<String, Any?>>(),
        )
      }
      group["occurrence_count"] = (group["occurrence_count"] as Int) + 1
      val weeks = group["weeks"] as MutableList<Int>
      if (entry.week > 0 && entry.week !in weeks) weeks.add(entry.week)
      val blocks = group["time_and_location"] as MutableList<Map<String, Any?>>
      val block = mapOf("weekday" to entry.weekday, "period" to entry.period, "location" to entry.location)
      if (block !in blocks) blocks.add(block)
    }

    return grouped.values.map { group ->
      mapOf(
        "name" to (group["name"] as? String).orEmpty(),
        "location" to (group["location"] as? String).orEmpty(),
        "category" to (group["category"] as? String).orEmpty(),
        "occurrence_count" to (group["occurrence_count"] as Int),
        "weeks" to (group["weeks"] as MutableList<Int>).sorted(),
        "time_and_location" to group["time_and_location"],
      )
    }
  }

  private fun normalizeCourse(raw: JSONObject?): CourseItem? {
    if (raw == null) return null
    val courseId = firstNonBlank(raw.optString("wlkcid"), raw.optString("id"), raw.optString("course_id"))
    val courseName = firstNonBlank(raw.optString("kcm"), raw.optString("name"), raw.optString("course_name"))
    if (courseId.isBlank() && courseName.isBlank()) return null
    return CourseItem(
      courseId = courseId,
      courseName = courseName.ifBlank { courseId.ifBlank { "未命名课程" } },
      teacherName = firstNonBlank(raw.optString("jsm"), raw.optString("teacher_name"), raw.optString("teacherName")),
      timeLocation = firstNonBlank(raw.optString("sjddb"), raw.optString("timeLocation")),
      courseNo = firstNonBlank(raw.optString("kch"), raw.optString("course_no")),
      classNo = firstNonBlank(raw.optString("kxhnumber"), raw.optString("class_no")),
    )
  }

  private fun getLearnJson(
    auth: CourseAuth,
    path: String,
    params: Map<String, Any?>? = null,
    referer: String = courseIndexUrl(auth),
    ajax: Boolean = false,
  ): Any {
    val url = if (path.startsWith("http")) path else "${auth.learnBaseUrl}/${path.trimStart('/')}"
    val text = requestText(
      url = url,
      cookieHeader = auth.learnCookie,
      params = params,
      accept = "application/json, text/plain, */*",
      referer = referer,
      ajax = ajax,
      csrf = auth.csrf,
      origin = originFrom(auth.learnBaseUrl),
    )
    return parseJson(text)
  }

  private fun getWebvpnText(auth: CourseAuth, url: String): String =
    requestText(
      url = url,
      cookieHeader = auth.webvpnCookie,
      accept = "application/json, text/javascript, */*; q=0.01",
      referer = WEBVPN_SECONDARY_URL,
      ajax = true,
      csrf = auth.csrf,
      origin = originFrom(WEBVPN_SECONDARY_URL),
      userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
      connectTimeoutMillis = 12_000,
      readTimeoutMillis = 24_000,
      followRedirects = true,
    )

  private fun warmUpLearnSession(auth: CourseAuth) {
    runCatching {
      requestText(
        url = courseIndexUrl(auth),
        cookieHeader = auth.learnCookie,
        accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        referer = courseIndexUrl(auth),
        ajax = false,
        csrf = auth.csrf,
        origin = originFrom(auth.learnBaseUrl),
      )
    }
  }

  private fun warmUpWebvpnSession(auth: CourseAuth) {
    runCatching {
      // perform an HTML-style prefetch to establish session/redirects similar to HomeworkSkillExecutor.getForm
      requestText(
        url = WEBVPN_SECONDARY_URL,
        cookieHeader = auth.webvpnCookie,
        accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        referer = WEBVPN_SECONDARY_URL,
        ajax = false,
        csrf = auth.csrf,
        origin = originFrom(WEBVPN_SECONDARY_URL),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
        connectTimeoutMillis = 12_000,
        readTimeoutMillis = 24_000,
        followRedirects = false,
      )
    }
  }

  private fun courseIndexUrl(auth: CourseAuth): String = "${auth.learnBaseUrl}$LEARN_COURSE_INDEX_PATH"

  private fun requestText(
    url: String,
    cookieHeader: String = "",
    params: Map<String, Any?>? = null,
    accept: String = "*/*",
    referer: String = "",
    ajax: Boolean = false,
    csrf: String = "",
    origin: String = "",
    userAgent: String = "OpenTHU-Agent/1.0",
    connectTimeoutMillis: Int = 20_000,
    readTimeoutMillis: Int = 20_000,
    followRedirects: Boolean = true,
  ): String {
    var targetUrl = url
    val cleaned = params.orEmpty().filterValues { value -> value != null && value.toString().isNotBlank() }
    if (cleaned.isNotEmpty()) {
      targetUrl += if (targetUrl.contains("?")) "&" else "?"
      targetUrl += cleaned.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value.toString(), StandardCharsets.UTF_8.name())}"
      }
    }

    val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
      instanceFollowRedirects = followRedirects
      requestMethod = "GET"
      connectTimeout = connectTimeoutMillis
      readTimeout = readTimeoutMillis
      setRequestProperty("Accept", accept)
      setRequestProperty("User-Agent", userAgent)
      if (cookieHeader.isNotBlank()) setRequestProperty("Cookie", cookieHeader)
      if (referer.isNotBlank()) setRequestProperty("Referer", referer)
      if (ajax) setRequestProperty("X-Requested-With", "XMLHttpRequest")
      if (origin.isNotBlank() && ajax) setRequestProperty("Origin", origin)
      if (csrf.isNotBlank()) {
        setRequestProperty("X-XSRF-TOKEN", csrf)
        setRequestProperty("X-CSRF-TOKEN", csrf)
        setRequestProperty("X-XSRFToken", csrf)
      }
    }

    return try {
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.let { BufferedInputStream(it).use { input -> input.readBytes() } } ?: ByteArray(0)
      val text = decodeResponse(body, connection.contentType)
      if (status !in 200..299) {
        throw IllegalStateException("HTTP $status for $targetUrl: ${text.take(512)}")
      }
      text
    } catch (e: Exception) {
      val shortStack = e.stackTrace.take(12).joinToString("\\n") { it.toString() }
      throw IllegalStateException("Request failed for $targetUrl: ${e.javaClass.simpleName}: ${e.message}\n$shortStack", e)
    } finally {
      connection.disconnect()
    }
  }

  private fun parseJson(text: String): Any {
    val trimmed = text.trim()
    return when {
      trimmed.startsWith("{") -> JSONObject(trimmed)
      trimmed.startsWith("[") -> JSONArray(trimmed)
      else -> JSONObject()
    }
  }

  private fun extractList(raw: Any?, vararg keys: Any): List<Any> {
    when (raw) {
      is JSONArray -> return (0 until raw.length()).mapNotNull { raw.opt(it) }
      is List<*> -> return raw.filterNotNull().map { it as Any }
      is JSONObject -> {
        for (key in keys) {
          when (key) {
            is String -> {
              val list = extractList(raw.opt(key))
              if (list.isNotEmpty()) return list
            }
            is List<*> -> {
              var node: Any? = raw
              for (pathKey in key) {
                node = (node as? JSONObject)?.opt(pathKey.toString())
              }
              val list = extractList(node)
              if (list.isNotEmpty()) return list
            }
          }
        }
      }
    }
    return emptyList()
  }

  private fun parseSemester(raw: Any?): SemesterInfo? {
    if (raw !is JSONObject) {
      val text = raw?.toString()?.trim().orEmpty()
      if (text.isBlank()) return null
      return SemesterInfo(text, text, "", "", "", 0)
    }

    val semesterId = firstNonBlank(raw.optString("id"), raw.optString("xnxq"), raw.optString("semester_id"))
    if (semesterId.isBlank()) return null

    val start = parseDate(firstNonBlank(raw.optString("kssj"), raw.optString("start_date"), raw.optString("startDate")))
    val end = parseDate(firstNonBlank(raw.optString("jssj"), raw.optString("end_date"), raw.optString("endDate")))
    val firstDay = start?.let { alignedMonday(it) }?.toString().orEmpty()
    val weekCount = if (start != null && end != null && firstDay.isNotBlank()) {
      val firstDate = parseIsoDate(firstDay) ?: return null
      max(((end.toEpochDay() - firstDate.toEpochDay()) / 7).toInt() + 1, 0)
    } else {
      0
    }

    return SemesterInfo(
      semesterId = semesterId,
      semesterName = firstNonBlank(raw.optString("xnxqmc"), raw.optString("name"), raw.optString("semester_name"), semesterId),
      startDate = start?.toString().orEmpty(),
      endDate = end?.toString().orEmpty(),
      firstDay = firstDay,
      weekCount = weekCount,
    )
  }

  private fun parseJsonpList(text: String): List<Any> {
    val stripped = text.trim()
    val start = stripped.indexOf('[')
    val end = stripped.lastIndexOf(']')
    if (start < 0 || end < start) return emptyList()
    return runCatching {
      val array = JSONArray(stripped.substring(start, end + 1))
      (0 until array.length()).mapNotNull { array.opt(it) }
    }.getOrDefault(emptyList())
  }

  private fun parseDate(value: String): LocalDate? {
    val text = value.trim()
    if (text.isBlank()) return null
    for (pattern in listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd")) {
      val normalized = if (pattern == "yyyyMMdd") text.take(8) else text.take(10)
      runCatching { return LocalDate.parse(normalized, DateTimeFormatter.ofPattern(pattern)) }
    }
    return null
  }

  private fun parseIsoDate(value: String): LocalDate? {
    val text = value.trim()
    if (text.isBlank()) return null
    return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
  }

  private fun alignedMonday(date: LocalDate): LocalDate =
    when (date.dayOfWeek.value) {
      6 -> date.plusDays(2)
      7 -> date.plusDays(1)
      else -> date.minusDays((date.dayOfWeek.value - 1).toLong())
    }

  private fun inferCurrentSemesterId(today: LocalDate = LocalDate.now()): String {
    val year = today.year
    return when (today.monthValue) {
      1 -> "${year - 1}-$year-1"
      in 2..7 -> "${year - 1}-$year-2"
      else -> "$year-${year + 1}-1"
    }
  }

  private fun timePeriodPair(start: String, end: String): List<Int> {
    val periodStart = mapOf(
      "08:00" to 1,
      "08:50" to 2,
      "09:50" to 3,
      "10:40" to 4,
      "11:30" to 5,
      "13:30" to 6,
      "14:20" to 7,
      "15:20" to 8,
      "16:10" to 9,
      "17:05" to 10,
      "17:55" to 11,
      "19:20" to 12,
      "20:10" to 13,
      "21:00" to 14,
    )
    val periodEnd = mapOf(
      "08:45" to 1,
      "09:35" to 2,
      "10:35" to 3,
      "11:25" to 4,
      "12:15" to 5,
      "14:15" to 6,
      "15:05" to 7,
      "16:05" to 8,
      "16:55" to 9,
      "17:50" to 10,
      "18:40" to 11,
      "20:05" to 12,
      "20:55" to 13,
      "21:45" to 14,
    )
    val begin = periodStart[start.take(5)]
    val finish = periodEnd[end.take(5)]
    return if (begin != null && finish != null) listOf(begin, finish) else emptyList()
  }

  private fun periodPair(start: String, end: String, fallback: String): List<Int> {
    val startText = start.trim()
    val endText = end.trim()
    if (startText.toIntOrNull() != null && endText.toIntOrNull() != null) {
      return listOf(startText.toInt(), endText.toInt())
    }
    Regex("(\\d{1,2})\\s*[-~至,，]\\s*(\\d{1,2})\\s*节?").find(fallback)?.let {
      return listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    Regex("第\\s*(\\d{1,2})\\s*节").find(fallback)?.let {
      val single = it.groupValues[1].toInt()
      return listOf(single, single)
    }
    return emptyList()
  }

  private fun String.toWeekday(): Int {
    val text = trim()
    if (text.isBlank()) return 0
    text.toIntOrNull()?.let { return if (it in 1..7) it else 0 }
    val lower = text.lowercase(Locale.getDefault())
    return when {
      "一" in lower || "mon" in lower -> 1
      "二" in lower || "tue" in lower -> 2
      "三" in lower || "wed" in lower -> 3
      "四" in lower || "thu" in lower -> 4
      "五" in lower || "fri" in lower -> 5
      "六" in lower || "sat" in lower -> 6
      "日" in lower || "天" in lower || "sun" in lower -> 7
      else -> 0
    }
  }

  private fun normalizeText(value: String?): String = value.orEmpty().trim().replace("\\s+".toRegex(), " ")

  private fun String.normalizeCookieHeader(): String =
    split(";")
      .map { it.trim() }
      .filter { it.contains("=") }
      .joinToString("; ")

  private fun firstNonBlank(vararg values: String?): String {
    for (value in values) {
      val text = value?.trim().orEmpty()
      if (text.isNotBlank()) return text
    }
    return ""
  }

  private fun dedupeScheduleEntries(entries: List<ScheduleEntry>): List<ScheduleEntry> {
    val seen = linkedSetOf<String>()
    val deduped = mutableListOf<ScheduleEntry>()
    for (entry in entries) {
      val key = entry.toDataMap().toString()
      if (seen.add(key)) deduped.add(entry)
    }
    return deduped
  }

  private fun isAuthFailure(message: String): Boolean {
    val lowered = message.lowercase(Locale.getDefault())
    return lowered.contains("http 401") ||
      lowered.contains("http 403") ||
      lowered.contains("authserver") ||
      lowered.contains("login") ||
      lowered.contains("cookie")
  }

  private fun authFailure(
    message: String,
    extra: Map<String, Any?> = emptyMap(),
  ): ActionExecutionReport =
    ActionExecutionReport(
      success = false,
      message = message,
      recoverable = false,
      semantic = "course_info_auth",
      metadata = mapOf("status" to "not_configured", "reason" to "login_required") + extra,
    )

  private fun genericFailure(
    message: String,
    reason: String,
    recoverable: Boolean,
    extra: Map<String, Any?> = emptyMap(),
  ): ActionExecutionReport =
    ActionExecutionReport(
      success = false,
      message = message,
      recoverable = recoverable,
      semantic = if (reason == "login_required") "course_info_auth" else "action_failed",
      metadata = mapOf(
        "status" to if (reason == "login_required") "not_configured" else "failed",
        "reason" to reason,
      ) + extra,
    )

  private fun decodeResponse(raw: ByteArray, contentType: String?): String {
    val charset = Regex("charset=([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
      .find(contentType.orEmpty())
      ?.groupValues
      ?.getOrNull(1)
      .orEmpty()
    for (candidate in listOf(charset, "utf-8", "gb18030")) {
      if (candidate.isBlank()) continue
      runCatching { return String(raw, Charset.forName(candidate)) }
    }
    return String(raw, StandardCharsets.UTF_8)
  }
}
