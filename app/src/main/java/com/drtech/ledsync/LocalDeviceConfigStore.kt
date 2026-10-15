package com.drtech.ledsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything needed to talk to the owner's Sync Box over the LAN. */
class LocalDeviceConfig(
    val host: String,
    val deviceId: String,
    /** 16-byte Tuya local key. Held in memory only; never logged or displayed. */
    val localKey: ByteArray,
)

/**
 * Keeps the device configuration encrypted with an Android Keystore AES-GCM key.
 *
 * Provisioning (one time, over ADB, debug builds): place a plaintext JSON file
 * `local-device.import.json` containing `{"host","deviceId","localKey"}` in the
 * app's private files directory. The next launch seals it into
 * `local-device.sealed` and deletes the plaintext import.
 */
class LocalDeviceConfigStore(context: Context) {
    private val dir: File = context.filesDir
    private val importFile = File(dir, IMPORT_NAME)
    private val sealedFile = AtomicFile(File(dir, SEALED_NAME))

    fun load(): LocalDeviceConfig? {
        runCatching {
            if (BuildConfig.DEBUG && importFile.exists()) {
                seal(importFile.readText(Charsets.UTF_8))
                importFile.delete()
            }
        }
        return runCatching {
            val json = JSONObject(open(sealedFile.readFully()))
            val key = json.getString("localKey").toByteArray(Charsets.US_ASCII)
            if (key.size != 16) return null
            LocalDeviceConfig(json.getString("host"), json.getString("deviceId"), key)
        }.getOrNull()
    }

    /** Validates and stores a box configuration, replacing any previous one. */
    fun save(host: String, deviceId: String, localKey: String) {
        seal(JSONObject().put("host", host).put("deviceId", deviceId).put("localKey", localKey).toString())
    }

    /** Forgets the stored box (address, ID and key). */
    fun clear() {
        sealedFile.delete()
        importFile.delete()
    }

    private fun seal(plainJson: String) {
        // Validate before storing so a malformed import never replaces a good config.
        val parsed = JSONObject(plainJson)
        require(parsed.getString("localKey").length == 16)
        require(parsed.getString("localKey").all { it.code in 33..126 }) { "Local key must be printable ASCII" }
        require(parsed.getString("host").isNotBlank() && parsed.getString("deviceId").isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val stream = sealedFile.startWrite()
        try {
            stream.write(cipher.iv + body)
            sealedFile.finishWrite(stream)
        } catch (error: Exception) {
            sealedFile.failWrite(stream)
            throw error
        }
    }

    private fun open(blob: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "ledsync_local_device"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val IMPORT_NAME = "local-device.import.json"
        const val SEALED_NAME = "local-device.sealed"
    }
}
