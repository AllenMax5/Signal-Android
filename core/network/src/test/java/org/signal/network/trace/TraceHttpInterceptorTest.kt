/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.network.trace

import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TraceHttpInterceptorTest {
  @After fun stop() = NetworkTrace.stop()

  @Test fun `one shot request is written once and response is not read in advance`() {
    val events = mutableListOf<JsonElement>()
    NetworkTrace.install({ events.add(it) }, "run", "step", "A")
    var writes = 0
    var reads = 0
    val request = Request.Builder().url("https://example.org/test").post(object : RequestBody() {
      override fun contentType(): MediaType? = null
      override fun isOneShot() = true
      override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8("{\"password\":\"request-secret\"}") }
    }).build()
    val source = object : Source {
      val remaining = Buffer().writeUtf8("{\"devices\":[1,2]}")
      override fun timeout() = Timeout.NONE
      override fun close() = Unit
      override fun read(sink: Buffer, byteCount: Long): Long { reads++; return remaining.read(sink, byteCount) }
    }.buffer()
    val body = object : ResponseBody() {
      override fun contentType(): MediaType? = null
      override fun contentLength() = -1L
      override fun source() = source
    }
    val chain = FakeChain(request) { observed ->
      assertTrue(observed.body!!.isOneShot())
      observed.body!!.writeTo(Buffer())
      Response.Builder().request(observed).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }
    val response = TraceHttpInterceptor().intercept(chain)
    assertEquals(1, writes)
    assertEquals(0, reads)
    assertEquals("{\"devices\":[1,2]}", response.body.string())
    assertTrue(reads > 0)
    assertTrue(events.toString().contains("request-secret"))
    assertEquals(1, events.count { it.toString().contains("\"kind\":\"response\"") })
  }

  @Test fun `closing an unread response records incomplete content`() {
    val events = mutableListOf<JsonElement>()
    NetworkTrace.install({ events.add(it) }, "run", "step", "A")
    val request = Request.Builder().url("https://example.org/test").build()
    val chain = FakeChain(request) { observed ->
      Response.Builder().request(observed).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(object : ResponseBody() {
          override fun contentType(): MediaType? = null
          override fun contentLength() = 6L
          override fun source() = Buffer().writeUtf8("secret")
        }).build()
    }
    TraceHttpInterceptor().intercept(chain).close()
    assertTrue(events.toString().contains("\"complete\":false"))
  }

  private class FakeChain(private val original: Request, private val proceedBlock: (Request) -> Response) : Interceptor.Chain {
    override fun request() = original
    override fun proceed(request: Request) = proceedBlock(request)
    override fun connection(): Connection? = null
    override fun call(): Call = OkHttpClient().newCall(original)
    override fun connectTimeoutMillis() = 0
    override fun readTimeoutMillis() = 0
    override fun writeTimeoutMillis() = 0
    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
  }
}
