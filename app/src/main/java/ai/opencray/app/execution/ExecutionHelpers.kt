package ai.opencray.app.execution

import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val WEBVPN_SECONDARY_URL = "https://webvpn.tsinghua.edu.cn/"

fun extractCsrfFromCookie(cookieHeader: String): String {
  fun extractCookieValue(header: String, name: String): String =
    header.split(';')
      .map { it.trim() }
      .firstOrNull { token -> token.substringBefore('=', "").trim().equals(name, ignoreCase = true) }
      ?.substringAfter('=')
      ?.trim()
      .orEmpty()

  return listOf("XSRF-TOKEN", "XSRFToken", "XSRFtoken", "_csrf").mapNotNull { name ->
    val v = extractCookieValue(cookieHeader, name)
    if (v.isBlank()) null else v
  }.firstOrNull().orEmpty()
}

fun originFrom(baseUrl: String): String =
  runCatching {
    val url = URL(baseUrl)
    "${url.protocol}://${url.host}"
  }.getOrElse {
    "https://learn.tsinghua.edu.cn"
  }

fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
