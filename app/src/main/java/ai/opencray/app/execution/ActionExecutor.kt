package ai.opencray.app.execution

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ai.opencray.app.domain.model.SystemAction
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
  /** Structured key-value data submitted to server (e.g. conflict details, created event id). */
  val data: Map<String, Any> = emptyMap(),
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

private data class HomeworkAttachment(
  val token: String,
  val fileName: String,
  val downloadUrl: String = "",
  val previewUrl: String = "",
)

private data class HomeworkRecord(
  val homeworkId: String,
  val studentHomeworkId: String,
  val title: String,
  val deadline: String,
  val submitted: Boolean,
  val courseId: String,
  val courseName: String,
  val detailUrl: String,
  val courseNo: String = "",
  val classNo: String = "",
  val teacherName: String = "",
  val statusGroup: String = "",
  val sourceEndpoint: String = "",
  val submitUrl: String = "",
)

private data class HomeworkCourse(
  val wlkcid: String,
  val courseName: String,
  val courseEnglishName: String = "",
  val courseNo: String = "",
  val classNo: String = "",
  val teacherName: String = "",
  val timeLocation: String = "",
  val homeworkTotal: String = "",
  val homeworkUnsubmitted: String = "",
  val courseUrl: String = "",
  val homeworkPageUrl: String = "",
)

class ActionExecutor(
  private val appContext: Context,
) {
  @Volatile private var cachedHomeworkAuth: HomeworkAuth? = null

  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    val actionId = action.id.substringBefore("#")
    return when (actionId) {
      "create_calendar_event" -> executeCreateCalendarEvent(action, goal)
      "detect_calendar_conflicts" -> executeConflictDetection(action, goal)
      "delete_calendar_event" -> executeDeleteCalendarEvent(action)
      "get_homework_cookie" -> executeGetHomeworkCookie(action)
      "get_courses" -> executeGetHomeworkCourses(action)
      "crawl_course_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = false)
      "crawl_unsubmitted_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = true)
      "preview_homework_attachments" -> executePreviewHomeworkAttachments(action)
      "upload_homework_attachment" -> executeUploadHomeworkAttachment(action)
      "submit_homework" -> executeSubmitHomework(action)
      "set_alarm_reminder", "set_alarm" -> executeAlarmIntent(action, goal)
      "open_tsinghua_news" -> openWebPage("https://www.tsinghua.edu.cn")
      "open_url" -> {
        val url =
          (action.payload?.get("url") as? String)
            ?: action.params["url"]
            ?: "https://www.tsinghua.edu.cn"
        openWebPage(url)
      }
      "open_context_review" ->
        ActionExecutionReport(
          success = true,
          message = "Opened context review fallback path.",
          recoverable = false,
        )
      else ->
        ActionExecutionReport(
          success = true,
          message = "No-op fallback executed for ${action.id}.",
          recoverable = false,
        )
    }
  }

  private fun executeGetHomeworkCookie(action: SystemAction): ActionExecutionReport {
    val providedCookies = readActionString(action, "cookies")
    val baseUrl = readActionString(action, "learn_base_url").ifBlank { "https://learn.tsinghua.edu.cn" }
    val providedCsrf = readActionString(action, "csrf_token")
    if (providedCookies.isNotBlank()) {
      val normalizedCookie = normalizeCookieHeader(providedCookies)
      if (normalizedCookie.isBlank()) {
        return ActionExecutionReport(
          success = false,
          message = "Invalid cookies input.",
          recoverable = false,
          data = mapOf("reason" to "invalid_cookies"),
        )
      }
      val csrf = providedCsrf.ifBlank { extractCookieValue(normalizedCookie, "XSRF-TOKEN") }
      cachedHomeworkAuth =
        HomeworkAuth(
          ok = true,
          message = "ok",
          baseUrl = baseUrl,
          cookie = normalizedCookie,
          csrf = csrf,
        )
      return ActionExecutionReport(
        success = true,
        message = "Homework cookie loaded from provided cookies.",
        recoverable = false,
        data =
          mapOf(
            "status" to "cookie_ready",
            "cookie_source" to "server_provided",
            "has_csrf" to csrf.isNotBlank(),
            "cookie_names" to cookieNames(normalizedCookie),
          ),
      )
    }

    val studentId = readActionString(action, "student_id")
    val password = readActionString(action, "password")
    if (studentId.isBlank() || password.isBlank()) {
      return ActionExecutionReport(
        success = false,
        message = "student_id/password are required when cookies is empty.",
        recoverable = false,
        data = mapOf("reason" to "missing_credentials"),
      )
    }

    val fetched = fetchHomeworkAuthByCredential(studentId = studentId, password = password, baseUrl = baseUrl)
    return if (fetched.ok) {
      cachedHomeworkAuth = fetched
      ActionExecutionReport(
        success = true,
        message = "Homework cookie acquired by credential login.",
        recoverable = false,
        data =
          mapOf(
            "status" to "cookie_ready",
            "cookie_source" to "credential_login",
            "has_csrf" to fetched.csrf.isNotBlank(),
            "cookie_names" to cookieNames(fetched.cookie),
          ),
      )
    } else {
      ActionExecutionReport(
        success = false,
        message = fetched.message,
        recoverable = false,
        data = mapOf("reason" to "auth_failed"),
      )
    }
  }

  private fun executeGetHomeworkCourses(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val forceRefresh = readActionString(action, "force_refresh").toBooleanStrictOrNull() ?: false
    return runCatching {
      val cachedBefore = readCachedHomeworkCourses()
      val courses = loadHomeworkCourses(auth, action, forceRefresh = forceRefresh)
      ActionExecutionReport(
        success = true,
        message = "Homework courses loaded: ${courses.size} item(s).",
        recoverable = false,
        data =
          mapOf(
            "status" to "courses_loaded",
            "count" to courses.size,
            "courses" to courses.map { it.toDataMap() },
            "from_cache" to (!forceRefresh && cachedBefore.isNotEmpty()),
            "persisted_to" to coursesCacheFile().absolutePath,
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Homework courses failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data = mapOf("reason" to "courses_failed", "exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executeCrawlCourseHomeworks(
    action: SystemAction,
    unsubmittedOnly: Boolean,
  ): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val baseUrl = auth.baseUrl
    val cookie = auth.cookie
    val csrf = auth.csrf
    val requestedCourseIds = parseCsvOrJsonArray(action.params["course_ids"])

    val records = mutableListOf<HomeworkRecord>()
    val endpointTypes = if (unsubmittedOnly) listOf("unsubmitted") else listOf("unsubmitted", "submitted_ungraded", "graded", "excellent")
    val endpointMap =
      mapOf(
        "unsubmitted" to "/b/wlxt/kczy/zy/student/zyListWj",
        "submitted_ungraded" to "/b/wlxt/kczy/zy/student/zyListYjwg",
        "graded" to "/b/wlxt/kczy/zy/student/zyListYpg",
        "excellent" to "/b/wlxt/kczy/zy/student/yxzylist",
      )

    return runCatching {
      val courses =
        loadHomeworkCourses(auth, action, forceRefresh = false)
          .filter { course -> requestedCourseIds.isEmpty() || course.wlkcid in requestedCourseIds }
      courses.forEach { course ->
        getForm(
          url = course.homeworkPageUrl,
          cookie = cookie,
          csrfToken = csrf,
          referer = course.courseUrl,
          htmlAccept = true,
        )
        endpointTypes.forEach { endpointType ->
          val endpoint = endpointMap[endpointType].orEmpty()
          val form = datatablePayload(course.wlkcid)
          val raw =
            postForm(
              url = "$baseUrl$endpoint",
              form = form,
              cookie = cookie,
              csrfToken = csrf,
              referer = course.homeworkPageUrl,
            )
          val parsed = runCatching { JSONObject(raw) }.getOrNull()
          val items = extractHomeworkRecords(parsed, course, endpointType, endpoint, baseUrl)
          records += items
        }
      }

      val deduped = records.distinctBy { "${it.homeworkId}::${it.studentHomeworkId}" }.sortedBy { it.deadline }
      val filtered = filterHomeworkRecords(deduped)
      persistHomeworkRecords(filtered)
      val status = if (unsubmittedOnly) "unsubmitted_crawled" else "crawled"
      ActionExecutionReport(
        success = true,
        message = "Homework crawl completed: ${filtered.size} item(s).",
        recoverable = false,
        data =
          mapOf(
            "status" to status,
            "count" to filtered.size,
            "homeworks" to filtered.map { it.toDataMap() },
            "count_before_filter" to deduped.size,
            "course_ids" to requestedCourseIds,
            "course_scope" to if (requestedCourseIds.isEmpty()) "all_courses_from_cache_or_fetch" else "selected_courses",
            "filters_applied" to listOf("exclude_title_contains_supplement", "exclude_deadline_passed_if_epoch_ms"),
            "persisted_to" to homeworkCacheFile().absolutePath,
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Homework crawl failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "crawl_failed",
            "exception" to (throwable.message ?: "unknown"),
            "course_ids" to requestedCourseIds,
          ),
      )
    }
  }

  private fun executePreviewHomeworkAttachments(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val baseUrl = auth.baseUrl
    val homeworkId = action.params["homework_id"]?.trim().orEmpty()
    if (homeworkId.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "homework_id is required.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }

    val detailUrl = "$baseUrl/b/wlxt/kczy/zy/student/detail"
    return runCatching {
      val raw =
        postForm(
          url = detailUrl,
          form = mapOf("id" to homeworkId),
          cookie = auth.cookie,
          csrfToken = auth.csrf,
        )
      val parsed = runCatching { JSONObject(raw) }.getOrNull()
      val attachments = extractAttachments(parsed, baseUrl)
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "$baseUrl/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment preview opened. Found ${attachments.size} attachment(s).",
        recoverable = false,
        data =
          mapOf(
            "status" to "preview_ready",
            "homework_id" to homeworkId,
            "attachments" to attachments.map { it.toDataMap() },
            "opened_url" to openUrl,
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Preview homework attachments failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "preview_failed",
            "homework_id" to homeworkId,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
  }

  private fun executeUploadHomeworkAttachment(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val homeworkId = action.params["homework_id"]?.trim().orEmpty()
    if (homeworkId.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "homework_id is required.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }
    val fileRef = action.params["file_path"]?.trim().orEmpty()
    val fileUri = action.params["file_uri"]?.trim().orEmpty()
    if (fileRef.isEmpty() && fileUri.isEmpty()) {
      // No file yet: open a page and document picker so user can pick a file on-device.
      val pickerIntent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      launchIntent(pickerIntent, "Opened file picker for homework attachment.")
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      return ActionExecutionReport(
        success = false,
        message = "No file provided. Opened file picker and homework page for manual selection.",
        recoverable = false,
        data =
          mapOf(
            "status" to "awaiting_file_selection",
            "homework_id" to homeworkId,
            "opened_url" to openUrl,
          ),
      )
    }

    return runCatching {
      val uploadUrl = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy"
      val filePart = resolveFilePart(fileRef, fileUri, action.params["file_name"].orEmpty())
      val textBody = action.params["submission_text"].orEmpty()
      val responseBody =
        postMultipart(
          url = uploadUrl,
          cookie = auth.cookie,
          csrfToken = auth.csrf,
          textParts =
            mapOf(
              "xszyid" to homeworkId,
              "zynr" to textBody,
              "isDeleted" to "0",
            ),
          filePart = filePart,
        )
      val token = "upload_${System.currentTimeMillis()}"
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment uploaded successfully.",
        recoverable = false,
        data =
          mapOf(
            "status" to "uploaded",
            "homework_id" to homeworkId,
            "attachment_token" to token,
            "file_name" to filePart.fileName,
            "opened_url" to openUrl,
            "upstream_response_excerpt" to responseBody.take(512),
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Upload homework attachment failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "upload_failed",
            "homework_id" to homeworkId,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
  }

  private fun executeSubmitHomework(action: SystemAction): ActionExecutionReport {
    val confirmed = action.params["confirm_submit"]?.toBooleanStrictOrNull() ?: false
    if (!confirmed) {
      return ActionExecutionReport(
        success = false,
        message = "Submit blocked: confirm_submit=true is required for high-risk homework submission.",
        recoverable = false,
        data = mapOf("reason" to "confirm_submit_required"),
      )
    }
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val requestedId = action.params["homework_id"]?.trim().orEmpty()
    val requestedXszyid = action.params["xszyid"]?.trim().orEmpty()
    val cachedHomework = findCachedHomework(requestedId.ifBlank { requestedXszyid })
    val xszyid =
      requestedXszyid
        .ifBlank { cachedHomework?.studentHomeworkId.orEmpty() }
        .ifBlank { requestedId }
    val zyid =
      action.params["zyid"]?.trim().orEmpty()
        .ifBlank { action.params["homework_zyid"]?.trim().orEmpty() }
        .ifBlank { cachedHomework?.homeworkId.orEmpty() }
    val wlkcid =
      action.params["wlkcid"]?.trim().orEmpty()
        .ifBlank { action.params["course_id"]?.trim().orEmpty() }
        .ifBlank { cachedHomework?.courseId.orEmpty() }
    if (xszyid.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "submit_homework requires xszyid/homework_id.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }
    if (wlkcid.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "submit_homework requires wlkcid/course_id. Run crawl_unsubmitted_homeworks first or pass course_id.",
        recoverable = false,
        data = mapOf("reason" to "missing_course_id", "xszyid" to xszyid),
      )
    }

    val submissionText = action.params["submission_text"].orEmpty()
    var fileRef = action.params["file_path"]?.trim().orEmpty()
    var fileUri = action.params["file_uri"]?.trim().orEmpty()
    val localFilePaths = parseCsvOrJsonArray(action.params["local_file_paths"])
    if (fileRef.isEmpty() && fileUri.isEmpty() && localFilePaths.isNotEmpty()) {
      fileRef = localFilePaths.first()
    }
    val attachmentTokens = parseCsvOrJsonArray(action.params["attachment_tokens"])
    val hasFile = fileRef.isNotEmpty() || fileUri.isNotEmpty()
    if (submissionText.isBlank() && !hasFile && attachmentTokens.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "submit_homework requires submission_text or file_path/file_uri/local_file_paths or attachment_tokens.",
        recoverable = false,
        data = mapOf("reason" to "missing_submission_content"),
      )
    }

    return runCatching {
      val submitUrl = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy"
      val filePart = if (hasFile) resolveFilePart(fileRef, fileUri, action.params["file_name"].orEmpty()) else null
      val attachmentTokenText = attachmentTokens.joinToString(",")
      val submitPageUrl = "${auth.baseUrl}/f/wlxt/kczy/zy/student/tijiao?wlkcid=$wlkcid&xszyid=$xszyid"
      val submitPage =
        getForm(
          url = submitPageUrl,
          cookie = auth.cookie,
          csrfToken = auth.csrf,
          referer = submitPageUrl,
          htmlAccept = true,
        )
      val form = parseSubmitForm(submitPage)
      val fields = form.fields.toMutableMap()
      fields["xszyid"] = xszyid
      fields["isDeleted"] = fields["isDeleted"].orEmpty().ifBlank { "0" }
      fields["zynr"] = submissionText
      if (attachmentTokenText.isNotBlank()) {
        fields["fjids"] = attachmentTokenText
      }
      val responseBody =
        postMultipart(
          url = submitUrl,
          cookie = auth.cookie,
          csrfToken = auth.csrf,
          referer = submitPageUrl,
          textParts = fields,
          filePart = filePart,
          fileFieldName = form.fileFieldName,
        )
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          cachedHomework?.detailUrl.orEmpty().ifEmpty { submitPageUrl }
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework submitted successfully.",
        recoverable = false,
        data =
          mapOf(
            "status" to "submitted",
            "homework_id" to zyid,
            "xszyid" to xszyid,
            "wlkcid" to wlkcid,
            "submitted_at" to Instant.now().toString(),
            "attachment_tokens" to attachmentTokens,
            "local_file_paths" to localFilePaths,
            "opened_url" to openUrl,
            "upstream_response_excerpt" to responseBody.take(512),
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Submit homework failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "submit_failed",
            "homework_id" to zyid,
            "xszyid" to xszyid,
            "wlkcid" to wlkcid,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
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
    val window = deriveWindow(action.params, goal) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing start_time/end_time.",
      recoverable = false,
    )
    val conflictDecision = action.params["conflict_decision"]?.trim()?.lowercase() ?: "prompt_user"
    val conflicts = queryConflicts(window.startMs, window.endMs)

    if (conflicts.isNotEmpty()) {
      when (conflictDecision) {
        "skip_write" -> {
          return ActionExecutionReport(
            success = true,
            message = "瀹歌尪鐑︽潻鍥у晸閸忋儻绱檚kip_write閿涘鈧?{conflictSummaryMessage(conflicts)}",
            recoverable = false,
            data = mapOf(
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
              message = "閸愯尙鐛婇崚鐘绘珟闂団偓鐟曚焦妲戠涵顔藉房閺夊喛绱檃llow_conflict_delete=true閿涘鈧?{conflictSummaryMessage(conflicts)}",
              recoverable = false,
              data = mapOf(
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
            message = "濡偓濞村鍩岄崘鑼崐閿涘矁顕柅澶嬪缁涙牜鏆?skip_write / coexist / delete_conflicts閵?{conflictSummaryMessage(conflicts)}",
            recoverable = false,
            data = mapOf(
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
          put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
      val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
      val eventId = uri?.lastPathSegment ?: "unknown"
      ActionExecutionReport(
        success = true,
        message = "Calendar event created: $title ${formatEpochMs(window.startMs)}-${formatEpochMs(window.endMs)} (event_id=$eventId).",
        recoverable = false,
        data = mapOf(
          "event_id" to eventId,
          "title" to title,
          "start" to formatEpochMs(window.startMs),
          "end" to formatEpochMs(window.endMs),
          "calendar_id" to calendarId,
          "deleted_conflicts" to if (conflicts.isNotEmpty()) conflictsToDataList(conflicts) else emptyList<Any>(),
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Create calendar event failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data = mapOf("exception" to (throwable.message ?: "unknown")),
      )
    }
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
    val window = deriveWindow(action.params, goal) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing start_time/end_time.",
      recoverable = false,
    )
    val conflicts = queryConflicts(window.startMs, window.endMs)
    return ActionExecutionReport(
      success = true,
      message = if (conflicts.isEmpty()) {
        "Conflict check completed: 0 overlap(s). Android calendar overlap supported=true."
      } else {
        conflictSummaryMessage(conflicts)
      },
      recoverable = false,
      data = mapOf(
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
        message = "Deleted $deleted calendar event(s); requested ${idsToDelete.size}.",
        recoverable = false,
        data = mapOf(
          "deleted_count" to deleted,
          "requested_ids" to idsToDelete,
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Delete calendar event failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data = mapOf(
          "exception" to (throwable.message ?: "unknown"),
          "requested_ids" to idsToDelete,
        ),
      )
    }
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    val timeStr = action.payload?.get("time") as? String
      ?: return ActionExecutionReport(false, "Missing 'time' in payload", false)

    val parsed = parseAlarmTime(timeStr)
      ?: return ActionExecutionReport(false, "Invalid 'time' format (expected ISO8601 UTC or HH:mm)", false)

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
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    return launchIntent(intent, "Alarm setup intent launched for ${hourArg}:${minArg.toString().padStart(2, '0')}")
  }

  private fun parseAlarmTime(timeRaw: String): Pair<Int, Int>? {
    runCatching {
      val zdt = Instant.parse(timeRaw).atZone(ZoneId.systemDefault())
      return Pair(zdt.hour, zdt.minute)
    }

    val match = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(timeRaw.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return Pair(hour, minute)
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
      "閵?{event.title}閵?{formatEpochMs(event.startMs)}-${formatEpochMs(event.endMs)}"
    }
    return "閸愯尙鐛婃禍瀣€?${conflicts.size}): $items"
  }

  private fun launchIntent(intent: Intent, successMessage: String): ActionExecutionReport {
    return runCatching {
      appContext.startActivity(intent)
      ActionExecutionReport(success = true, message = successMessage, recoverable = false)
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Intent execution failed",
        recoverable = true,
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

  private fun deriveWindow(
    params: Map<String, String>,
    goal: String,
  ): CalendarWindow? {
    val extracted = extractIsoDateTime(goal)
    val startRaw = params["start_time"]?.trim().orEmpty().ifEmpty { extracted.firstOrNull().orEmpty() }
    val endRaw = params["end_time"]?.trim().orEmpty().ifEmpty { extracted.getOrNull(1).orEmpty() }

    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val now = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
    val start = runCatching { OffsetDateTime.parse(startRaw, formatter) }.getOrNull() ?: now.plusHours(1)
    val end = runCatching { OffsetDateTime.parse(endRaw, formatter) }.getOrNull() ?: start.plusHours(1)
    if (!end.isAfter(start)) return null
    return CalendarWindow(
      startMs = start.toInstant().toEpochMilli(),
      endMs = end.toInstant().toEpochMilli(),
    )
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
    // Exclude all-day events (ALL_DAY=1) 閳?their midnight UTC times cause false positives
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

  private fun homeworkCacheDir(): File =
    File(appContext.filesDir, "homework_skill").apply { mkdirs() }

  private fun coursesCacheFile(): File = File(homeworkCacheDir(), "courses.json")

  private fun homeworkCacheFile(): File = File(homeworkCacheDir(), "homeworks.json")

  private fun loadHomeworkCourses(
    auth: HomeworkAuth,
    action: SystemAction,
    forceRefresh: Boolean,
  ): List<HomeworkCourse> {
    if (!forceRefresh) {
      val cached = readCachedHomeworkCourses()
      if (cached.isNotEmpty()) return cached
    }
    val courses = fetchHomeworkCourses(auth, action)
    persistHomeworkCourses(courses)
    return courses
  }

  private fun fetchHomeworkCourses(
    auth: HomeworkAuth,
    action: SystemAction,
  ): List<HomeworkCourse> {
    val semesterId = readActionString(action, "semester_id").ifBlank { "2025-2026-2" }
    val locale = readActionString(action, "locale").ifBlank { "zh" }
    val ts = System.currentTimeMillis()
    val url =
      "${auth.baseUrl}/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/$semesterId/$locale?timestamp=$ts"
    val raw =
      getForm(
        url = url,
        cookie = auth.cookie,
        csrfToken = auth.csrf,
        referer = "${auth.baseUrl}/f/wlxt/index/course/student/",
        ajax = true,
      )
    val root = JSONObject(raw)
    val arr = root.optJSONArray("resultList") ?: JSONArray()
    val courses = mutableListOf<HomeworkCourse>()
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val wlkcid = normalizeNullableText(item.optString("wlkcid"))
      if (wlkcid.isBlank()) continue
      courses +=
        HomeworkCourse(
          wlkcid = wlkcid,
          courseName = normalizeNullableText(item.optString("kcm")),
          courseEnglishName = normalizeNullableText(item.optString("ywkcm")),
          courseNo = normalizeNullableText(item.optString("kch")),
          classNo = normalizeNullableText(item.optString("kxhnumber")),
          teacherName = normalizeNullableText(item.optString("jsm")),
          timeLocation = normalizeNullableText(item.optString("sjddb")),
          homeworkTotal = normalizeNullableText(item.optString("zyzs")),
          homeworkUnsubmitted = normalizeNullableText(item.optString("wjzys")),
          courseUrl = "${auth.baseUrl}/f/wlxt/index/course/student/course?wlkcid=$wlkcid",
          homeworkPageUrl = "${auth.baseUrl}/f/wlxt/kczy/zy/student/beforePageList?wlkcid=$wlkcid",
        )
    }
    return courses
  }

  private fun persistHomeworkCourses(courses: List<HomeworkCourse>) {
    val root =
      JSONObject()
        .put("updated_at", Instant.now().toString())
        .put("courses", JSONArray(courses.map { JSONObject(it.toDataMap()) }))
    coursesCacheFile().writeText(root.toString(2), Charsets.UTF_8)
  }

  private fun readCachedHomeworkCourses(): List<HomeworkCourse> {
    val file = coursesCacheFile()
    if (!file.exists()) return emptyList()
    return runCatching {
      val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("courses") ?: JSONArray()
      val result = mutableListOf<HomeworkCourse>()
      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val wlkcid = normalizeNullableText(item.optString("wlkcid"))
        if (wlkcid.isBlank()) continue
        result +=
          HomeworkCourse(
            wlkcid = wlkcid,
            courseName = normalizeNullableText(item.optString("course_name")),
            courseEnglishName = normalizeNullableText(item.optString("course_english_name")),
            courseNo = normalizeNullableText(item.optString("course_no")),
            classNo = normalizeNullableText(item.optString("class_no")),
            teacherName = normalizeNullableText(item.optString("teacher_name")),
            timeLocation = normalizeNullableText(item.optString("time_location")),
            homeworkTotal = normalizeNullableText(item.optString("homework_total")),
            homeworkUnsubmitted = normalizeNullableText(item.optString("homework_unsubmitted")),
            courseUrl = normalizeNullableText(item.optString("course_url")),
            homeworkPageUrl = normalizeNullableText(item.optString("homework_page_url")),
          )
      }
      result
    }.getOrElse { emptyList() }
  }

  private fun persistHomeworkRecords(records: List<HomeworkRecord>) {
    val root =
      JSONObject()
        .put("updated_at", Instant.now().toString())
        .put("homeworks", JSONArray(records.map { JSONObject(it.toDataMap()) }))
    homeworkCacheFile().writeText(root.toString(2), Charsets.UTF_8)
  }

  private fun findCachedHomework(id: String): HomeworkRecord? {
    val normalized = normalizeNullableText(id)
    if (normalized.isBlank()) return null
    val file = homeworkCacheFile()
    if (!file.exists()) return null
    return runCatching {
      val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("homeworks") ?: JSONArray()
      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val zyid = normalizeNullableText(item.optString("zyid")).ifBlank { normalizeNullableText(item.optString("homework_id")) }
        val xszyid = normalizeNullableText(item.optString("xszyid")).ifBlank { normalizeNullableText(item.optString("student_homework_id")) }
        if (normalized == zyid || normalized == xszyid) {
          return@runCatching HomeworkRecord(
            homeworkId = zyid,
            studentHomeworkId = xszyid,
            title = normalizeNullableText(item.optString("homework_title")).ifBlank { normalizeNullableText(item.optString("title")) },
            deadline = normalizeNullableText(item.optString("deadline")),
            submitted = item.optBoolean("submitted", false),
            courseId = normalizeNullableText(item.optString("course_wlkcid")).ifBlank { normalizeNullableText(item.optString("course_id")) },
            courseName = normalizeNullableText(item.optString("course_name")),
            detailUrl = normalizeNullableText(item.optString("detail_url")),
            courseNo = normalizeNullableText(item.optString("course_no")),
            classNo = normalizeNullableText(item.optString("class_no")),
            teacherName = normalizeNullableText(item.optString("teacher_name")),
            statusGroup = normalizeNullableText(item.optString("status_group")),
            sourceEndpoint = normalizeNullableText(item.optString("source_endpoint")),
            submitUrl = normalizeNullableText(item.optString("submit_url")),
          )
        }
      }
      null
    }.getOrNull()
  }

  private fun parseSubmitForm(html: String): SubmitForm {
    val formMatch =
      Regex("""<form[^>]*id=["']form_sn1["'][\s\S]*?</form>""", RegexOption.IGNORE_CASE)
        .find(html)
    val formHtml = formMatch?.value ?: html
    val fields = linkedMapOf<String, String>()
    val inputRegex = Regex("""<(input|textarea)[^>]*>[\s\S]*?(?:</textarea>)?""", RegexOption.IGNORE_CASE)
    inputRegex.findAll(formHtml).forEach { match ->
      val tag = match.value
      val name = attrValue(tag, "name")
      if (name.isBlank()) return@forEach
      val value =
        if (tag.startsWith("<textarea", ignoreCase = true)) {
          tag.substringAfter('>', "").substringBeforeLast("</textarea>", "")
        } else {
          attrValue(tag, "value")
        }
      fields[name] = htmlUnescape(value)
    }
    val fileFieldName =
      Regex("""<input[^>]*type=["']file["'][^>]*>""", RegexOption.IGNORE_CASE)
        .find(formHtml)
        ?.value
        ?.let { attrValue(it, "name") }
        ?.takeIf { it.isNotBlank() }
        ?: "fileupload"
    return SubmitForm(fields = fields, fileFieldName = fileFieldName)
  }

  private fun attrValue(
    tag: String,
    name: String,
  ): String {
    val regex = Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    return regex.find(tag)?.groupValues?.getOrNull(1).orEmpty()
  }

  private fun htmlUnescape(value: String): String =
    value
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&mdash;", "-")
      .replace("&#39;", "'")

  private fun firstJsonString(
    obj: JSONObject,
    vararg keys: String,
  ): String {
    val lookup = mutableMapOf<String, String>()
    val iterator = obj.keys()
    while (iterator.hasNext()) {
      val key = iterator.next()
      lookup[key.lowercase()] = key
    }
    keys.forEach { candidate ->
      val actual = lookup[candidate.lowercase()] ?: return@forEach
      val value = normalizeNullableText(obj.opt(actual)?.toString().orEmpty())
      if (value.isNotBlank()) return value
    }
    return ""
  }

  private fun datatablePayload(
    wlkcid: String,
    start: Int = 0,
    length: Int = 1000,
  ): Map<String, String> {
    val aoData =
      JSONArray()
        .put(JSONObject().put("name", "sEcho").put("value", 1))
        .put(JSONObject().put("name", "iDisplayStart").put("value", start))
        .put(JSONObject().put("name", "iDisplayLength").put("value", length))
        .put(JSONObject().put("name", "iSortCol_0").put("value", 0))
        .put(JSONObject().put("name", "sSortDir_0").put("value", "desc"))
        .put(JSONObject().put("name", "wlkcid").put("value", wlkcid))
        .toString()
    val condition = JSONArray().put(JSONObject().put("name", "wlkcid").put("value", wlkcid)).toString()
    return mapOf(
      "sEcho" to "1",
      "iDisplayStart" to start.toString(),
      "iDisplayLength" to length.toString(),
      "iSortCol_0" to "0",
      "sSortDir_0" to "desc",
      "wlkcid" to wlkcid,
      "aoData" to aoData,
      "searchCondition" to condition,
      "defaultSearchCondition" to condition,
    )
  }

  private data class HomeworkAuth(
    val ok: Boolean,
    val message: String,
    val baseUrl: String = "https://learn.tsinghua.edu.cn",
    val cookie: String = "",
    val csrf: String = "",
  )

  private data class MultipartFilePart(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
  )

  private data class SubmitForm(
    val fields: Map<String, String>,
    val fileFieldName: String,
  )

  private fun resolveHomeworkAuth(action: SystemAction): HomeworkAuth {
    val cookieFromParams = readActionString(action, "session_cookie")
    val cookieFromPayload = readActionString(action, "cookies")
    val cachedCookie = cachedHomeworkAuth?.cookie.orEmpty()
    val cookie = normalizeCookieHeader(cookieFromParams.ifEmpty { cookieFromPayload }.ifEmpty { cachedCookie })
    val baseUrl =
      readActionString(action, "learn_base_url").ifEmpty {
        cachedHomeworkAuth?.baseUrl.orEmpty()
      }.ifEmpty {
        "https://learn.tsinghua.edu.cn"
      }
    val csrf =
      readActionString(action, "csrf_token").ifEmpty {
        extractCookieValue(cookie, "XSRF-TOKEN")
      }.ifEmpty {
        cachedHomeworkAuth?.csrf.orEmpty()
      }
    if (cookie.isEmpty()) {
      return HomeworkAuth(
        ok = false,
        message = "Missing homework cookie. Execute get_homework_cookie first or pass cookies/session_cookie.",
      )
    }
    return HomeworkAuth(ok = true, message = "ok", baseUrl = baseUrl, cookie = cookie, csrf = csrf)
  }

  private fun fetchHomeworkAuthByCredential(
    studentId: String,
    password: String,
    baseUrl: String,
  ): HomeworkAuth {
    val idBase = "https://id.tsinghua.edu.cn"
    val cookieJar = mutableMapOf<String, MutableMap<String, String>>()
    return runCatching {
      val loginPage = httpGet("$idBase/do/off/ui/auth/login", hostCookieHeader = "")
      captureSetCookies("id.tsinghua.edu.cn", loginPage.headers["Set-Cookie"], cookieJar)

      val loginCheckUrl = extractLoginCheckUrl(loginPage.body, idBase)
      val hiddenInputs = extractHiddenInputs(loginPage.body)
      val form = linkedMapOf<String, String>()
      hiddenInputs.forEach { (k, v) ->
        if (k !in setOf("username", "password", "_eventId")) {
          form[k] = v
        }
      }
      form["username"] = studentId
      form["password"] = password
      form["_eventId"] = "submit"

      val postLogin =
        httpPostForm(
          loginCheckUrl,
          form,
          hostCookieHeader = cookieHeaderForHost("id.tsinghua.edu.cn", cookieJar),
        )
      captureSetCookies("id.tsinghua.edu.cn", postLogin.headers["Set-Cookie"], cookieJar)

      var redirectUrl = postLogin.location
      var currentUrl = loginCheckUrl
      repeat(8) {
        if (redirectUrl.isBlank()) return@repeat
        val resolved = resolveRedirectUrl(currentUrl, redirectUrl)
        val host = URL(resolved).host
        val hop =
          httpGet(
            resolved,
            hostCookieHeader = cookieHeaderForHost(host, cookieJar),
          )
        captureSetCookies(host, hop.headers["Set-Cookie"], cookieJar)
        currentUrl = resolved
        redirectUrl = hop.location
      }

      val learnHost = URL(baseUrl).host
      val learnCookie = cookieHeaderForHost(learnHost, cookieJar)
      val fallbackIdCookie = cookieHeaderForHost("id.tsinghua.edu.cn", cookieJar)
      val resolvedCookie = normalizeCookieHeader(learnCookie.ifBlank { fallbackIdCookie })
      if (resolvedCookie.isBlank()) {
        HomeworkAuth(
          ok = false,
          message = "Login did not return cookies. Check student_id/password or upstream auth flow.",
        )
      } else {
        HomeworkAuth(
          ok = true,
          message = "ok",
          baseUrl = baseUrl,
          cookie = resolvedCookie,
          csrf = extractCookieValue(resolvedCookie, "XSRF-TOKEN"),
        )
      }
    }.getOrElse { throwable ->
      HomeworkAuth(
        ok = false,
        message = "Acquire cookie by credential failed: ${throwable.message ?: "unknown"}",
      )
    }
  }

  private data class SimpleHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val location: String,
  )

  private fun httpGet(
    url: String,
    hostCookieHeader: String,
  ): SimpleHttpResponse {
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "GET"
    conn.connectTimeout = 12_000
    conn.readTimeout = 18_000
    if (hostCookieHeader.isNotBlank()) {
      conn.setRequestProperty("Cookie", hostCookieHeader)
    }
    conn.setRequestProperty("User-Agent", "OpenTHU-Android/1.0")
    val code = conn.responseCode
    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    val headers = mutableMapOf<String, List<String>>()
    conn.headerFields.forEach { (rawKey, rawValue) ->
      val key = rawKey?.trim().orEmpty()
      if (key.isNotEmpty()) {
        headers[key] = rawValue ?: emptyList()
      }
    }
    val location = conn.getHeaderField("Location").orEmpty()
    conn.disconnect()
    return SimpleHttpResponse(code, body, headers, location)
  }

  private fun httpPostForm(
    url: String,
    form: Map<String, String>,
    hostCookieHeader: String,
  ): SimpleHttpResponse {
    val body =
      form.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
      }
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "POST"
    conn.connectTimeout = 12_000
    conn.readTimeout = 18_000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    conn.setRequestProperty("User-Agent", "OpenTHU-Android/1.0")
    if (hostCookieHeader.isNotBlank()) {
      conn.setRequestProperty("Cookie", hostCookieHeader)
    }
    conn.outputStream.use { os ->
      os.write(body.toByteArray(StandardCharsets.UTF_8))
    }
    val code = conn.responseCode
    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
    val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    val headers = mutableMapOf<String, List<String>>()
    conn.headerFields.forEach { (rawKey, rawValue) ->
      val key = rawKey?.trim().orEmpty()
      if (key.isNotEmpty()) {
        headers[key] = rawValue ?: emptyList()
      }
    }
    val location = conn.getHeaderField("Location").orEmpty()
    conn.disconnect()
    return SimpleHttpResponse(code, raw, headers, location)
  }

  private fun captureSetCookies(
    host: String,
    setCookieHeaders: List<String>?,
    cookieJar: MutableMap<String, MutableMap<String, String>>,
  ) {
    if (setCookieHeaders.isNullOrEmpty()) return
    val hostCookies = cookieJar.getOrPut(host) { linkedMapOf() }
    setCookieHeaders.forEach { header ->
      val firstPart = header.substringBefore(';').trim()
      val split = firstPart.indexOf('=')
      if (split <= 0) return@forEach
      val name = firstPart.substring(0, split).trim()
      val value = firstPart.substring(split + 1).trim()
      if (name.isNotBlank()) {
        hostCookies[name] = value
      }
    }
  }

  private fun cookieHeaderForHost(
    host: String,
    cookieJar: Map<String, Map<String, String>>,
  ): String =
    cookieJar[host]
      ?.entries
      ?.joinToString("; ") { (k, v) -> "$k=$v" }
      .orEmpty()

  private fun resolveRedirectUrl(
    baseUrl: String,
    location: String,
  ): String = URL(URL(baseUrl), location).toString()

  private fun extractLoginCheckUrl(
    html: String,
    idBase: String,
  ): String {
    val matched =
      Regex("""<form[^>]*action=["']([^"']*?/do/off/ui/auth/login/check[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    if (matched.isBlank()) {
      return "$idBase/do/off/ui/auth/login/check"
    }
    return if (matched.startsWith("http://") || matched.startsWith("https://")) {
      matched
    } else {
      resolveRedirectUrl(idBase, matched)
    }
  }

  private fun extractHiddenInputs(html: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val inputRegex = Regex("""<input[^>]*type=["']hidden["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
    val nameRegex = Regex("""name=["']([^"']+)["']""", setOf(RegexOption.IGNORE_CASE))
    val valueRegex = Regex("""value=["']([^"']*)["']""", setOf(RegexOption.IGNORE_CASE))
    inputRegex.findAll(html).forEach { match ->
      val tag = match.value
      val name = nameRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty().trim()
      val value = valueRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty()
      if (name.isNotBlank()) {
        result[name] = value
      }
    }
    return result
  }

  private fun readActionString(
    action: SystemAction,
    key: String,
  ): String {
    val fromParams = action.params[key]?.trim().orEmpty()
    if (fromParams.isNotBlank()) return fromParams
    return action.payload?.get(key)?.toString()?.trim().orEmpty()
  }

  private fun normalizeCookieHeader(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
      .split(';')
      .map { it.trim() }
      .filter { token -> token.contains('=') }
      .joinToString("; ")
  }

  private fun extractCookieValue(
    cookieHeader: String,
    cookieName: String,
  ): String {
    val prefix = "$cookieName="
    return cookieHeader.split(';')
      .map { it.trim() }
      .firstOrNull { it.startsWith(prefix) }
      ?.substringAfter('=')
      ?.trim()
      .orEmpty()
  }

  private fun cookieNames(cookieHeader: String): List<String> =
    cookieHeader.split(';')
      .map { it.trim().substringBefore('=').trim() }
      .filter { it.isNotBlank() }

  private fun postForm(
    url: String,
    form: Map<String, String>,
    cookie: String,
    csrfToken: String,
    referer: String = "https://learn.tsinghua.edu.cn/f/wlxt/kczy/zy/student/index",
  ): String {
    val body =
      form.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
      }
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "POST"
    conn.connectTimeout = 12_000
    conn.readTimeout = 18_000
    conn.doOutput = true
    conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    conn.setRequestProperty("Cache-Control", "no-cache")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    conn.setRequestProperty("Cookie", cookie)
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty("Origin", "https://learn.tsinghua.edu.cn")
    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    if (csrfToken.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", csrfToken)
    }
    conn.outputStream.use { os ->
      val bytes = body.toByteArray(StandardCharsets.UTF_8)
      os.write(bytes)
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = readResponseText(conn, stream)
    val location = conn.getHeaderField("Location").orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code for $url${if (location.isNotBlank()) " location=$location" else ""}: ${raw.take(512)}")
    }
    return raw
  }

  private fun getForm(
    url: String,
    cookie: String,
    csrfToken: String,
    referer: String,
    ajax: Boolean = false,
    htmlAccept: Boolean = false,
  ): String {
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "GET"
    conn.connectTimeout = 12_000
    conn.readTimeout = 24_000
    conn.setRequestProperty(
      "Accept",
      when {
        htmlAccept -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        ajax -> "application/json, text/javascript, */*; q=0.01"
        else -> "*/*"
      },
    )
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    conn.setRequestProperty("Cache-Control", "no-cache")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Cookie", cookie)
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
    )
    if (ajax) {
      conn.setRequestProperty("Origin", "https://learn.tsinghua.edu.cn")
      conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    }
    if (csrfToken.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", csrfToken)
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = readResponseText(conn, stream)
    val location = conn.getHeaderField("Location").orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code for $url${if (location.isNotBlank()) " location=$location" else ""}: ${raw.take(256)}")
    }
    return raw
  }

  private fun postMultipart(
    url: String,
    cookie: String,
    csrfToken: String,
    referer: String = "https://learn.tsinghua.edu.cn/f/wlxt/kczy/zy/student/index",
    textParts: Map<String, String>,
    filePart: MultipartFilePart?,
    fileFieldName: String = "fileupload",
  ): String {
    val boundary = "----OpenTHUBoundary${System.currentTimeMillis()}"
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "POST"
    conn.connectTimeout = 12_000
    conn.readTimeout = 24_000
    conn.doOutput = true
    conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    conn.setRequestProperty("Cache-Control", "no-cache")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    conn.setRequestProperty("Cookie", cookie)
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty("Origin", "https://learn.tsinghua.edu.cn")
    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
    )
    if (csrfToken.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", csrfToken)
    }

    DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
      textParts.forEach { (name, value) ->
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(StandardCharsets.UTF_8))
        out.writeBytes("\r\n")
      }
      if (filePart != null) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes(
          "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"${filePart.fileName}\"\r\n",
        )
        out.writeBytes("Content-Type: ${filePart.mimeType}\r\n\r\n")
        out.write(filePart.bytes)
        out.writeBytes("\r\n")
      }
      out.writeBytes("--$boundary--\r\n")
      out.flush()
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = readResponseText(conn, stream)
    val location = conn.getHeaderField("Location").orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code for $url${if (location.isNotBlank()) " location=$location" else ""}: ${raw.take(512)}")
    }
    return raw
  }

  private fun resolveFilePart(
    filePath: String,
    fileUriText: String,
    overrideFileName: String,
  ): MultipartFilePart {
    if (filePath.isNotBlank()) {
      val file = File(filePath)
      if (!file.exists() || !file.isFile) {
        throw IllegalArgumentException("File not found at file_path: $filePath")
      }
      val bytes = FileInputStream(file).use { input -> input.readBytes() }
      val resolvedName = overrideFileName.ifBlank { file.name.ifBlank { "upload.bin" } }
      return MultipartFilePart(
        fileName = resolvedName,
        mimeType = guessMimeType(resolvedName),
        bytes = bytes,
      )
    }
    if (fileUriText.isNotBlank()) {
      val uri = Uri.parse(fileUriText)
      val bytes =
        appContext.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
          ?: throw IllegalArgumentException("Cannot open file_uri: $fileUriText")
      val name = overrideFileName.ifBlank { guessFileNameFromUri(uri) }
      return MultipartFilePart(
        fileName = name,
        mimeType = guessMimeType(name),
        bytes = bytes,
      )
    }
    throw IllegalArgumentException("Either file_path or file_uri is required.")
  }

  private fun parseCsvOrJsonArray(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim() }.filter { it.isNotEmpty() }
      }.getOrElse { emptyList() }
    }
    return text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun filterHomeworkRecords(rows: List<HomeworkRecord>): List<HomeworkRecord> {
    val nowMs = System.currentTimeMillis()
    return rows.filter { row ->
      if (row.title.contains("\u8865\u4ea4")) {
        return@filter false
      }
      val deadlineMs = row.deadline.trim().toLongOrNull()
      if (deadlineMs != null && deadlineMs < nowMs) {
        return@filter false
      }
      true
    }
  }

  private fun extractHomeworkRecords(
    root: JSONObject?,
    course: HomeworkCourse,
    statusGroup: String,
    endpoint: String,
    baseUrl: String,
  ): List<HomeworkRecord> {
    if (root == null) return emptyList()
    val list = mutableListOf<HomeworkRecord>()
    val candidates = mutableListOf<JSONObject>()
    collectObjectsRecursively(root, candidates)
    candidates.forEach { obj ->
      val zyid = firstJsonString(obj, "zyid", "zyId", "id")
      val xszyid = firstJsonString(obj, "xszyid", "xszyId", "studentHomeworkId")
      val title = htmlUnescape(firstJsonString(obj, "bt", "title", "name", "zymc", "zybt"))
      if (zyid.isBlank() && xszyid.isBlank()) return@forEach
      if (title.isBlank()) return@forEach
      val deadline = firstJsonString(obj, "jzsj", "deadline", "jzsjStr")
      val submitted = statusGroup != "unsubmitted"
      val detailUrl =
        if (statusGroup == "unsubmitted" && zyid.isNotBlank() && xszyid.isNotBlank()) {
          "$baseUrl/f/wlxt/kczy/zy/student/viewZy?wlkcid=${course.wlkcid}&sfgq=0&zyid=$zyid&xszyid=$xszyid"
        } else if (statusGroup == "graded" && zyid.isNotBlank() && xszyid.isNotBlank()) {
          "$baseUrl/f/wlxt/kczy/zy/student/viewCj?wlkcid=${course.wlkcid}&zyid=$zyid&xszyid=$xszyid"
        } else if (statusGroup == "excellent" && zyid.isNotBlank() && xszyid.isNotBlank()) {
          "$baseUrl/f/wlxt/kczy/zy/student/viewYxzy?wlkcid=${course.wlkcid}&zyid=$zyid&xszyid=$xszyid"
        } else {
          ""
        }
      val submitUrl =
        if (statusGroup == "unsubmitted" && xszyid.isNotBlank()) {
          "$baseUrl/f/wlxt/kczy/zy/student/tijiao?wlkcid=${course.wlkcid}&xszyid=$xszyid"
        } else {
          ""
        }
      list +=
        HomeworkRecord(
          homeworkId = zyid,
          studentHomeworkId = xszyid,
          title = title,
          deadline = deadline,
          submitted = submitted,
          courseId = course.wlkcid,
          courseName = course.courseName.ifBlank { firstJsonString(obj, "kcmc", "courseName") },
          detailUrl = detailUrl,
          courseNo = course.courseNo,
          classNo = course.classNo,
          teacherName = course.teacherName,
          statusGroup = statusGroup,
          sourceEndpoint = endpoint,
          submitUrl = submitUrl,
        )
    }
    return list
  }

  private fun readResponseText(
    conn: HttpURLConnection,
    stream: java.io.InputStream?,
  ): String {
    if (stream == null) return ""
    val bytes = stream.use { it.readBytes() }
    if (bytes.isEmpty()) return ""
    val declaredCharset = extractCharsetFromContentType(conn.contentType)
    val primary = decodeBytes(bytes, declaredCharset ?: StandardCharsets.UTF_8.name())
    if (looksLikeMojibake(primary)) {
      val fallback = decodeBytes(bytes, "GB18030")
      if (!looksLikeMojibake(fallback)) return fallback
    }
    return primary
  }

  private fun extractCharsetFromContentType(contentType: String?): String? {
    if (contentType.isNullOrBlank()) return null
    val match = Regex("""charset\s*=\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(contentType)
    return match?.groupValues?.getOrNull(1)?.trim()
  }

  private fun decodeBytes(
    bytes: ByteArray,
    charsetName: String,
  ): String =
    runCatching {
      String(bytes, Charset.forName(charsetName))
    }.getOrElse {
      String(bytes, StandardCharsets.UTF_8)
    }

  private fun looksLikeMojibake(text: String): Boolean {
    if (text.isBlank()) return false
    return text.contains("\uFFFD") ||
      text.contains("\u00EF\u00BF\u00BD") ||
      text.contains("\u00E9\u008D\u0090") ||
      text.contains("\u00E7\u00BC\u0083") ||
      text.contains("\u00E6\u00B6\u0093") ||
      text.contains("\u00E7\u00BB\u0094")
  }
  private fun normalizeNullableText(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.equals("null", ignoreCase = true)) return ""
    return trimmed
  }

  private fun extractAttachments(
    root: JSONObject?,
    baseUrl: String,
  ): List<HomeworkAttachment> {
    if (root == null) return emptyList()
    val objects = mutableListOf<JSONObject>()
    collectObjectsRecursively(root, objects)
    val result = mutableListOf<HomeworkAttachment>()
    objects.forEach { obj ->
      val name = obj.optString("name").ifBlank { obj.optString("wjmc") }
      val token = obj.optString("id").ifBlank { obj.optString("wjid") }
      if (name.isBlank() && token.isBlank()) return@forEach
      val download = obj.optString("downloadUrl")
      val preview = obj.optString("previewUrl")
      result +=
        HomeworkAttachment(
          token = token.ifBlank { "att_${result.size + 1}" },
          fileName = name.ifBlank { "attachment_${result.size + 1}" },
          downloadUrl = absolutizeUrl(download, baseUrl),
          previewUrl = absolutizeUrl(preview, baseUrl),
        )
    }
    return result.distinctBy { "${it.token}::${it.fileName}" }
  }

  private fun collectObjectsRecursively(
    root: Any?,
    collector: MutableList<JSONObject>,
  ) {
    when (root) {
      is JSONObject -> {
        collector += root
        val keys = root.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          collectObjectsRecursively(root.opt(key), collector)
        }
      }
      is JSONArray -> {
        for (i in 0 until root.length()) {
          collectObjectsRecursively(root.opt(i), collector)
        }
      }
    }
  }

  private fun appendCsrf(
    url: String,
    csrfToken: String,
  ): String {
    if (csrfToken.isBlank()) return url
    if (url.contains("token=")) return url
    val sep = if (url.contains("?")) "&" else "?"
    return "$url${sep}token=${urlEncode(csrfToken)}"
  }

  private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

  private fun guessMimeType(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
      lower.endsWith(".pdf") -> "application/pdf"
      lower.endsWith(".doc") -> "application/msword"
      lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      lower.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
      lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      lower.endsWith(".txt") -> "text/plain"
      lower.endsWith(".zip") -> "application/zip"
      else -> "application/octet-stream"
    }
  }

  private fun guessFileNameFromUri(uri: Uri): String {
    val segment = uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
    return if (segment.isBlank()) "upload.bin" else segment
  }

  private fun HomeworkRecord.toDataMap(): Map<String, Any> =
    mapOf(
      "homework_id" to homeworkId,
      "zyid" to homeworkId,
      "student_homework_id" to studentHomeworkId,
      "xszyid" to studentHomeworkId,
      "title" to title,
      "homework_title" to title,
      "deadline" to deadline,
      "submitted" to submitted,
      "course_id" to courseId,
      "course_wlkcid" to courseId,
      "course_name" to courseName,
      "course_no" to courseNo,
      "class_no" to classNo,
      "teacher_name" to teacherName,
      "status_group" to statusGroup,
      "source_endpoint" to sourceEndpoint,
      "detail_url" to detailUrl,
      "submit_url" to submitUrl,
    )

  private fun HomeworkCourse.toDataMap(): Map<String, Any> =
    mapOf(
      "wlkcid" to wlkcid,
      "course_id" to wlkcid,
      "course_name" to courseName,
      "course_english_name" to courseEnglishName,
      "course_no" to courseNo,
      "class_no" to classNo,
      "teacher_name" to teacherName,
      "time_location" to timeLocation,
      "homework_total" to homeworkTotal,
      "homework_unsubmitted" to homeworkUnsubmitted,
      "course_url" to courseUrl,
      "homework_page_url" to homeworkPageUrl,
    )

  private fun HomeworkAttachment.toDataMap(): Map<String, Any> =
    mapOf(
      "attachment_token" to token,
      "file_name" to fileName,
      "download_url" to downloadUrl,
      "preview_url" to previewUrl,
    )

  private fun absolutizeUrl(url: String, baseUrl: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("/")) return baseUrl.trimEnd('/') + trimmed
    return "$baseUrl/$trimmed"
  }

  private fun extractIsoDateTime(text: String): List<String> =
    Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""")
      .findAll(text)
      .map { it.value }
      .toList()
}
