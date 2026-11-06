# 互传兼容性审计：EasyShare（Android）↔ HandySend（HarmonyOS）

审计对象：

- 本仓库分支（`com.byd.easyshare`，基于 HotKids/EasyShare v1.0.4）
- [muleos/HandySend](https://github.com/muleos/HandySend) 的 `easyshare` 修改版

结论：**协议层双向兼容**，无阻塞性差异。平台层存在 3 个只能靠真机确认的风险点，列在文末。

---

## 1. 为什么「车机拿不到 MAC」不影响本应用互传

`P2pInfo.mac` 是发送方在 BLE 凭据通道里声明的「群主设备地址」。它在不同实现里的用途完全不同：

| 实现 | 对 `P2pInfo.mac` 的处理 |
|---|---|
| 手机自带互传（华为 / 小米 / 中兴…） | **强校验**：必须等于其观察到的群主设备地址，否则中止入组（小米报 `unrecognized network owner`） |
| EasyShare | 只解密、不比对（`P2pReceiverService` 全文无 `mac` 引用） |
| HandySend | 只解析、不比对（`parseP2pInfo` 后无任何比对逻辑） |

也就是说：只要对端是 EasyShare 或 HandySend，这个字段填什么都能通。因此本分支在拿不到真实地址时下发占位值 `40:00:00:00:00:01`（单播、非全 0、合法格式），这是安全的。

同理，接收方向 EasyShare 已去掉对 `p2pInfo.mac` 的长度 `require`，HandySend 下发 `02:00:00:00:00:00` 也不会被拒。

## 2. 分项对照

### 2.1 BLE 发现与 GATT 凭据通道

| 项目 | EasyShare | HandySend | 结论 |
|---|---|---|---|
| 广播 Service UUID | `00003331-0000-1000-8000-008123456789` | 同 | ✅ |
| 广播 serviceData 长度 | 6 字节（品牌/5GHz）+ 27 字节（ID/设备名） | 同 | ✅ |
| GATT Service | `00009955-…-00805f9b34fb` | 同 | ✅ |
| CHAR_STATUS（读 DeviceInfo） | `00009954-…` | 同 | ✅ |
| CHAR_P2P（写 P2pInfo） | `00009953-…` | 同 | ✅ |
| MTU 请求 | 512 | 512 | ✅ |

EasyShare 的扫描回调按 `serviceData` 的**长度**（6 / 27）分派字段，不依赖品牌 UUID 的具体值，因此 HandySend 用「第三方品牌 0xff」广播也能被识别。

### 2.2 DeviceInfo（对端能力声明）

| 字段 | EasyShare | HandySend |
|---|---|---|
| `state` | 0 | 0 |
| `key` | P-256 公钥（Base64 SPKI） | 同 |
| `mac` | 本机 P2P/蓝牙地址（可脱敏） | `02:00:00:00:00:00` |
| `catShare` | `BuildConfig.VERSION_CODE`（本分支 = 10） | 7 |
| `catShareCrypto` | **2**（声明支持 HKDF+AES-GCM） | **不下发** |

这条差异决定了加密档位：

- **HandySend 作为发送方**：读到 EasyShare 的 `catShare=10 > 0` → 判定为第三方实现对端 → 跳过读取本机 P2P 地址（本次修改点），直接下发 `FALLBACK_MAC`；因为 EasyShare 的 `catShareCrypto=2` 只影响 EasyShare 自己的发送行为，HandySend 仍按旧档（ECDH+AES-CTR）加密。
- **EasyShare 作为发送方**：读到 HandySend 的 DeviceInfo **没有** `catShareCrypto` → `securePeer=false` → 走旧档：ECDH P-256 + AES-256-CTR（固定 IV `0102030405060708`，**逐字段独立 cipher**）、`P2pInfo` 不带 `catShareToken` / `catShareCert`、TLS 不校验证书。这正是 HandySend `MtaCrypto` 实现的档位。

### 2.3 加密

| 项目 | EasyShare（旧档） | HandySend | 结论 |
|---|---|---|---|
| 曲线 | P-256 | `ECC256` | ✅ |
| 共享密钥 | ECDH 32 字节 | ECDH 32 字节 | ✅ |
| 对称 | AES-256-CTR，NoPadding | `AES256|CTR|NoPadding` | ✅ |
| IV | ASCII `0102030405060708` | 同 | ✅ |
| 字段 | `ssid` / `psk` / `mac` 各自 Base64 | 同 | ✅ |
| 逐字段重置计数器 | 是（每次新建 Cipher） | 是 | ✅ |

### 2.4 WebSocket 应用层

| 项目 | EasyShare | HandySend | 结论 |
|---|---|---|---|
| 路径 | `/websocket` | 同 | ✅ |
| 首帧 | `action:0:versionNegotiation {version:1,versions:[1]}` | 同 | ✅ |
| 版本协商 ack | 等 `ack` 且 `name=versionNegotiation`（不校验 id） | 回 `ack:<id>:versionNegotiation` | ✅ |
| 接收请求 | `action:1:sendRequest`（`taskId`/`id`/`senderName`/`senderBrandId`/`fileCount`/`totalSize`/`mimeType`） | 解析同名字段，缺省容错 | ✅ |
| 接收端回 ack | 收到任意 `action` 回 `ack` | 收到 vn / sendRequest 各回 ack | ✅ |
| 结果回执 | 等 `action/status` 的 `type`+`reason`（`1`+`ok` 为成功） | 发 `status`，`type=1`、`reason=ok` | ✅ |

HandySend 的 `deviceType` 等附加字段 EasyShare 不解析即忽略（`JsonWithUnknownKeys`），反之亦然。

### 2.5 下载

| 项目 | EasyShare（发送方） | HandySend（接收方） |
|---|---|---|
| 请求 | `GET /download?taskId=<id>[&token=]` | 同 |
| taskId 校验 | 必须等于本次会话 id | 必须等于本次会话 id |
| token 校验 | 仅「双方都是新版」（`catShareCrypto≥2`）时要求；本次为旧档 → 不校验 | 不校验 |
| 证书 | 自签；对方不给 `catShareCert` 时不 pin | Rust `danger_accept_invalid_certs(true)` |
| 响应 | `application/zip` 流，chunked | 流式解压，条目上限 10000 |

反向（HandySend 发送 → EasyShare 接收）：EasyShare 的 `SSLContext` + `SessionTrustManager(null)` 接受自签证书；`?token=` 在旧档下不拼、也不校验。✅

### 2.6 Wi-Fi Direct

| 项目 | EasyShare | HandySend | 结论 |
|---|---|---|---|
| 建组方 | 发送方 | 发送方 | ✅ |
| 组名 | `DIRECT-` + 8 随机字符 | 同前缀 | ✅ |
| 入组方式 | `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk)` | `p2pConnect{groupName, passphrase, goBand:AUTO}` | ✅ 都是「按 SSID+PSK 入组」 |
| 组名校验 | `verifiedJoinedOwnerName` 比对 `networkName == ssid` | 不比对 | ✅ |

---

## 3. 剩下的风险（只能真机确认）

1. **HarmonyOS 侧 GO 是否广播 SSID**：EasyShare 接收端是「按 SSID 入组」，若 HandySend 的群组是隐藏网络则入组失败。这与小米/华为互传已验证过的路径相同，风险低但未实测。
2. **HarmonyOS `webSocket` 对自签证书的默认策略**：HandySend 接收端用 `@kit.NetworkKit` 的 `webSocket`（非 Rust），若系统校验自签证书会握手失败。HandySend 对厂商端同路径可用，风险低。
3. **车机固件是否真的开放 Wi-Fi Direct / BLE 外设角色**：与 MAC、与本应用都无关，属固件能力。

## 4. 需要在 EasyShare 端确认的设置

| 设置项 | 要求 | 原因 |
|---|---|---|
| 跳过 MAC 校验 | **开启**（默认） | 车机拿不到真实群主地址 |
| 仅接收加密传输 | **关闭**（默认） | HandySend 只实现旧档，开了会把 HandySend 直接拒在 GATT 写入阶段（`SessionSecurity.isPeerAllowed`） |
