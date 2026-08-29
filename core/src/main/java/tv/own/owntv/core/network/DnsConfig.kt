package tv.own.owntv.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Immutable snapshot of the global custom DNS config — a sibling to the global proxy.
 * Two modes: a plain DNS-over-UDP IP (host + port), or a DNS-over-HTTPS (DoH) URL.
 *
 * DNS resolution happens BEFORE a request is sent, so this is a per-OkHttpClient setting,
 * not a per-request one. The DNS is resolved by the singleton client, which reads the
 * live snapshot from [DnsConfigHolder.current] on every lookup.
 */
data class DnsConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DNS_DEFAULT_PORT,
    val dohUrl: String = "",
) {
    /** Host:port mode enabled with a sane host/port. */
    val plainUsable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535

    /** DoH mode enabled with a URL. */
    val dohUsable: Boolean get() = enabled && dohUrl.isNotBlank()

    companion object {
        const val DNS_DEFAULT_PORT = 53
    }
}

/**
 * Well-known DNS-over-HTTPS endpoints — offered as one-tap presets in the settings UI.
 */
object DohPresets {
    val GOOGLE = "https://dns.google/dns-query"
    val CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
    val QUAD9 = "https://dns.quad9.net/dns-query"

    val all = listOf(
        "Google" to GOOGLE,
        "Cloudflare" to CLOUDFLARE,
        "Quad9" to QUAD9,
    )
}

/**
 * Live holder for the custom DNS config — same pattern as [ProxyConfigHolder].
 * Provides an OkHttp [Dns] that reads the live snapshot so DNS can be toggled at
 * runtime without rebuilding the singleton [OkHttpClient].
 *
 * DNS-over-HTTPS bootstraps itself: the DoH requests go through a separate bootstrap
 * OkHttpClient that uses system DNS so there is no infinite loop.
 */
class DnsConfigHolder(
    configFlow: Flow<DnsConfig>,
    initialConfig: DnsConfig = DnsConfig(),
    private val fallbackToSystem: Boolean = true,
) {

    @Volatile
    private var current: DnsConfig = initialConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bootstrap client for DoH requests — always uses system DNS, never our custom DNS. */
    private val bootstrapClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    init {
        configFlow.onEach { current = it }.launchIn(scope)
    }

    fun snapshot(): DnsConfig = current

    /**
     * The [Dns] implementation fed to OkHttpClient.Builder. On every lookup it reads
     * the live [current] snapshot and routes to the appropriate backend, falling back
     * to system DNS when custom DNS is disabled or errors occur.
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cfg = current
            return when {
                cfg.dohUsable -> resolveViaDoH(hostname, cfg.dohUrl)
                cfg.plainUsable -> resolveViaUdp(hostname, cfg.host, cfg.port)
                else -> Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    // --- DNS-over-HTTPS (RFC 8484) via manual HTTP requests ---

    private fun resolveViaDoH(hostname: String, dohUrl: String): List<InetAddress> {
        return try {
            val results = mutableListOf<InetAddress>()
            // Query both A and AAAA, merge results
            results.addAll(dohLookup(hostname, dohUrl, "A"))
            results.addAll(dohLookup(hostname, dohUrl, "AAAA"))
            if (results.isNotEmpty()) {
                Log.d(TAG, "DoH lookup $hostname → ${results.map { it.hostAddress }} (server=$dohUrl)")
                results
            } else {
                if (!fallbackToSystem) return emptyList()
                Log.w(TAG, "DoH lookup returned no addresses for $hostname, falling back to system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
        } catch (e: Exception) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "DoH lookup failed for $hostname, falling back to system DNS", e)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun dohLookup(hostname: String, dohUrl: String, type: String): List<InetAddress> {
        val url = "${dohUrl.trimEnd('/')}?name=$hostname&type=$type"
        val request = Request.Builder().url(url)
            .header("Accept", "application/dns-json")
            .build()
        val response = bootstrapClient.newCall(request).execute()
        return response.use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("DoH HTTP ${resp.code}")
            val body = resp.body.string()
            parseDohJson(body)
        }
    }

    private fun parseDohJson(json: String): List<InetAddress> {
        val obj = JSONObject(json)
        val answers = obj.optJSONArray("Answer") ?: return emptyList()
        val results = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            val data = a.optString("data", "")
            if (data.isBlank()) continue
            try {
                // data is either an IPv4 string ("1.2.3.4") or an IPv6 string ("::1")
                val addr = InetAddress.getByName(data)
                // Only keep the type we asked for (A or AAAA may both appear)
                if (addr is Inet4Address || addr is Inet6Address) {
                    results.add(addr)
                }
            } catch (_: Exception) {
                // skip malformed entries
            }
        }
        return results
    }

    // --- Plain DNS-over-UDP resolver ---

    private fun resolveViaUdp(hostname: String, server: String, port: Int): List<InetAddress> {
        try {
            // Both families, like the DoH path: asking only for A left an AAAA-only host unresolvable
            // whenever custom plain DNS was in use. A failure of the AAAA half never costs the A answers.
            val result = askUdp(hostname, DNS_TYPE_A, server, port) +
                runCatching { askUdp(hostname, DNS_TYPE_AAAA, server, port) }.getOrDefault(emptyList())
            Log.d(TAG, "UDP DNS lookup $hostname → ${result.map { it.hostAddress }} (server=$server:$port)")
            return if (result.isNotEmpty() || !fallbackToSystem) result else Dns.SYSTEM.lookup(hostname)
        } catch (e: SocketTimeoutException) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "UDP DNS timeout for $hostname via $server:$port, falling back to system DNS")
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: Exception) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "UDP DNS lookup failed for $hostname via $server:$port, falling back to system DNS", e)
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** One question of [qtype], with its own random ID checked against the answer. */
    @Throws(Exception::class)
    private fun askUdp(hostname: String, qtype: Int, server: String, port: Int): List<InetAddress> {
        val id = randomQueryId()
        val response = sendUdpQuery(buildDnsQuery(hostname, qtype, id), server, port)
        return parseDnsResponse(response, id)
    }

    @Throws(Exception::class)
    private fun sendUdpQuery(query: ByteArray, server: String, port: Int): ByteArray {
        val sock = DatagramSocket()
        sock.soTimeout = REQUEST_TIMEOUT_MS
        try {
            val addr = InetSocketAddress(server, port)
            val req = DatagramPacket(query, query.size, addr)
            sock.send(req)
            val buf = ByteArray(RESPONSE_BUFFER_SIZE)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return resp.data.copyOf(resp.length)
        } finally {
            sock.close()
        }
    }

    companion object {
        private const val TAG = "DnsConfig"
        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val RESPONSE_BUFFER_SIZE = 1024

        // DNS header offsets (12 bytes)
        private const val DNS_HEADER_LEN = 12
        private const val DNS_FLAG_QR_MASK = 0x8000
        private const val DNS_FLAG_OPCODE_MASK = 0x7800
        private const val DNS_FLAG_RCODE_MASK = 0x000F
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_CLASS_IN = 1

        /**
         * One DNS question for [hostname].
         *
         * [qtype] is [DNS_TYPE_A] or [DNS_TYPE_AAAA] — the UDP resolver asks for both, because DoH always
         * did and a host that publishes only AAAA was unreachable through the plain-DNS path alone.
         *
         * [id] is random per query and checked on the way back. It used to be the constant 1, which makes
         * an off-path forged answer trivial to accept: anything arriving on the socket in time matched.
         */
        fun buildDnsQuery(hostname: String, qtype: Int = DNS_TYPE_A, id: Int = randomQueryId()): ByteArray {
            val labels = hostname.split('.')
            // Header (12) + labels + null terminator + QTYPE (2) + QCLASS (2)
            var size = DNS_HEADER_LEN + 1 + 4 // minimum
            for (label in labels) size += label.length + 1
            val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            // Header
            buf.putShort(id.toShort()) // ID — random, verified in parseDnsResponse
            buf.putShort(0x0100) // Flags: standard query, recursion desired
            buf.putShort(0x0001) // QDCOUNT = 1
            buf.putShort(0x0000) // ANCOUNT = 0
            buf.putShort(0x0000) // NSCOUNT = 0
            buf.putShort(0x0000) // ARCOUNT = 0
            // Question — QNAME
            for (label in labels) {
                buf.put(label.length.toByte())
                buf.put(label.encodeToByteArray())
            }
            buf.put(0x00.toByte()) // null terminator
            buf.putShort(qtype.toShort())
            buf.putShort(DNS_CLASS_IN.toShort())
            return buf.array()
        }

        /** A query ID in 1..65535 from a cryptographically strong source. */
        fun randomQueryId(): Int = java.security.SecureRandom().nextInt(0xFFFF) + 1

        fun parseDnsResponse(data: ByteArray, expectedId: Int? = null): List<InetAddress> {
            if (data.size < DNS_HEADER_LEN) return emptyList()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val id = buf.getShort()
            val flags = buf.getShort()
            if (expectedId != null && id != expectedId.toShort()) return emptyList()
            // Check QR (response), no error, standard query
            val qr = (flags.toInt() and DNS_FLAG_QR_MASK) != 0
            val rcode = flags.toInt() and DNS_FLAG_RCODE_MASK
            val opcode = (flags.toInt() and DNS_FLAG_OPCODE_MASK) ushr 11
            if (!qr || rcode != 0 || opcode != 0) return emptyList()
            val qdCount = buf.getShort().toInt() and 0xFFFF
            val anCount = buf.getShort().toInt() and 0xFFFF

            // Skip question section
            var pos = buf.position()
            for (q in 0 until qdCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0) return emptyList()
                pos += 4 // QTYPE + QCLASS
            }

            // Parse answer records
            val results = mutableListOf<InetAddress>()
            for (a in 0 until anCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0) break
                if (pos + 10 > data.size) break
                val atype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val rdLen = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
                val rdPos = pos + 10
                if (rdPos + rdLen > data.size) break
                when (atype) {
                    DNS_TYPE_A -> {
                        if (rdLen == 4 && rdPos + 4 <= data.size) {
                            results.add(Inet4Address.getByAddress(data.copyOfRange(rdPos, rdPos + 4)))
                        }
                    }
                    DNS_TYPE_AAAA -> {
                        if (rdLen == 16 && rdPos + 16 <= data.size) {
                            results.add(Inet6Address.getByAddress(null, data.copyOfRange(rdPos, rdPos + 16)))
                        }
                    }
                }
                pos = rdPos + rdLen
            }
            return results
        }

        private fun skipDnsName(data: ByteArray, start: Int): Int {
            if (start >= data.size) return -1
            var pos = start
            while (pos < data.size) {
                val len = data[pos].toInt() and 0xFF
                if (len == 0) return pos + 1 // null terminator
                if (len and 0xC0 == 0xC0) return pos + 2 // pointer (2-byte)
                pos += 1 + len
            }
            return -1
        }
    }
}
