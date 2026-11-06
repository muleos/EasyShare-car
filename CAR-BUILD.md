# 车机版编译与安装说明

基于 [HotKids/EasyShare](https://github.com/HotKids/EasyShare) v1.0.4（MIT）修改，面向比亚迪 DiLink 5.0 / Android 12 车机。

## 改动清单（5 个文件）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/me/pipi/easyshare/AppSettings.kt` | 新增持久化字段 `customP2pMac`（手填 P2P MAC）与 `skipMacValidation`（默认 `true`） |
| `app/src/main/java/me/pipi/easyshare/ui/main/MainScreen.kt` | 设置页新增 MAC 输入框（`P2pMacDialog`）与跳过校验开关 |
| `app/src/main/java/me/pipi/easyshare/services/P2pSenderService.kt` | 跳过校验时：手填值合法则用，否则用占位 `40:00:00:00:00:01`；不再抛 `Missing P2P device address`，不调用 Shizuku。关闭开关时回退原优先级：手填 → `group.owner.deviceAddress` → Shizuku |
| `app/src/main/java/me/pipi/easyshare/services/GattServerService.kt` | 接收侧去掉对端 MAC 长度校验 |
| `app/build.gradle.kts` | 启用被上游禁用的 `debug` 变体以便本地构建；`versionName` 加 `-car` 标识 |

完整 diff 见 [`patches/EasyShare-car-changes.patch`](patches/EasyShare-car-changes.patch)。

## 编译环境

- JDK **21**（Gradle 9.x 要求 17+，实测 21 可用）
- Gradle **9.8.1**（与 `gradle/wrapper/gradle-wrapper.properties` 一致）
- Android SDK：`platforms;android-37`（`compileSdk = 37`）、`build-tools;37.0.0`、`platform-tools`
- 在仓库根目录写 `local.properties`：`sdk.dir=/你的/Android/Sdk` 路径（该文件已被 `.gitignore` 忽略）

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug --no-daemon
```

> 上游禁用了 debug 变体且 `packageRelease` 强校验 4 个 `SIGNING_*` 官方签名变量，因此本分支只建议 `assembleDebug`。上游的 `.github/workflows/android.yml` CI 配置依赖官方签名凭据，在本派生分支没有实际作用，且推送所用令牌无 `Workflows: write` 权限，故**未包含在本仓库**；如需可从[上游仓库](https://github.com/HotKids/EasyShare)取回。其余源码、资源与文档完整保留。

产物位于 `app/build/outputs/apk/debug/`：`app-arm64-v8a-debug.apk`、`app-universal-debug.apk`。也可直接使用本仓库 Release 中已编译好的 APK。

## 安装与使用

1. **先卸载车机上已装的官方 EasyShare** —— 包名同为 `me.pipi.easyshare` 但签名不同，否则报「应用未安装 / 签名冲突」。
2. U 盘拷入 arm64-v8a APK，用车机文件管理器安装（需开启「未知来源」；DiLink 5.0 若被安装限制拦住，可在车机计算器输入 `20211225` 解锁工厂菜单）。
3. 打开 App → 设置区，确认「跳过 MAC 校验（EasyShare 互传）」为**开启**（默认开启）→ **直接发送**，无需填 MAC、无需 Shizuku、无需 ADB。
4. 接收方手机用**官方版 EasyShare** 即可，接收不依赖 Shizuku。
5. 若以后接收方换成手机自带互传，才需关闭开关并在输入框填车机真实 P2P Device Address（`xx:xx:xx:xx:xx:xx`、首字节偶数、非全 0、非 `02:00:00:00:00:00`）。

## 已验证 / 未验证

- ✅ 编译通过（`BUILD SUCCESSFUL`）、V2 签名有效、包名 `me.pipi.easyshare`、`versionName=1.0.4-car`、`minSdk=31`（Android 12）、`targetSdk=37`
- ✅ 改动确认进入 dex（`customP2pMac` / `skipMacValidation` / 占位 MAC）
- ⚠️ **未在真车实测传输**：BLE 发现与 Wi-Fi Direct 建组能否在 DiLink 5.0 固件上跑通需实车验证
- ⚠️ 已知固件问题：比亚迪车机在 WLAN 客户端未连接时约每 10 秒全频段扫描，会让 Wi-Fi Direct 周期性卡顿；**建议先让车机连上一个 Wi-Fi** 再传
- ⚠️ 这是 debug 签名、未混淆的测试构建（含 PAG 动画库，约 56 MB），非官方发布版，请勿对外分发
- ⚠️ 若车机找不到 Wi-Fi Direct / BLE 发现不到对端，属固件能力问题而非 MAC 问题，可退回「车机开热点 + ES 文件浏览器快传」

## 兜底方案（不编译）

车机开热点或连手机热点，同一局域网用 ES 文件浏览器「快传」、FTP/HTTP 文件服务 App，或直接用 U 盘、车机微信/QQ，均不需要 MAC / ADB / Shizuku。
