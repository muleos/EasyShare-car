package com.byd.easyshare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.byd.easyshare.utils.BleUtils
import com.byd.easyshare.utils.DeviceName
import com.byd.easyshare.utils.P2pIdentity

class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceName: String
        get() = BleUtils.normalizeDeviceName(prefs.getString("deviceName", null) ?: DeviceName.get())
        set(value) {
            prefs.edit { putString("deviceName", BleUtils.normalizeDeviceName(value)) }
        }

    var downloadUri: String?
        get() = prefs.getString("downloadUri", null)
        set(value) {
            prefs.edit { putString("downloadUri", value) }
        }

    var enhancedModeEnabled: Boolean
        get() = prefs.getBoolean("enhancedModeEnabled", true)
        set(value) {
            prefs.edit { putBoolean("enhancedModeEnabled", value) }
        }

    var secureReceiveOnly: Boolean
        get() = prefs.getBoolean("secureReceiveOnly", false)
        set(value) {
            prefs.edit { putBoolean("secureReceiveOnly", value) }
        }

    var backgroundReceiveEnabled: Boolean
        get() = prefs.getBoolean("backgroundReceiveEnabled", false)
        set(value) {
            prefs.edit { putBoolean("backgroundReceiveEnabled", value) }
        }

    var brandId: Int
        get() = prefs.getInt("brandId", -1)
        set(value) {
            prefs.edit { putInt("brandId", value) }
        }

    /** 手动填写的本机 P2P Device Address，留空表示不指定。 */
    var customP2pMac: String
        get() = prefs.getString("customP2pMac", null) ?: ""
        set(value) {
            prefs.edit { putString("customP2pMac", value.trim()) }
        }

    /** EasyShare 互传时不校验对端 P2P 身份，无需 Shizuku / ADB。默认开启。 */
    var skipMacValidation: Boolean
        get() = prefs.getBoolean("skipMacValidation", true)
        set(value) {
            prefs.edit { putBoolean("skipMacValidation", value) }
        }

    /**
     * 自动捕获到并已持久化的本机 P2P Device Address。
     * 与手填的 [customP2pMac] 分开保存：ADB / Shizuku 权限关闭、甚至重启车机后，
     * 发送流程仍会优先复用这里的值，不再依赖实时读取。
     */
    var savedP2pMac: String
        get() = prefs.getString("savedP2pMac", null) ?: ""
        private set(value) {
            prefs.edit { putString("savedP2pMac", value.trim()) }
        }

    /** 已保存地址的来源：adb / p2p0 / group / manual。 */
    var savedP2pMacSource: String
        get() = prefs.getString("savedP2pMacSource", null) ?: ""
        private set(value) {
            prefs.edit { putString("savedP2pMacSource", value) }
        }

    /** 保存时间（毫秒），用于界面展示。 */
    var savedP2pMacTime: Long
        get() = prefs.getLong("savedP2pMacTime", 0L)
        private set(value) {
            prefs.edit { putLong("savedP2pMacTime", value) }
        }

    /**
     * 归一化、校验并持久化一个真实地址。非法 / 系统占位地址会被拒绝，返回 null。
     * 手填值由 [customP2pMac] 单独管理，不通过这里写入，避免覆盖用户输入。
     */
    fun saveDetectedP2pMac(raw: String?, source: String): String? {
        val normalized = P2pIdentity.usable(raw) ?: return null
        prefs.edit {
            putString("savedP2pMac", normalized)
            putString("savedP2pMacSource", source)
            putLong("savedP2pMacTime", System.currentTimeMillis())
        }
        return normalized
    }

    /** 清除自动保存的地址（不影响手填值）。 */
    fun clearSavedP2pMac() {
        prefs.edit {
            remove("savedP2pMac")
            remove("savedP2pMacSource")
            remove("savedP2pMacTime")
        }
    }

}
