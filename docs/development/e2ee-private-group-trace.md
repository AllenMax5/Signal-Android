# 私密群 A/B 独立账号测试：请求日志与解析准备

本轮仅准备客户端源码观测与离线解析。真实手机登录账号 A，模拟器登录**独立账号 B**，两者 ACI 应不同；模拟器不是 A 的关联设备。已有多设备实验原始 JSONL、截图、解码结果和文档全部保留，本轮使用新的 runId 和新目录，不复用旧文档生成器的输入。没有执行账号登录、建群或实测，也没有更新公开文档。

## 采集前

沿用 [e2ee-trace.md](e2ee-trace.md) 中 Debug 构建与自动采集机制。构建：

```bash
./gradlew :Signal-Android:assemblePlayProdDebug
```

若当前安装仍有旧实验日志，先 `status`，记下旧 RUN_ID，执行 `stop` 并 `export --run-id OLD_RUN_ID --output NEW_PRIVATE_ARCHIVE_DIRECTORY`；每台设备独立执行，确认导出成功后才处理安装与账号切换。不得在导出前卸载或清除数据。此次源码修改不会删除任何旧日志，export 仍以排他创建方式拒绝覆盖同名文件。

调试包原样采集测试材料，包含授权头、Profile Key、群 Master Key 和消息明文。输出仅放在私有目录，例如 `~/signal-private-captures/`，不放入静态网站根目录。本次没有改变既有 Debug TLS 配置、密码协议或生产构建策略。

## 一次性建立新批次

自动采集沿用旧实验的 `primary/linked` 默认标签，不能据此判断账号或设备关系。本轮应在操作账号之前显式切换为同一个新 runId 下的 `accountA/accountB`。如果自动采集已开启，先按前述方式归档并停止，再 start。下面的 SERIAL 和 PACKAGE 均需替换为实际值；不要复制旧 runId。

```bash
python3 tools/e2ee_trace.py --serial PHONE_SERIAL --package PACKAGE start --run-id private_group_20261102_01 --device accountA --step full_run
python3 tools/e2ee_trace.py --serial EMULATOR_SERIAL --package PACKAGE start --run-id private_group_20261102_01 --device accountB --step full_run
```

之后连续操作即可，进程重启会续采。无需每步调用工具：

1. A 在真实手机完成账号登录/注册，B 在模拟器以自己的号码完成独立注册。若 A 已经登录且本轮没有重新登录，日志只能证明后续流程，不能补称捕获了登录。
2. A 创建仅含自己的私密群，建议名称 `E2EE-GROUP-AB-01`，暂不选 B。
3. A 添加 B，等待 B 收到群更新。若 B 没有可用 ProfileKeyCredential，可能先进入 pending 邀请流程，需要按界面完成接受；以请求与最终群状态为准。
4. A 发送固定文本 `E2EE-GROUP-AB-001`，B 确认收到。保留两端完整记录，网络成功不等于 B 解密或入库。

若应用 UI 不支持仅创建者的群，不改协议绕过此限制，记录实际 UI 结果，再决定是否需要单独的测试入口。源码的 createNewGroup 支持额外成员集合为空；这不等于所有 UI 都暴露了该入口。

结束后分别 `status`、`stop`，记录 dropped/failed/nativeFailed/flushed，再分别导出到新目录：

```bash
python3 tools/e2ee_trace.py --serial PHONE_SERIAL --package PACKAGE export --run-id private_group_20261102_01 --output ~/signal-private-captures/private_group_20261102_01/accountA
python3 tools/e2ee_trace.py --serial EMULATOR_SERIAL --package PACKAGE export --run-id private_group_20261102_01 --output ~/signal-private-captures/private_group_20261102_01/accountB
python3 tools/e2ee_group_decode.py ~/signal-private-captures/private_group_20261102_01/accountA ~/signal-private-captures/private_group_20261102_01/accountB --output ~/signal-private-captures/private_group_20261102_01/decoded
```

解析要求安装 `protoc`，不需要 Python 第三方包。只读取输入，新建输出目录，拒绝覆盖。`decoded.jsonl` 保留来源文件、行号、runId、device、callId、原始字段和按 schema 解码的 protobuf 文本；`manifest.json` 保存输入与 schema 的 SHA-256、protoc 版本、解码错误/截断计数。使用**此次构建的源码 checkout**解析，避免以后 schema 更新带来歧义。客户端和原生层 JSONL 都应保留，原生未覆盖的 body 不补造。

## 本轮新增证据

| 事件/位置 | 可回答的问题 | 证据边界 |
| --- | --- | --- |
| `profile_commitment_pre_upload` | 哪个 ACI/version 登记了哪份 commitment | 本地上传前材料；网络请求和响应另行核对；Profile Key 没有明文上传 |
| `profile_credential_request_created` | credentialRequest 由客户端生成，针对哪个 ACI/version、与什么 commitment 对应 | 只记录公开请求和本地 commitment，不记录临时 request context 私密材料；联系人 Profile Key 需已知 |
| `profile_credential_verified` | 客户端是否成功验证发行响应并得到 credential | 不是群服务器验证 presentation 的证据；缓存命中或响应不含凭证时可能没有该事件 |
| `group_secret_params_created` | 建群时群标识、公参与 Master Key、额外成员数 | Master Key 为本地秘密，不是给群服务器的请求字段 |
| `group_profile_presentation_created` | 成员资格展示携带哪些 UUID/ProfileKey 密文及角色 | 不解析或猜测 ZK proof 内部标量；serverVerificationObserved=false |
| `group_auth_credential_verified` / `group_auth_presentation_created` | 身份凭证在本地验真后如何生成群作用域展示 | HTTP Authorization 中承载公参和展示；本地事件不声称观测了服务器内部验真 |
| `group_create_request` / `group_state_response` | PUT/GET `/v2/groups/` 的 Group/GroupResponse 字段 | 类型来自实际序列化边界，真实 HTTP status/callId 以网络日志为准 |
| `group_patch_request` / `group_patch_response` / `group_history_response` | PATCH 新 revision、成员变更、签名和历史 | 解析 GroupChange 内嵌 actions；失败 HTTP body 不强行按成功 schema 解码 |
| `group_state_decrypted` / `group_change_decrypted` | 客户端如何得到明文成员列表和 Profile Key | 本地明文，带 groupIdentifier，不能写成群服务器可见数据 |
| `group_change_validation` | 客户端本次是否实际请求验签并检查群绑定 | false 表示本次路径跳过，不等于验证失败；只在通过该阶段后记录 |
| `group_send_path_selected` | 正文计划走 Sender Key 还是 pairwise，目标与消息时间戳 | 是初始路由选择；后续失败/回退以实际加密与发送日志为准 |
| `pairwise_content_pre_encrypt` / `group_sender_key_content_pre_encrypt` | E2EE 内部 Content 的 groupV2 Master Key、revision、正文/群更新 | 本地加密前明文；不可称为服务端读取了这些字段 |
| 既有 `inbound_message_decrypted` | B 解密后的 Content、群上下文及 SKDM | 仅证明解密返回，不能单独证明成员校验和消息提交成功 |
| `group_message_preprocessing_completed` / `group_message_insert_returned` | 解密后群状态与成员检查是否返回 IGNORE，以及是否得到本地插入结果 | transactionCommitted=false；空 messageId 可能是群更新/无正文，不能直接算作失败 |

既有注册、预密钥、逐设备消息 API、入站信封和原生网络采集继续工作。新增 GroupTrace 在没有 Debug sink 时不执行字段采集；Java 诊断 accessor 的异常会被隔离，不改变协议结果。

### 零知识证明需要说明的边界

群服务持有群公参、群内 UUID/ProfileKey 密文、角色、revision 等状态，并验证针对这些密文的资格证明。它仍能看到成员数量、角色和同一群内密文标识的关系；不能将“成员身份隐私”表述为“服务器完全看不到成员记录”。跨服务的流量时间等旁路关联也不由这里的证明消除。

认证凭证发行发生在账号身份域，随后在群域提交展示；ProfileKeyCredential 的盲发行和展示证明把“已获服务认证的身份/资料钥匙”和“本群密文”绑定。群秘密由成员通过 E2EE 消息共享，拥有群秘密的客户端能解开成员身份与资料钥匙。日志必须把网络密文与本地明文分别展示。

两人 A/B 群通常只有一个其他目标账号；在当前默认最小目标数策略下可能使用 pairwise fan-out，而非 Sender Key 正文。私密群状态、零知识证明和角色验证仍照常使用。此次不增加 C，也不改内部开关强制 Sender Key；最终文档根据实际路径写。

跨层没有共同 callId 的事件，以原始字节 SHA-256、groupIdentifier/公参、revision、账号和消息时间戳共同关联，不能单凭时间邻近认定因果。HTTP 200 是请求被接受的证据，不是服务器内部证明验证实现的直接观测。旧批次不合并成新实验，缺失事件、异常退出无 summary、队列丢弃与截断都应保留为缺口。
