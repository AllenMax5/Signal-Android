# Signal Android 多设备实验：Debug 接口观测

> 2026-11-05 升级恢复说明：见 [e2ee-trace-recovery-20261105.md](e2ee-trace-recovery-20261105.md)。当前源码使用 libsignal 0.106.1 系列；下文保留的 0.105.4 构建记录属于此前采集批次。


这套工具用于整理“操作步骤 → 客户端请求 → 返回结果”的文档证据。仅 Debug 包有原始字段采集：**安装后首次打开应用便自动开始，不要求逐幕发送 ADB 命令；进程重启后使用同一 runId 和新的文件前缀自动续采**。实验用 Debug 构建会关闭 Signal Java 与 libsignal 原生连接的服务端证书链校验，供 mitmproxy 解密传输层；发布构建仍使用原校验。**schemaVersion=2 / captureMode=raw 与 mitmproxy 流量会包含真实号码、验证码、授权头、消息正文、配置私钥和密文；只使用测试账号，原始文件不得直接放到局域网文档目录或提交到仓库。**

## 1. 准备与采集

Signal Android 的 Debug 变体默认解析本机 Maven 中的实验版 `org.signal:libsignal-client` 和 `org.signal:libsignal-android` `0.105.4-e2ee-lab.1`；Release 继续解析官方 `0.105.4`。Android Studio 普通 Build/Run 不再联合配置或编译 `../libsignal`，也无需额外 Gradle 参数。实验版 AAR 已用 `e2eeTrace=true` 编译，包含 arm64 JNI 与原生采集能力；原生 TLS 证书链校验也因此关闭，仅供测试账号使用。Debug 专用启动器会在 `Application.onCreate` 之前开启原始采集，覆盖首次注册请求。

首次使用或修改 libsignal 源码后，先在相邻源码工程执行 `RUSTUP_TOOLCHAIN=stable ./gradlew :android:publishToMavenLocal :client:publishToMavenLocal`（工作目录为 `../libsignal/java/`）。该工程的 `gradle.properties` 默认设置实验版本号、`e2eeTrace=true`、本机 NDK 27 和 arm64 ABI；`local.properties` 中的 `sdk.dir` 指向已安装的 Android SDK。发布后应在 `~/.m2/repository/org/signal/` 看到同版本的 AAR、JAR 和 Gradle module 元数据。Signal 的 `settings.gradle.kts` 已包含 `mavenLocal()`，版本固定在 `gradle/libs.versions.toml`。实验版依赖的 SHA-256 记录于 `gradle/verification-metadata.xml`；源码修改后应递增实验版本、重新发布、更新版本目录和校验值，避免用同一版本覆盖不同内容。

构建命令现在只是 `./gradlew :Signal-Android:assemblePlayProdDebug`。先安装 Debug APK 并打开应用。本次检查的 Play/Prod/Debug 合并清单包名为 `org.thoughtcrime.securesms`。下面使用实际安装包名替换 `PACKAGE`，使用 `adb devices` 中的模拟器编号替换 `emulator-5556`。脚本要求明确指定设备，不会自动选择连接的真实手机。

同步代理抓包：在电脑启动 mitmproxy，监听局域网可达的地址；把手机/模拟器的 Wi-Fi HTTP 代理设为该电脑的局域网 IP 与监听端口。Debug 清单允许 Android 网络栈信任用户安装的 mitmproxy CA，Signal 专用 Java TrustStore 在 Debug 下跳过服务端证书链校验，实验版 libsignal 的原生 TLS 也跳过该校验。Android 的 `ProxySelector` 会把系统 HTTP 代理传给 libsignal 的 HTTP CONNECT 路由；修改系统代理后重启应用，以免已有连接继续直连。抓包时确认 mitmproxy 中出现实际请求，而非仅看到 CONNECT。OkHttp 仍要求代理生成的证书主机名匹配目标域名。关闭代理后应恢复设备代理设置；此 Debug 包不验证服务端证书链，不能用于真实账号。

模拟器可先运行 `mitmdump --listen-host 127.0.0.1 --listen-port 8899 -w /tmp/signal-e2ee-test.flows --set ssl_insecure=true`，再执行 `adb -s emulator-5556 shell settings put global http_proxy 10.0.2.2:8899`；实机则让 mitmproxy 监听局域网地址，并在手机 Wi-Fi 设置中填写电脑 IP。本机复测中 mitmproxy 到 Signal 的上游证书链验证失败，因此示例加了 `ssl_insecure=true`；它只改变代理到上游的验证，与客户端 Debug 代码分开。结束后用 `adb -s emulator-5556 shell settings put global http_proxy :0` 清理模拟器代理。mitmproxy flow 文件本身含未脱敏凭据与正文，只存私有位置。

**实测限制**：mitmproxy 12.3.1 已接收 Debug libsignal 经 HTTP CONNECT 的连接和客户端 TLS，但解析 Signal 主聊天的 HTTP/2 Extended CONNECT WebSocket 时报告 `Invalid HTTP/2 request headers: Unknown pseudo headers: {b':protocol': b'websocket'}`，随后客户端回退直连。证书绕过无法修复代理自身的协议解析；不能把此时 mitmproxy 中没有聊天 flow 解释为客户端未发请求。Java 普通 HTTPS 可作为代理辅助证据，主聊天与密钥透明性等原生路径以 `-native.jsonl` 的请求/响应和 gRPC 帧为准。若用忽略该域名的穿透方式维持连接，mitmproxy 也不会得到该域名的明文。

一轮 01–08 测试只需确保两台设备都安装这个 Debug 包，然后从欢迎页开始连续操作。**测试中无需和采集者逐步交互，也无需运行 `start`/`step`**。在第一次启动时，每台设备各自创建一个 `auto_...` runId；实机标记为 `primary`，模拟器标记为 `linked`，所有未人工标记的事件都用 `stepId=full_run`。文档整理时按时间、设备、请求路径和响应关联还原幕次；若幕次边界仅能根据操作时间推断，应在文档中注明。进程重启只增加一个文件前缀，不覆盖已有片段。

测试前可一次性确认两台设备都返回 `enabled=true autoEnabled=true autoError=null`；之后无需再操作采集器。测试完成后通知采集者，由其对两台设备分别执行 `stop` 和无 `--prefix` 的 `export`，导出该设备保留的全部分片：

```bash
python3 tools/e2ee_trace.py --serial SERIAL --package PACKAGE status
python3 tools/e2ee_trace.py --serial SERIAL --package PACKAGE stop
python3 tools/e2ee_trace.py --serial SERIAL --package PACKAGE export --output PRIVATE_OUTPUT_DIRECTORY --run-id RUN_ID_FROM_STATUS
```

`status` 返回该设备的 `runId`。`export --run-id` 会把该 run 的所有进程片段和 16 MiB 分片一起导出，避开旧测试文件。`stop` 会持久关闭自动续采；如要同一次安装上再做新一轮，可显式 `start --run-id NEW_RUN --device primary|linked --step full_run`，新会话也会跨进程续采。**不要在导出前卸载应用或清除数据**，否则私有 JSONL 会丢失。工具不自动操作账号或截屏；现有日志足以做接口字段级分析，界面截图若未当时保存，不能从日志重建。mitmproxy 是可选的独立辅助来源，必须在测试前单独配置并运行；应用内 JSONL 不依赖它。

Logcat 使用 `E2EE_TRACE` Tag，仅输出操作名、调用编号与状态摘要。详细字段写入应用私有 `no_backup/e2ee-trace/`，不接入 Signal 常规持久化日志或日志上传。同一前缀的 `-native.jsonl` 是 libsignal 原生侧文件，`export` 会连同 Android 分片一起导出；原生 `callId` 用来关联请求与返回。广播接收器声明在 `src/debug`，由系统 `DUMP` 权限限制，普通第三方应用不能调用。控制命令成功的广播结果码为 `-1`，失败为 `0`。

## 2. 如何关联

### PNI → ACI 会话选择补采（2026-10-29）

新增观测不改变身份选择、验签和会话建立逻辑。`outbound_message_pre_encrypt` 新增 `recipientAci`、`recipientPni`、`localIdentityDomain`，与最终 `destination` 对照。当前 MessageService 的 `identity_session_selected` 记录本机身份公钥、远端地址/设备、`messageTimestamp` 和 `sessionPresentBefore`；`identity_session_initialized` 记录目标、远端身份公钥和 `sessionPresentAfter`；`identity_session_encrypted` 记录加密已返回及密文类型。初始化事件不含消息时间戳，应与同端目标、设备、真实预密钥请求及事件顺序一起关联，不能仅靠时间相近认定因果。

`MessageDecryptor` 新增 `inbound_identity_store_selected` 和 `inbound_identity_decrypt_completed`，按信封 `clientTimestamp` 关联目标身份域与解密结果。`hasResult` 不表示消息已提交入库。`pni_aci_association_received/verified/applied/skipped` 记录关联声明、双方身份公钥及签名、验签结果、联系人更新返回或跳过原因。`applied.transactionCommitted=false` 明确它仍处于调用者事务内，不能作为持久化提交成功的证据。所有新增日志沿用已有 Debug sink；本轮为后续重测保留，无 sink 时不采集，没有增加私钥字段。

重新测试时两端安装更新 Debug 包，从未建立该联系人会话的环境开始，号码搜索后连续双向收发。查询阶段若已获知 ACI，直接走 ACI 是合法结果，不保证触发 PNI 入口或 alternate-identity 签名分支。只有同一发送端的地址选择、对应密钥请求、会话建立、后续地址选择均有记录时，才整理为完整切换实测。现有 R010 和 R012 方向相反，不能拼成同一发送端的切换证明。

| 字段 | 含义与边界 |
| --- | --- |
| runId / device / stepId | 人工设置的本次实验、设备别名与步骤。仅允许 ASCII 字母、数字、下划线、短横线 |
| callId | 同一进程内一次观测调用的 UUID；用它匹配 request、response/result/failure |
| protocolRequestId | 已存在的外层 WS 请求编号。不要当作所有网络链路通用的追踪编号 |
| localAdapterHandle | 入站适配器生成的本地句柄，不是服务端提供的真实 wire ID |
| timestampMs | 本机墙上时间，用于和截图对齐；模拟器时间可能有偏差 |
| monotonicNs / durationMs | 本进程内的时序和耗时，不能直接跨设备比较 |
| layer | http、websocket_request、websocket_inbound 或 client_api；部分独立生命周期 event 没有 layer |

请求开始时固定步骤。默认全程是 `full_run`，若人工切换步骤，已经开始的请求的返回仍归入原步骤。异常类型与原始错误文本都会保存。停止后仍在运行的请求可能没有结尾，`trace_stopped` 明确标记这一点。

原生采集目前在调用线程写文件并刷新，因此采集状态下的耗时可能含磁盘开销。文档只用时间戳排列证据，不把该耗时当成正式包的性能数据；跨层关联若没有共同 `callId`，必须注明是依据路径和时间的推断。

## 3. 已接入范围

| 入口 | 能看到的内容 | 不能据此断言的内容 |
| --- | --- | --- |
| 使用 SignalServiceNetworkAccess 公共配置的 OkHttp 客户端，包括 SignalRestClient | method、host、原值 path/headers/headerLines、实际写出的请求体与实际读到的响应体、真实 HTTP status | 不覆盖绕过该配置独立创建的全部客户端；不展开 OkHttp 内部每次重定向或重试 |
| SignalWebSocket 普通/超时/挂起请求 | method、原值 path/headerLines、外层 request ID、请求/响应原始字节和协议 status | 不宣称观察到 native 内部的 HTTP/2 帧或 TLS 原文 |
| ProvisioningSocket 扫码配置连接 | 打开、入站请求编号/路径、密文原始字节、ACK 是否进入发送队列、临时地址与临时公钥；主设备另记加密前 ProvisionMessage 字段，次设备另记解密后的相同字段 | ACK 入队不等于对端收到；不宣称能看到 native 未暴露的服务器内部状态 |
| LinkDeviceApi 的 native getDevices / removeDevice / setDeviceName | 调用参数、设备列表公开属性和 native RequestResult 分类 | 这是 API 层结果，不是自行还原的服务器 JSON；native 内部重试不可见 |
| MessageApiV2 native 单设备/同步/密封发送 | 真实目标、deviceId、registrationId、逐设备密文原始字节、结果分类；本地发送作业另记明文、messageId 与同步副本起点 | 不把 API success 当作收件人解密成功或已读 |
| WebSocket 收到 Envelope | 真实来源、来源 deviceId、时间、密文原始字节；解密后另记 Content 字节及常用字段 | 信封收到不等于正文解密和入库完成 |
| libsignal 原生 HTTP/2 边界 | 实际路由 URI、method、完整 header 值的 Base64、响应 status/header；`native_h2_request/response` 使用 `callId` 配对 | 此边界自身不消费 body，事件明确记录 `bodyObserved=false`；不能把 HTTP/2 帧或 TLS 原文当作已采集 |
| libsignal 原生 gRPC 帧 | `native_grpc_request/response` 的 path、header、status；按 `callId`、`direction`、`frameIndex` 保存每一帧的 Base64 数据或 trailers，`native_grpc_body_end.complete` 表明该方向是否完整读到结尾；本地 request extension 把同一个 `callId` 带到 HTTP/2 边界 | 这是 gRPC body 的原始字节片段，需按序拼接、解析 5 字节 gRPC 包头并按对应 `.proto` 解码；帧边界不等于 protobuf 字段边界。本地 extension 不进入发往服务端的 header |
| KeyTransparencyApi.check 与 libsignal 原生层 | Java 调用参数与结果、原生 KT gRPC SearchV2/DistinguishedV2/MonitorV2 请求/响应 protobuf Base64 原文、结构 Debug 表示、透明性证明输入及本地验真结果；人工安全码显示值和标记动作另记 | typed protobuf、gRPC 帧与 HTTP/2 边界以本地 `callId` 关联；证明输入的 `Debug` 表示用于阅读，protobuf 原文才是逐字节证据 |

`client_api` 的 `result.decodedResult.kind` 区分 success、non_success、retryable_network_error、application_error。未知 native 对象只记录类型及 `decoded=false`：这是接口未暴露字段，并非脱敏。要得到更多字段，须在有类型信息的调用边界显式映射；不可把猜测值写入文档。

## 4. 原值与完整性

- 路径、查询参数、请求头及已解码字段保持捕获原值。字节字段与原始 body 使用 Base64 保存；`decoded` 只在完整 body 可按 JSON 解析时填写，否则为 `null`，不能把它误当作空响应。
- 每个 HTTP body 最多复制 16 MiB，记录 `observedBytes`、`capturedBytes`、`truncated`、`complete`。核心 JSON/protobuf 请求通常不触及该上限；若出现 `truncated=true`，该请求不可作“所有字段齐全”的证据。响应随调用方读取而复制，不预读响应、不再次调用一次性请求体。提前关闭标为 `complete=false`。
- Android 写入队列最多 4096 条，满时丢弃观测记录，不阻塞网络等待磁盘；`status/stop` 返回 dropped、failed，原生文件写入状态另见 `nativeFailed`。正常停止后的 Android `capture_summary` 也记录 dropped。进程被杀时可能缺少 summary，不能视为零丢失。
- 每次采集按约 16 MiB 分片，不自动删除旧分片。原值采集可能快速占用设备空间；一轮完成后停止、导出，检查每个进程片段的 `capture_summary` 与 `dropped=0`、`failed=false`。停止后等待写入最多 5 秒；`flushed=false` 时应稍后导出。异常退出的片段可能没有 summary，需结合下一片段和运行期状态审查。卸载或清除应用数据会删除尚未导出的 JSONL。

## 5. 使用原生 ADB

脚本只封装这些命令，没有另外的后台服务：

```bash
adb -s SERIAL shell am broadcast -n PACKAGE/org.thoughtcrime.securesms.debugtrace.TraceControlReceiver --es command start --es runId link-001 --es device primary --es stepId scan-qr
adb -s SERIAL shell am broadcast -n PACKAGE/org.thoughtcrime.securesms.debugtrace.TraceControlReceiver --es command stop
adb -s SERIAL shell run-as PACKAGE ls no_backup/e2ee-trace
adb -s SERIAL exec-out run-as PACKAGE cat no_backup/e2ee-trace/FILE.jsonl
adb -s SERIAL logcat -s E2EE_TRACE
```

发布包不包含这个接收器、文件写入器或启用入口。底层观测调用在未安装 sink 时不采集。上线前仍应按团队发布流程检查构建变体与合并后的 Manifest。

当前联调验证：本机 Maven 实验版的 arm64 JNI 已打入 Debug APK，并原位安装于实机和 `emulator-5556`；两端首次启动自动得到 Android 分片及 `-native.jsonl`，`status` 为 `enabled=true autoEnabled=true autoError=null`、`dropped=0`、`failed=false`、`nativeFailed=false`。模拟器 `force-stop` 后再次启动，runId 保持不变、文件前缀更新；`stop` 后再次启动没有恢复采集。此前已观测 `native_h2_request/response` 的 `callId` 配对、method/URI/headers/status。KT 三种 gRPC 查询及证明验真需要第 05 幕实际操作后检查，不能把编译通过当作运行期已观测。

## 6. 01–08 重测顺序

主手机和模拟器安装 Debug 包后即可依次完成全部操作；欢迎页首次启动即自动开始记录，次设备即使到第 06 幕才打开，也会在首次启动时自动开始。无需在每幕前设置 `step`。建议沿用下表顺序，并使用预先约定的测试文本，以便从日志中辨认阶段；时间戳只能帮助定位，不能单独证明跨设备因果关系。

| stepId | 观察操作 |
| --- | --- |
| `01-registration` | 手机号、验证码、PIN/资料与首次进入聊天列表；不要在收到验证码前才启动采集 |
| `02-contact` | 打开测试联系人，加载资料、身份与预密钥 |
| `03-safety-number` | 双方显示完整安全码、核对、仅主手机人工标记 |
| `04-first-messages` | 主手机和联系人按约定文本双向发送 |
| `05-automatic-verification` | 打开自动验证并等待最终状态 |
| `06-link-device` | 次设备出示二维码、主手机扫码批准、次设备完成配置 |
| `07-dual-delivery` | 双设备收发、同步副本、次设备离线及上线补收 |
| `08-unlink` | 主手机移除次设备，之后再测试联系人新消息的接收边界 |

完成后两台设备各自 `stop` 并 `export`，核对 `capture_summary.dropped=0`、`failed=false`，以及关键 body 的 `complete=true`、`truncated=false`，并确认存在对应 `-native.jsonl`。原始 JSONL 包含可登录或解密测试账号的材料；保存于非公开位置，整理文档时以已解码字段做字段路径说明，原始 Base64 作为可复核证据。未插桩的原生请求与无法从已捕获原文重建的证明字段仍须标为“未捕获”，不能补写猜测值。

## 7. 下一轮八幕一次性重测与截图

上一轮 B04 的第 03 幕没有实际点击标记的事件，第 07、08 幕在停止采集后才操作，且只有旧批次的界面截图。下一轮从卸载前启动电脑端截图观察器，贯穿安装、注册、关联、双端消息、移除；应用内 JSONL 仍由 Debug 包首次启动自动采集。截图观察器只在指定 Signal 包位于前台时截图，按 PNG 内容去重；每张图和网络默认路由和系统默认网络变化写入带毫秒时间戳的私有 `screenshots.jsonl`。它不执行手机点击，也不替代应用内请求/响应日志。主手机不能 ADB 注入点击时，人工照常操作即可。

```bash
python3 tools/e2ee_lab_capture.py \
  --device primary=PRIMARY_SERIAL --device linked=LINKED_SERIAL \
  --package org.thoughtcrime.securesms \
  --output PRIVATE_OUTPUT/screenshots-run
```

在独立终端启动后再卸载并重装刚编译的 `Signal-Android-play-prod-universal-debug-8.33.1.apk`，两端安装同一 APK；用户可以不与采集者逐幕交互。若两端系统时钟不同步，同时运行 `python3 tools/e2ee_lab_clock_sync.py --device primary=PRIMARY_SERIAL --device linked=LINKED_SERIAL --output PRIVATE_OUTPUT/clock-sync.jsonl`，周期性记录相对电脑的时钟偏差与采样误差。跨设备时序须先校正时间，再用请求关联 ID 和消息标记确认因果。完成第 08 幕后用 Ctrl-C 停止截图观察器，再分别执行 `e2ee_trace.py status`、`stop`、`export --run-id`。必须先导出再清除应用。`PRIVATE_OUTPUT` 需位于公开文档目录以外，原始截图可能含号码、安全码、联系人和消息正文，不自动复制到局域网页面。观察器若断开或达到默认 2048 MiB 上限，会在 manifest 中留下错误或 `size_limit` 事件；不可把缺图解释为该页面没有出现。

测试消息使用固定文本，便于从明文事件、密文提交与双端解密记录中无歧义地对照：

| 幕次 | 一轮连续操作与文本标记 |
| --- | --- |
| 01 | 主手机卸载、重装，从欢迎页重新用手机号登录；完成图形验证、短信码、PIN/资料，进入聊天列表。 |
| 02 | 打开测试联系人资料及会话，等待资料/身份请求；不要仅凭显示昵称判定身份。 |
| 03 | 双方打开安全码、完整核对；主手机点击“标记为已验证”，等待本地保存。此时若尚无关联设备，消息式同步作业可能跳过；后续新设备应从加密存储取回该状态。 |
| 04 | 主手机向联系人发送 `E2EE-R3-P-001`；联系人回复 `E2EE-R3-B-001`，主手机确认收到。 |
| 05 | 在安全码页运行自动验证并等到明确结果，保留结果页一段时间供截图。 |
| 06 | 模拟器出示二维码，主手机扫码批准；确认模拟器成为关联设备、双方设备列表更新。二维码只留在私有截图，不发布其临时地址或公钥。 |
| 07 | 先在主手机对该联系人的安全码执行一次“撤回验证 → 再标记已验证”，等待关联设备收到验证同步及存储同步；然后联系人发送 `E2EE-R3-B-002`，确认主手机与模拟器都收到；模拟器发送 `E2EE-R3-L-001`。模拟器断网后联系人发送 `E2EE-R3-B-003`，确认主手机收到；模拟器恢复网络后确认补收。每个关键页面停留至少 5 秒。 |
| 08 | 主手机移除这个模拟器，确认设备列表；联系人发送 `E2EE-R3-B-004`，确认主手机收到，并观察模拟器无法继续接收新消息。截图观察器与两端应用内 trace 在此之后才停止。 |

导出后先运行下列审计，缺项就保留原始数据并记录为缺口，不靠猜测补写：

本次新包还会输出 `aci_identity_key_selected` / `aci_identity_key_restored` / `aci_identity_key_registration_commit`，说明 ACI 身份钥是本地新生成、复用还是在注册提交时恢复；`contact_profile_key_observed` 标出联系人 Profile Key 来自 DataMessage、群组 V2 或 Storage Service；`safety_number_automatic_inputs` 记录自动验证实际使用的 ACI、E164、身份公钥、Profile Key 及由它派生的 unidentifiedAccessKey。以上原始字段只保存在私有 JSONL，不直接发布到局域网文档。由于 Profile Key 可能在开始采集前就已存在，审计缺少来源事件时仍要标记为证据缺口，不能推断来源。

```bash
python3 tools/e2ee_lab_audit.py PRIVATE_OUTPUT/jsonl \
  --screenshots PRIVATE_OUTPUT/screenshots-run/screenshots.jsonl \
  --marker-prefix E2EE-R3
```

审计只输出事件数量和缺失项，不打印原始字段。它检查标记保存、KT 证明、关联 200、双端新消息、移除后的主设备新消息与两端截图。`08_no_linked_decryption_observed` 只表示采集期未看到指定消息在旧设备解密；要判断旧设备确实失去资格，还需把在线状态、采集持续性、设备列表、服务端响应与联系人发送结果一起核对。B04 生成器绑定了旧 runId 和时间边界；新一轮应另建批次输入与幕次边界，不能直接覆盖 B04 原始页面。
