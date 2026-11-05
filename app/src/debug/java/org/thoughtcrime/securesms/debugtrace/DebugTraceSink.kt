/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.debugtrace

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.signal.network.trace.NetworkTrace
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Asynchronous raw JSONL writer, separate from Signal's persistent/uploadable logs. */
internal class DebugTraceSink(
  private val directory: File,
  private val prefix: String,
  private val runId: String,
  private val device: String
) : NetworkTrace.Sink, Closeable {
  private val dropped = AtomicLong()
  private val executor = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4096), { task ->
    Thread(task, "e2ee-trace-writer").apply { isDaemon = true }
  }) {
    override fun terminated() {
      // 即使控制端等待超时，也由写入线程在排空后落盘统计并关闭文件。
      runCatching {
        if (!failed) write("{\"schemaVersion\":2,\"captureMode\":\"raw\",\"kind\":\"capture_summary\",\"runId\":\"$runId\",\"device\":\"$device\",\"dropped\":${dropped.get()},\"failed\":$failed}\n")
      }.onFailure {
        failed = true
        Log.w("E2EE_TRACE", "Capture finalization failed: ${it.javaClass.simpleName}")
      }
      runCatching { writer?.close() }
    }
  }
  private var writer: BufferedWriter? = null
  private var part = 0
  private var bytes = 0L

  @Volatile private var closed = false

  @Volatile private var failed = false
  val droppedCount: Long get() = dropped.get()
  val hasFailed: Boolean get() = failed

  override fun offer(event: JsonElement) {
    if (closed || failed) return
    try {
      executor.execute {
        try {
          val line = event.toString() + "\n"
          write(line)
          val fields = event.jsonObject
          Log.v("E2EE_TRACE", "$device ${fields["kind"]?.jsonPrimitive?.content} call=${fields["callId"]} ${fields["operation"]?.jsonPrimitive?.content}")
        } catch (e: Exception) {
          failed = true
          runCatching { writer?.close() }
          Log.w("E2EE_TRACE", "Capture writer failed: ${e.javaClass.simpleName}")
        }
      }
    } catch (e: RejectedExecutionException) {
      val count = dropped.incrementAndGet()
      if (count and (count - 1) == 0L) Log.w("E2EE_TRACE", "Capture queue full or closing; dropped=$count")
    }
  }

  private fun write(line: String) {
    val length = line.toByteArray(Charsets.UTF_8).size
    if (writer == null || bytes + length > 16 * 1024 * 1024) {
      writer?.close()
      check(directory.isDirectory || directory.mkdirs())
      part++
      val file = File(directory, "$prefix-${part.toString().padStart(4, '0')}.jsonl")
      writer = file.bufferedWriter(Charsets.UTF_8)
      bytes = 0
    }
    writer!!.write(line)
    writer!!.flush()
    bytes += length
  }

  override fun close() {
    closed = true
    // 队列排空后关闭文件；不在网络线程或广播主线程等待磁盘写入。
    executor.shutdown()
  }

  fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
    val drained = executor.awaitTermination(timeout, unit)
    return drained
  }
}
