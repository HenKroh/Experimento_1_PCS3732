"""Acionamento da fechadura.

Isola o resto do serviço do atuador: relé, solenoide ou servo. A garantia que
importa é o retravamento automático — a porta não pode ficar aberta porque uma
conexão BLE caiu no meio da operação.
"""

from __future__ import annotations

import logging
import threading

LOGGER = logging.getLogger(__name__)


class LockController:
    """Destrava por um tempo máximo e retranca sozinho."""

    def __init__(self, backend, *, unlock_duration: float = 5.0, led_name: str = "unlocked") -> None:
        self._backend = backend
        self._unlock_duration = unlock_duration
        self._led_name = led_name
        self._lock = threading.RLock()
        self._timer: threading.Timer | None = None
        self._unlocked = False

    @property
    def is_unlocked(self) -> bool:
        with self._lock:
            return self._unlocked

    def unlock(self) -> None:
        """Destrava e agenda o retravamento.

        Um segundo desbloqueio enquanto a porta ainda está aberta reinicia a
        contagem em vez de abrir uma segunda janela — assim o tempo total de
        abertura continua limitado a partir do último comando válido.
        """
        with self._lock:
            self._cancel_timer()
            self._unlocked = True
            self._backend.set_actuator(True)
            self._backend.set_led(self._led_name, True)
            LOGGER.info("Fechadura destravada por %.1fs", self._unlock_duration)

            self._timer = threading.Timer(self._unlock_duration, self.lock)
            self._timer.daemon = True
            self._timer.start()

    def lock(self) -> None:
        with self._lock:
            self._cancel_timer()
            if not self._unlocked:
                return
            self._unlocked = False
            self._backend.set_actuator(False)
            self._backend.set_led(self._led_name, False)
            LOGGER.info("Fechadura retravada")

    def shutdown(self) -> None:
        """Retranca ao encerrar: um processo que morre não deixa a porta aberta."""
        self.lock()

    def _cancel_timer(self) -> None:
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
