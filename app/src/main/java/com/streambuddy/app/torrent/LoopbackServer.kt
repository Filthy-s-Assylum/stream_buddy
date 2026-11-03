package com.streambuddy.app.torrent

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A verified byte range the server may read. Implementations must only return
 * true once the range is backed by verified data on disk.
 */
fun interface RangeReadiness {
    fun awaitRange(start: Long, endExclusive: Long): Boolean

    companion object {
        val ALWAYS: RangeReadiness = RangeReadiness { _, _ -> true }
    }
}

data class ServedFile(
    val file: File,
    val size: Long,
    val contentType: String,
    val readiness: RangeReadiness = RangeReadiness.ALWAYS,
)

/** The complete allowlist of what the server is willing to expose. */
interface FileCatalog {
    fun media(): ServedFile?
    fun subtitle(index: Int): ServedFile?
}

/**
 * Loopback-only HTTP server used to stream verified torrent bytes into the
 * player. Security properties:
 *
 *  - binds explicitly to 127.0.0.1, never 0.0.0.0;
 *  - every request must carry a 256-bit random token in the path, compared in
 *    constant time;
 *  - only files from [catalog] can be read, so no path is ever built from the
 *    request target;
 *  - request line + headers are capped at 8 KiB and the socket read timeout is
 *    short;
 *  - only GET and HEAD are accepted, one request per connection, then close;
 *  - no request path, token or query is logged anywhere.
 */
class LoopbackServer(
    private val catalog: FileCatalog,
    private val token: String = newToken(),
) {

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val bytesServed = AtomicLong(0)
    private val requestCount = AtomicLong(0)
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val executor = Executors.newFixedThreadPool(MAX_CONNECTIONS) { runnable ->
        Thread(runnable, "sb-loopback").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var port: Int = 0

    val servedBytes: Long get() = bytesServed.get()
    val requests: Long get() = requestCount.get()

    /** Starts the server and returns the base URL, including the token. */
    @Synchronized
    fun start(): String {
        if (running.get()) return baseUrl()
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 8)
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "sb-loopback-accept").apply {
            isDaemon = true
            start()
        }
        return baseUrl()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        executor.shutdownNow()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    fun mediaUrl(): String = "${baseUrl()}/media"

    fun subtitleUrl(index: Int): String = "${baseUrl()}/subs/$index"

    fun baseUrl(): String {
        check(port > 0) { "Server is not running" }
        return "http://$LOOPBACK:$port/t/$token"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                if (!running.get()) {
                    runCatching { client.close() }
                    break
                }
                sockets.add(client)
                runCatching { executor.execute { handle(client) } }
            } catch (_: IOException) {
                if (running.get()) {
                    // Transient accept failure; keep serving other requests.
                } else {
                    break
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val request = readRequest(input) ?: return
            requestCount.incrementAndGet()

            val queryless = request.target.substringBefore('?')
            if (!queryless.startsWith(ROUTE_PREFIX)) {
                writeError(output, 404, "Not found")
                return
            }
            val rest = queryless.removePrefix(ROUTE_PREFIX)
            val requestToken = rest.substringBefore('/')
            if (!constantTimeEquals(requestToken, token)) {
                // Deliberately indistinguishable from a missing file so the
                // server does not confirm whether a token exists.
                writeError(output, 404, "Not found")
                return
            }
            val route = rest.substringAfter('/', "")
            when {
                route == "media" -> serve(output, catalog.media(), request)
                route == "health" -> writeText(output, 200, "ok")
                route.startsWith("subs/") -> {
                    val index = route.removePrefix("subs/").toIntOrNull()
                    if (index == null || index < 0) writeError(output, 404, "Not found")
                    else serve(output, catalog.subtitle(index), request)
                }
                else -> writeError(output, 404, "Not found")
            }
        } catch (_: SocketTimeoutException) {
            // Slow or hostile client; drop it.
        } catch (_: IOException) {
            // Client disconnected mid-stream is normal.
        } catch (_: Throwable) {
            // Never let a malformed request crash the accept loop.
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun serve(output: OutputStream, served: ServedFile?, request: HttpRequest) {
        if (served == null || !served.file.isFile || served.size <= 0) {
            writeError(output, 404, "Not found")
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            output.write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        val range = when (val parsed = HttpRange.parse(request.headers["range"], served.size)) {
            RangeParse.Full -> ByteRange(0, served.size - 1)
            is RangeParse.Single -> parsed.range
            is RangeParse.Invalid -> {
                output.write(
                    (
                        "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */${served.size}\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n" +
                            "X-Content-Type-Options: nosniff\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                return
            }
        }

        val partial = range.start > 0 || range.endInclusive != served.size - 1
        val headers = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(served.contentType).append("\r\n")
            append("Content-Length: ").append(range.length).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) {
                append("Content-Range: ").append(range.contentRange(served.size)).append("\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray())
        output.flush()
        if (request.method == "HEAD") return

        RandomAccessFile(served.file, "r").use { file ->
            file.seek(range.start)
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var position = range.start
            while (position <= range.endInclusive) {
                val wanted = minOf(buffer.size.toLong(), range.endInclusive - position + 1).toInt()
                val readyEnd = position + wanted
                if (!served.readiness.awaitRange(position, readyEnd)) {
                    // Headers are already sent, so the only safe action is to
                    // stop. The player treats a short read as a retryable
                    // network error and resumes from its last position.
                    return
                }
                val read = file.read(buffer, 0, wanted)
                if (read <= 0) return
                output.write(buffer, 0, read)
                position += read
                bytesServed.addAndGet(read.toLong())
            }
            output.flush()
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val bytes = ArrayList<Byte>(512)
        var total = 0
        while (total < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            total++
            bytes.add(value.toByte())
            if (bytes.size >= 4 &&
                bytes[bytes.size - 4] == '\r'.code.toByte() &&
                bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() &&
                bytes[bytes.size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        if (total >= MAX_HEADER_BYTES) return null
        val text = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3) return null
        val method = requestLine[0].uppercase(Locale.US)
        val target = requestLine[1]
        val version = requestLine[2]
        if (method !in ALLOWED_METHODS) return null
        if (version != "HTTP/1.1" && version != "HTTP/1.0") return null
        if (target.length > 2048) return null
        if (target.any { it.code < 0x20 || it == '\u007f' }) return null
        if (target.contains("..") || target.contains("%2e", ignoreCase = true)) return null

        val headers = buildMap {
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                put(line.substring(0, colon).trim().lowercase(Locale.US), line.substring(colon + 1).trim())
            }
        }
        return HttpRequest(method, target, headers)
    }

    private fun writeText(output: OutputStream, status: Int, body: String) {
        val payload = body.toByteArray()
        output.write(
            (
                "HTTP/1.1 $status ${statusText(status)}\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        output.write(payload)
        output.flush()
    }

    private fun writeError(output: OutputStream, status: Int, message: String) {
        writeText(output, status, "$status ${statusText(status)}")
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val ROUTE_PREFIX = "/t/"
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val MAX_CONNECTIONS = 4
        private val ALLOWED_METHODS = setOf("GET", "HEAD")

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
