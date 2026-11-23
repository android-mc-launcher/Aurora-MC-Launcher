package com.yuanbao.mcjlauncher.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密：用 Android Keystore 里的 AES-256-GCM 密钥加密 accessToken / refreshToken。
 * 密文格式 Base64(12 字节 IV || ciphertext+tag)，明文永不落盘。
 * minSdk 24 上 KeyGenParameterSpec 可用；密钥 material 不出 TEE/StrongBox。
 */
object SecurePrefs {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "mcjl.token.aes256gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val merged = ByteArray(IV_SIZE + body.size)
        System.arraycopy(iv, 0, merged, 0, IV_SIZE)
        System.arraycopy(body, 0, merged, IV_SIZE, body.size)
        return Base64.encodeToString(merged, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        return runCatching {
            val merged = Base64.decode(payload, Base64.NO_WRAP)
            require(merged.size > IV_SIZE) { "ciphertext too short" }
            val iv = merged.copyOfRange(0, IV_SIZE)
            val body = merged.copyOfRange(IV_SIZE, merged.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
