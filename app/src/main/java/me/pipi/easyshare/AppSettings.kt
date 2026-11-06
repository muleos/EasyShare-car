package me.pipi.easyshare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceName

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

}
