package com.drtech.tuya

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Local-network client for Tuya protocol 3.4 devices (TCP 6668).
 *
 * The 16-byte [localKey] is held only in memory, is never included in messages
 * or exception text, and is used solely to authenticate the session-key
 * negotiation. Calls are serialized; use one instance per device.
 */
class TuyaLanClient(
    private val host: String,
    private val deviceId: String,
    private val localKey: ByteArray,
    private val port: Int = 6668,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : Closeable {
    init {
        require(localKey.size == 16) { "Tuya local key must be exactly 16 bytes" }
    }

    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var sessionKey: ByteArray? = null
    private var seq = 0
    private val random = SecureRandom()

    /** Most recent DPS values seen from query responses and device pushes. */
    val lastDps: Map<String, Any?> get() = synchronized(this) { LinkedHashMap(dpsCache) }
    private val dpsCache = LinkedHashMap<String, Any?>()

    val isConnected: Boolean get() = synchronized(this) { sessionKey != null && socket?.isClosed == false }

    @Synchronized
    fun connect() {
        close()
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            s.tcpNoDelay = true
            input = s.getInputStream()
            output = s.getOutputStream()
            negotiateSessionKey()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Read-only DPS query. Returns the device's current data points keyed by numeric ID. */
    @Synchronized
    fun queryDps(): Map<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        body["gwId"] = deviceId
        body["devId"] = deviceId
        body["uid"] = deviceId
        body["t"] = clock().toString()
        send(TuyaCommand.DP_QUERY, MiniJson.write(body).toByteArray(Charsets.UTF_8))
        val reply = readUntil { it.cmd == TuyaCommand.DP_QUERY || it.cmd == TuyaCommand.DP_QUERY_NEW }
        return parseDps(reply.payload) ?: throw TuyaProtocolException(
            "Device returned no data points",
        )
    }

    /** Sends a control command. Keys are numeric DP IDs as strings. Returns the device return code. */
    @Synchronized
    fun setDps(dps: Map<String, Any?>): Int {
        require(dps.isNotEmpty()) { "No DPS supplied" }
        val body = LinkedHashMap<String, Any?>()
        body["protocol"] = 5
        body["t"] = clock()
        body["data"] = mapOf("dps" to dps)
        send(TuyaCommand.CONTROL_NEW, MiniJson.write(body).toByteArray(Charsets.UTF_8))
        val ack = readUntil { it.cmd == TuyaCommand.CONTROL_NEW }
        return ack.retCode ?: 0
    }

    @Synchronized
    fun heartbeat() {
        send(TuyaCommand.HEART_BEAT, ByteArray(0))
        readUntil { it.cmd == TuyaCommand.HEART_BEAT }
    }

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        sessionKey?.fill(0)
        sessionKey = null
    }

    /** Interrupt a blocked read/connect without waiting for the client monitor.
     * Call close() on a worker afterwards to clear the session key. */
    fun abort() {
        runCatching { socket?.close() }
    }

    private fun negotiateSessionKey() {
        val localNonce = ByteArray(16).also(random::nextBytes)
        sendRaw(TuyaCommand.SESS_KEY_NEG_START, localNonce, localKey)

        val resp = TuyaCodec.decode(TuyaCodec.readFrame(requireNotNull(input)), localKey)
        if (resp.cmd != TuyaCommand.SESS_KEY_NEG_RESP) {
            throw TuyaProtocolException("Unexpected reply during negotiation (cmd 0x${resp.cmd.toString(16)})")
        }
        if (resp.payload.size != 48) throw TuyaProtocolException("Malformed negotiation reply")
        val remoteNonce = resp.payload.copyOfRange(0, 16)
        val proof = resp.payload.copyOfRange(16, 48)
        if (!TuyaCrypto.constantTimeEquals(proof, TuyaCrypto.hmacSha256(localKey, localNonce))) {
            throw TuyaProtocolException("Device failed key proof; local key is probably wrong")
        }

        sendRaw(TuyaCommand.SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(localKey, remoteNonce), localKey)

        val mixed = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        sessionKey = TuyaCrypto.aesEcb(localKey, mixed, encrypt = true, pkcs7 = false)
    }

    private fun send(cmd: Int, payload: ByteArray) {
        val key = sessionKey ?: throw TuyaProtocolException("Not connected")
        sendRaw(cmd, payload, key)
    }

    private fun sendRaw(cmd: Int, payload: ByteArray, key: ByteArray) {
        val out = output ?: throw TuyaProtocolException("Not connected")
        seq += 1
        out.write(TuyaCodec.encode(cmd, seq, payload, key))
        out.flush()
    }

    private fun readUntil(accept: (TuyaMessage) -> Boolean): TuyaMessage {
        val key = sessionKey ?: throw TuyaProtocolException("Not connected")
        val stream = input ?: throw TuyaProtocolException("Not connected")
        repeat(16) {
            val message = TuyaCodec.decode(TuyaCodec.readFrame(stream), key)
            if (message.cmd == TuyaCommand.STATUS) {
                parseDps(message.payload)?.let { dpsCache.putAll(it) }
            }
            if (accept(message)) {
                if (message.cmd == TuyaCommand.DP_QUERY || message.cmd == TuyaCommand.DP_QUERY_NEW) {
                    parseDps(message.payload)?.let { dpsCache.putAll(it) }
                }
                return message
            }
        }
        throw TuyaProtocolException("No matching reply from device")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDps(payload: ByteArray): Map<String, Any?>? {
        if (payload.isEmpty()) return null
        val parsed = try {
            MiniJson.parse(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        } as? Map<String, Any?> ?: return null
        val direct = parsed["dps"] as? Map<String, Any?>
        if (direct != null) return direct
        val data = parsed["data"] as? Map<String, Any?>
        return data?.get("dps") as? Map<String, Any?>
    }
}
