# 2026-11-05：升级后的采集补丁恢复

## 恢复来源与备份

Android Studio 更新前已自动 Shelf；并非所有未提交改动已被永久删除。

- `.idea/shelf/Uncommitted_changes_before_Update_at_2026_11_5_09_07_[Changes]/shelved.patch`：30 个文件的原始补丁。
- 同名带 `1` 的目录：更新后仍保留的 3 个文件的补丁。
- 恢复前备份：`/Users/allen/signal-source-backups/pre-trace-restore-20261105-092951/`，含 Android 工作区补丁、未跟踪文件、Shelf 原件、libsignal 原工作区补丁与未跟踪文件。
- 上游 Android HEAD：`6cafad327d`；不回退到旧版 `16f138410a`。

## 适配点

- 原 `MessageService` 内的会话选择、初始化和加密事件迁入新版 `IndividualMessageService`。
- 新版 `GroupMessageService` 增加同名选路事件，保留实际 Sender Key 与 fan-out 目标及实现名。
- `MultiDeviceVerifiedUpdateJob.java` 已被上游改成 Kotlin；事件恢复到新 CoroutineJob 的实际开始、跳过、提交成功和失败边界。
- 恢复 HTTP、WebSocket、预配、资料、身份、安全码、多设备同步、消息明文及解密和入库边界。
- 保留上游新增的导入、设备恢复行为及新版 Kotlin/Gradle 配置。未原样覆盖旧配置文件，也未恢复旧 Java TrustManager 绕过补丁。

## 与新版依赖对齐

Android Release 保持官方 libsignal `0.106.1`。Debug 使用本机 Maven 的 `0.106.1-e2ee-lab.1`。

为保留旧实验库，原 `/Users/allen/workspace/work/libsignal` 不改源码；从上游 `v0.106.1` 导出到 `/Users/allen/workspace/work/libsignal-e2ee-0.106.1`，将原 JNI、HTTP/2、gRPC、KT 采集补丁迁入后构建。`target` 复用原编译缓存，源码独立。实验库沿用原有仅用于测试的 `e2eeTrace` feature。

构建本机实验依赖：

```sh
cd /Users/allen/workspace/work/libsignal-e2ee-0.106.1
RUSTUP_TOOLCHAIN=stable ./java/gradlew -p java :android:publishToMavenLocal :client:publishToMavenLocal
```

构建 Android Debug：

```sh
cd /Users/allen/workspace/work/signal
./gradlew :Signal-Android:assemblePlayProdDebug
```

本机需要 JDK、Python 3.10+、已安装 Android target 的 Rust toolchain、NDK 与 Android SDK；`JAVA_HOME`、`PATH` 应指向这些运行时。实验依赖重新构建后必须核对并更新 Gradle 校验值，不能用原 0.105.4 的校验值代替。

## 历史数据

本次恢复不修改手机应用数据，不安装或卸载手机包，不启动新采集。多设备、A/B 私密群、三人群的历史 JSONL、截图和文档保持原样。

## 恢复验证结果

- 原始 44 个采集事件名全部恢复，覆盖表在备份目录的 `restored-event-coverage.json`。
- Python 采集工具测试：16 项通过。
- NetworkTrace / TraceHttpInterceptor 单元测试：8 项通过。
- libsignal 0.106.1-e2ee-lab.1：JNI 编译与本机 Maven 发布成功。
- `:lib:network:compileKotlin` 与完整 `:Signal-Android:assemblePlayProdDebug` 均通过。
- APK 内已验证自动采集入口、控制接收器、Java/JNI 桥接和关键事件；其中 libsignal_jni.so 与新实验 AAR 的 SHA-256 一致。
- 本机实验依赖只含 arm64 JNI；使用 arm64 包。其他 ABI 的分包不能据此宣称支持原生采集。
- 尚未安装到手机或重新执行真实账号采集；编译与静态包核验不等于运行时全链路验证。

APK：`app/build/outputs/apk/playProd/debug/Signal-Android-play-prod-arm64-v8a-debug-8.35.0.apk`。
