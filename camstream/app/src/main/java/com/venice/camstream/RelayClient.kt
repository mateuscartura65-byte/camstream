package com.venice.camstream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class RelayClient(
    private val baseUrl: String,
    private val deviceId: String,
    private val token: String,
    private val onStatus: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        connect()
    }

    private fun connect() {
        if (!running) return

        onStatus("relay: conectando")
        val request = Request.Builder().url(buildUrl()).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("relay: online")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("relay: offline")
                if (running) {
                    Thread.sleep(3000)
                    connect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) {
                    Thread.sleep(2000)
                    connect()
                }
            }
        })
    }

    private fun buildUrl(): String {
        val b = baseUrl.trim().trimEnd('/')

        val wsBase = when {
            b.startsWith("ws://") || b.startsWith("wss://") -> b
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            else -> "wss://$b"
        }

        val t = if (token.isNotBlank()) "&token=$token" else ""
        return "$wsBase/ws?id=$deviceId$t"
    }

    fun send(jpeg: ByteArray) {
        ws?.send(jpeg.toByteString())
    }

    fun stop() {
        running = false
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
        }
    }
}