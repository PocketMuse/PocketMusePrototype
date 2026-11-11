package com.example.pocketmuseprototype.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The app's session mutex serializes all reads and writes to this file and key. */
internal class KeystoreSessionStore(context: Context) : SessionStore {
    private val file = context.noBackupFilesDir.resolve("civitai-session-v1").toPath()
    private val staging = file.resolveSibling("civitai-session-v1.tmp")

    override suspend fun read(): TokenPair? = withContext(Dispatchers.IO) {
        val bytes = try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return@withContext null
        }
        if (bytes.size < IV_BYTES + TAG_BITS / Byte.SIZE_BITS) throw SessionRejected()
        val keys = keyStore()
        try {
            val key = keys.getKey(KEY_ALIAS, null) as? SecretKey ?: throw SessionRejected()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            Json.decodeFromString<TokenPair>(plaintext.decodeToString())
        } catch (_: KeyPermanentlyInvalidatedException) {
            try {
                keys.deleteEntry(KEY_ALIAS)
            } catch (_: GeneralSecurityException) {
                throw IOException("Cannot reset Civitai encryption key")
            }
            throw SessionRejected()
        } catch (_: AEADBadTagException) {
            throw SessionRejected()
        } catch (_: SerializationException) {
            throw SessionRejected()
        } catch (_: GeneralSecurityException) {
            throw IOException("Cannot decrypt Civitai session")
        } catch (_: ProviderException) {
            throw IOException("Civitai session encryption provider unavailable")
        }
    }

    override suspend fun write(tokens: TokenPair?) = withContext(Dispatchers.IO) {
        if (tokens == null) {
            Files.deleteIfExists(staging)
            Files.deleteIfExists(file)
            return@withContext
        }
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, keyForWrite())
            }
        } catch (_: GeneralSecurityException) {
            throw IOException("Cannot initialize Civitai session encryption")
        } catch (_: ProviderException) {
            throw IOException("Civitai session encryption provider unavailable")
        }
        val ciphertext = try {
            cipher.doFinal(Json.encodeToString(tokens).encodeToByteArray())
        } catch (_: GeneralSecurityException) {
            throw IOException("Cannot encrypt Civitai session")
        } catch (_: ProviderException) {
            throw IOException("Civitai session encryption provider unavailable")
        }
        try {
            FileOutputStream(staging.toFile()).use { stream ->
                stream.write(cipher.iv)
                stream.write(ciphertext)
                stream.fd.sync()
            }
            Files.move(staging, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
        Unit
    }

    private fun keyForWrite(): SecretKey {
        val existing = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (_: GeneralSecurityException) {
        throw IOException("Cannot open Civitai key store")
    } catch (_: ProviderException) {
        throw IOException("Civitai session encryption provider unavailable")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pocketmuse.civitai.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
