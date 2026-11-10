package com.byd.easyshare

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.byd.easyshare.ui.main.MainScreen
import com.byd.easyshare.ui.main.MainViewModel
import com.byd.easyshare.ui.main.HomeReceiveRecovery
import com.byd.easyshare.ui.main.homeReceiveRecovery
import com.byd.easyshare.ui.theme.EasyShareTheme
import com.byd.easyshare.utils.missingTransferPermissions
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var enableReceiverAfterPermission = false
    private var recoverReceiverAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val shouldEnable = enableReceiverAfterPermission
        val shouldRecover = recoverReceiverAfterPermission
        enableReceiverAfterPermission = false
        recoverReceiverAfterPermission = false
        val missingPermissions = missingTransferPermissions(includeNotifications = true)
        viewModel.refreshReceiveState()
        if (missingPermissions.isEmpty()) {
            if (shouldEnable) viewModel.setReceiverEnabled(true)
            MyApplication.getInstance().reconcileReceiver()
        } else if (shouldRecover && missingPermissions.any { !shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        } else if (shouldEnable) {
            Toast.makeText(
                this,
                getString(R.string.permission_not_granted),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableReceiverAfterPermission = savedInstanceState?.getBoolean(PENDING_RECEIVER_PERMISSION) ?: false
        recoverReceiverAfterPermission = savedInstanceState?.getBoolean(PENDING_RECEIVER_RECOVERY) ?: false
        enableEdgeToEdge()

        setContent {
            EasyShareTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val chooseReceivePath = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantFlags)
                    } catch (error: SecurityException) {
                        Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    state.receivePath
                        ?.let(android.net.Uri::parse)
                        ?.takeIf { it != uri }
                        ?.let { oldUri ->
                            runCatching {
                                contentResolver.releasePersistableUriPermission(oldUri, grantFlags)
                            }
                        }
                    viewModel.setReceivePath(uri)
                }

                // 应用内发送：优先调起系统文档选择器（SAF，支持多选）；
                // 部分车机固件没有 DocumentsUI，则回退到通用「打开文件」选择器。
                val chooseFilesFallback = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris ->
                    if (uris.isNotEmpty()) launchShareFiles(uris)
                }
                val chooseFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) launchShareFiles(uris)
                }

                MainScreen(
                    state = state,
                    onReceiverChanged = ::setReceiverEnabled,
                    onRecoverReceive = ::recoverReceive,
                    onDeviceNameChanged = viewModel::setDeviceName,
                    onBrandChanged = viewModel::setBrand,
                    onChooseReceivePath = { chooseReceivePath.launch(null) },
                    onSecureReceiveOnlyChanged = viewModel::setSecureReceiveOnly,
                    onEnhancedModeChanged = viewModel::setEnhancedMode,
                    onCaptureLogs = ::captureAndShareLogs,
                    onChooseFiles = {
                        runCatching { chooseFiles.launch(arrayOf("*/*")) }
                            .onFailure { runCatching { chooseFilesFallback.launch("*/*") } }
                    },
                    onCustomMacChanged = viewModel::setCustomP2pMac,
                    onSkipMacValidationChanged = viewModel::setSkipMacValidation,
                    onCaptureMac = { viewModel.captureP2pMac(showResult = true) },
                    onClearSavedMac = viewModel::clearSavedP2pMac,
                )
            }
        }
        if (savedInstanceState == null) {
            val permissions = missingTransferPermissions(includeNotifications = true)
            if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PENDING_RECEIVER_PERMISSION, enableReceiverAfterPermission)
        outState.putBoolean(PENDING_RECEIVER_RECOVERY, recoverReceiverAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReceiveState()
        // 连上 ADB 后再打开应用，也会自动读取并保存真实 MAC。
        viewModel.onForeground()
    }

    /**
     * 把文件选择器返回的 Uri 列表按 ACTION_SEND_MULTIPLE 的口径交给 ShareActivity，
     * 复用系统分享那条已经验证过的发送链路（蓝牙发现 → 选设备 → Wi-Fi Direct 传输）。
     */
    private fun launchShareFiles(uris: List<Uri>) {
        val shareIntent = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // ClipData 保证多个 Uri 的读权限一并传递给目标 Activity 与发送服务。
            val clip = ClipData.newRawUri("selected files", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
        }
        uris.forEach { uri ->
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            startActivity(shareIntent)
        } catch (error: Throwable) {
            Toast.makeText(this, "无法打开发送页面", Toast.LENGTH_LONG).show()
        }
    }

    private fun recoverReceive() {
        viewModel.refreshReceiveState()
        when (homeReceiveRecovery(viewModel.state.value)) {
            HomeReceiveRecovery.PERMISSIONS -> {
                recoverReceiverAfterPermission = true
                permissionLauncher.launch(missingTransferPermissions(includeNotifications = true).toTypedArray())
            }
            HomeReceiveRecovery.WIFI -> startActivity(Intent(Settings.Panel.ACTION_WIFI))
            HomeReceiveRecovery.BLUETOOTH -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            HomeReceiveRecovery.RETRY -> MyApplication.getInstance().reconcileReceiver()
            HomeReceiveRecovery.NONE -> Unit
        }
    }

    private fun setReceiverEnabled(enabled: Boolean) {
        if (!enabled) {
            enableReceiverAfterPermission = false
            viewModel.setReceiverEnabled(false)
            return
        }
        val permissions = missingTransferPermissions(includeNotifications = true)
        if (permissions.isEmpty()) {
            viewModel.setReceiverEnabled(true)
        } else {
            enableReceiverAfterPermission = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun captureAndShareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logDir = File(cacheDir, "logs").apply { mkdirs() }
                val logFile = File(logDir, "logcat.txt")
                logFile.outputStream().use { output ->
                    val process = Runtime.getRuntime().exec("logcat -d")
                    try {
                        process.inputStream.copyTo(output)
                    } finally {
                        process.destroy()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${BuildConfig.APPLICATION_ID}.fileProvider",
                    logFile,
                )
                val shareIntent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setType("text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.capture_logs)))
                }
            } catch (exception: Exception) {
                Log.e("LogcatCapture", "Failed to save logs", exception)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.log_capture_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val PENDING_RECEIVER_PERMISSION = "pendingReceiverPermission"
        private const val PENDING_RECEIVER_RECOVERY = "pendingReceiverRecovery"
    }
}
