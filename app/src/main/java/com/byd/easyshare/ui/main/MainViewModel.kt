package com.byd.easyshare.ui.main

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.byd.easyshare.AppSettings
import com.byd.easyshare.MyApplication
import com.byd.easyshare.R
import com.byd.easyshare.models.ReceivedHistoryItem
import com.byd.easyshare.utils.DeviceUtils
import com.byd.easyshare.utils.IncomingTransferUiCoordinator
import com.byd.easyshare.utils.LiveUpdateCoordinator
import com.byd.easyshare.utils.P2pMacManager
import com.byd.easyshare.utils.ReceivedHistoryStore
import com.byd.easyshare.utils.TransferUiCoordinator
import com.byd.easyshare.utils.ServiceState
import com.byd.easyshare.utils.ShizukuUtils
import com.byd.easyshare.utils.TAG
import com.byd.easyshare.utils.getReceiverFlags
import com.byd.easyshare.utils.missingTransferPermissions
import com.byd.easyshare.utils.registerInternalBroadcastReceiver
import rikka.shizuku.Shizuku

data class MainUiState(
    val receiverEnabled: Boolean = false,
    val receiverRunning: Boolean = false,
    val wifiEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val receivePermissionsGranted: Boolean = false,
    val busy: Boolean = false,
    val transferStatus: HomeTransferStatus? = null,
    val deviceName: String = "Android",
    val configuredBrandId: Int = -1,
    val effectiveBrandId: Int = 0,
    val receivePath: String? = null,
    val secureReceiveOnly: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    /** 手填的 P2P MAC（已持久化）。 */
    val customP2pMac: String = "",
    /** ADB / 网卡自动捕获并持久化的 P2P MAC，关闭 ADB 后依然有效。 */
    val savedP2pMac: String = "",
    val savedP2pMacSource: String = "",
    val savedP2pMacTime: Long = 0L,
    val skipMacValidation: Boolean = true,
    val capturingMac: Boolean = false,
    /** 首页「最近接收」列表，最新在前。 */
    val receivedHistory: List<ReceivedHistoryItem> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application
        get() = getApplication()
    private val settings = AppSettings(context)
    private var enableEnhancedModeAfterPermission = false
    private var capturingMac = false

    private val _state = MutableStateFlow(
        MainUiState(
            receiverEnabled = settings.backgroundReceiveEnabled,
            receiverRunning = MyApplication.getInstance().receiverRunning,
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
            receivePermissionsGranted = context.missingTransferPermissions(includeNotifications = true).isEmpty(),
            busy = MyApplication.getInstance().getBusy(),
            deviceName = settings.deviceName,
            configuredBrandId = settings.brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
            receivePath = settings.downloadUri,
            secureReceiveOnly = settings.secureReceiveOnly,
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_UPDATE_RECEIVER_STATE -> {
                    _state.value = _state.value.copy(
                        receiverRunning = intent.getBooleanExtra("isRunning", false)
                    )
                }
                MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED -> {
                    _state.value = _state.value.copy(
                        receiverEnabled = settings.backgroundReceiveEnabled
                    )
                }
                MyApplication.ACTION_BUSY_CHANGED -> {
                    _state.value = _state.value.copy(
                        busy = intent.getBooleanExtra("busy", false)
                    )
                }
            }
        }
    }

    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    _state.value = _state.value.copy(
                        wifiEnabled = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN
                        ) == WifiManager.WIFI_STATE_ENABLED
                    )
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    _state.value = _state.value.copy(
                        bluetoothEnabled = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                        ) == BluetoothAdapter.STATE_ON
                    )
                }
            }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val granted = result == PackageManager.PERMISSION_GRANTED
        if (enableEnhancedModeAfterPermission) {
            ShizukuUtils.setEnhancedModeEnabled(context, granted)
            enableEnhancedModeAfterPermission = false
        }
        _state.value = _state.value.copy(
            shizukuGranted = granted && settings.enhancedModeEnabled
        )
        // ADB / Shizuku 一授权成功就立刻读取并落盘，之后即使断开 ADB 也能发送。
        if (granted) captureP2pMac()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _state.value = _state.value.copy(
            shizukuAvailable = true,
            shizukuGranted = settings.enhancedModeEnabled &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
        )
        // 车机通过 adb 启动 Shizuku 后会回调这里：自动保存真实 MAC。
        captureP2pMac()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = _state.value.copy(
            shizukuAvailable = false,
            shizukuGranted = false,
        )
    }

    init {
        viewModelScope.launch {
            combine(
                LiveUpdateCoordinator.state,
                IncomingTransferUiCoordinator.states,
                TransferUiCoordinator.states,
                ::homeTransferStatus,
            ).collect { status ->
                val previous = _state.value.transferStatus
                _state.value = _state.value.copy(transferStatus = status)
                // 传输从「进行中」回到空闲 = 已结束，此时刷新接收历史。
                if (previous != null && status == null) refreshReceivedHistory()
            }
        }
        context.registerInternalBroadcastReceiver(
            appStateReceiver,
            IntentFilter().apply {
                addAction(ServiceState.ACTION_UPDATE_RECEIVER_STATE)
                addAction(MyApplication.ACTION_BUSY_CHANGED)
                addAction(MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED)
            },
        )
        context.registerReceiver(
            radioStateReceiver,
            IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            null,
            null,
            getReceiverFlags(),
        )
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        context.sendBroadcast(ServiceState.getQueryIntent())
        refreshMacState()
        refreshReceivedHistory()
        // 冷启动也尝试一次：无 ADB 时可从 p2p0 网卡读到并保存。
        captureP2pMac()
    }

    /** 回到前台时：还没保存过地址、或 ADB 正连着，就再捕获一次（含地址变化的情况）。 */
    fun onForeground() {
        refreshMacState()
        refreshReceivedHistory()
        val adbGranted = _state.value.shizukuAvailable &&
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        if (_state.value.savedP2pMac.isEmpty() || adbGranted) captureP2pMac()
    }

    /** 把持久化的 MAC 设置同步到界面。 */
    fun refreshMacState() {
        val saved = P2pMacManager.current(context)
        _state.value = _state.value.copy(
            customP2pMac = settings.customP2pMac,
            savedP2pMac = saved?.mac ?: "",
            savedP2pMacSource = saved?.source ?: "",
            savedP2pMacTime = saved?.time ?: 0L,
            skipMacValidation = settings.skipMacValidation,
        )
    }

    /** 设置页「手动填写 MAC」的保存动作：写入本机，重启 / 关闭 ADB 后仍然有效。 */
    fun setCustomP2pMac(value: String) {
        settings.customP2pMac = value
        refreshMacState()
    }

    fun clearSavedP2pMac() {
        P2pMacManager.clear(context)
        refreshMacState()
        Toast.makeText(context, "已清除自动保存的 MAC", Toast.LENGTH_SHORT).show()
    }

    /** 读取「最近接收」列表。 */
    fun refreshReceivedHistory() {
        val entries = runCatching { ReceivedHistoryStore.list(context) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(receivedHistory = entries)
    }

    fun clearReceivedHistory() {
        ReceivedHistoryStore.clear(context)
        refreshReceivedHistory()
        Toast.makeText(context, "已清空接收历史", Toast.LENGTH_SHORT).show()
    }

    /**
     * 捕获并持久化本机真实 P2P MAC。
     * ADB / Shizuku 可用且已授权时优先走 WifiP2pManager.requestDeviceInfo，
     * 否则退回免权限的 p2p0 网卡读取；成功后写入 SharedPreferences。
     * [showResult] 为 true 时弹出结果提示（设置页手动触发）。
     */
    fun captureP2pMac(showResult: Boolean = false) {
        if (capturingMac) return
        capturingMac = true
        _state.value = _state.value.copy(capturingMac = true)
        viewModelScope.launch {
            val adbGranted = _state.value.shizukuAvailable &&
                runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            val result = withContext(Dispatchers.IO) {
                runCatching { P2pMacManager.capture(context, adbGranted) }.getOrNull()
            }
            refreshMacState()
            capturingMac = false
            _state.value = _state.value.copy(capturingMac = false)
            if (showResult) {
                val message = if (result != null) {
                    "已保存 MAC ${result.mac}（${P2pMacManager.sourceLabel(result.source)}），关闭 ADB 后仍可发送"
                } else {
                    "未读到真实 MAC：请确认 ADB / Shizuku 已授权，或在「手动填写 MAC」中输入地址"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun setReceiverEnabled(enabled: Boolean) {
        MyApplication.getInstance().setBackgroundReceiveEnabled(enabled)
        _state.value = _state.value.copy(receiverEnabled = enabled)
        refreshReceiveState()
    }

    fun refreshReceiveState() {
        _state.value = _state.value.copy(
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
            receivePermissionsGranted = context.missingTransferPermissions(includeNotifications = true).isEmpty(),
            receiverRunning = MyApplication.getInstance().receiverRunning,
        )
    }

    private fun isWifiEnabled(): Boolean = try {
        context.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (_: SecurityException) {
        false
    }

    private fun isBluetoothEnabled(): Boolean = try {
        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    fun setDeviceName(name: String) {
        if (deviceNameError(name) != null) return
        val safeName = name.trim()
        settings.deviceName = safeName
        _state.value = _state.value.copy(deviceName = safeName)
        MyApplication.getInstance().refreshReceiverIdentity()
    }

    fun setBrand(brandId: Int) {
        settings.brandId = brandId
        _state.value = _state.value.copy(
            configuredBrandId = brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
        )
        MyApplication.getInstance().refreshReceiverIdentity()
    }

    fun setReceivePath(uri: Uri) {
        settings.downloadUri = uri.toString()
        _state.value = _state.value.copy(receivePath = uri.toString())
    }

    fun setSecureReceiveOnly(enabled: Boolean) {
        settings.secureReceiveOnly = enabled
        _state.value = _state.value.copy(secureReceiveOnly = enabled)
    }

    fun setEnhancedMode(enabled: Boolean) {
        val current = _state.value
        when {
            !current.shizukuAvailable -> {
                Toast.makeText(context, R.string.shizuku_unavailable, Toast.LENGTH_LONG).show()
            }
            !enabled -> {
                enableEnhancedModeAfterPermission = false
                ShizukuUtils.setEnhancedModeEnabled(context, false)
                _state.value = current.copy(shizukuGranted = false)
            }
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                ShizukuUtils.setEnhancedModeEnabled(context, true)
                _state.value = current.copy(shizukuGranted = true)
            }
            else -> try {
                enableEnhancedModeAfterPermission = true
                Shizuku.requestPermission(0)
            } catch (error: Throwable) {
                enableEnhancedModeAfterPermission = false
                Log.e(TAG, "Failed to request Shizuku permission", error)
                Toast.makeText(context, R.string.shizuku_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCleared() {
        context.unregisterReceiver(appStateReceiver)
        context.unregisterReceiver(radioStateReceiver)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
