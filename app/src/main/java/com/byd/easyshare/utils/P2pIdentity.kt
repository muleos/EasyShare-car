package com.byd.easyshare.utils

import android.util.Log
import java.net.NetworkInterface

/**
 * 本机 Wi-Fi Direct 设备地址（P2P Device Address）解析。
 *
 * 优先级由调用方决定，本对象只负责三件事：
 * 1. [normalize]：把用户手填的各种写法（带冒号 / 带横线 / 纯 12 位十六进制 / 大写）归一成
 *    协议要求的 `aa:bb:cc:dd:ee:ff` 小写形式。上游实现只做正则校验，任何一种其它写法都会被
 *    静默丢弃并回退到占位值，这是「填了 MAC 却不生效」的直接原因。
 * 2. [usable]：归一化后再按互传协议口径校验（必须是单播地址、不能是全 0、不能是系统占位
 *    `02:00:00:00:00:00`）。
 * 3. [fromInterfaces]：不依赖 root / Shizuku / ADB，直接读 `p2p0` / `p2p1` 网卡的硬件地址。
 *    Android 只对 `WifiInfo` / `WifiP2pManager` 返回的地址做脱敏，`NetworkInterface` 的
 *    ioctl 路径在多数机型（含 DiLink）仍然可读，因此这一步能在车机上真正拿到真实地址。
 */
object P2pIdentity {
    private const val TAG = "P2pIdentity"

    /**
     * 兜底占位地址：仅在「跳过 MAC 校验」模式且拿不到任何真实地址时写入 `P2pInfo.mac`。
     * 该字段只被对端用于校验群主身份，EasyShare / HandySend 等第三方实现都不比对，
     * 因此填一个格式合法的常量不会影响传输。取 0x40 起始是为了满足「单播 + 全局」位语义。
     */
    const val PLACEHOLDER_ADDRESS = "40:00:00:00:00:01"

    /** 归一化任意常见写法；无法构成 6 字节 MAC 时返回 null。 */
    fun normalize(raw: String?): String? {
        val compact = raw
            ?.trim()
            ?.replace("-", "")
            ?.replace(":", "")
            ?.replace(" ", "")
            ?.lowercase()
            ?: return null
        if (compact.length != 12) return null
        if (!compact.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return compact.chunked(2).joinToString(":")
    }

    /** 归一化 + 协议口径校验，返回可直接写入 `P2pInfo.mac` 的值。 */
    fun usable(raw: String?): String? {
        val normalized = normalize(raw) ?: return null
        return DeviceUtils.usableP2pDeviceAddress(normalized)
    }

    /**
     * 尝试直接读取本机 P2P 网卡地址，无需 root / Shizuku。
     * 读不到、抛出异常、或读到被系统脱敏的占位地址时返回 null。
     */
    fun fromInterfaces(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.name.startsWith("p2p") }
                .mapNotNull { iface ->
                    runCatching { iface.hardwareAddress }.getOrNull()
                }
                .map { bytes -> bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) } }
                .mapNotNull { DeviceUtils.usableP2pDeviceAddress(it) }
                .firstOrNull()
                ?.also { Log.i(TAG, "Resolved P2P device address from interface") }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read P2P interface address", e)
            null
        }
    }
}
