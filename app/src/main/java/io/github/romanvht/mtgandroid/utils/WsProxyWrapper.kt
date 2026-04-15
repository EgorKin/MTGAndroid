package io.github.romanvht.mtgandroid.utils

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Experimental local MTProto-over-WebSocket proxy prototype.
 *
 * This implementation accepts local TCP connections from Telegram and tunnels
 * raw bytes through WebSocket endpoint selected by DC id extracted from the
 * client's initial packet.
 */
object WsProxyWrapper {
    private const val TAG = "WsProxyWrapper"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientThreads = Collections.synchronizedList(mutableListOf<Thread>())

    @Volatile
    private var isRunning = false

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val fallbackWsUrls = listOf(
        "wss://pluto.web.telegram.org/apiws",
        "wss://venus.web.telegram.org/apiws",
        "wss://aurora.web.telegram.org/apiws",
        "wss://vesta.web.telegram.org/apiws"
    )

    fun startProxy(context: Context, bindAddress: String, secret: String): Boolean {
        if (secret.isBlank()) {
            DebugLogStore.e(TAG, "Secret is empty")
            return false
        }

        return try {
            stopProxy()

            val (host, port) = parseBindAddress(bindAddress)
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }

            serverSocket = server
            isRunning = true

            acceptThread = thread(name = "ws-proxy-accept", isDaemon = true) {
                acceptLoop(context, secret)
            }

            val preflightOk = preflightWs(context)
            if (!preflightOk) {
                DebugLogStore.w(
                    TAG,
                    "WebSocket preflight did not complete in time; starting anyway and continuing with runtime checks"
                )
            }

            DebugLogStore.i(TAG, "WS proxy listening on $host:$port")
            true
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "Failed to start WS proxy", e)
            stopProxy()
            false
        }
    }

    fun stopProxy() {
        isRunning = false

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            DebugLogStore.w(TAG, "Error closing server socket", e)
        }

        serverSocket = null
        acceptThread = null

        synchronized(clientThreads) {
            clientThreads.forEach { it.interrupt() }
            clientThreads.clear()
        }
    }

    private fun acceptLoop(context: Context, secret: String) {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: break
                val handler = thread(name = "ws-proxy-client", isDaemon = true) {
                    handleClient(context, client, secret)
                }
                clientThreads.add(handler)
            } catch (e: IOException) {
                if (isRunning) {
                    DebugLogStore.e(TAG, "Accept loop error", e)
                }
                break
            }
        }
    }

    private fun handleClient(context: Context, client: Socket, secret: String) {
        client.soTimeout = 20000

        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        var ws: WebSocket? = null

        try {
            val firstPacket = readFirstPacket(input)
            val dcId = extractDcId(firstPacket)
            val wsUrls = resolveWsUrls(context, dcId)
            DebugLogStore.d(TAG, "Client connected. DC=$dcId URLs=${wsUrls.joinToString()}")

            val relay = SocketRelay(client, output)
            ws = connectWebSocketWithFallback(wsUrls, relay)
                ?: throw IOException("Cannot establish WebSocket session for DC=$dcId")

            // Send first packet and continue forwarding client stream as WS binary frames.
            ws.send(firstPacket.toByteString())

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (isRunning && !client.isClosed && !Thread.currentThread().isInterrupted) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (!ws.send(buffer.toByteString(0, read))) break
            }
        } catch (e: InterruptedException) {
            // Thread interruption is expected during shutdown.
            DebugLogStore.d(TAG, "Client relay interrupted")
        } catch (e: Exception) {
            DebugLogStore.w(TAG, "Client relay error", e)
        } finally {
            try {
                ws?.close(1000, "done")
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveWsUrls(context: Context, dcId: Int): List<String> {
        val template = PreferencesUtils.getWsTemplate(context).trim()
        val resolvedFromTemplate = if (template.contains("%d")) {
            template.format(dcId.coerceIn(1, 5))
        } else {
            template
        }

        return LinkedHashSet<String>().apply {
            if (resolvedFromTemplate.isNotBlank()) {
                add(resolvedFromTemplate)
            }
            addAll(fallbackWsUrls)
        }.toList()
    }

    private fun readFirstPacket(input: BufferedInputStream): ByteArray {
        val first = ByteArray(64)
        var offset = 0
        while (offset < first.size) {
            val read = input.read(first, offset, first.size - offset)
            if (read <= 0) throw IOException("Unexpected EOF while reading first packet")
            offset += read
        }
        return first
    }

    private fun extractDcId(firstPacket: ByteArray): Int {
        // MTProto proxy handshake carries DC id in little-endian int in the tail area.
        // Use offset 56 as prototype heuristic.
        return try {
            val raw = ByteBuffer.wrap(firstPacket, 56, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            when {
                raw in 1..5 -> raw
                raw in -5..-1 -> -raw
                else -> 2
            }
        } catch (e: Exception) {
            DebugLogStore.w(TAG, "Cannot extract DC id, fallback to 2", e)
            2
        }
    }

    private fun preflightWs(context: Context): Boolean {
        val urls = resolveWsUrls(context, 2)
        urls.forEach { url ->
            val ws = openWebSocket(url, object : WebSocketListener() {})
            if (ws != null) {
                ws.close(1000, "preflight")
                DebugLogStore.d(TAG, "Preflight open OK: url=$url")
                return true
            }
        }
        DebugLogStore.w(TAG, "Preflight failed for all URLs: ${urls.joinToString()}")
        return false
    }

    private fun connectWebSocketWithFallback(urls: List<String>, relay: SocketRelay): WebSocket? {
        urls.forEach { url ->
            val ws = openWebSocket(url, relay)
            if (ws != null) {
                DebugLogStore.d(TAG, "Connected via WS URL: $url")
                return ws
            }
        }
        return null
    }

    private fun openWebSocket(url: String, listener: WebSocketListener): WebSocket? {
        val completionLatch = CountDownLatch(1)
        val socketRef = AtomicReference<WebSocket?>()
        val failureRef = AtomicReference<String?>()
        val openedRef = AtomicReference(false)

        val request = Request.Builder()
            .url(url)
            .header("Origin", "https://web.telegram.org")
            .build()

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedRef.set(true)
                socketRef.set(webSocket)
                completionLatch.countDown()
                listener.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onMessage(webSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failureRef.set(
                    if (response != null) {
                        "HTTP ${response.code} ${response.message}"
                    } else {
                        "${t.javaClass.simpleName}: ${t.message}"
                    }
                )
                completionLatch.countDown()
                if (openedRef.get()) {
                    listener.onFailure(webSocket, t, response)
                }
            }
        })

        if (completionLatch.await(8, TimeUnit.SECONDS) && openedRef.get()) {
            return socketRef.get()
        }

        ws.cancel()
        val failure = failureRef.get()
        if (failure != null) {
            DebugLogStore.w(TAG, "WS open failed for $url (${failureRef.get()})")
        } else {
            DebugLogStore.w(TAG, "WS open timeout for $url")
        }
        return null
    }

    private fun parseBindAddress(bindAddress: String): Pair<String, Int> {
        val idx = bindAddress.lastIndexOf(':')
        require(idx > 0 && idx < bindAddress.length - 1) { "Invalid bind address: $bindAddress" }

        val host = bindAddress.substring(0, idx)
        val port = bindAddress.substring(idx + 1).toInt()

        return host to port
    }

    private class SocketRelay(
        private val clientSocket: Socket,
        private val output: BufferedOutputStream
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            DebugLogStore.d(TAG, "WS opened: ${response.code}")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                output.write(bytes.toByteArray())
                output.flush()
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "Error writing WS->TCP", e)
                closeBoth(webSocket)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeBoth(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeBoth(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLogStore.e(TAG, "WS failure", t)
            closeBoth(webSocket)
        }

        private fun closeBoth(webSocket: WebSocket) {
            try {
                webSocket.cancel()
            } catch (_: Exception) {
            }
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
        }
    }
}
