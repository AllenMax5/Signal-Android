/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.network.trace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NetworkTraceTest {
  @After fun stop() = NetworkTrace.stop()

  @Test fun `raw capture preserves decoded fields headers and path`() {
    val sanitizer = TraceSanitizer()
    val result = sanitizer.body("""{"password":"pw-secret","nested":{"privateKey":"private-secret","aci":"account-secret","profileKey":"profile-secret"},"devices":[1,2]}""".toByteArray()).toString()
    listOf("pw-secret", "private-secret", "account-secret", "profile-secret").forEach { assertTrue(result.contains(it)) }
    assertEquals("Bearer secret", sanitizer.headers(mapOf("Authorization" to "Bearer secret"))["Authorization"])
    assertEquals("/v1/provisioning/device-secret?token=secret", sanitizer.path("https://example.org/v1/provisioning/device-secret?token=secret"))
    assertEquals("/v1/websocket/provisioning/", sanitizer.path("https://example.org/v1/websocket/provisioning/"))
    assertEquals(sanitizer.sanitize(mapOf("aci" to "same")), sanitizer.sanitize(sanitizer.sanitize(mapOf("aci" to "same"))))
  }

  @Test fun `non JSON bytes are preserved and incomplete bodies are explicit`() {
    val sanitizer = TraceSanitizer()
    val raw = sanitizer.body(byteArrayOf(0, 1, 2)).toString()
    assertTrue(raw.contains("AAEC"))
    assertTrue(raw.contains("\"decoded\":null"))
    assertTrue(sanitizer.body(byteArrayOf(1, 2), observedBytes = 3).toString().contains("\"truncated\":true"))
    val unsafe = object { override fun toString(): String = error("Must not stringify native objects") }
    sanitizer.sanitize(unsafe)
  }

  @Test fun `complete protobuf capture exposes wire outline without inventing field names`() {
    val sanitizer = TraceSanitizer()
    val body = sanitizer.body(byteArrayOf(0x08, 0x25, 0x12, 0x03, 0x01, 0x02, 0x03), contentType = "application/x-protobuf").toString()
    assertTrue(body.contains("\"bodyKind\":\"protobuf_untyped\""))
    assertTrue(body.contains("\"fieldNumber\":1,\"wireType\":0,\"valueByteLength\":1"))
    assertTrue(body.contains("\"fieldNumber\":2,\"wireType\":2,\"valueByteLength\":3"))
    assertTrue(body.contains("\"rawBase64\":\"CCUSAwECAw==\""))
    val truncated = sanitizer.body(byteArrayOf(0x08, 0x25), observedBytes = 3, contentType = "application/x-protobuf").toString()
    assertTrue(truncated.contains("\"protobufWireFields\":null"))
  }

  @Test fun `steps are snapshotted and terminal records appear once`() {
    val events = mutableListOf<JsonElement>()
    NetworkTrace.install({ events.add(it) }, "run", "before", "A")
    val call = NetworkTrace.begin("client_api", "test") { emptyMap() }!!
    NetworkTrace.step("after")
    call.result(Unit)
    call.error(IOException())
    val terminals = events.filter { it.toString().contains("\"kind\":\"result\"") }
    assertEquals(1, terminals.size)
    assertTrue(terminals.single().toString().contains("\"stepId\":\"before\""))
  }

  @Test fun `cancellation is rethrown unchanged and recorded as cancellation`() = runBlocking {
    val events = mutableListOf<JsonElement>()
    NetworkTrace.install({ events.add(it) }, "run", "step", "A")
    val cancelled = java.util.concurrent.CancellationException("sensitive cancellation text")
    try {
      NetworkTrace.observe("cancel", { emptyMap() }) { throw cancelled }
      fail("Expected cancellation")
    } catch (actual: java.util.concurrent.CancellationException) {
      assertSame(cancelled, actual)
    }
    assertEquals(1, events.count { it.toString().contains("\"kind\":\"cancelled\"") })
    assertTrue(events.toString().contains("sensitive cancellation text"))
  }

  @Test fun `disabled tracing is lazy and broken sinks preserve outcomes and exceptions`() = runBlocking {
    NetworkTrace.stop()
    assertNull(NetworkTrace.begin("http", "disabled") { error("Not evaluated") })
    NetworkTrace.install({ error("Disk failure") }, "run", "step", "A")
    val expected = Any()
    assertSame(expected, NetworkTrace.observe("success", { emptyMap() }) { expected })
    val exception = IOException("secret-error-message")
    try {
      NetworkTrace.observe("failure", { emptyMap() }) { throw exception }
      fail("Expected IOException")
    } catch (actual: IOException) {
      assertSame(exception, actual)
    }
  }
}
