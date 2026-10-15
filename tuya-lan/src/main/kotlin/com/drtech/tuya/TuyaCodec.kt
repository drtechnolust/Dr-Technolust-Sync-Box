package com.drtech.tuya

import java.io.InputStream
import java.nio.ByteBuffer

class TuyaProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object TuyaCommand {
    const val SESS_KEY_NEG_START = 0x03
    const val SESS_KEY_NEG_RESP = 0x04
    const val SESS_KEY_NEG_FINISH = 0x05
    const val CONTROL = 0x07
    const val STATUS = 0x08
    const val HEART_BEAT = 0x09
    const val DP_QUERY = 0x0A
    const val CONTROL_NEW = 0x0D
    const val DP_QUERY_NEW = 0x10
    const val UPDATE_DPS = 0x12
}

class TuyaMessage(
    val seq: Int,
    val cmd: Int,
    /** Device return code; present only on device-to-client frames. */
    val retCode: Int?,
    val payload: ByteArray,
)

/**
 * Frame layout for protocol 3.4:
 * `00 00 55 AA | seq(4) | cmd(4) | len(4) | [retcode(4)] | AES-ECB payload | HMAC-SHA256(32) | 00 00 AA 55`
 * where `len` counts everything after the 16-byte header.
 */
object TuyaCodec {
    private const val PREFIX = 0x000055AA
    private const val SUFFIX = 0x0000AA55
    private const val HEADER_SIZE = 16
    private const val HMAC_SIZE = 32
    private const val SUFFIX_SIZE = 4
    const val MAX_FRAME_BODY = 64 * 1024

    private val VERSION_HEADER: ByteArray = "3.4".toByteArray(Charsets.US_ASCII) + ByteArray(12)

    /** Commands whose payload is not prefixed with the 15-byte "3.4" version header. */
    private val NO_VERSION_HEADER = setOf(
        TuyaCommand.DP_QUERY, TuyaCommand.DP_QUERY_NEW, TuyaCommand.UPDATE_DPS,
        TuyaCommand.HEART_BEAT, TuyaCommand.SESS_KEY_NEG_START,
        TuyaCommand.SESS_KEY_NEG_RESP, TuyaCommand.SESS_KEY_NEG_FINISH,
    )

    fun encode(cmd: Int, seq: Int, payload: ByteArray, key: ByteArray, retCode: Int? = null): ByteArray {
        val plain = if (cmd in NO_VERSION_HEADER) payload else VERSION_HEADER + payload
        val encrypted = TuyaCrypto.aesEcb(key, plain, encrypt = true)
        val retBytes = if (retCode != null) 4 else 0
        val bodyLength = retBytes + encrypted.size + HMAC_SIZE + SUFFIX_SIZE
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodyLength)
        buffer.putInt(PREFIX).putInt(seq).putInt(cmd).putInt(bodyLength)
        if (retCode != null) buffer.putInt(retCode)
        buffer.put(encrypted)
        val signed = buffer.array().copyOfRange(0, buffer.position())
        buffer.put(TuyaCrypto.hmacSha256(key, signed))
        buffer.putInt(SUFFIX)
        return buffer.array()
    }

    fun decode(frame: ByteArray, key: ByteArray): TuyaMessage {
        if (frame.size < HEADER_SIZE + HMAC_SIZE + SUFFIX_SIZE) throw TuyaProtocolException("Frame too short")
        val buffer = ByteBuffer.wrap(frame)
        if (buffer.getInt(0) != PREFIX) throw TuyaProtocolException("Bad frame prefix")
        if (buffer.getInt(frame.size - SUFFIX_SIZE) != SUFFIX) throw TuyaProtocolException("Bad frame suffix")
        val seq = buffer.getInt(4)
        val cmd = buffer.getInt(8)
        if (buffer.getInt(12) != frame.size - HEADER_SIZE) throw TuyaProtocolException("Bad frame length")

        val signedEnd = frame.size - HMAC_SIZE - SUFFIX_SIZE
        val expected = TuyaCrypto.hmacSha256(key, frame.copyOfRange(0, signedEnd))
        val actual = frame.copyOfRange(signedEnd, signedEnd + HMAC_SIZE)
        if (!TuyaCrypto.constantTimeEquals(expected, actual)) throw TuyaProtocolException("Frame authentication failed")

        var bodyStart = HEADER_SIZE
        var retCode: Int? = null
        // Ciphertext is always a multiple of 16 bytes, so a 4-byte remainder means a return code.
        if ((signedEnd - bodyStart) % 16 == 4) {
            retCode = buffer.getInt(bodyStart)
            bodyStart += 4
        }
        val cipherText = frame.copyOfRange(bodyStart, signedEnd)
        var plain = if (cipherText.isEmpty()) cipherText else {
            try {
                TuyaCrypto.aesEcb(key, cipherText, encrypt = false)
            } catch (e: Exception) {
                throw TuyaProtocolException("Payload decryption failed", e)
            }
        }
        if (cmd !in NO_VERSION_HEADER && plain.size >= VERSION_HEADER.size &&
            plain.copyOfRange(0, 3).contentEquals(VERSION_HEADER.copyOfRange(0, 3))
        ) {
            plain = plain.copyOfRange(VERSION_HEADER.size, plain.size)
        }
        return TuyaMessage(seq, cmd, retCode, plain)
    }

    /** Reads exactly one frame from [input] and returns its raw bytes. */
    fun readFrame(input: InputStream): ByteArray {
        val header = readFully(input, HEADER_SIZE)
        val bodyLength = ByteBuffer.wrap(header).getInt(12)
        if (bodyLength < HMAC_SIZE + SUFFIX_SIZE || bodyLength > MAX_FRAME_BODY) {
            throw TuyaProtocolException("Implausible frame length $bodyLength")
        }
        return header + readFully(input, bodyLength)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) throw TuyaProtocolException("Connection closed by device")
            read += n
        }
        return out
    }
}
