"""Fila de solicitações de cadastro pendentes.

O botão físico é um recurso único: se dois celulares pedem acesso ao mesmo
tempo, o proprietário precisa saber qual está aprovando. A fila resolve isso —
os botões sempre decidem sobre a solicitação mais antiga, e as demais continuam
esperando a sua vez (ou o próprio timeout).
"""

from __future__ import annotations

import logging
import time
from collections import deque
from dataclasses import dataclass
from typing import Any, Callable

LOGGER = logging.getLogger(__name__)


# `eq=False` de propósito: a fila procura pedidos por identidade. Com a
# igualdade estrutural do dataclass, dois pedidos idênticos do mesmo celular
# seriam o mesmo objeto para o `remove`.
@dataclass(eq=False)
class Request:
    peer: str
    device_id: str
    device_name: str
    requested_at: float
    timer: Any = None


class EnrollmentQueue:
    """Solicitações pendentes, resolvidas em ordem de chegada."""

    def __init__(
        self,
        *,
        timeout: float,
        schedule: Callable[[float, Callable[[], None]], Any],
        cancel: Callable[[Any], None],
        on_timeout: Callable[[Request], None],
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._timeout = timeout
        self._schedule = schedule
        self._cancel = cancel
        self._on_timeout = on_timeout
        self._clock = clock
        self._pending: deque[Request] = deque()

    @property
    def is_empty(self) -> bool:
        return not self._pending

    @property
    def head(self) -> Request | None:
        return self._pending[0] if self._pending else None

    def __len__(self) -> int:
        return len(self._pending)

    # ----------------------------------------------------------------- #

    def add(self, peer: str, device_id: str, device_name: str) -> Request:
        """Enfileira um pedido, substituindo outro pendente do mesmo celular.

        Reenviar o pedido (o usuário tocou duas vezes em "Solicitar acesso")
        não deve criar duas entradas na fila.
        """
        self._drop(lambda request: request.peer == peer)

        request = Request(
            peer=peer,
            device_id=device_id,
            device_name=device_name,
            requested_at=self._clock(),
        )
        request.timer = self._schedule(self._timeout, lambda: self._expire(request))
        self._pending.append(request)
        LOGGER.info(
            "Solicitação de cadastro de %r (%s); %d na fila", device_name, peer, len(self._pending)
        )
        return request

    def resolve_head(self) -> Request | None:
        """Retira a solicitação mais antiga para aprovar ou negar."""
        if not self._pending:
            return None
        request = self._pending.popleft()
        self._cancel_timer(request)
        return request

    def forget_peer(self, peer: str) -> None:
        """Descarta o pedido de um celular que se desconectou antes da decisão."""
        self._drop(lambda request: request.peer == peer)

    def clear(self) -> None:
        self._drop(lambda _request: True)

    # ----------------------------------------------------------------- #

    def _expire(self, request: Request) -> None:
        if request not in self._pending:
            return
        self._pending.remove(request)
        request.timer = None
        LOGGER.info("Solicitação de %r expirou sem decisão", request.device_name)
        self._on_timeout(request)

    def _drop(self, predicate: Callable[[Request], bool]) -> None:
        for request in [r for r in self._pending if predicate(r)]:
            self._pending.remove(request)
            self._cancel_timer(request)

    def _cancel_timer(self, request: Request) -> None:
        if request.timer is not None:
            self._cancel(request.timer)
            request.timer = None
