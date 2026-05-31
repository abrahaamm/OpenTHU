package ai.opencray.app

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LearnCookieLoginActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_LEARN_BASE_URL = "learn_base_url"
    const val EXTRA_COOKIE = "homework_cookie"
    const val EXTRA_CSRF = "homework_csrf"
    const val EXTRA_WEBVPN_COOKIE = "webvpn_cookie"
    private const val DEFAULT_LEARN_BASE_URL = "https://learn.tsinghua.edu.cn"
    private const val WEBVPN_ROOT_URL = "https://webvpn.tsinghua.edu.cn"
    private const val WEBVPN_OAUTH_LOGIN_URL = "https://webvpn.tsinghua.edu.cn/login?oauth_login=true"
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 OpenTHU/1.0"
  }

  private lateinit var webView: WebView
  private lateinit var statusText: TextView
  private lateinit var cookieManager: CookieManager
  private var learnBaseUrl: String = DEFAULT_LEARN_BASE_URL
  private var capturedLearnCookie: String = ""
  private var capturedLearnCsrf: String = ""
  private var capturedWebvpnCookie: String = ""
  private var attemptedDirectLearnAfterWebvpn: Boolean = false
  private var attemptedLearnSsoLogin: Boolean = false
  private var validatingLearnCookie: Boolean = false
  private var pendingLearnCookie: String = ""
  private var lastMainFrameHttpError: Int? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_learn_cookie_login)

    learnBaseUrl =
      intent.getStringExtra(EXTRA_LEARN_BASE_URL)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_LEARN_BASE_URL

    statusText = findViewById(R.id.learn_login_status_text)
    webView = findViewById(R.id.learn_login_webview)
    cookieManager = CookieManager.getInstance()

    setupCookieManager()
    setupWebView()
    bindActions()
    startFreshUnifiedLogin()
  }

  override fun onDestroy() {
    webView.stopLoading()
    webView.destroy()
    super.onDestroy()
  }

  private fun setupCookieManager() {
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
  }

  private fun setupWebView() {
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      cacheMode = WebSettings.LOAD_DEFAULT
      userAgentString = USER_AGENT
    }
    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean = false

        override fun onPageStarted(
          view: WebView,
          url: String,
          favicon: android.graphics.Bitmap?,
        ) {
          lastMainFrameHttpError = null
          super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedHttpError(
          view: WebView,
          request: WebResourceRequest,
          errorResponse: WebResourceResponse,
        ) {
          if (request.isForMainFrame) {
            lastMainFrameHttpError = errorResponse.statusCode
            statusText.text = getString(R.string.learn_login_status_http_error, errorResponse.statusCode)
          }
          super.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onPageFinished(
          view: WebView,
          url: String,
        ) {
          super.onPageFinished(view, url)
          captureSessionIfReady(url)
          maybeContinueLearnSso(url)
          if (
            capturedWebvpnCookie.isNotBlank() &&
            capturedLearnCookie.isBlank() &&
            !attemptedDirectLearnAfterWebvpn &&
            isLikelyCompletedWebvpnLogin(url)
          ) {
            attemptedDirectLearnAfterWebvpn = true
            loadDirectLearnHome()
            return
          }
          if (capturedLearnCookie.isNotBlank()) {
            saveCapturedSession(finishOnSuccess = true)
            return
          }
          updateStatus(url)
        }
      }
  }

  private fun bindActions() {
    findViewById<Button>(R.id.learn_login_close_button).setOnClickListener {
      setResult(Activity.RESULT_CANCELED)
      finish()
    }
    findViewById<Button>(R.id.learn_login_save_button).setOnClickListener {
      captureSessionIfReady(webView.url.orEmpty())
      if (!saveCapturedSession(finishOnSuccess = true)) {
        statusText.text = getString(R.string.learn_login_status_no_cookie)
        Toast.makeText(this, R.string.learn_login_cookie_not_ready, Toast.LENGTH_SHORT).show()
      }
    }
    findViewById<Button>(R.id.learn_login_clear_button).setOnClickListener {
      Toast.makeText(this, R.string.learn_login_cookie_cleared, Toast.LENGTH_SHORT).show()
      startFreshUnifiedLogin()
    }
    findViewById<Button>(R.id.learn_login_reload_button).setOnClickListener {
      startFreshUnifiedLogin()
    }
    findViewById<Button>(R.id.learn_login_open_learn_button).setOnClickListener {
      attemptedDirectLearnAfterWebvpn = true
      loadDirectLearnHome()
    }
  }

  private fun loadUnifiedLogin() {
    statusText.text = getString(R.string.learn_login_status_loading)
    webView.loadUrl(WEBVPN_OAUTH_LOGIN_URL)
  }

  private fun loadDirectLearnHome() {
    val target = learnBaseUrl.trimEnd('/').ifBlank { DEFAULT_LEARN_BASE_URL }
    statusText.text = getString(R.string.learn_login_status_loading)
    webView.loadUrl(target)
  }

  private fun captureSessionIfReady(currentUrl: String): Boolean {
    val learnHost = hostOf(learnBaseUrl)
    val currentHost = hostOf(currentUrl)
    val currentCookie = cookieFor(currentUrl)
    if (isPotentialLearnSessionPage(currentUrl)) {
      val nextLearnCookie =
        normalizeCookieHeader(
          firstNonBlank(
            if (currentHost == learnHost) currentCookie else "",
            cookieFor(learnBaseUrl),
          ),
      )
      if (hasUsableLearnCookie(nextLearnCookie)) {
        validateAndCaptureLearnCookie(nextLearnCookie)
      } else if (nextLearnCookie.isNotBlank()) {
        statusText.text = getString(R.string.learn_login_status_missing_csrf)
      }
    }

    val webvpnCookie =
      normalizeCookieHeader(
        firstNonBlank(
          cookieFor(WEBVPN_ROOT_URL),
          if (currentHost == hostOf(WEBVPN_ROOT_URL)) currentCookie else "",
        ),
      )
    if (webvpnCookie.isNotBlank()) {
      capturedWebvpnCookie = webvpnCookie
    }

    return capturedLearnCookie.isNotBlank() || capturedWebvpnCookie.isNotBlank()
  }

  private fun resetCapturedSession() {
    capturedLearnCookie = ""
    capturedLearnCsrf = ""
    capturedWebvpnCookie = ""
    attemptedDirectLearnAfterWebvpn = false
    attemptedLearnSsoLogin = false
    validatingLearnCookie = false
    pendingLearnCookie = ""
    lastMainFrameHttpError = null
  }

  private fun startFreshUnifiedLogin() {
    statusText.text = getString(R.string.learn_login_status_loading)
    resetCapturedSession()
    webView.stopLoading()
    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
    WebStorage.getInstance().deleteAllData()
    cookieManager.removeAllCookies {
      cookieManager.flush()
      runOnUiThread { loadUnifiedLogin() }
    }
  }

  private fun saveCapturedSession(finishOnSuccess: Boolean): Boolean {
    if (capturedLearnCookie.isBlank() && capturedWebvpnCookie.isBlank()) {
      return false
    }

    val editor =
      getSharedPreferences("openthu_settings", MODE_PRIVATE)
        .edit()
        .putString("learn_base_url", learnBaseUrl)
    if (capturedLearnCookie.isNotBlank()) {
      editor
        .putString("homework_cookie", capturedLearnCookie)
        .putString("homework_csrf", capturedLearnCsrf)
    }
    if (capturedWebvpnCookie.isNotBlank()) {
      editor.putString("webvpn_cookie", capturedWebvpnCookie)
    }
    editor.apply()
    cookieManager.flush()
    statusText.text = getString(R.string.learn_login_status_saved)
    if (finishOnSuccess) {
      setResult(
        Activity.RESULT_OK,
        intent
          .putExtra(EXTRA_COOKIE, capturedLearnCookie)
          .putExtra(EXTRA_CSRF, capturedLearnCsrf)
          .putExtra(EXTRA_WEBVPN_COOKIE, capturedWebvpnCookie)
          .putExtra(EXTRA_LEARN_BASE_URL, learnBaseUrl),
      )
      finish()
    }
    return true
  }

  private fun cookieFor(url: String): String =
    if (url.isBlank()) {
      ""
    } else {
      cookieManager.getCookie(url).orEmpty()
    }

  private fun hostOf(url: String): String =
    runCatching { URL(url).host }.getOrDefault("")

  private fun normalizeCookieHeader(raw: String): String =
    raw
      .split(';')
      .map { it.trim() }
      .filter { it.contains('=') }
      .joinToString("; ")

  private fun extractCookieValue(
    cookieHeader: String,
    cookieName: String,
  ): String {
    return cookieHeader.split(';')
      .map { it.trim() }
      .firstOrNull { token ->
        token.substringBefore('=', "").trim().equals(cookieName, ignoreCase = true)
      }
      ?.substringAfter('=')
      ?.trim()
      .orEmpty()
  }

  private fun extractLearnCsrf(cookieHeader: String): String =
    firstNonBlank(
      extractCookieValue(cookieHeader, "XSRF-TOKEN"),
      extractCookieValue(cookieHeader, "XSRFToken"),
      extractCookieValue(cookieHeader, "XSRFtoken"),
      extractCookieValue(cookieHeader, "_csrf"),
    )

  private fun updateStatus(url: String) {
    if (validatingLearnCookie) {
      statusText.text = getString(R.string.learn_login_status_validating)
      return
    }
    lastMainFrameHttpError?.let { statusCode ->
      if (capturedLearnCookie.isBlank()) {
        statusText.text = getString(R.string.learn_login_status_http_error, statusCode)
        return
      }
    }
    val capturedLabels =
      listOfNotNull(
        "WebVPN".takeIf { capturedWebvpnCookie.isNotBlank() },
        "Learn".takeIf { capturedLearnCookie.isNotBlank() },
      )
    statusText.text =
      if (capturedLabels.isEmpty()) {
        getString(R.string.learn_login_status_page, compactUrl(url))
      } else {
        getString(R.string.learn_login_status_captured, capturedLabels.joinToString(" / "))
      }
  }

  private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

  private fun isLikelyCompletedWebvpnLogin(url: String): Boolean {
    if (hostOf(url) != hostOf(WEBVPN_ROOT_URL)) return false
    val statusCode = lastMainFrameHttpError
    return !url.contains("/login", ignoreCase = true) &&
      !url.contains("oauth_login", ignoreCase = true) &&
      !url.contains("error", ignoreCase = true) &&
      (statusCode == null || statusCode in 200..399)
  }

  private fun isPotentialLearnSessionPage(url: String): Boolean {
    if (hostOf(url) != hostOf(learnBaseUrl)) return false
    val statusCode = lastMainFrameHttpError
    if (statusCode != null && statusCode !in 200..399) return false
    val lowerUrl = url.lowercase()
    if (
      lowerUrl.contains("login") ||
      lowerUrl.contains("authserver") ||
      lowerUrl.contains("oauth") ||
      lowerUrl.contains("error")
    ) {
      return false
    }
    return lowerUrl.contains("/f/wlxt/") ||
      lowerUrl.contains("/b/wlxt/") ||
      lowerUrl.contains("/f/redirectbystuorteacher")
  }

  private fun hasUsableLearnCookie(cookieHeader: String): Boolean =
    extractCookieValue(cookieHeader, "JSESSIONID").isNotBlank() &&
      extractLearnCsrf(cookieHeader).isNotBlank()

  private fun maybeContinueLearnSso(url: String) {
    if (hostOf(url) != hostOf(learnBaseUrl)) return
    val statusCode = lastMainFrameHttpError
    if (statusCode != null && statusCode !in 200..399) return
    val lowerUrl = url.lowercase()
    if (
      attemptedLearnSsoLogin ||
      capturedLearnCookie.isNotBlank() ||
      !(
        lowerUrl.endsWith("/f/login") ||
          lowerUrl.contains("/f/login?") ||
          lowerUrl.endsWith("/f/redirectbystuorteacher")
      )
    ) {
      return
    }
    attemptedLearnSsoLogin = true
    statusText.text = getString(R.string.learn_login_status_continue_learn)
    webView.evaluateJavascript(
      """
      (function() {
        var loginButton = document.getElementById('loginButtonId');
        if (loginButton) {
          loginButton.click();
          return true;
        }
        var candidates = Array.prototype.slice.call(document.querySelectorAll('a,button,input[type=button],input[type=submit]'));
        var target = candidates.find(function(el) {
          var text = (el.innerText || el.value || el.textContent || '').trim();
          return text.indexOf('登录') >= 0 || text.indexOf('网络学堂') >= 0;
        });
        if (target) {
          target.click();
          return true;
        }
        return false;
      })();
      """.trimIndent(),
      null,
    )
  }

  private fun validateAndCaptureLearnCookie(cookieHeader: String) {
    if (
      cookieHeader == capturedLearnCookie ||
      cookieHeader == pendingLearnCookie ||
      validatingLearnCookie
    ) {
      return
    }
    pendingLearnCookie = cookieHeader
    validatingLearnCookie = true
    statusText.text = getString(R.string.learn_login_status_validating)
    thread(name = "learn-cookie-validator") {
      val validation = validateLearnCookie(cookieHeader)
      runOnUiThread {
        validatingLearnCookie = false
        if (validation) {
          capturedLearnCookie = cookieHeader
          capturedLearnCsrf = extractLearnCsrf(cookieHeader)
          saveCapturedSession(finishOnSuccess = true)
        } else {
          if (pendingLearnCookie == cookieHeader) pendingLearnCookie = ""
          capturedLearnCookie = ""
          capturedLearnCsrf = ""
          statusText.text = getString(R.string.learn_login_status_unverified_cookie)
        }
      }
    }
  }

  private fun validateLearnCookie(cookieHeader: String): Boolean {
    val base = learnBaseUrl.trimEnd('/').ifBlank { DEFAULT_LEARN_BASE_URL }
    val csrf = extractLearnCsrf(cookieHeader)
    val validationUrl = "$base/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq"
    return runCatching {
      val connection = URL(validationUrl).openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 5000
      connection.readTimeout = 5000
      connection.requestMethod = "GET"
      connection.setRequestProperty("User-Agent", USER_AGENT)
      connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
      connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
      connection.setRequestProperty("Referer", "$base/f/wlxt/index/course/student/")
      connection.setRequestProperty("Cookie", cookieHeader)
      if (csrf.isNotBlank()) {
        connection.setRequestProperty("X-XSRF-TOKEN", csrf)
        connection.setRequestProperty("X-CSRF-TOKEN", csrf)
        connection.setRequestProperty("X-XSRFToken", csrf)
      }
      val statusCode = connection.responseCode
      val body =
        (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
          ?.bufferedReader(Charsets.UTF_8)
          ?.use { it.readText().take(4096) }
          .orEmpty()
      connection.disconnect()
      isValidLearnValidationResponse(statusCode, body)
    }.getOrDefault(false)
  }

  private fun isValidLearnValidationResponse(
    statusCode: Int,
    body: String,
  ): Boolean {
    if (statusCode !in 200..299) return false
    val trimmed = body.trim()
    if (trimmed.isBlank()) return false
    val lower = trimmed.lowercase()
    if (
      lower.contains("<html") ||
      lower.contains("登录页") ||
      lower.contains("未登录") ||
      lower.contains("登录失效") ||
      lower.contains("authserver") ||
      lower.contains("j_spring_security")
    ) {
      return false
    }
    return trimmed.startsWith("[") || trimmed.startsWith("{")
  }

  private fun compactUrl(url: String): String =
    runCatching {
      val parsed = URL(url)
      "${parsed.host}${parsed.path}".take(72)
    }.getOrDefault(url.take(72))
}
