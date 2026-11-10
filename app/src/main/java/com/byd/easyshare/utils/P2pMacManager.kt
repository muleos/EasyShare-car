package com.byd.easyshare.utils

import android.content.Context
import com.byd.easyshare.AppSettings

/**
 * 本机 P2P MAC 的「一次读取、永久保存」。
 *
 * 车机在没有 ADB / Shizuku 权限时拿不到 WifiP2pManager 返回的真实 deviceAddress，
 * 而手机自带互传又会校验这个字段。这里把任何路径读到的真实地址归一化后写入
 * SharedPreferences：ADB 断开、Shizuku 停止、车机重启之后，发送端仍然直接使用
 * 已保存的值，不再需要实时权限。
 */
object P2pMacManager {
    const val SOURCE_ADB = "adb"
    const val SOURCE_INTERFACE = "p2p0"
    const val SOURCE_GROUP = "group"
    const val SOURCE_MANUAL = "manual"

    data class Saved(val mac: String, val source: String, val time: Long)

    data class Capture(val mac: String, val source: String)

    fun current(context: Context): Saved? {
        val settings = AppSettings(context)
        val mac = P2pIdentity.usable(settings.savedP2pMac) ?: return null
        return Saved(mac, settings.savedP2pMacSource, settings.savedP2pMacTime)
    }

    fun sourceLabel(source: String): String = when (source) {
        SOURCE_ADB -> "ADB / Shizuku"
        SOURCE_INTERFACE -> "p2p0 网卡"
        SOURCE_GROUP -> "P2P 群主"
        SOURCE_MANUAL -> "手动保存"
        else -> source.ifBlank { "未知来源" }
    }

    /** 归一化并落盘；非法地址（含 02:00:00:00:00:00 占位）不写入。 */
    fun persist(context: Context, raw: String?, source: String): String? =
        AppSettings(context).saveDetectedP2pMac(raw, source)

    /** 只在尚未保存过、或地址发生变化时才写盘，避免发送流程反复刷新时间。 */
    fun persistIfNew(context: Context, raw: String?, source: String): String? {
        val normalized = P2pIdentity.usable(raw) ?: return null
        val existing = current(context)
        if (existing?.mac == normalized) return null
        return persist(context, normalized, source)
    }

    fun clear(context: Context) = AppSettings(context).clearSavedP2pMac()

    /**
     * 主动捕获一次并保存。
     * [allowAdb] 为 true 时先走 ADB / Shizuku 的 WifiP2pManager.requestDeviceInfo
     * （真实 deviceAddress，只有 ADB 授权后才拿得到），失败再退回免权限的 p2p0 网卡读取。
     * 调用方负责在 IO 线程执行。
     */
    suspend fun capture(context: Context, allowAdb: Boolean): Capture? {
        if (allowAdb) {
            val settings = AppSettings(context)
            if (!settings.enhancedModeEnabled) {
                // 增强模式是 Shizuku user service 的开关；ADB 已授权时自动打开，
                // 这样用户连上 ADB 后不必再去设置页手动开一次。
                ShizukuUtils.setEnhancedModeEnabled(context, true)
            }
            val raw = runCatching { ShizukuUtils.getP2pDeviceAddress(context) }.getOrNull()
            val mac = persistIfNew(context, raw, SOURCE_ADB)
            if (mac != null) return Capture(mac, SOURCE_ADB)
        }
        val local = P2pIdentity.fromInterfaces()
        val localMac = persistIfNew(context, local, SOURCE_INTERFACE)
        if (localMac != null) return Capture(localMac, SOURCE_INTERFACE)
        // 本次没读到新值但之前已保存过，同样视为「当前可用」，避免界面误报失败。
        return current(context)?.let { Capture(it.mac, it.source) }
    }
}
