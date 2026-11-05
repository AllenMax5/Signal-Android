/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.debugtrace

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import org.signal.network.trace.NetworkTrace
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Shell-only control entry point. The debug initializer starts capture before app startup. */
class TraceControlReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    Thread({
      try {
        pending.resultData = DebugTraceController.command(context.applicationContext, intent)
        pending.resultCode = Activity.RESULT_OK
      } catch (e: Throwable) {
        pending.resultCode = Activity.RESULT_CANCELED
        pending.resultData = "Trace control failed: ${e.javaClass.simpleName}"
      } finally {
        pending.finish()
      }
    }, "e2ee-trace-control").start()
  }

}

/** A persisted debug session lets one uninterrupted experiment survive Android process restarts. */
internal object DebugTraceController {
  private data class CaptureState(val runId: String, val device: String, val step: String, val enabled: Boolean)

  private var sink: DebugTraceSink? = null
  private var filePrefix: String? = null
  private var lastAutoError: String? = null

  @Synchronized
  fun autoStart(context: Context) {
    if (sink != null) return
    try {
      val directory = directory(context)
      val state = readState(directory) ?: CaptureState(
        runId = "auto_${System.currentTimeMillis()}",
        device = if (Build.FINGERPRINT.contains("generic", ignoreCase = true) || Build.MODEL.startsWith("sdk_")) "linked" else "primary",
        step = "full_run",
        enabled = true
      ).also { writeState(directory, it) }
      if (state.enabled) begin(context, state)
      lastAutoError = null
    } catch (e: Throwable) {
      // 采集故障不能阻断 Debug 应用启动；status 会报告失败，便于测试前发现。
      lastAutoError = e.javaClass.simpleName
      Log.w("E2EE_TRACE", "Auto capture failed: ${e.javaClass.simpleName}")
    }
  }

  @Synchronized
  fun command(context: Context, intent: Intent): String = when (intent.getStringExtra("command")) {
      "start" -> {
        val runId = label(intent, "runId")
        val device = label(intent, "device")
        val step = intent.getStringExtra("stepId")?.also(::validate) ?: "unassigned"
        check(sink == null) { "Stop the existing capture before starting another" }
        val state = CaptureState(runId, device, step, true)
        writeState(directory(context), state)
        begin(context, state)
        "started prefix=$filePrefix directory=no_backup/e2ee-trace"
      }
      "step" -> {
        check(sink != null) { "No active capture" }
        val step = label(intent, "stepId")
        val directory = directory(context)
        val state = requireNotNull(readState(directory))
        writeState(directory, state.copy(step = step))
        NetworkTrace.step(step)
        NativeTraceBridge.step(step)
        "step=$step prefix=$filePrefix"
      }
      "stop" -> {
        val directory = directory(context)
        readState(directory)?.let { writeState(directory, it.copy(enabled = false)) }
        NetworkTrace.stop()
        NativeTraceBridge.stop()
        val previous = sink
        sink = null
        previous?.close()
        val flushed = previous?.awaitClosed(5, TimeUnit.SECONDS) ?: true
        "stopped prefix=$filePrefix flushed=$flushed dropped=${previous?.droppedCount ?: 0} failed=${previous?.hasFailed ?: false} nativeFailed=${NativeTraceBridge.hasFailed()}"
      }
      "status" -> "enabled=${NetworkTrace.enabled} autoEnabled=${readState(directory(context))?.enabled ?: false} runId=${readState(directory(context))?.runId} prefix=$filePrefix dropped=${sink?.droppedCount ?: 0} failed=${sink?.hasFailed ?: false} nativeFailed=${NativeTraceBridge.hasFailed()} autoError=$lastAutoError"
      else -> "commands: start, step, stop, status"
    }

  private fun begin(context: Context, state: CaptureState) {
    validate(state.runId)
    validate(state.device)
    validate(state.step)
    // 每次进程启动使用新文件前缀，同一 runId 贯穿整轮；避免进程异常退出后覆盖旧文件。
    val prefix = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
    val directory = directory(context)
    check(directory.isDirectory || directory.mkdirs())
    val nativeFile = File(directory, "$prefix-native.jsonl")
    // 原生与 Android 使用同一轮次；原生写独立文件，避免跨语言并发写同一 JSONL。
    check(NativeTraceBridge.start(nativeFile.canonicalPath, state.runId, state.device, state.step)) { "Native trace startup failed" }
    check(nativeFile.isFile) { "Native trace unavailable; build libsignal with e2eeTrace" }
    val created = DebugTraceSink(directory, prefix, state.runId, state.device)
    sink = created
    filePrefix = prefix
    NetworkTrace.install(created, state.runId, state.step, state.device)
    Log.v("E2EE_TRACE", "Capture started run=${state.runId} prefix=$prefix")
  }

  private fun directory(context: Context) = File(context.noBackupFilesDir, "e2ee-trace")

  private fun readState(directory: File): CaptureState? {
    val file = File(directory, "auto-session.properties")
    val atomic = AtomicFile(file)
    if (!file.isFile && !File(directory, "auto-session.properties.bak").isFile) return null
    // 与 AtomicFile 写入配对读取，异常断电后恢复旧状态，避免把半写文件当成关闭采集。
    val values = Properties().apply { atomic.openRead().use(::load) }
    return CaptureState(
      runId = requireNotNull(values.getProperty("runId")),
      device = requireNotNull(values.getProperty("device")),
      step = requireNotNull(values.getProperty("step")),
      enabled = values.getProperty("enabled") == "true"
    )
  }

  private fun writeState(directory: File, state: CaptureState) {
    check(directory.isDirectory || directory.mkdirs())
    val file = AtomicFile(File(directory, "auto-session.properties"))
    val output = file.startWrite()
    try {
      Properties().apply {
        setProperty("runId", state.runId)
        setProperty("device", state.device)
        setProperty("step", state.step)
        setProperty("enabled", state.enabled.toString())
      }.store(output, null)
      file.finishWrite(output)
    } catch (e: Throwable) {
      file.failWrite(output)
      throw e
    }
  }

  private fun label(intent: Intent, name: String): String = requireNotNull(intent.getStringExtra(name)).also(::validate)
  private fun validate(value: String) = require(value.matches(Regex("[A-Za-z0-9_-]{1,64}")))
}

/** The published libsignal artifact does not contain this optional source-build hook. */
private object NativeTraceBridge {
  private const val CLASS_NAME = "org.signal.libsignal.internal.E2eeTraceNative"
  private val nativeClass: Class<*>? by lazy { runCatching { Class.forName(CLASS_NAME) }.getOrNull() }

  fun start(path: String, runId: String, device: String, stepId: String): Boolean =
    call("start", arrayOf(String::class.java, String::class.java, String::class.java, String::class.java), path, runId, device, stepId) as Boolean

  fun step(stepId: String) {
    call("step", arrayOf(String::class.java), stepId)
  }

  fun stop() {
    if (nativeClass != null) call("stop", emptyArray())
  }

  fun hasFailed(): Boolean = nativeClass?.let { call("hasFailed", emptyArray()) as Boolean } ?: false

  private fun call(name: String, parameterTypes: Array<Class<*>>, vararg arguments: Any): Any? {
    // Studio's ordinary Maven dependency has no native trace hook; resolve it only when capture starts.
    val klass = nativeClass ?: error("Native trace unavailable; build libsignal from source with -Pe2eeTrace=true")
    return klass.getMethod(name, *parameterTypes).invoke(null, *arguments)
  }
}
