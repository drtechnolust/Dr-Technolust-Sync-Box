package com.drtech.tuya

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A Tuya device that announced itself on the local network. */
data class DiscoveredDevice(
    val ip: String,
    val deviceId: String,
    val productKey: String?,
    val version: String?,
)

/**
 * Finds Tuya devices by listening for their periodic UDP broadcasts (ports 6666
 * and 6667). The broadcast carries the device's address, ID and product key and
 * is encrypted with a key that is public in every Tuya LAN implementation; it
 * does NOT contain the device's local key.
 */
object TuyaDiscovery {
    private val broadcastKey: ByteArray =
        MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray(Charsets.US_ASCII))

    private const val PREFIX = 0x000055AA
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    /** Listens for [timeoutMs] and returns every distinct device heard. */
    fun scan(timeoutMs: Long = 7_000): List<DiscoveredDevice> {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val sockets = listOf(6666, 6667).mapNotNull { port ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 250
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline && sockets.isNotEmpty()) {
                for (socket in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val data = buffer.copyOfRange(0, packet.length)
                    parsePacket(data, packet.address.hostAddress)?.let { found[it.deviceId] = it }
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
        return found.values.toList()
    }

    /**
     * Parses one broadcast frame. [sourceIp] (the packet's real sender) wins over any
     * address the payload claims. Returns null for anything that is not a recognizable announce.
     */
    fun parsePacket(data: ByteArray, sourceIp: String?): DiscoveredDevice? {
        if (data.size < 28 || ByteBuffer.wrap(data).getInt(0) != PREFIX) return null
        // 16-byte header, 4-byte return code, payload, 4-byte CRC, 4-byte suffix.
        val body = data.copyOfRange(20, data.size - 8)
        val text = decryptOrPlain(body) ?: return null
        val json = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        val id = (json["gwId"] ?: json["devId"]) as? String ?: return null
        // The announce is unauthenticated, so trust the address it really came from over the
        // one it claims; fall back to the claim (if it is a plain IPv4) only without a sender.
        val ip = sourceIp?.takeIf { IPV4.matches(it) }
            ?: (json["ip"] as? String)?.takeIf { IPV4.matches(it) }
            ?: return null
        return DiscoveredDevice(
            ip = ip,
            deviceId = id,
            productKey = json["productKey"] as? String,
            version = json["version"] as? String,
        )
    }

    private fun decryptOrPlain(body: ByteArray): String? {
        runCatching {
            String(TuyaCrypto.aesEcb(broadcastKey, body, encrypt = false), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }?.let { return it }
        return String(body, Charsets.UTF_8).takeIf { it.trimStart().startsWith("{") }
    }

    /** Test helper: builds a frame the way a device would announce itself. */
    internal fun buildAnnounce(json: String): ByteArray {
        val encrypted = TuyaCrypto.aesEcb(broadcastKey, json.toByteArray(Charsets.UTF_8), encrypt = true)
        val buffer = ByteBuffer.allocate(16 + 4 + encrypted.size + 8)
        buffer.putInt(PREFIX).putInt(0).putInt(0x13).putInt(4 + encrypted.size + 8)
        buffer.putInt(0).put(encrypted).putInt(0).putInt(0x0000AA55)
        return buffer.array()
    }
}
