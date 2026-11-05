/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.network.trace

import kotlinx.serialization.json.JsonElement
import org.signal.libsignal.net.LinkedDevice
import org.signal.libsignal.net.MismatchedDeviceException
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.net.SingleOutboundMessage
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Inert unless the debug-only Android controller installs a sink. */
object NetworkTrace {
  fun interface Sink {
    /** Must be non-blocking. Failures must never change the observed request's outcome. */
    fun offer(event: JsonElement)
  }

  data class Context(val runId: String, val stepId: String, val device: String)
  private data class Session(val sink: Sink, val context: Context, val sanitizer: TraceSanitizer)

  @Volatile private var session: Session? = null
  val enabled: Boolean get() = session != null

  @JvmStatic
  fun install(sink: Sink, runId: String, stepId: String, device: String) {
    session = Session(sink, Context(runId, stepId, device), TraceSanitizer())
    event("trace_started", mapOf("captureMode" to "raw", "bodyLimitBytes" to TraceSanitizer.MAX_BODY_BYTES))
  }

  @JvmStatic
  @Synchronized
  fun step(stepId: String) {
    session = session?.let { it.copy(context = it.context.copy(stepId = stepId)) }
    event("step_marker", emptyMap())
  }

  @JvmStatic
  @Synchronized
  fun stop() {
    event("trace_stopped", mapOf("pendingResponsesMayBeIncomplete" to true))
    session = null
  }

  fun event(operation: String, fields: Map<String, Any?>) {
    val active = session ?: return
    emit(active, operation, "event", null, null, fields)
  }

  fun begin(layer: String, operation: String, parameters: () -> Map<String, Any?>): Call? {
    val active = session ?: return null
    return runCatching {
      Call.create(UUID.randomUUID().toString(), layer, operation, active.sink, active.context, active.sanitizer).also { it.write("request", parameters()) }
    }.getOrNull()
  }

  suspend fun <T> observe(operation: String, parameters: () -> Map<String, Any?>, block: suspend () -> T): T {
    val call = begin("client_api", operation, parameters)
    try {
      return block().also { call?.result(it) }
    } catch (t: Throwable) {
      call?.error(t)
      throw t
    }
  }

  fun body(bytes: ByteArray?, complete: Boolean = true, observedBytes: Long = bytes?.size?.toLong() ?: 0, contentType: String? = null): JsonElement? = session?.sanitizer?.body(bytes ?: byteArrayOf(), observedBytes, complete, contentType)

  fun path(path: String): String = session?.sanitizer?.path(path) ?: "[trace disabled]"
  fun headers(headers: Map<String, String>): Map<String, String> = session?.sanitizer?.headers(headers) ?: emptyMap()

  fun outboundMessages(contents: List<SingleOutboundMessage<*>>): List<Map<String, Any?>> = contents.map {
    mapOf(
      "deviceId" to it.deviceId,
      "registrationId" to it.registrationId,
      "encryptedPayload" to when (val message = it.message) {
        is ByteArray -> session?.sanitizer?.binary(message)
        is CiphertextMessage -> mapOf("type" to message.type, "serialized" to session?.sanitizer?.binary(message.serialize()))
        else -> mapOf("type" to message?.javaClass?.simpleName)
      }
    )
  }

  class Call private constructor(
    val id: String,
    private val layer: String,
    private val operation: String,
    private val active: Session
  ) {
    private val start = System.nanoTime()
    private val finished = AtomicBoolean(false)

    fun write(kind: String, fields: Map<String, Any?>) {
      emit(active, operation, kind, id, layer, fields + ("durationMs" to (System.nanoTime() - start) / 1_000_000))
    }

    fun response(status: Int, headers: Map<String, String>, bytes: ByteArray, observedBytes: Long = bytes.size.toLong(), complete: Boolean = true) {
      if (finished.compareAndSet(false, true)) runCatching {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        write("response", mapOf("status" to status, "headers" to active.sanitizer.headers(headers), "responseBody" to active.sanitizer.body(bytes, observedBytes, complete, contentType)))
      }
    }

    fun result(value: Any?) {
      if (finished.compareAndSet(false, true)) runCatching { write("result", mapOf("decodedResult" to describe(value))) }
    }

    fun error(t: Throwable) {
      if (finished.compareAndSet(false, true)) write(if (t is CancellationException) "cancelled" else "failure", mapOf("errorType" to t.javaClass.name, "errorMessage" to t.message))
    }

    fun cancel() {
      if (finished.compareAndSet(false, true)) write("cancelled", emptyMap())
    }

    companion object {
      internal fun create(id: String, layer: String, operation: String, sink: Sink, context: Context, sanitizer: TraceSanitizer): Call = Call(id, layer, operation, Session(sink, context, sanitizer))
    }
  }

  private fun describe(value: Any?): Any? = when (value) {
    is RequestResult.Success<*> -> mapOf("kind" to "success", "value" to describe(value.result))
    is RequestResult.NonSuccess<*> -> mapOf("kind" to "non_success", "errorDetails" to describe(value.error))
    is RequestResult.RetryableNetworkError -> mapOf("kind" to "retryable_network_error", "errorType" to value.networkError.javaClass.simpleName, "retryAfterMs" to value.retryAfter?.toMillis())
    is RequestResult.ApplicationError -> mapOf("kind" to "application_error", "errorType" to value.cause.javaClass.simpleName)
    is LinkedDevice -> mapOf("id" to value.id, "registrationId" to value.registrationId, "lastSeen" to value.lastSeen.toString(), "encryptedName" to value.encryptedName, "createdAtCiphertext" to value.createdAtCiphertext)
    is MismatchedDeviceException -> mapOf("type" to value.javaClass.simpleName, "entries" to value.entries.map { mapOf("account" to it.account.toString(), "missingDevices" to it.missingDevices, "extraDevices" to it.extraDevices, "staleDevices" to it.staleDevices) })
    is Throwable -> mapOf("type" to value.javaClass.name, "message" to value.message)
    is Iterable<*> -> value.take(256).map(::describe)
    is Unit, null -> null
    else -> value
  }

  private fun emit(active: Session, operation: String, kind: String, callId: String?, layer: String?, fields: Map<String, Any?>) {
    // 观测器失效不能改变网络结果；步骤在请求开始时快照，长请求不被后续标记误归类。
    runCatching {
      active.sink.offer(
        active.sanitizer.sanitize(
          mapOf("schemaVersion" to 2, "captureMode" to "raw", "runId" to active.context.runId, "stepId" to active.context.stepId, "device" to active.context.device, "callId" to callId, "layer" to layer, "operation" to operation, "kind" to kind, "timestampMs" to System.currentTimeMillis(), "monotonicNs" to System.nanoTime(), "data" to fields)
        )
      )
    }
  }
}
