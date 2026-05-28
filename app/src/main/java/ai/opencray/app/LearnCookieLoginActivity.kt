package ai.opencray.app

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

class LearnCookieLoginActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_LEARN_BASE_URL = "learn_base_url"
    const val EXTRA_COOKIE = "homework_cookie"
    const val EXTRA_CSRF = "homework_csrf"
    const val EXTRA_WEBVPN_COOKIE = "webvpn_cookie"
    private const val DEFAULT_LEARN_BASE_URL = "https://learn.tsinghua.edu.cn"
    private const val WEBVPN_ROOT_URL = "https://webvpn.tsinghua.edu.cn"
    private const val WEBVPN_OAUTH_LOGIN_URL = "https://webvpn.tsinghua.edu.cn/login?oauth_login=true"
  }

  private lateinit var webView: WebView
  private lateinit var statusText: TextView
  private lateinit var cookieManager: CookieManager
  private var learnBaseUrl: String = DEFAULT_LEARN_BASE_URL
  private var capturedLearnCookie: String = ""
  private var capturedLearnCsrf: String = ""
  private var capturedWebvpnCookie: String = ""
  private var attemptedDirectLearnAfterWebvpn: Boolean = false

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
    loadUnifiedLogin()
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
      userAgentString =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 OpenTHU/1.0"
    }
    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean = false

        override fun onPageFinished(
          view: WebView,
          url: String,
        ) {
          super.onPageFinished(view, url)
          captureSessionIfReady(url)
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
      cookieManager.removeAllCookies {
        cookieManager.flush()
        capturedLearnCookie = ""
        capturedLearnCsrf = ""
        capturedWebvpnCookie = ""
        attemptedDirectLearnAfterWebvpn = false
        Toast.makeText(this, R.string.learn_login_cookie_cleared, Toast.LENGTH_SHORT).show()
        loadUnifiedLogin()
      }
    }
    findViewById<Button>(R.id.learn_login_reload_button).setOnClickListener {
      attemptedDirectLearnAfterWebvpn = false
      loadUnifiedLogin()
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
    val target = "${learnBaseUrl.trimEnd('/')}/f/wlxt/index/course/student/index"
    statusText.text = getString(R.string.learn_login_status_loading)
    webView.loadUrl(target)
  }

  private fun captureSessionIfReady(currentUrl: String): Boolean {
    val learnHost = hostOf(learnBaseUrl)
    val currentHost = hostOf(currentUrl)
    val onLearnPage = learnHost.isNotBlank() && currentHost == learnHost && currentUrl.contains("/wlxt/")
    val learnCookie = cookieFor(learnBaseUrl)
    val currentCookie = cookieFor(currentUrl)
    val nextLearnCookie =
      normalizeCookieHeader(
        if (learnCookie.isNotBlank()) {
          learnCookie
        } else if (onLearnPage) {
          currentCookie
        } else {
          ""
        },
      )
    if (nextLearnCookie.isNotBlank()) {
      capturedLearnCookie = nextLearnCookie
      capturedLearnCsrf = extractCookieValue(nextLearnCookie, "XSRF-TOKEN")
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
    val prefix = "$cookieName="
    return cookieHeader.split(';')
      .map { it.trim() }
      .firstOrNull { it.startsWith(prefix) }
      ?.substringAfter('=')
      ?.trim()
      .orEmpty()
  }

  private fun updateStatus(url: String) {
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
    return !url.contains("/login", ignoreCase = true) &&
      !url.contains("oauth_login", ignoreCase = true)
  }

  private fun compactUrl(url: String): String =
    runCatching {
      val parsed = URL(url)
      "${parsed.host}${parsed.path}".take(72)
    }.getOrDefault(url.take(72))
}
