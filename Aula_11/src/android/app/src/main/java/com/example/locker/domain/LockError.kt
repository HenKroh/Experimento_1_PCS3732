package com.example.locker.domain

import com.example.locker.protocol.LockProtocol

/**
 * Erros que o app sabe explicar ao usuário.
 *
 * Espelha `ios/SmartLock/Domain/LockError.swift`; a `message` é o texto exibido
 * na interface, então precisa fazer sentido para quem está diante da porta.
 */
sealed class LockError(message: String) : Exception(message) {
    /** Rádio desligado, sem permissão, ou aparelho sem BLE. */
    class BluetoothUnavailable(reason: String) : LockError(reason)

    object NotConnected : LockError("Sem conexão com a fechadura.")

    class CharacteristicMissing(name: String) :
        LockError("A fechadura não expõe a característica $name.")

    object Timeout : LockError("A fechadura não respondeu a tempo.")

    object Cancelled : LockError("Operação cancelada.")

    class UnsupportedProtocol(version: Int) : LockError(
        "Fechadura usa a versão $version do protocolo; o app usa a ${LockProtocol.VERSION}."
    )

    object MalformedResponse : LockError("Resposta da fechadura em formato inválido.")

    object NoCredential :
        LockError("Este aparelho ainda não tem credencial para esta fechadura.")

    class AccessDenied(reason: String?) :
        LockError(reason ?: "Acesso negado pela fechadura.")

    object RateLimited :
        LockError("Muitas tentativas inválidas. Aguarde antes de tentar de novo.")

    /** Falha no Keystore/armazenamento local da credencial. */
    class Storage(reason: String) : LockError("Falha ao guardar a credencial: $reason")

    /** Qualquer falha do canal BLE que não se encaixe nos casos acima. */
    class Transport(message: String) : LockError(message)
}

/** Texto pronto para a interface, mesmo para exceções que não são [LockError]. */
fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Falha inesperada (${this::class.simpleName})."
