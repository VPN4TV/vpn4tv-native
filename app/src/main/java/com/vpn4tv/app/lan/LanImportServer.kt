package com.vpn4tv.app.lan

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Minimal embedded HTTP/1.1 server for LAN-based profile import — the
 * fallback when the Telegram onboarding can't reach our control server
 * (ISP blocks the bot API / DNS) and the entry under Settings → Import
 * over local network.
 *
 * Not exposed in the default onboarding: the bot flow stays the primary
 * funnel (it asks "buy a subscription or bring your own key"). This server
 * only carries the bring-your-own-key half for users already past that gate.
 *
 * Security model — the window is small and the secret is unguessable:
 * - Binds 0.0.0.0:<random high port>; alive only while the import screen is
 *   in the foreground (start() on ON_START, stop() on ON_STOP — see
 *   LanImportScreen) and never longer than AUTO_STOP_MS as a hard backstop.
 * - Every request path must equal "/<token>" where token is 4 SecureRandom
 *   chars from an unambiguous alphabet (~20 bits). Short for phone typing;
 *   safe given the LAN-only, ephemeral-port, screen-lifetime-bounded window
 *   (see randomToken).
 * - Serves exactly one GET (the form) and one POST (the config). Anything
 *   else is 404. No filesystem access, no path-traversal surface.
 * - Each connection is handled on its own short-lived thread with read
 *   deadlines, so a slow/partial client can't starve the legitimate user.
 *
 * Raw sockets, no dependency: we need one GET and one POST, so NanoHTTPD
 * would be dead weight.
 */
class LanImportServer(
    private val onConfig: (String) -> Result<String>,
) {
    private val token: String = randomToken()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    var port: Int = 0
        private set

    /** http://<lan-ip>:<port>/<token> — null until start() bound a socket. */
    fun importUrl(): String? {
        val ip = lanIpv4() ?: return null
        val p = port.takeIf { it > 0 } ?: return null
        return "http://$ip:$p/$token"
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "lan-import-accept", isDaemon = true) {
            try {
                // Port 0 → OS picks a free ephemeral port; avoids clashing
                // with whatever else cheap TV firmware squats on fixed ports.
                val socket = ServerSocket(0)
                serverSocket = socket
                port = socket.localPort
                Log.i(TAG, "LAN import server on :$port path /$token")
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept failed: ${e.message}")
                        break
                    }
                    // One short-lived thread per connection: a slow/partial
                    // client can't block the next legitimate request.
                    thread(name = "lan-import-conn", isDaemon = true) {
                        try {
                            handle(client)
                        } catch (e: Exception) {
                            Log.w(TAG, "request handling failed: ${e.message}")
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server thread died: ${e.message}", e)
            }
        }
        // Hard wall-clock backstop: even if the lifecycle wiring ever fails
        // to call stop(), the port never stays open beyond AUTO_STOP_MS.
        thread(name = "lan-import-timeout", isDaemon = true) {
            val deadline = System.nanoTime() + AUTO_STOP_MS * 1_000_000L
            while (running && System.nanoTime() < deadline) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
            if (running) {
                Log.i(TAG, "auto-stop after ${AUTO_STOP_MS}ms")
                stop()
            }
        }
    }

    fun stop() {
        running = false
        port = 0 // so importUrl() reports null until a fresh bind
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream()
        val out = client.getOutputStream()

        // Read request line + headers as raw bytes up to the blank line. Byte
        // I/O (not BufferedReader) is deliberate: Content-Length is in bytes,
        // and reading the body as chars would mis-count multibyte UTF-8 (a
        // Cyrillic #name fragment) and hang waiting for bytes that never come.
        val headerBytes = readHeaders(input) ?: run {
            respond(out, 400, "text/plain", "bad request"); return
        }
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return
        if (requestLine.size < 2) { respond(out, 400, "text/plain", "bad request"); return }
        val method = requestLine[0]
        val path = requestLine[1].substringBefore('?')

        var contentLength = 0
        for (i in 1 until lines.size) {
            val header = lines[i]
            val idx = header.indexOf(':')
            if (idx > 0 && header.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = header.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }

        // Strict equality against the secret path. No URL-decoding /
        // normalization in front of it, so encoded forms can only fail to
        // match, never bypass.
        if (path != "/$token") {
            respond(out, 404, "text/plain", "not found")
            return
        }

        when (method) {
            "GET" -> respond(out, 200, "text/html; charset=utf-8", htmlPage())
            "POST" -> {
                if (contentLength <= 0 || contentLength > MAX_BODY) {
                    respondJson(out, 400, false, "empty or oversized body")
                    return
                }
                val body = readBody(input, contentLength) ?: run {
                    respondJson(out, 400, false, "incomplete body"); return
                }
                // Body is application/x-www-form-urlencoded: config=<encoded>.
                val config = parseFormField(body, "config")?.trim().orEmpty()
                if (config.isEmpty()) {
                    respondJson(out, 400, false, "no config provided")
                    return
                }
                val result = onConfig(config)
                result.fold(
                    onSuccess = { respondJson(out, 200, true, it) },
                    onFailure = { respondJson(out, 200, false, it.message ?: "import failed") },
                )
            }
            else -> respond(out, 405, "text/plain", "method not allowed")
        }
    }

    /** Reads bytes until the CRLFCRLF header terminator, capped at MAX_HEADER. */
    private fun readHeaders(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0 // matches \r \n \r \n
        while (buf.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return if (buf.size() > 0) buf.toByteArray() else null
            buf.write(b)
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                (state == 1 || state == 3) && b == '\n'.code -> state + 1
                else -> 0
            }
            if (state == 4) {
                // strip the trailing CRLFCRLF
                val arr = buf.toByteArray()
                return arr.copyOf(arr.size - 4)
            }
        }
        return null // headers too large
    }

    /** Reads exactly [length] bytes for the body, bounded by the read timeout. */
    private fun readBody(input: InputStream, length: Int): String? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = try {
                input.read(body, read, length - read)
            } catch (_: Exception) {
                break // soTimeout / reset — return what we have if complete
            }
            if (n < 0) break
            read += n
        }
        if (read < length) return null
        return String(body, StandardCharsets.UTF_8)
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"; 400 -> "400 Bad Request"; 404 -> "404 Not Found"
            405 -> "405 Method Not Allowed"; else -> "$code Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun respondJson(out: OutputStream, code: Int, ok: Boolean, message: String) {
        val safe = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        respond(out, code, "application/json; charset=utf-8", """{"ok":$ok,"message":"$safe"}""")
    }

    private fun parseFormField(body: String, field: String): String? {
        for (pair in body.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) == field) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        return null
    }

    private fun htmlPage(): String = """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VPN4TV — добавить ключ</title>
<style>
  body { font-family: -apple-system, system-ui, sans-serif; background: #0e0e12; color: #fff; margin: 0; padding: 24px; }
  .card { max-width: 560px; margin: 0 auto; }
  h1 { font-size: 22px; margin-bottom: 4px; }
  p { color: #9a9aa6; font-size: 14px; line-height: 1.4; }
  textarea { width: 100%; box-sizing: border-box; min-height: 140px; margin-top: 12px;
    padding: 14px; font-size: 16px; border-radius: 12px; border: 1px solid #2a2a36;
    background: #16161e; color: #fff; resize: vertical; }
  button { width: 100%; margin-top: 14px; padding: 16px; font-size: 17px; font-weight: 600;
    border: none; border-radius: 12px; background: #4f7cff; color: #fff; cursor: pointer; }
  button:disabled { opacity: .5; }
  #status { margin-top: 16px; font-size: 15px; min-height: 22px; }
  .ok { color: #43d17a; } .err { color: #ff6b6b; }
</style>
</head>
<body>
<div class="card">
  <h1>Добавить ключ или подписку</h1>
  <p>Вставьте ссылку подписки (https://…) либо ключ vless:// / vmess:// / trojan:// / ss:// / naive+https:// / wg://. Можно несколько строк.</p>
  <textarea id="cfg" placeholder="https://…  или  vless://…" autofocus></textarea>
  <button id="go" onclick="send()">Подключить</button>
  <div id="status"></div>
</div>
<script>
  async function send() {
    var btn = document.getElementById('go');
    var st = document.getElementById('status');
    var cfg = document.getElementById('cfg').value.trim();
    if (!cfg) { st.textContent = 'Вставьте ключ или ссылку'; st.className = 'err'; return; }
    btn.disabled = true; st.textContent = 'Отправляем…'; st.className = '';
    try {
      var r = await fetch(location.pathname, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'config=' + encodeURIComponent(cfg)
      });
      var j = await r.json();
      if (j.ok) { st.textContent = '✓ ' + (j.message || 'Готово — проверьте телевизор'); st.className = 'ok'; }
      else { st.textContent = '✗ ' + (j.message || 'Не удалось'); st.className = 'err'; btn.disabled = false; }
    } catch (e) {
      st.textContent = '✗ Ошибка соединения с телевизором'; st.className = 'err'; btn.disabled = false;
    }
  }
</script>
</body>
</html>
""".trimIndent()

    companion object {
        private const val TAG = "LanImportServer"
        private const val MAX_BODY = 512 * 1024 // a subscription is at most a few hundred KB
        private const val MAX_HEADER = 16 * 1024
        private const val READ_TIMEOUT_MS = 10_000
        private const val AUTO_STOP_MS = 5 * 60 * 1000L // hard backstop: 5 min

        private val secureRandom = SecureRandom()

        // 4 chars from an unambiguous lowercase-alnum alphabet (no 0/1/l/o/i)
        // so the URL stays short + quick to type on a phone:
        // http://<ip>:<port>/a7k2. ~20 bits — adequate here: LAN-only, an
        // ephemeral random port the attacker must also find, and the server
        // lives only while the import screen is open (5-min hard cap), so
        // brute-forcing within the window is impractical.
        private const val TOKEN_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

        private fun randomToken(): String =
            (0 until 4).map { TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)] }.joinToString("")

        /**
         * First site-local IPv4 on a real LAN interface. Excludes the VPN tun
         * (172.19.0.x is site-local but unreachable from the phone) and other
         * virtual interfaces — picking the tun address would just show the
         * user a URL that never loads.
         */
        fun lanIpv4(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback && !isVirtual(it.name) }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            } catch (e: Exception) {
                null
            }
        }

        private fun isVirtual(name: String): Boolean {
            return name.startsWith("tun") || name.startsWith("ppp") ||
                name.startsWith("rmnet") || name.startsWith("dummy")
        }
    }
}
