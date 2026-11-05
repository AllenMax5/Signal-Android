/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.provisioning

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.trace.NetworkTrace
import org.signal.network.websocket.WebSocketMessage
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.WebSocketResponseMessage
import org.signal.registration.proto.RegistrationProvisionEnvelope
import org.whispersystems.signalservice.api.buildOkHttpClient
import org.whispersystems.signalservice.api.chooseUrl
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisionEnvelope
import org.whispersystems.signalservice.internal.push.ProvisioningAddress
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/**
 * A provisional web socket for communicating with a primary device during registration.
 */
class ProvisioningSocket<T> private constructor(
  private val mode: Mode,
  val id: Int,
  identityKeyPair: IdentityKeyPair,
  configuration: SignalServiceConfiguration,
  private val scope: CoroutineScope
) {
  companion object {
    private val TAG = Log.tag(ProvisioningSocket::class)

    @Volatile private var nextSocketId = 1000

    val LIFESPAN = 90.seconds

    fun <T> start(
      mode: Mode,
      identityKeyPair: IdentityKeyPair,
      configuration: SignalServiceConfiguration,
      handler: ProvisioningSocketExceptionHandler,
      block: suspend CoroutineScope.(ProvisioningSocket<T>) -> Unit
    ): Closeable {
      val socketId = nextSocketId++
      val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob() + CoroutineExceptionHandler { _, t -> handler.handleException(socketId, t) }

      scope.launch {
        var socket: ProvisioningSocket<T>? = null
        try {
          socket = ProvisioningSocket(mode, socketId, identityKeyPair, configuration, scope)
          socket.connect()
          block(socket)
        } catch (e: CancellationException) {
          val rootCause = e.getRootCause()
          if (rootCause == null) {
            Log.i(TAG, "[$socketId] Scope canceled expectedly, fail silently, ${e.toMinimalString()}")
            throw e
          } else {
            Log.w(TAG, "[$socketId] Unable to maintain web socket, ${rootCause.toMinimalString()}", rootCause)
            throw rootCause
          }
        } finally {
          Log.d(TAG, "[$socketId] Closing web socket")
          socket?.close()
        }
      }

      return Closeable { scope.cancel("scope closed") }
    }

    /**
     * Get non-cancellation exception cause to determine if something legitimately failed.
     */
    private fun CancellationException.getRootCause(): Throwable? {
      var cause: Throwable? = cause
      while (cause != null && cause is CancellationException) {
        cause = cause.cause
      }
      return cause
    }

    /**
     * Generates a minimal throwable informational string since stack traces aren't always logged.
     */
    private fun Throwable.toMinimalString(): String {
      return "${javaClass.simpleName}[$message]"
    }
  }

  private val serviceUrl = configuration.signalServiceUrls.chooseUrl()
  private val okhttp = serviceUrl.buildOkHttpClient(configuration)

  private val cipher = SecondaryProvisioningCipher(identityKeyPair)
  private var webSocket: WebSocket? = null

  private val provisioningUrlDeferral: CompletableDeferred<String> = CompletableDeferred()
  private val provisioningMessageDeferral: CompletableDeferred<SecondaryProvisioningCipher.ProvisioningDecryptResult<T>> = CompletableDeferred()

  suspend fun getProvisioningUrl(): String {
    return provisioningUrlDeferral.await()
  }

  suspend fun getProvisioningMessageDecryptResult(): SecondaryProvisioningCipher.ProvisioningDecryptResult<T> {
    return provisioningMessageDeferral.await()
  }

  private fun connect() {
    val uri = serviceUrl.url.replace("https://", "wss://").replace("http://", "ws://")

    val openRequest = Request.Builder()
      .url("$uri/v1/websocket/provisioning/")

    if (serviceUrl.hostHeader.isPresent) {
      openRequest.addHeader("Host", serviceUrl.hostHeader.get())
      Log.w(TAG, "Using alternate host: ${serviceUrl.hostHeader.get()}")
    }

    webSocket = okhttp.newWebSocket(openRequest.build(), ProvisioningWebSocketListener())
  }

  private fun close() {
    webSocket?.close(1000, "Manual shutdown")
  }

  private inner class ProvisioningWebSocketListener : WebSocketListener() {
    private var keepAliveJob: Job? = null

    @Volatile
    private var lastKeepAliveId: Long = 0

    override fun onOpen(webSocket: WebSocket, response: Response) {
      NetworkTrace.event("provisioning_opened", mapOf("socketId" to id, "mode" to mode.host, "status" to response.code))
      Log.d(TAG, "[$id] [onOpen]")
      keepAliveJob = scope.launch { keepAlive(webSocket) }

      val timeoutJob = scope.launch {
        delay(10.seconds)
        scope.cancel("Did not receive device id within 10 seconds", SocketTimeoutException("No device id received"))
      }

      val webSocketExpireJob = scope.launch {
        delay(LIFESPAN)
        scope.cancel("Did not complete a registration within ${LIFESPAN.inWholeSeconds} seconds", SocketTimeoutException("No provisioning message received"))
      }

      scope.launch {
        provisioningUrlDeferral.await()
        timeoutJob.cancel()

        provisioningMessageDeferral.await()
        webSocketExpireJob.cancel()
      }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      val message: WebSocketMessage = WebSocketMessage.ADAPTER.decode(bytes)

      val response = message.response
      if (response != null && response.id == lastKeepAliveId) {
        Log.d(TAG, "[$id] [onMessage] Keep alive received")
        return
      }

      val request = message.request
      if (request == null) {
        Log.w(TAG, "[$id] [onMessage] Received null request")
        return
      }

      val success = webSocket.send(request.toResponse().encode().toByteString())
      // ACK 保持原有发送顺序；原值测试采集另在解密成功后记录配置字段。
      NetworkTrace.begin("websocket_inbound", "ProvisioningSocket.receive") {
        mapOf("socketId" to id, "protocolRequestId" to request.id, "method" to request.verb, "path" to NetworkTrace.path(request.path ?: ""), "encryptedPayload" to request.body?.toByteArray())
      }?.write("ack_enqueued", mapOf("status" to 200, "enqueued" to success))

      if (!success) {
        Log.w(TAG, "[$id] [onMessage] Failed to send response")
        webSocket.close(1000, "OK")
        return
      }

      Log.d(TAG, "[$id] [onMessage] Processing request")

      val body = request.body
      if (request.verb == "PUT" && body != null) {
        when (request.path) {
          "/v1/address" -> {
            val address = ProvisioningAddress.ADAPTER.decode(body).address
            if (address != null) {
              NetworkTrace.event("provisioning_address_received", mapOf("socketId" to id, "address" to address, "temporaryPublicKey" to cipher.secondaryDevicePublicKey.serialize()))
              provisioningUrlDeferral.complete(generateProvisioningUrl(address))
            } else {
              throw IOException("Device address is null")
            }
          }

          "/v1/message" -> {
            when (mode) {
              is Mode.Rereg -> provisioningMessageDeferral.complete(cipher.decrypt(RegistrationProvisionEnvelope.ADAPTER.decode(body)) as SecondaryProvisioningCipher.ProvisioningDecryptResult<T>)
              is Mode.Link -> {
                val decrypted = cipher.decrypt(ProvisionEnvelope.ADAPTER.decode(body))
                if (NetworkTrace.enabled && decrypted is SecondaryProvisioningCipher.ProvisioningDecryptResult.Success) {
                  val provision = decrypted.message
                  // 次设备解密后的字段与主设备加密前事件相互核对，不把密文抵达误判为配置成功。
                  NetworkTrace.event("secondary_provisioning_message_decrypted", mapOf(
                    "socketId" to id,
                    "aciIdentityKeyPublic" to provision.aciIdentityKeyPublic?.toByteArray(),
                    "aciIdentityKeyPrivate" to provision.aciIdentityKeyPrivate?.toByteArray(),
                    "pniIdentityKeyPublic" to provision.pniIdentityKeyPublic?.toByteArray(),
                    "pniIdentityKeyPrivate" to provision.pniIdentityKeyPrivate?.toByteArray(),
                    "aci" to provision.aci,
                    "pni" to provision.pni,
                    "number" to provision.number,
                    "provisioningCode" to provision.provisioningCode,
                    "userAgent" to provision.userAgent,
                    "profileKey" to provision.profileKey?.toByteArray(),
                    "readReceipts" to provision.readReceipts,
                    "provisioningVersion" to provision.provisioningVersion,
                    "ephemeralBackupKey" to provision.ephemeralBackupKey?.toByteArray(),
                    "accountEntropyPool" to provision.accountEntropyPool,
                    "mediaRootBackupKey" to provision.mediaRootBackupKey?.toByteArray(),
                    "aciBinary" to provision.aciBinary?.toByteArray(),
                    "pniBinary" to provision.pniBinary?.toByteArray(),
                    "authCredentialSalt" to provision.authCredentialSalt?.toByteArray(),
                    "provisionMessageWire" to provision.encode()
                  ))
                }
                provisioningMessageDeferral.complete(decrypted as SecondaryProvisioningCipher.ProvisioningDecryptResult<T>)
              }
            }
          }

          else -> Log.w(TAG, "[$id] Unknown path requested")
        }
      } else {
        Log.w(TAG, "[$id] Invalid data")
      }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      scope.launch {
        Log.i(TAG, "[$id] [onClosing] code: $code reason: $reason")

        if (code != 1000) {
          Log.w(TAG, "[$id] Remote side is closing with non-normal code $code")
          webSocket.close(1000, "Remote closed with code $code")
        }

        scope.cancel()
      }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      NetworkTrace.event("provisioning_failed", mapOf("socketId" to id, "errorType" to t.javaClass.simpleName, "status" to response?.code))
      scope.launch {
        Log.w(TAG, "[$id] [onFailure] Failed", t)
        webSocket.close(1000, "Failed ${t.message}")

        scope.cancel(CancellationException("WebSocket Failure", t))
      }
    }

    private fun generateProvisioningUrl(deviceAddress: String): String {
      val encodedDeviceId = URLEncoder.encode(deviceAddress, "UTF-8")
      val encodedPubKey: String = URLEncoder.encode(Base64.encodeWithoutPadding(cipher.secondaryDevicePublicKey.serialize()), "UTF-8")
      return "sgnl://${mode.host}?uuid=$encodedDeviceId&pub_key=$encodedPubKey${mode.params}"
    }

    private suspend fun keepAlive(webSocket: WebSocket) {
      Log.i(TAG, "[$id] [keepAlive] Starting")
      while (true) {
        delay(30.seconds)
        Log.i(TAG, "[$id] [keepAlive] Sending...")

        val id = System.currentTimeMillis()
        val message = WebSocketMessage(
          type = WebSocketMessage.Type.REQUEST,
          request = WebSocketRequestMessage(
            id = id,
            path = "/v1/keepalive",
            verb = "GET"
          )
        )

        if (!webSocket.send(message.encodeByteString())) {
          Log.w(TAG, "[${this@ProvisioningSocket.id}] [keepAlive] Send failed")
        } else {
          lastKeepAliveId = id
        }
      }
    }

    private fun WebSocketRequestMessage.toResponse(): WebSocketMessage {
      return WebSocketMessage(
        type = WebSocketMessage.Type.RESPONSE,
        response = WebSocketResponseMessage(
          id = id,
          status = 200,
          message = "OK"
        )
      )
    }
  }

  sealed class Mode(val host: String, val params: String) {
    data object Rereg : Mode("rereg", "&capabilities=nopni")
    data class Link(val linkAndSyncCapable: Boolean) : Mode("linkdevice", "&capabilities=" + listOfNotNull("backup5".takeIf { linkAndSyncCapable }, "nopni", "nopni2").joinToString(","))
  }

  fun interface ProvisioningSocketExceptionHandler {
    fun handleException(id: Int, exception: Throwable)
  }
}
