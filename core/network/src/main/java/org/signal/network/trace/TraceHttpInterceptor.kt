/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.network.trace

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer

/** Copies only bytes already written/read by the application; never probes or consumes a response. */
class TraceHttpInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    if (!NetworkTrace.enabled) return chain.proceed(chain.request())
    val original = chain.request()
    val call = NetworkTrace.begin("http", "${original.method} ${original.url.host}") {
      mapOf("method" to original.method, "host" to original.url.host, "path" to NetworkTrace.path(original.url.toString()), "headers" to NetworkTrace.headers(original.headers.toMap()), "headerLines" to (0 until original.headers.size).map { mapOf("name" to original.headers.name(it), "value" to original.headers.value(it)) })
    } ?: return chain.proceed(original)
    val request = if (original.body == null || original.body!!.isDuplex()) original else original.newBuilder().method(original.method, copyRequest(original.body!!, call)).build()
    try {
      val response = chain.proceed(request)
      val headers = response.headers.toMap()
      call.write("response_headers", mapOf("status" to response.code, "headers" to NetworkTrace.headers(headers), "headerLines" to (0 until response.headers.size).map { mapOf("name" to response.headers.name(it), "value" to response.headers.value(it)) }))
      val body = response.body
      return response.newBuilder().body(copyResponse(body, response.code, headers, call)).build()
    } catch (t: Throwable) {
      call.error(t)
      throw t
    }
  }

  private fun copyRequest(delegate: RequestBody, call: NetworkTrace.Call): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = delegate.isOneShot()
    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
      val capture = Capture()
      val tee = object : ForwardingSink(sink) {
        override fun write(source: Buffer, byteCount: Long) {
          capture.copy(source, 0, byteCount)
          super.write(source, byteCount)
        }
      }.buffer()
      // 不预先重写 RequestBody；只复制实际发送的字节，保留一次性请求体的语义。
      delegate.writeTo(tee)
      tee.emit()
      call.write("request_body", mapOf("requestBody" to NetworkTrace.body(capture.bytes(), observedBytes = capture.observed, contentType = delegate.contentType()?.toString())))
    }
  }

  private fun copyResponse(delegate: ResponseBody, status: Int, headers: Map<String, String>, call: NetworkTrace.Call): ResponseBody = object : ResponseBody() {
    private val capture = Capture()
    private var reachedEnd = false
    private val tee = object : ForwardingSource(delegate.source()) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        try {
          val start = sink.size
          val count = super.read(sink, byteCount)
          if (count == -1L) {
            reachedEnd = true
            finish()
          } else {
            capture.copy(sink, start, count)
          }
          return count
        } catch (t: Throwable) {
          call.error(t)
          throw t
        }
      }

      override fun close() {
        try {
          super.close()
        } finally {
          finish()
        }
      }
    }.buffer()

    private fun finish() = call.response(status, headers, capture.bytes(), capture.observed, reachedEnd)
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = tee
  }

  private class Capture {
    private val buffer = Buffer()
    var observed = 0L
      private set

    fun copy(source: Buffer, offset: Long, count: Long) {
      val remaining = TraceSanitizer.MAX_BODY_BYTES - buffer.size
      if (remaining > 0) source.copyTo(buffer, offset, minOf(remaining, count))
      observed += count
    }

    fun bytes(): ByteArray = buffer.snapshot().toByteArray()
  }
}
