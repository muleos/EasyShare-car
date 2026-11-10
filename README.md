# EasyShare（车机版）

> 基于 [HotKids/EasyShare](https://github.com/HotKids/EasyShare) 的互传联盟协议实现，针对**车机（DiLink 5.0 / Android 12+）** 场景做了实用化改造。
> 上游为 MIT 许可，原版权声明完整保留在 [`LICENSE`](LICENSE)；本仓库的改动说明见 [`CHANGELOG.md`](CHANGELOG.md)。

| 项目 | 值 |
|---|---|
| 应用名 | EasyShare |
| 包名 | `com.android.easyshare` |
| 版本 | 1.0.0（versionCode 1） |
| 最低系统 | Android 12（API 31） |
| 安装包 | Release 页 `EasyShare-1.0.0-*.apk` |

EasyShare 通过蓝牙发现附近的互传联盟设备，再用 Wi‑Fi Direct + HTTPS/WebSocket 建立点对点连接传文件。文件只在两台设备之间直传，不经过云端。

---

## 相对上游的主要改动

### 1. 应用内直接选文件发送

首页「发送文件 → 选择文件」直接调起系统文件管理器（SAF，支持多选），选完即进入设备选择页。
不用再绕到文件管理器里「分享 → EasyShare」。

- 主用 `OpenMultipleDocuments`；缺少 DocumentsUI 的精简固件会自动回退到通用「打开文件」选择器。
- 多个 Uri 通过 `ACTION_SEND_MULTIPLE` + `ClipData` 传递读权限，复用原有发送链路。

### 2. P2P MAC 地址：读一次、永久保存

车机在没有 ADB / Shizuku 时常常拿不到真实 P2P Device Address，而手机自带互传会校验这个字段。这里的做法是「读到就存下来」：

- **自动读取**：连上 ADB / Shizuku 并授权后，用 `WifiP2pManager.requestDeviceInfo()` 读真实地址；读不到则退回免权限的 `p2p0` 网卡硬件地址。
- **自动保存**：拿到后归一化成 `aa:bb:cc:dd:ee:ff` 写入本机，**关闭 ADB、停掉 Shizuku、重启车机都不影响**。
- **手动输入**：设置页「P2P MAC 地址」一行同时提供状态展示与手动输入保存（支持 `40:b0:fa:82:17:46`、`40-b0-fa-82-17-46`、`40b0fa821746`）。
- **发送时优先用**：手填 → 已保存 → 群主地址 → p2p0 网卡。都不会再抛 `Missing P2P device address`。

想手动取值可以用：

```bash
adb shell cat /sys/class/net/p2p0/address
```

### 3. 接收历史列表

首页新增「最近接收」：记录每次成功接收的文件名、大小、时间，点击用系统「打开方式」打开。

- 平板 / 横屏双栏布局：显示在**左侧应用名（设备卡）下方**。
- 手机竖屏单栏布局：跟在设备卡后面。
- 最多保留 80 条，按 Uri 去重，可一键清空。

### 4. 其它

- 图标改为纯矢量自适应图标（蓝底 + 白色声波弧），任意尺寸都清晰，不再有位图模糊。
- 设置页合并为一个 MAC 条目，去掉了多余的校验开关。

---

## 安装

```bash
adb install -r EasyShare-1.0.0-arm64-v8a.apk
```

- 车机一般用 `arm64-v8a`；不确定架构就用 `universal`。
- `minSdk 31`，Android 12 以下装不上。
- 若之前装过 `com.byd.easyshare` 的旧包，两者包名不同可以共存；确认不需要后可自行卸载旧包。

## 使用

1. 打开应用，授予附近设备 / 通知等权限，Wi‑Fi 与蓝牙保持开启。
2. **接收**：打开「后台接收」开关，另一台设备即可发现本机。收到的文件默认落在 `Downloads/Easy Share`，可在设置里改目录。
3. **发送**：点「选择文件」挑文件 → 选中目标设备 → 等待传输完成。
4. **（可选）保存 MAC**：若接收方是手机自带互传且发送失败，连一次 ADB 让应用自动读一次地址，或手动把 `p2p0` 地址填进「P2P MAC 地址」并保存。
5. **历史**：首页「最近接收」可以看到收到的文件，点击直接打开。

## 构建

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

# 可选：release 签名（缺省则输出未签名包）
export SIGNING_KEYSTORE_PATH=/path/to/keystore.jks
export SIGNING_STORE_PASSWORD=...
export SIGNING_KEY_ALIAS=...
export SIGNING_KEY_PASSWORD=...

./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`（`arm64-v8a` / `x86_64` / `universal` 三个 APK）。

## 代码位置

| 关注点 | 文件 |
|---|---|
| MAC 读取与持久化 | `app/src/main/java/com/byd/easyshare/utils/P2pMacManager.kt`、`AppSettings.kt` |
| 发送时 MAC 解析顺序 | `app/src/main/java/com/byd/easyshare/services/P2pSenderService.kt` |
| 接收历史存储 | `app/src/main/java/com/byd/easyshare/utils/ReceivedHistoryStore.kt` |
| 首页 UI（发送入口 / MAC 条目 / 历史列表） | `app/src/main/java/com/byd/easyshare/ui/main/MainScreen.kt` |
| 文件选择器与发送入口 | `app/src/main/java/com/byd/easyshare/MainActivity.kt` |
| 矢量图标 | `app/src/main/res/drawable/ic_launcher_foreground.xml`、`ic_launcher_background.xml` |

## 参与贡献 / 安全

提交代码前请阅读 [贡献指南](CONTRIBUTING.md)；安全问题请按 [安全策略](SECURITY.md) 私下报告。

## 开源许可

MIT。基于 [HotKids/EasyShare](https://github.com/HotKids/EasyShare)（其上游为 [kmod-midori/CatShare](https://github.com/kmod-midori/CatShare)），版权声明见 [LICENSE](LICENSE)。
