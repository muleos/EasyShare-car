# 车机版编译与安装说明（v2）

基于 [HotKids/EasyShare](https://github.com/HotKids/EasyShare) v1.0.4（MIT）修改，面向比亚迪 DiLink 5.0 / Android 12 车机。

v2 起本分支是一个**独立应用**：包名 `com.byd.easyshare`、应用名「比亚迪分享」、华为分享图标，可与官方 EasyShare 共存。

---

## 一、改动清单

### v1：车内无需 MAC / ADB 也能发送

| 文件 | 改动 |
|---|---|
| `AppSettings.kt` | 新增 `customP2pMac`（手填 P2P MAC）与 `skipMacValidation`（默认 `true`） |
| `ui/main/MainScreen.kt` | 设置页新增 MAC 输入项与「跳过 MAC 校验」开关 |
| `services/P2pSenderService.kt` | 跳过校验时不再抛 `Missing P2P device address` |
| `services/GattServerService.kt` | 接收侧去掉对端 MAC 长度校验 |
| `app/build.gradle.kts` | 启用被上游禁用的 `debug` 变体 |

### v2：真正可用的 MAC 解析 + 独立品牌

| 文件 | 改动 |
|---|---|
| `utils/P2pIdentity.kt`（新增） | 手填值归一化（`aa:bb:…` / `aa-bb-…` / `aabb…` / 大写 全部接受）、协议口径校验、**免 root 免 Shizuku 直接读 `p2p0` 网卡硬件地址** |
| `services/P2pSenderService.kt` | 解析顺序改为：手填 → 群主地址 → `p2p0` 网卡 → Shizuku → 占位；并打出 `P2P identity resolved: source=…` 日志 |
| `ui/main/MainScreen.kt` | 输入框实时校验/回显归一化结果；设置页展示自动检测到的地址 |
| 全仓 | 包名 `me.pipi.easyshare` → `com.byd.easyshare`（含 AIDL、ProGuard、Shizuku 服务、权限名） |
| `res/values*/strings.xml` | `app_name` → 比亚迪分享 |
| `res/mipmap-*` | 华为分享图标：各密度 `ic_launcher(_round).png` + 自适应前景 `ic_launcher_foreground.png` |

`patches/EasyShare-car-changes.patch` 只反映 **v1** 的改动，v2 请直接看本文件与仓库 diff（v2 含全仓改名，整体 diff 不适合以补丁形式阅读）。

---

## 二、三个关键结论

详见 [`docs/INTEROP-AUDIT.md`](docs/INTEROP-AUDIT.md)。

1. **车机能不能「真的拿到」MAC？**
   - 原来不能：`WifiP2pGroup.owner.deviceAddress` 对三方应用恒返回 `02:00:00:00:00:00`，`PackageManager` / `WifiInfo` 同样被脱敏。
   - 现在**有可能**：建组后 `p2p0` 网卡真实存在，v2 会直接读它的硬件地址。读到就用真实值，读不到才用占位。
   - 但**无论真假都不影响本应用互传**：`P2pInfo.mac` 在 EasyShare / HandySend 等第三方实现里只做字段解析，不参与任何校验或寻址。

2. **能不能和 HandySend（鸿蒙）互传？**
   协议层双向兼容（UUID、字段、ECDH+AES-CTR、WS 消息、`/download` 路由全部对齐），前提是接收方 EasyShare 关闭「仅接收加密传输」（默认关闭）。

3. **为什么必须用 EasyShare / HandySend 这类对端？**
   手机自带互传（华为/小米/OPPO 原生）会严格比对 `P2pInfo.mac` 与群主设备地址，车机拿不到真实地址时一定失败。这是协议层约束，不是本应用能绕过的。

---

## 三、编译

- JDK **21**、Gradle **9.8.1**（wrapper 自带）、Android SDK `platforms;android-37` + `build-tools;37.0.0`
- 仓库根目录 `local.properties` 写 `sdk.dir=/你的/Android/Sdk`
- 上游 `packageRelease` 强制要求 4 个 `SIGNING_*` 变量，本分支用本地自签：

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export SIGNING_KEYSTORE_PATH=/path/to/temp.jks
export SIGNING_STORE_PASSWORD=你的口令
export SIGNING_KEY_ALIAS=你的别名
export SIGNING_KEY_PASSWORD=你的口令
./gradlew assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`（车机）、`app-universal-release.apk`（全 ABI）。

> `assembleDebug` 也可用（v1 已启用 debug 变体），适合快速试。debug 版含 PAG 动画库、未混淆，体积约 56 MB。

## 四、安装与使用

1. 包名已变更，会作为**新应用**安装，不会覆盖旧的 `me.pipi.easyshare`（官方版/上一版车机版都可保留，按需卸载）。
2. 首次打开授予「附近的设备 / 位置」权限。
3. 设置页确认：
   - 「跳过 MAC 校验」= **开启**（默认）
   - 「P2P MAC 地址」留空即可；想指定就填，三种写法都认
4. 手机端装 EasyShare（官方或本分支）或 HandySend，打开「接收」。
5. 车机上分享文件 → 选手机 → 发送。

排查：`adb logcat -s P2pSenderService` 里会有一行 `P2P identity resolved: source=manual|group-owner|interface|placeholder`，直接说明用的是哪个来源。

## 五、已验证 / 未验证

- ✅ `assembleRelease` 通过；V2 签名有效；`package=com.byd.easyshare`、`versionName=1.0.4-byd`、`versionCode=10`、`minSdk=31`、`targetSdk=37`、`application-label=比亚迪分享`；5 个密度图标与自适应图标均已打入 APK
- ✅ 协议层逐项比对通过（见 `docs/INTEROP-AUDIT.md`）
- ⚠️ **未在实车、未在两台真机之间实测传输**
- ⚠️ `p2p0` 网卡地址能否读到取决于 DiLink 固件；读不到会自动降级为占位值，不影响 EasyShare/HandySend 互传
- ⚠️ 比亚迪固件在 WLAN 未连接时约每 10 秒全频段扫描，会让 Wi-Fi Direct 周期性卡顿；**建议先让车机连上一个 Wi-Fi 再传**

## 六、兜底方案

车机开热点或连手机热点，用 ES 文件浏览器「快传」、FTP/HTTP 服务 App，或 U 盘直传，均不需要 MAC / ADB / Shizuku。
