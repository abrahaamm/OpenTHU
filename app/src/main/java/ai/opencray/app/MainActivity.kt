package ai.opencray.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import ai.opencray.app.ui.OpenCrayComposeApp

class MainActivity : AppCompatActivity() {
  companion object {
    private const val CALENDAR_PERMISSION_REQUEST = 1201
  }

  private lateinit var viewModel: MainViewModel
  private var pendingCalendarActionId: String? = null
  private var selectedChatFileUri: Uri? by mutableStateOf(null)
  private var selectedChatFileName: String by mutableStateOf("")

  private val chatFilePickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@registerForActivityResult
      runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      selectedChatFileUri = uri
      selectedChatFileName = resolveDisplayName(uri)
    }

  private val learnCookieLoginLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
      val data = result.data ?: return@registerForActivityResult
      viewModel.mergeLearnLoginResult(
        learnBaseUrl = data.getStringExtra(LearnCookieLoginActivity.EXTRA_LEARN_BASE_URL).orEmpty(),
        homeworkCookie = data.getStringExtra(LearnCookieLoginActivity.EXTRA_COOKIE).orEmpty(),
        homeworkCsrf = data.getStringExtra(LearnCookieLoginActivity.EXTRA_CSRF).orEmpty(),
        webvpnCookie = data.getStringExtra(LearnCookieLoginActivity.EXTRA_WEBVPN_COOKIE).orEmpty(),
      )
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    viewModel = ViewModelProvider(this)[MainViewModel::class.java]
    viewModel.setCalendarPermissionDelegate {
      ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        CALENDAR_PERMISSION_REQUEST,
      )
    }
    setContent {
      OpenCrayComposeApp(
        viewModel = viewModel,
        selectedFileName = selectedChatFileName,
        onAttachClick = { chatFilePickerLauncher.launch(arrayOf("*/*")) },
        onClearAttachment = { clearChatAttachment() },
        onSendMessage = { text ->
          viewModel.sendChatMessage(
            text = text,
            attachedFileUri = selectedChatFileUri?.toString(),
            attachedFileName = selectedChatFileName,
          )
          clearChatAttachment()
        },
        onLaunchLearnCookieLogin = { learnBaseUrl ->
          learnCookieLoginLauncher.launch(
            Intent(this, LearnCookieLoginActivity::class.java)
              .putExtra(LearnCookieLoginActivity.EXTRA_LEARN_BASE_URL, learnBaseUrl),
          )
        },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    viewModel.setAppInForeground(true)
  }

  override fun onPause() {
    viewModel.persistCurrentSettings()
    super.onPause()
  }

  override fun onStop() {
    viewModel.setAppInForeground(false)
    super.onStop()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != CALENDAR_PERMISSION_REQUEST) return
    if (hasCalendarPermissions()) {
      pendingCalendarActionId?.let { actionId -> viewModel.executeAction(actionId) }
      viewModel.notifyCalendarPermissionGranted()
    }
    pendingCalendarActionId = null
  }

  private fun hasCalendarPermissions(): Boolean {
    val readGranted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val writeGranted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    return readGranted && writeGranted
  }

  private fun clearChatAttachment() {
    selectedChatFileUri = null
    selectedChatFileName = ""
  }

  private fun resolveDisplayName(uri: Uri): String {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex >= 0 && cursor.moveToFirst()) {
        return cursor.getString(nameIndex).orEmpty().ifBlank { getString(R.string.chat_attachment_empty_file) }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: getString(R.string.chat_attachment_empty_file)
  }
}
