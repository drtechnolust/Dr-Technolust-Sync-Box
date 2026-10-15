package com.drtech.tuya

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Thin wrappers over the JCA primitives used by Tuya LAN protocol 3.4. */
internal object TuyaCrypto {
    fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean, pkcs7: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (pkcs7) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
        )
        return cipher.doFinal(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
