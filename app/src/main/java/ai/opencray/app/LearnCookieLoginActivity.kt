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
    private const val DEFAULT_LEARN_BASE_URL = "https://learn.tsinghua.edu.cn"
  }

  private lateinit var webView: WebView
  private lateinit var statusText: TextView
  private lateinit var cookieManager: CookieManager
  private var learnBaseUrl: String = DEFAULT_LEARN_BASE_URL

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
    loadLearnHome()
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
          statusText.text = getString(R.string.learn_login_status_page, compactUrl(url))
          captureCookieIfReady(url, finishOnSuccess = true)
        }
      }
  }

  private fun bindActions() {
    findViewById<Button>(R.id.learn_login_close_button).setOnClickListener {
      setResult(Activity.RESULT_CANCELED)
      finish()
    }
    findViewById<Button>(R.id.learn_login_save_button).setOnClickListener {
      if (!captureCookieIfReady(webView.url.orEmpty(), finishOnSuccess = true, manual = true)) {
        Toast.makeText(this, R.string.learn_login_cookie_not_ready, Toast.LENGTH_SHORT).show()
      }
    }
    findViewById<Button>(R.id.learn_login_clear_button).setOnClickListener {
      cookieManager.removeAllCookies {
        cookieManager.flush()
        Toast.makeText(this, R.string.learn_login_cookie_cleared, Toast.LENGTH_SHORT).show()
        loadLearnHome()
      }
    }
    findViewById<Button>(R.id.learn_login_reload_button).setOnClickListener {
      loadLearnHome()
    }
  }

  private fun loadLearnHome() {
    val target = "${learnBaseUrl.trimEnd('/')}/f/wlxt/index/course/student/index"
    statusText.text = getString(R.string.learn_login_status_loading)
    webView.loadUrl(target)
  }

  private fun captureCookieIfReady(
    currentUrl: String,
    finishOnSuccess: Boolean,
    manual: Boolean = false,
  ): Boolean {
    val learnHost = hostOf(learnBaseUrl)
    val currentHost = hostOf(currentUrl)
    val onLearnPage = learnHost.isNotBlank() && currentHost == learnHost && currentUrl.contains("/wlxt/")
    val learnCookie = cookieFor(learnBaseUrl)
    val currentCookie = cookieFor(currentUrl)
    val cookie =
      normalizeCookieHeader(
        if (learnCookie.isNotBlank()) {
          learnCookie
        } else if (onLearnPage) {
          currentCookie
        } else {
          ""
        },
      )
    if (cookie.isBlank()) {
      if (manual) {
        statusText.text = getString(R.string.learn_login_status_no_cookie)
      }
      return false
    }

    if (!manual && !onLearnPage) {
      return false
    }

    val csrf = extractCookieValue(cookie, "XSRF-TOKEN")
    getSharedPreferences("openthu_settings", MODE_PRIVATE)
      .edit()
      .putString("learn_base_url", learnBaseUrl)
      .putString("homework_cookie", cookie)
      .putString("homework_csrf", csrf)
      .apply()
    cookieManager.flush()

    statusText.text = getString(R.string.learn_login_status_saved)
    if (finishOnSuccess) {
      setResult(
        Activity.RESULT_OK,
        intent
          .putExtra(EXTRA_COOKIE, cookie)
          .putExtra(EXTRA_CSRF, csrf)
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

  private fun compactUrl(url: String): String =
    runCatching {
      val parsed = URL(url)
      "${parsed.host}${parsed.path}".take(72)
    }.getOrDefault(url.take(72))
}
