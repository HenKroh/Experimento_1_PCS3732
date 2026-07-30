"""Desafio–resposta com HMAC-SHA256.

Reproduz, do lado do periférico, as mesmas regras que o `MockLockTransport` do
app iOS implementa — que serve como especificação executável do firmware:

* cada leitura de `Authentication Challenge` gera um nonce novo;
* o nonce expira em `ttl` segundos e só vale uma vez;
* a autenticação bem-sucedida abre uma janela curta, válida para um único
  `Unlock Command` e para o `deviceId` que se autenticou;
* tentativas inválidas seguidas levam a bloqueio temporário (`rate_limited`).

O estado é mantido por *peer* (o endereço BLE do celular conectado), então dois
celulares em paralelo não embaralham desafios um do outro.
"""

from __future__ import annotations

import hmac
import os
import time
from dataclasses import dataclass
from hashlib import sha256
from typing import Callable

from . import protocol


def response_mac(
    secret: bytes,
    nonce: bytes,
    device_id: str,
    context: str = protocol.UNLOCK_CONTEXT,
) -> bytes:
    """`HMAC-SHA256(secret, contexto || 0x00 || deviceId || 0x00 || nonce)`.

    Tem de bater byte a byte com `LockCrypto.response` no iOS e com o
    equivalente no Android.
    """
    message = context.encode("utf-8") + b"\x00" + device_id.encode("utf-8") + b"\x00" + nonce
    return hmac.new(secret, message, sha256).digest()


@dataclass(frozen=True)
class Outcome:
    """Resultado de uma etapa do protocolo, pronto para virar Operation Result."""

    status: str
    reason: str | None = None
    device_id: str | None = None

    @property
    def ok(self) -> bool:
        return self.status == protocol.STATUS_OK


@dataclass
class _Challenge:
    nonce: bytes
    expires_at: float


@dataclass
class _Session:
    device_id: str
    expires_at: float


class Authenticator:
    """Máquina de desafio–resposta, sem dependência de BLE ou de GPIO."""

    def __init__(
        self,
        secret_provider: Callable[[str], bytes | None],
        *,
        ttl: float = 5.0,
        max_failed_attempts: int = 3,
        lockout_duration: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
        randomness: Callable[[int], bytes] = os.urandom,
    ) -> None:
        # Recebe o deviceId e devolve o segredo de 32 bytes, ou None se o
        # dispositivo for desconhecido ou estiver revogado.
        self._secret_provider = secret_provider
        self._ttl = ttl
        self._max_failed_attempts = max_failed_attempts
        self._lockout_duration = lockout_duration
        self._clock = clock
        self._randomness = randomness

        self._challenges: dict[str, _Challenge] = {}
        self._sessions: dict[str, _Session] = {}
        # Nonces já gastos, com o instante em que podem ser esquecidos. Guardar
        # até a expiração basta: depois disso o desafio já seria recusado por
        # tempo, então não há janela de repetição.
        self._used_nonces: dict[bytes, float] = {}
        self._failures: dict[str, int] = {}
        self._locked_out_until: dict[str, float] = {}

    @property
    def ttl(self) -> float:
        return self._ttl

    # ----------------------------------------------------------------- #
    # Desafio
    # ----------------------------------------------------------------- #

    def issue_challenge(self, peer: str) -> tuple[bytes, float]:
        """Gera um nonce novo para `peer` e invalida o desafio anterior dele."""
        self._purge_used_nonces()
        now = self._clock()

        nonce = self._randomness(protocol.NONCE_LENGTH)
        self._challenges[peer] = _Challenge(nonce=nonce, expires_at=now + self._ttl)
        # Ler um desafio novo derruba a autenticação anterior: só a prova mais
        # recente vale, e ela ainda não chegou.
        self._sessions.pop(peer, None)
        return nonce, self._ttl

    # ----------------------------------------------------------------- #
    # Resposta
    # ----------------------------------------------------------------- #

    def verify_response(self, peer: str, device_id: str, mac: bytes) -> Outcome:
        """Confere a prova criptográfica e, se válida, abre a janela de comando."""
        now = self._clock()

        if self._is_locked_out(peer, now):
            return Outcome(protocol.STATUS_RATE_LIMITED, "Bloqueado temporariamente.")

        challenge = self._challenges.pop(peer, None)
        if challenge is None:
            return Outcome(protocol.STATUS_ERROR, "Nenhum desafio ativo.")

        if challenge.expires_at <= now:
            return Outcome(protocol.STATUS_DENIED, "Desafio expirado.")

        if challenge.nonce in self._used_nonces:
            return Outcome(protocol.STATUS_DENIED, "Nonce já utilizado.")

        secret = self._secret_provider(device_id)
        if secret is None:
            # Um deviceId desconhecido conta como tentativa inválida: é o que um
            # atacante tentaria primeiro.
            self._register_failure(peer, now)
            return Outcome(protocol.STATUS_DENIED, "Dispositivo não autorizado.", device_id)

        expected = response_mac(secret, challenge.nonce, device_id)
        if not hmac.compare_digest(expected, mac):
            self._register_failure(peer, now)
            return Outcome(protocol.STATUS_DENIED, "Prova criptográfica inválida.", device_id)

        self._used_nonces[challenge.nonce] = now + self._ttl
        self._failures.pop(peer, None)
        self._sessions[peer] = _Session(device_id=device_id, expires_at=now + self._ttl)
        return Outcome(protocol.STATUS_OK, None, device_id)

    # ----------------------------------------------------------------- #
    # Comando
    # ----------------------------------------------------------------- #

    def consume_session(self, peer: str, device_id: str) -> Outcome:
        """Valida e gasta a autenticação: o mesmo desafio não abre a porta duas vezes."""
        now = self._clock()
        session = self._sessions.pop(peer, None)

        if session is None or session.expires_at <= now or session.device_id != device_id:
            return Outcome(protocol.STATUS_DENIED, "Sessão não autenticada.", device_id)

        self._challenges.pop(peer, None)
        return Outcome(protocol.STATUS_OK, None, device_id)

    # ----------------------------------------------------------------- #
    # Ciclo de vida da conexão
    # ----------------------------------------------------------------- #

    def forget_peer(self, peer: str) -> None:
        """Descarta o estado volátil de um celular que se desconectou.

        O bloqueio por tentativas inválidas *não* é descartado — senão bastaria
        reconectar para zerar o contador.
        """
        self._challenges.pop(peer, None)
        self._sessions.pop(peer, None)

    # ----------------------------------------------------------------- #
    # Auxiliares
    # ----------------------------------------------------------------- #

    def _is_locked_out(self, peer: str, now: float) -> bool:
        until = self._locked_out_until.get(peer)
        if until is None:
            return False
        if until > now:
            return True
        del self._locked_out_until[peer]
        return False

    def _register_failure(self, peer: str, now: float) -> None:
        failures = self._failures.get(peer, 0) + 1
        if failures >= self._max_failed_attempts:
            self._locked_out_until[peer] = now + self._lockout_duration
            self._failures.pop(peer, None)
        else:
            self._failures[peer] = failures

    def _purge_used_nonces(self) -> None:
        now = self._clock()
        expired = [nonce for nonce, deadline in self._used_nonces.items() if deadline <= now]
        for nonce in expired:
            del self._used_nonces[nonce]
