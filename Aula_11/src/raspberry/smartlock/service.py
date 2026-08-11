"""Regras da fechadura, sem depender de BLE nem de GPIO real.

É aqui que cadastro, desafio–resposta, atuador e log se encontram. As
características GATT chamam os métodos `on_*`; os botões físicos chamam
`approve`/`deny`. Tudo o que sai para o celular passa por `_notify_*`.

Manter esta classe livre de D-Bus é o que permite exercitar o protocolo inteiro
nos testes, com um notificador de mentira no lugar do BlueZ.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Callable, Protocol

from . import protocol
from .authentication import Authenticator
from .config import Config
from .database import Database
from .enrollment import EnrollmentQueue, Request
from .lock_controller import LockController

LOGGER = logging.getLogger(__name__)


class Notifier(Protocol):
    """O que o serviço precisa do lado BLE."""

    def notify_approval(self, payload: bytes) -> None: ...
    def notify_result(self, payload: bytes) -> None: ...


class Scheduler(Protocol):
    """Agendamento no loop principal; trocável por um duplo determinístico."""

    def schedule(self, delay: float, callback: Callable[[], None]) -> Any: ...
    def cancel(self, handle: Any) -> None: ...
    def defer(self, callback: Callable[[], None]) -> None: ...


class SmartLockService:
    def __init__(
        self,
        config: Config,
        database: Database,
        authenticator: Authenticator,
        lock_controller: LockController,
        gpio_backend,
        scheduler: Scheduler,
        *,
        randomness: Callable[[int], bytes] = os.urandom,
    ) -> None:
        self._config = config
        self._db = database
        self._auth = authenticator
        self._lock = lock_controller
        self._gpio = gpio_backend
        self._scheduler = scheduler
        self._randomness = randomness
        self._notifier: Notifier | None = None

        self._queue = EnrollmentQueue(
            timeout=config.enrollment_timeout,
            schedule=scheduler.schedule,
            cancel=scheduler.cancel,
            on_timeout=self._on_enrollment_timeout,
        )

    def attach(self, notifier: Notifier) -> None:
        self._notifier = notifier

    # ------------------------------------------------------------------ #
    # Device Information
    # ------------------------------------------------------------------ #

    def device_info(self) -> bytes:
        return protocol.device_info(
            self._config.lock_id, self._config.lock_name, self._config.firmware
        )

    # ------------------------------------------------------------------ #
    # Cadastro
    # ------------------------------------------------------------------ #

    def on_access_request(self, peer: str, payload: bytes) -> None:
        try:
            device_id, device_name = protocol.parse_access_request(payload)
        except protocol.ProtocolError as error:
            LOGGER.warning("Access Request inválido de %s: %s", peer, error)
            self._db.log_access(protocol.OP_ENROLL, protocol.STATUS_ERROR, peer=peer, reason=str(error))
            return

        self._queue.add(peer, device_id, device_name)
        self._gpio.set_led("waiting", True)
        self._db.log_access(
            protocol.OP_ENROLL, protocol.APPROVAL_PENDING, peer=peer, device_id=device_id
        )

        # `pending` avisa o app que o pedido chegou e o LED acendeu; quem
        # encerra a espera é a decisão no botão físico.
        self._notify_approval(protocol.APPROVAL_PENDING, device_id)

    def approve(self) -> None:
        """Botão *Permitir*. Pode vir de outra thread."""
        self._scheduler.defer(self._approve_now)

    def deny(self) -> None:
        """Botão *Negar*. Pode vir de outra thread."""
        self._scheduler.defer(self._deny_now)

    def _approve_now(self) -> None:
        request = self._queue.resolve_head()
        if request is None:
            LOGGER.info("Botão Permitir sem solicitação pendente; ignorado")
            return

        secret = self._randomness(protocol.SECRET_LENGTH)
        self._db.register_device(request.device_id, secret, request.device_name)
        LOGGER.info("Cadastro aprovado para %r", request.device_name)

        self._db.log_access(
            protocol.OP_ENROLL,
            protocol.APPROVAL_APPROVED,
            peer=request.peer,
            device_id=request.device_id,
        )
        self._notify_approval(
            protocol.APPROVAL_APPROVED, request.device_id, secret=secret
        )
        self._blink("approved")
        self._refresh_waiting_led()

    def _deny_now(self) -> None:
        request = self._queue.resolve_head()
        if request is None:
            LOGGER.info("Botão Negar sem solicitação pendente; ignorado")
            return

        LOGGER.info("Cadastro negado para %r", request.device_name)
        self._db.log_access(
            protocol.OP_ENROLL,
            protocol.APPROVAL_DENIED,
            peer=request.peer,
            device_id=request.device_id,
        )
        self._notify_approval(protocol.APPROVAL_DENIED, request.device_id)
        self._blink("denied")
        self._refresh_waiting_led()

    def _on_enrollment_timeout(self, request: Request) -> None:
        self._db.log_access(
            protocol.OP_ENROLL,
            protocol.APPROVAL_TIMEOUT,
            peer=request.peer,
            device_id=request.device_id,
        )
        self._notify_approval(protocol.APPROVAL_TIMEOUT, request.device_id)
        self._refresh_waiting_led()

    # ------------------------------------------------------------------ #
    # Desafio–resposta
    # ------------------------------------------------------------------ #

    def on_challenge_read(self, peer: str) -> bytes:
        nonce, ttl = self._auth.issue_challenge(peer)
        return protocol.challenge(nonce, ttl)

    def on_auth_response(self, peer: str, payload: bytes) -> None:
        try:
            device_id, mac = protocol.parse_auth_response(payload)
        except protocol.ProtocolError as error:
            LOGGER.warning("Authentication Response inválido de %s: %s", peer, error)
            self._db.log_access(
                protocol.OP_AUTH, protocol.STATUS_ERROR, peer=peer, reason=str(error)
            )
            self._notify_result(protocol.OP_AUTH, protocol.STATUS_ERROR, "Mensagem malformada.")
            return

        outcome = self._auth.verify_response(peer, device_id, mac)
        # O segredo nunca entra no log; só o desfecho.
        LOGGER.info("Autenticação de %s: %s (%s)", device_id, outcome.status, outcome.reason or "-")
        self._db.log_access(
            protocol.OP_AUTH,
            outcome.status,
            peer=peer,
            device_id=device_id,
            reason=outcome.reason,
        )
        self._notify_result(protocol.OP_AUTH, outcome.status, outcome.reason)

    # ------------------------------------------------------------------ #
    # Desbloqueio
    # ------------------------------------------------------------------ #

    def on_unlock_command(self, peer: str, payload: bytes) -> None:
        try:
            device_id = protocol.parse_unlock_command(payload)
        except protocol.ProtocolError as error:
            LOGGER.warning("Unlock Command inválido de %s: %s", peer, error)
            self._db.log_access(
                protocol.OP_UNLOCK, protocol.STATUS_ERROR, peer=peer, reason=str(error)
            )
            self._notify_result(protocol.OP_UNLOCK, protocol.STATUS_ERROR, "Mensagem malformada.")
            return

        outcome = self._auth.consume_session(peer, device_id)
        if outcome.ok:
            self._lock.unlock()
            self._db.touch_device(device_id)

        self._db.log_access(
            protocol.OP_UNLOCK,
            outcome.status,
            peer=peer,
            device_id=device_id,
            reason=outcome.reason,
        )
        self._notify_result(protocol.OP_UNLOCK, outcome.status, outcome.reason)

    # ------------------------------------------------------------------ #
    # Ciclo de vida da conexão
    # ------------------------------------------------------------------ #

    def on_peer_disconnected(self, peer: str) -> None:
        """Limpa o que era da conexão; o bloqueio por tentativas continua valendo."""
        LOGGER.info("Celular %s desconectou", peer)
        self._auth.forget_peer(peer)
        self._queue.forget_peer(peer)
        self._refresh_waiting_led()

    # ------------------------------------------------------------------ #
    # Administração
    # ------------------------------------------------------------------ #

    def revoke(self, device_id: str) -> bool:
        revoked = self._db.revoke_device(device_id)
        if revoked:
            LOGGER.info("Dispositivo %s revogado", device_id)
        return revoked

    # ------------------------------------------------------------------ #
    # Auxiliares
    # ------------------------------------------------------------------ #

    def _notify_approval(self, state: str, device_id: str, secret: bytes | None = None) -> None:
        payload = protocol.approval_status(
            state, device_id, secret=secret, lock_name=self._config.lock_name
        )
        self._emit(lambda: self._notifier.notify_approval(payload) if self._notifier else None)

    def _notify_result(self, op: str, status: str, reason: str | None) -> None:
        payload = protocol.operation_result(op, status, reason)
        self._emit(lambda: self._notifier.notify_result(payload) if self._notifier else None)

    def _emit(self, send: Callable[[], None]) -> None:
        # Adiado de propósito: quando a notificação nasce dentro de um
        # `WriteValue`, mandá-la já faria a notificação correr com a resposta
        # da escrita. O app trata as duas ordens, mas a ordem natural é a que
        # se depura melhor.
        self._scheduler.defer(send)

    def _refresh_waiting_led(self) -> None:
        self._gpio.set_led("waiting", not self._queue.is_empty)

    def _blink(self, led: str, duration: float = 2.0) -> None:
        self._gpio.set_led(led, True)
        self._scheduler.schedule(duration, lambda: self._gpio.set_led(led, False))
