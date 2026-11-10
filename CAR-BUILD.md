# 车机版安装与编译说明

面向比亚迪 DiLink 5.0 / Android 12+ 车机（其他 Android 12+ 车机同理）。
本分支基于 [HotKids/EasyShare](https://github.com/HotKids/EasyShare)（MIT），是**独立应用**：包名 `com.android.easyshare`、应用名 EasyShare，可与官方 EasyShare 共存。

当前版本：`1.0.0`（`versionCode = 1`），`minSdk 31` / `targetSdk 37`。

---

## 一、安装

```bash
adb install -r EasyShare-1.0.0-arm64-v8a.apk
```

- 车机基本都是 `arm64-v8a`；不确定就用 `universal`。
- 与早期 `com.byd.easyshare` 的包名不同，两者互不影响，可同时安装。

## 二、首次使用

1. 打开应用，按提示授予「附近的设备」「通知」等权限，保持 Wi‑Fi 与蓝牙开启。
2. **接收**：打开首页「后台接收」开关，另一台设备即可发现本机；收到的文件默认落在 `Downloads/Easy Share`（设置里可改目录）。
3. **发送**：首页「发送文件 → 选择文件」，系统文件管理器里选完文件即进入设备选择页。
4. **MAC**：连一次 ADB（或 Shizuku）后应用会自动读取本机 P2P 地址并保存；也可以手动输入。

## 三、拿到真实 P2P MAC 的三种方式

| 方式 | 操作 | 说明 |
|---|---|---|
| 自动（推荐） | 车机执行 `adb shell sh /sdcard/start_shizuku.sh` 启动 Shizuku，或用 ADB 授权后打开应用 | 应用收到 Shizuku binder / 权限后自动读取并写入本机，之后关掉 ADB 依然有效 |
| 手动命令 | `adb shell cat /sys/class/net/p2p0/address` | 需要先开启一次 Wi‑Fi Direct（例如打开「后台接收」），再把结果填进设置页 |
| 免权限兜底 | 无需任何操作 | 应用会自己读 `p2p0` 网卡，读不到就用占位地址，本应用之间互传不受影响 |

设置页「P2P MAC 地址」一行会显示当前状态：`已保存 xx:xx:…（来源）｜ADB 已连接/未连接`。

## 四、三个关键结论

详见 [`docs/INTEROP-AUDIT.md`](docs/INTEROP-AUDIT.md)。

1. **车机能不能「真的拿到」MAC？**
   - 三方应用通过 `WifiP2pGroup.owner.deviceAddress` / `WifiInfo` 只能拿到脱敏值 `02:00:00:00:00:00`。
   - 走 Shizuku（以 shell 身份 `requestDeviceInfo`）或直接读 `p2p0` 网卡硬件地址可以拿到真实值，本应用两者都做了，并会持久化。
2. **能不能和 HandySend（鸿蒙）互传？**
   协议层双向兼容（UUID、字段、ECDH+AES-CTR、WS 消息、`/download` 路由对齐），前提是接收方关闭「仅接收加密传输」。
3. **为什么最好用 EasyShare / HandySend 这类对端？**
   手机自带互传（华为 / 小米 / OPPO 原生实现）会严格比对 `P2pInfo.mac` 与群主设备地址；车机若拿不到真实地址，这类对端一定失败。这是协议约束，不是本应用能绕过的——此时请先保存真实 MAC。

## 五、编译

- JDK **21**、Gradle **9.8.1**（wrapper 自带）、Android SDK `platforms;android-37` + `build-tools;37.0.0`
- 仓库根目录 `local.properties` 写 `sdk.dir=/你的/Android/Sdk`
- Release 打包强制要求 4 个 `SIGNING_*` 变量（缺省则无法签名）：

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export SIGNING_KEYSTORE_PATH=/path/to/keystore.jks
export SIGNING_STORE_PASSWORD=你的口令
export SIGNING_KEY_ALIAS=你的别名
export SIGNING_KEY_PASSWORD=你的口令
./gradlew :app:assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`（车机）、`app-universal-release.apk`（全 ABI）、`app-x86_64-release.apk`。

## 六、排错

```bash
adb logcat -s P2pSenderService P2pIdentity P2pReceiverService
```

| 日志 | 含义 |
|---|---|
| `P2P identity resolved: source=saved` | 正在用已保存的 MAC（ADB 已关闭时也应出现） |
| `source=manual` | 用的是设置页手填值 |
| `source=interface` | 用的是本次读到的 `p2p0` 网卡地址 |
| `source=placeholder` | 没拿到真实地址，用的占位值（与第三方互传不影响） |
| `Missing P2P device address` | 不该再出现；若出现请反馈日志 |

## 七、兜底方案

车机开热点或连手机热点，用文件管理器「快传」、FTP/HTTP 服务 App，或 U 盘直传，均不需要 MAC / ADB / Shizuku。
