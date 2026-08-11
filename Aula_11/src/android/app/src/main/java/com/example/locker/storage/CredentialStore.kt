package com.example.locker.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.locker.domain.LockCredential
import com.example.locker.domain.LockError
import com.example.locker.protocol.Base64ByteArraySerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda uma [LockCredential] por fechadura.
 *
 * Espelha `ios/SmartLock/Keychain/CredentialStore.swift`.
 */
interface CredentialStore {
    fun all(): List<LockCredential>
    fun credential(lockId: String): LockCredential?
    fun save(credential: LockCredential)
    fun delete(lockId: String)
}

/**
 * Credenciais cifradas com uma chave AES-256-GCM do Android Keystore.
 *
 * A chave é gerada dentro do Keystore e nunca sai de lá: mesmo com acesso ao
 * arquivo de preferências, o segredo não é legível fora deste aparelho. É o
 * equivalente ao `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` do iOS — o
 * cadastro fica vinculado ao aparelho que o proprietário aprovou no botão.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    override fun all(): List<LockCredential> =
        load().values.sortedBy { it.createdAt }

    override fun credential(lockId: String): LockCredential? = load()[lockId]

    override fun save(credential: LockCredential) {
        val updated = load().toMutableMap()
        updated[credential.lockId] = credential
        persist(updated)
    }

    override fun delete(lockId: String) {
        val updated = load().toMutableMap()
        if (updated.remove(lockId) == null) return
        persist(updated)
    }

    // ----------------------------------------------------------------- //
    // Persistência
    // ----------------------------------------------------------------- //

    private fun load(): Map<String, LockCredential> {
        val stored = preferences.getString(CREDENTIALS_KEY, null) ?: return emptyMap()
        return try {
            val plaintext = decrypt(Base64.getDecoder().decode(stored))
            json.decodeFromString(ListSerializer(StoredCredential.serializer()), plaintext.decodeToString())
                .associate { it.lockId to it.toDomain() }
        } catch (error: Exception) {
            // A chave do Keystore some quando o app é restaurado em outro
            // aparelho ou quando a tela de bloqueio é removida em algumas
            // versões. Sem ela o dado é irrecuperável: apagar e pedir novo
            // cadastro é melhor do que travar o app a cada abertura.
            Log.w(TAG, "Credenciais ilegíveis, começando do zero: ${error.message}")
            preferences.edit().remove(CREDENTIALS_KEY).apply()
            emptyMap()
        }
    }

    private fun persist(credentials: Map<String, LockCredential>) {
        try {
            val plaintext = json.encodeToString(
                ListSerializer(StoredCredential.serializer()),
                credentials.values.map(StoredCredential::from),
            ).toByteArray(Charsets.UTF_8)
            val encoded = Base64.getEncoder().encodeToString(encrypt(plaintext))
            preferences.edit().putString(CREDENTIALS_KEY, encoded).apply()
        } catch (error: Exception) {
            // Falha do Keystore é o caso esperado; guardar em claro não é opção.
            throw LockError.Storage(error.message ?: error::class.java.simpleName)
        }
    }

    // ----------------------------------------------------------------- //
    // Criptografia
    // ----------------------------------------------------------------- //

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        // O IV é gerado pelo provider e viaja junto: cada gravação usa um novo.
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH) { "payload cifrado truncado" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, payload, 0, IV_LENGTH),
        )
        return cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val PREFERENCES_NAME = "smartlock_credentials"
        const val CREDENTIALS_KEY = "credentials"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.example.locker.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}

/** Forma serializada da credencial; o segredo viaja em Base64, como no protocolo. */
@Serializable
private data class StoredCredential(
    val lockId: String,
    val lockName: String,
    val deviceId: String,
    @Serializable(with = Base64ByteArraySerializer::class) val secret: ByteArray,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
) {
    fun toDomain() = LockCredential(
        lockId = lockId,
        lockName = lockName,
        deviceId = deviceId,
        secret = secret,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    companion object {
        fun from(credential: LockCredential) = StoredCredential(
            lockId = credential.lockId,
            lockName = credential.lockName,
            deviceId = credential.deviceId,
            secret = credential.secret,
            createdAt = credential.createdAt,
            lastUsedAt = credential.lastUsedAt,
        )
    }
}

/** Usado nos previews do Compose e nos testes. */
class InMemoryCredentialStore(seed: List<LockCredential> = emptyList()) : CredentialStore {
    private val storage = linkedMapOf<String, LockCredential>()

    init {
        seed.forEach { storage[it.lockId] = it }
    }

    override fun all(): List<LockCredential> = storage.values.sortedBy { it.createdAt }

    override fun credential(lockId: String): LockCredential? = storage[lockId]

    override fun save(credential: LockCredential) {
        storage[credential.lockId] = credential
    }

    override fun delete(lockId: String) {
        storage.remove(lockId)
    }
}
