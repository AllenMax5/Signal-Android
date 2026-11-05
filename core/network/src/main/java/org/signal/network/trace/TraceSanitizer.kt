/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.network.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Raw, opt-in Debug capture. Unknown native objects are not expanded because their fields are not decoded here. */
class TraceSanitizer {
  private val json = Json { isLenient = false }

  fun sanitize(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> (key as? String ?: "unknownField") to sanitize(item) })
    is Iterable<*> -> JsonArray(value.map(::sanitize))
    is IntArray -> JsonArray(value.map(::JsonPrimitive))
    is Array<*> -> JsonArray(value.map(::sanitize))
    is ByteArray -> binary(value)
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Unit -> JsonNull
    else -> JsonObject(mapOf("type" to JsonPrimitive(value.javaClass.name), "decoded" to JsonPrimitive(false)))
  }

  fun headers(headers: Map<String, String>): Map<String, String> = headers

  fun path(path: String): String {
    val uri = runCatching { URI(path) }.getOrNull() ?: return path
    return (uri.rawPath ?: path) + (uri.rawQuery?.let { "?$it" } ?: "")
  }

  /** Keep exact bytes; add a schema-free wire outline only for complete protobuf bodies. */
  fun body(bytes: ByteArray, observedBytes: Long = bytes.size.toLong(), complete: Boolean = true, contentType: String? = null): JsonElement {
    val truncated = observedBytes > bytes.size
    val decoded = if (!truncated && complete) runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull() else null
    val protobuf = contentType?.contains("protobuf", ignoreCase = true) == true
    // 二进制字段不应被猜成业务明文；只在正文完整时记录 protobuf 顶层 tag 轮廓，便于下轮按真实 schema 离线解码。
    val wireFields = if (protobuf && decoded == null && !truncated && complete) protobufWireFields(bytes) else null
    return JsonObject(
      mapOf(
        "observedBytes" to JsonPrimitive(observedBytes),
        "capturedBytes" to JsonPrimitive(bytes.size),
        "complete" to JsonPrimitive(complete),
        "truncated" to JsonPrimitive(truncated),
        "encoding" to JsonPrimitive("base64"),
        "contentType" to (contentType?.let(::JsonPrimitive) ?: JsonNull),
        "bodyKind" to JsonPrimitive(if (bytes.isEmpty()) "empty" else if (decoded != null) "json" else if (protobuf) "protobuf_untyped" else if (contentType?.startsWith("image/", ignoreCase = true) == true) "image" else "binary_untyped"),
        "rawBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
        "decoded" to (decoded ?: JsonNull),
        "protobufWireFields" to (wireFields?.let(::JsonArray) ?: JsonNull)
      )
    )
  }

  private fun protobufWireFields(bytes: ByteArray): List<JsonElement>? = runCatching {
    val fields = mutableListOf<JsonElement>()
    var offset = 0
    while (offset < bytes.size && fields.size < 512) {
      val tag = readVarint(bytes, offset) ?: return@runCatching null
      offset = tag.second
      val number = tag.first ushr 3
      val wireType = (tag.first and 7).toInt()
      if (number == 0L) return@runCatching null
      val valueLength: Long
      when (wireType) {
        0 -> {
          val value = readVarint(bytes, offset) ?: return@runCatching null
          valueLength = (value.second - offset).toLong()
          offset = value.second
        }
        1, 5 -> {
          valueLength = if (wireType == 1) 8 else 4
          if (valueLength > bytes.size - offset) return@runCatching null
          offset += valueLength.toInt()
        }
        2 -> {
          val size = readVarint(bytes, offset) ?: return@runCatching null
          offset = size.second
          valueLength = size.first
          if (valueLength > bytes.size - offset) return@runCatching null
          offset += valueLength.toInt()
        }
        else -> return@runCatching null
      }
      fields += JsonObject(mapOf("fieldNumber" to JsonPrimitive(number), "wireType" to JsonPrimitive(wireType), "valueByteLength" to JsonPrimitive(valueLength)))
    }
    if (offset != bytes.size) return@runCatching null
    fields
  }.getOrNull()

  private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
    var value = 0L
    var offset = start
    for (shift in 0..63 step 7) {
      if (offset >= bytes.size) return null
      val byte = bytes[offset++].toInt() and 0xff
      value = value or ((byte and 0x7f).toLong() shl shift)
      if (byte and 0x80 == 0) return value to offset
    }
    return null
  }

  fun binary(bytes: ByteArray): JsonObject = JsonObject(
    mapOf(
      "byteLength" to JsonPrimitive(bytes.size),
      "encoding" to JsonPrimitive("base64"),
      "rawBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
      "sha256" to JsonPrimitive(digest(bytes))
    )
  )

  companion object {
    // Ordinary JSON/protobuf requests fit well below this bound; oversized media is explicitly marked truncated.
    const val MAX_BODY_BYTES = 16 * 1024 * 1024

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
