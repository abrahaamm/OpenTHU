package ai.opencray.app.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject

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
  val detailUrl: String = "",
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

class HomeworkSkillExecutor(
  private val appContext: Context,
) {
  @Volatile private var cachedHomeworkAuth: HomeworkAuth? = null

  fun execute(action: SystemAction): ActionExecutionReport =
    when (action.id.substringBefore("#")) {
      "get_homework_cookie" -> executeGetHomeworkCookie(action)
      "crawl_course_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = false)
      "crawl_unsubmitted_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = true)
      "preview_homework_attachments" -> executePreviewHomeworkAttachments(action)
      "upload_homework_attachment" -> executeUploadHomeworkAttachment(action)
      "submit_homework" -> executeSubmitHomework(action)
      else ->
        ActionExecutionReport(
          success = false,
          message = "Unsupported homework action: ${action.id}.",
          recoverable = false,
          semantic = "unsupported_action",
        )
    }

  private fun executeGetHomeworkCookie(action: SystemAction): ActionExecutionReport {
    val providedCookies =
      firstNonBlank(
        readActionString(action, "cookies"),
        readActionString(action, "session_cookie"),
        readActionString(action, "homework_cookie"),
        readActionString(action, "learn_cookie"),
        readHomeworkSetting("homework_cookie"),
        readHomeworkSetting("learn_cookie"),
      )
    val baseUrl =
      firstNonBlank(
        readActionString(action, "learn_base_url"),
        readHomeworkSetting("learn_base_url"),
        "https://learn.tsinghua.edu.cn",
      )
    val providedCsrf =
      firstNonBlank(
        readActionString(action, "csrf_token"),
        readActionString(action, "homework_csrf"),
        readActionString(action, "learn_csrf"),
        readHomeworkSetting("homework_csrf"),
        readHomeworkSetting("learn_csrf"),
      )

    if (providedCookies.isNotBlank()) {
      val normalizedCookie = normalizeCookieHeader(providedCookies)
      if (normalizedCookie.isBlank()) {
        return homeworkFailure(
          message = "Invalid homework cookie input.",
          reason = "invalid_cookies",
          recoverable = false,
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
        message = "Homework cookie loaded and cached from provided Learn cookie.",
        recoverable = false,
        semantic = "homework_cookie_ready",
        metadata =
          mapOf(
            "status" to "cookie_ready",
            "cookie_source" to "provided_cookie",
            "has_csrf" to csrf.isNotBlank(),
            "cookie_names" to cookieNames(normalizedCookie),
            "learn_base_url" to baseUrl,
          ),
      )
    }

    val studentId = readActionString(action, "student_id")
    val password = readActionString(action, "password")
    if (studentId.isNotBlank() || password.isNotBlank()) {
      return homeworkFailure(
        message = "不再支持在 skill 内使用学号密码登录。请去设置页的「清华统一登录」完成登录后再重试。",
        reason = "login_required",
        recoverable = false,
        semantic = "homework_cookie_login_required",
        extra = mapOf("learn_base_url" to baseUrl),
      )
    }

    return ActionExecutionReport(
      success = false,
      message = "网络学堂登录态未配置。请去设置页的「清华统一登录」完成登录后再重试。",
      recoverable = false,
      semantic = "homework_cookie_login_required",
      metadata =
        mapOf(
          "status" to "login_required",
          "reason" to "login_required",
          "learn_base_url" to baseUrl,
        ),
    )
  }

  private fun executeCrawlCourseHomeworks(
    action: SystemAction,
    unsubmittedOnly: Boolean,
  ): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return homeworkFailure(
        auth.message,
        "login_required",
        recoverable = false,
        semantic = "homework_cookie_login_required",
      )
    }
    val requestedCourseIds = parseCsvOrJsonArray(action.params["course_ids"])
    val endpointTypes =
      if (unsubmittedOnly) {
        listOf("unsubmitted")
      } else {
        listOf("unsubmitted", "submitted_ungraded", "graded", "excellent")
      }
    val endpointMap =
      mapOf(
        "unsubmitted" to "/b/wlxt/kczy/zy/student/zyListWj",
        "submitted_ungraded" to "/b/wlxt/kczy/zy/student/zyListYjwg",
        "graded" to "/b/wlxt/kczy/zy/student/zyListYpg",
        "excellent" to "/b/wlxt/kczy/zy/student/yxzylist",
      )

    return runCatching {
      val records = mutableListOf<HomeworkRecord>()
      val courses =
        loadHomeworkCourses(auth, action, forceRefresh = false)
          .filter { course -> requestedCourseIds.isEmpty() || course.wlkcid in requestedCourseIds }
      courses.forEach { course ->
        getForm(
          url = course.homeworkPageUrl,
          auth = auth,
          referer = course.courseUrl,
          htmlAccept = true,
        )
        endpointTypes.forEach { endpointType ->
          val endpoint = endpointMap[endpointType].orEmpty()
          val raw =
            postForm(
              url = "${auth.baseUrl}$endpoint",
              form = datatablePayload(course.wlkcid),
              auth = auth,
              referer = course.homeworkPageUrl,
            )
          val parsed = runCatching { JSONObject(raw) }.getOrNull()
          records += extractHomeworkRecords(parsed, course, endpointType, endpoint, auth.baseUrl)
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
        semantic = "homework_crawled",
        metadata =
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
      homeworkFailure(
        message = "Homework crawl failed: ${throwable.message ?: "unknown"}",
        reason = "crawl_failed",
        recoverable = true,
        extra = mapOf("course_ids" to requestedCourseIds, "exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executePreviewHomeworkAttachments(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return homeworkFailure(
        auth.message,
        "login_required",
        recoverable = false,
        semantic = "homework_cookie_login_required",
      )
    }
    val homeworkId = readActionString(action, "homework_id")
    if (homeworkId.isBlank()) {
      return homeworkFailure("homework_id is required.", "missing_homework_id", recoverable = false)
    }

    return runCatching {
      val raw =
        postForm(
          url = "${auth.baseUrl}/b/wlxt/kczy/zy/student/detail",
          form = mapOf("id" to homeworkId),
          auth = auth,
        )
      val attachments = extractAttachments(runCatching { JSONObject(raw) }.getOrNull(), auth.baseUrl)
      val openUrl =
        readActionString(action, "homework_detail_url").ifBlank {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment preview opened. Found ${attachments.size} attachment(s).",
        recoverable = false,
        semantic = "homework_preview_ready",
        metadata =
          mapOf(
            "status" to "preview_ready",
            "homework_id" to homeworkId,
            "attachments" to attachments.map { it.toDataMap() },
            "opened_url" to openUrl,
          ),
      )
    }.getOrElse { throwable ->
      homeworkFailure(
        message = "Preview homework attachments failed: ${throwable.message ?: "unknown"}",
        reason = "preview_failed",
        recoverable = true,
        extra = mapOf("homework_id" to homeworkId, "exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executeUploadHomeworkAttachment(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return homeworkFailure(
        auth.message,
        "login_required",
        recoverable = false,
        semantic = "homework_cookie_login_required",
      )
    }
    val requestedId = readActionString(action, "homework_id")
    if (requestedId.isBlank()) {
      return homeworkFailure("homework_id is required.", "missing_homework_id", recoverable = false)
    }
    val cachedHomework = findCachedHomework(requestedId)
    val xszyid =
      firstNonBlank(
        readActionString(action, "xszyid"),
        readActionString(action, "student_homework_id"),
        cachedHomework?.studentHomeworkId.orEmpty(),
        requestedId,
      )
    val fileRef = readActionString(action, "file_path")
    val fileUri = readActionString(action, "file_uri")
    if (fileRef.isBlank() && fileUri.isBlank()) {
      val pickerIntent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      appContext.startActivity(pickerIntent)
      val openUrl =
        readActionString(action, "homework_detail_url").ifBlank {
          cachedHomework?.detailUrl.orEmpty().ifBlank {
            "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$requestedId"
          }
        }
      openWebPage(openUrl)
      return ActionExecutionReport(
        success = false,
        message = "No file provided. Opened file picker and homework page for manual selection.",
        recoverable = false,
        semantic = "homework_awaiting_file_selection",
        metadata =
          mapOf(
            "status" to "awaiting_file_selection",
            "reason" to "missing_file",
            "homework_id" to requestedId,
            "xszyid" to xszyid,
            "opened_url" to openUrl,
          ),
      )
    }

    return runCatching {
      val filePart = resolveFilePart(fileRef, fileUri, readActionString(action, "file_name"))
      val responseBody =
        postMultipart(
          url = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy",
          auth = auth,
          textParts =
            mapOf(
              "xszyid" to xszyid,
              "zynr" to readActionString(action, "submission_text"),
              "isDeleted" to "0",
            ),
          filePart = filePart,
        )
      val token = "upload_${System.currentTimeMillis()}"
      val openUrl =
        readActionString(action, "homework_detail_url").ifBlank {
          cachedHomework?.detailUrl.orEmpty().ifBlank {
            "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$requestedId"
          }
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment uploaded successfully.",
        recoverable = false,
        semantic = "homework_attachment_uploaded",
        metadata =
          mapOf(
            "status" to "uploaded",
            "homework_id" to requestedId,
            "xszyid" to xszyid,
            "attachment_token" to token,
            "file_name" to filePart.fileName,
            "opened_url" to openUrl,
            "upstream_response_excerpt" to responseBody.take(512),
          ),
      )
    }.getOrElse { throwable ->
      homeworkFailure(
        message = "Upload homework attachment failed: ${throwable.message ?: "unknown"}",
        reason = "upload_failed",
        recoverable = true,
        extra = mapOf("homework_id" to requestedId, "xszyid" to xszyid, "exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executeSubmitHomework(action: SystemAction): ActionExecutionReport {
    val confirmed = readActionString(action, "confirm_submit").toBooleanStrictOrNull() ?: false
    if (!confirmed) {
      return homeworkFailure(
        message = "Submit blocked: confirm_submit=true is required for high-risk homework submission.",
        reason = "confirm_submit_required",
        recoverable = false,
        semantic = "approval_required",
      )
    }
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return homeworkFailure(
        auth.message,
        "login_required",
        recoverable = false,
        semantic = "homework_cookie_login_required",
      )
    }
    val requestedId = readActionString(action, "homework_id")
    val requestedXszyid = readActionString(action, "xszyid")
    val cachedHomework = findCachedHomework(firstNonBlank(requestedId, requestedXszyid))
    val xszyid = firstNonBlank(requestedXszyid, cachedHomework?.studentHomeworkId.orEmpty(), requestedId)
    val zyid =
      firstNonBlank(
        readActionString(action, "zyid"),
        readActionString(action, "homework_zyid"),
        cachedHomework?.homeworkId.orEmpty(),
        requestedId,
      )
    val wlkcid =
      firstNonBlank(
        readActionString(action, "wlkcid"),
        readActionString(action, "course_id"),
        cachedHomework?.courseId.orEmpty(),
      )
    if (xszyid.isBlank()) {
      return homeworkFailure("submit_homework requires xszyid/homework_id.", "missing_homework_id", recoverable = false)
    }
    if (wlkcid.isBlank()) {
      return homeworkFailure(
        "submit_homework requires wlkcid/course_id. Run crawl_unsubmitted_homeworks first or pass course_id.",
        "missing_course_id",
        recoverable = false,
        extra = mapOf("xszyid" to xszyid),
      )
    }

    val submissionText = readActionString(action, "submission_text")
    var fileRef = readActionString(action, "file_path")
    var fileUri = readActionString(action, "file_uri")
    val localFilePaths = parseCsvOrJsonArray(action.params["local_file_paths"])
    if (fileRef.isBlank() && fileUri.isBlank() && localFilePaths.isNotEmpty()) {
      fileRef = localFilePaths.first()
    }
    val attachmentTokens = parseCsvOrJsonArray(action.params["attachment_tokens"])
    val hasFile = fileRef.isNotBlank() || fileUri.isNotBlank()
    if (submissionText.isBlank() && !hasFile && attachmentTokens.isEmpty()) {
      return homeworkFailure(
        "submit_homework requires submission_text or file_path/file_uri/local_file_paths or attachment_tokens.",
        "missing_submission_content",
        recoverable = false,
      )
    }

    return runCatching {
      val filePart = if (hasFile) resolveFilePart(fileRef, fileUri, readActionString(action, "file_name")) else null
      val submitPageUrl = "${auth.baseUrl}/f/wlxt/kczy/zy/student/tijiao?wlkcid=$wlkcid&xszyid=$xszyid"
      val submitPage = getForm(url = submitPageUrl, auth = auth, referer = submitPageUrl, htmlAccept = true)
      val form = parseSubmitForm(submitPage)
      val fields = form.fields.toMutableMap()
      fields["xszyid"] = xszyid
      fields["isDeleted"] = fields["isDeleted"].orEmpty().ifBlank { "0" }
      fields["zynr"] = submissionText
      val attachmentTokenText = attachmentTokens.joinToString(",")
      if (attachmentTokenText.isNotBlank()) {
        fields["fjids"] = attachmentTokenText
      }
      val responseBody =
        postMultipart(
          url = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy",
          auth = auth,
          referer = submitPageUrl,
          textParts = fields,
          filePart = filePart,
          fileFieldName = form.fileFieldName,
        )
      val openUrl =
        readActionString(action, "homework_detail_url").ifBlank {
          cachedHomework?.detailUrl.orEmpty().ifBlank { submitPageUrl }
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework submitted successfully.",
        recoverable = false,
        semantic = "homework_submitted",
        metadata =
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
      homeworkFailure(
        message = "Submit homework failed: ${throwable.message ?: "unknown"}",
        reason = "submit_failed",
        recoverable = true,
        extra =
          mapOf(
            "homework_id" to zyid,
            "xszyid" to xszyid,
            "wlkcid" to wlkcid,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
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
    val cookie =
      normalizeCookieHeader(
        firstNonBlank(
          readActionString(action, "session_cookie"),
          readActionString(action, "cookies"),
          readActionString(action, "homework_cookie"),
          readActionString(action, "learn_cookie"),
          cachedHomeworkAuth?.cookie.orEmpty(),
          readHomeworkSetting("homework_cookie"),
          readHomeworkSetting("learn_cookie"),
        ),
      )
    val baseUrl =
      firstNonBlank(
        readActionString(action, "learn_base_url"),
        cachedHomeworkAuth?.baseUrl.orEmpty(),
        readHomeworkSetting("learn_base_url"),
        "https://learn.tsinghua.edu.cn",
      )
    val csrf =
      firstNonBlank(
        readActionString(action, "csrf_token"),
        readActionString(action, "homework_csrf"),
        readActionString(action, "learn_csrf"),
        extractCookieValue(cookie, "XSRF-TOKEN"),
        cachedHomeworkAuth?.csrf.orEmpty(),
        readHomeworkSetting("homework_csrf"),
        readHomeworkSetting("learn_csrf"),
      )
    if (cookie.isEmpty()) {
      return HomeworkAuth(
        ok = false,
        message = "网络学堂登录态未配置。请去设置页的「清华统一登录」完成登录后再重试。",
      )
    }
    return HomeworkAuth(ok = true, message = "ok", baseUrl = baseUrl, cookie = cookie, csrf = csrf)
  }

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
        auth = auth,
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

  private fun homeworkCacheDir(): File =
    File(appContext.filesDir, "homework_skill").apply { mkdirs() }

  private fun coursesCacheFile(): File = File(homeworkCacheDir(), "courses.json")

  private fun homeworkCacheFile(): File = File(homeworkCacheDir(), "homeworks.json")

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

  private fun postForm(
    url: String,
    form: Map<String, String>,
    auth: HomeworkAuth,
    referer: String = "${auth.baseUrl}/f/wlxt/kczy/zy/student/index",
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
    conn.setRequestProperty("Cookie", auth.cookie)
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty("Origin", originFrom(auth.baseUrl))
    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )
    if (auth.csrf.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", auth.csrf)
    }
    conn.outputStream.use { os ->
      os.write(body.toByteArray(StandardCharsets.UTF_8))
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
    auth: HomeworkAuth,
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
    conn.setRequestProperty("Cookie", auth.cookie)
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
    )
    if (ajax) {
      conn.setRequestProperty("Origin", originFrom(auth.baseUrl))
      conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    }
    if (auth.csrf.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", auth.csrf)
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
    auth: HomeworkAuth,
    referer: String = "${auth.baseUrl}/f/wlxt/kczy/zy/student/index",
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
    conn.setRequestProperty("Cookie", auth.cookie)
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty("Origin", originFrom(auth.baseUrl))
    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    conn.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0",
    )
    if (auth.csrf.isNotBlank()) {
      conn.setRequestProperty("X-XSRF-TOKEN", auth.csrf)
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

  private fun filterHomeworkRecords(rows: List<HomeworkRecord>): List<HomeworkRecord> {
    val nowMs = System.currentTimeMillis()
    return rows.filter { row ->
      if (row.title.contains("补交")) {
        return@filter false
      }
      val deadlineMs = row.deadline.trim().toLongOrNull()
      if (deadlineMs != null && deadlineMs < nowMs) {
        return@filter false
      }
      true
    }
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

  private fun readActionString(
    action: SystemAction,
    key: String,
  ): String {
    val fromParams = action.params[key]?.trim().orEmpty()
    if (fromParams.isNotBlank()) return fromParams
    return action.payload?.get(key)?.toString()?.trim().orEmpty()
  }

  private fun readHomeworkSetting(key: String): String =
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
      .getString(key, "")
      .orEmpty()
      .trim()

  private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

  private fun homeworkFailure(
    message: String,
    reason: String,
    recoverable: Boolean,
    semantic: String = "homework_failed",
    extra: Map<String, Any?> = emptyMap(),
  ): ActionExecutionReport =
    ActionExecutionReport(
      success = false,
      message = message,
      recoverable = recoverable,
      semantic = semantic,
      metadata =
        mapOf(
          "status" to if (reason == "login_required") "login_required" else "failed",
          "reason" to reason,
        ) + extra,
    )

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

  private fun normalizeNullableText(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.equals("null", ignoreCase = true)) return ""
    return trimmed
  }

  private fun originFrom(baseUrl: String): String =
    runCatching {
      val url = URL(baseUrl)
      "${url.protocol}://${url.host}"
    }.getOrElse {
      "https://learn.tsinghua.edu.cn"
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

  private fun openWebPage(url: String) {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    appContext.startActivity(intent)
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
}
