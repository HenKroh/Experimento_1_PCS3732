"""O Smart Lock Service e suas sete características.

```text
Smart Lock Service
├── Device Information       read
├── Access Request           write
├── Approval Status          notify
├── Authentication Challenge read
├── Authentication Response  write
├── Unlock Command           write
└── Operation Result         notify
```

As características não decidem nada: convertem o vaivém GATT em chamadas ao
handler (`SmartLockService`) e mandam de volta o que ele devolve.
"""

from __future__ import annotations

import logging

from .. import protocol
from .bluez import Characteristic, Service

LOGGER = logging.getLogger(__name__)


class _ReadOnly(Characteristic):
    def __init__(self, bus, index, uuid, service, handler, encrypt: bool) -> None:
        flags = ["encrypt-read"] if encrypt else ["read"]
        super().__init__(bus, index, uuid, flags, service)
        self._handler = handler


class _WriteOnly(Characteristic):
    def __init__(self, bus, index, uuid, service, handler, encrypt: bool) -> None:
        # Escrita *com* resposta: o app precisa saber que a mensagem chegou
        # antes de esperar a notificação correspondente.
        flags = ["encrypt-write"] if encrypt else ["write"]
        super().__init__(bus, index, uuid, flags, service)
        self._handler = handler


class _NotifyOnly(Characteristic):
    def __init__(self, bus, index, uuid, service, encrypt: bool) -> None:
        flags = ["encrypt-notify"] if encrypt else ["notify"]
        super().__init__(bus, index, uuid, flags, service)


class DeviceInfoCharacteristic(_ReadOnly):
    def read(self, peer: str) -> bytes:
        return self._handler.device_info()


class AccessRequestCharacteristic(_WriteOnly):
    def write(self, value: bytes, peer: str) -> None:
        self._handler.on_access_request(peer, value)


class AuthChallengeCharacteristic(_ReadOnly):
    def read(self, peer: str) -> bytes:
        return self._handler.on_challenge_read(peer)


class AuthResponseCharacteristic(_WriteOnly):
    def write(self, value: bytes, peer: str) -> None:
        self._handler.on_auth_response(peer, value)


class UnlockCommandCharacteristic(_WriteOnly):
    def write(self, value: bytes, peer: str) -> None:
        self._handler.on_unlock_command(peer, value)


class SmartLockGATTService(Service):
    """Monta o serviço e guarda as duas características de notificação."""

    def __init__(self, bus, index: int, handler, *, encrypt: bool = False) -> None:
        super().__init__(bus, index, protocol.SERVICE_UUID, primary=True)

        self.device_info = DeviceInfoCharacteristic(
            bus, 0, protocol.DEVICE_INFO_UUID, self, handler, encrypt
        )
        self.access_request = AccessRequestCharacteristic(
            bus, 1, protocol.ACCESS_REQUEST_UUID, self, handler, encrypt
        )
        self.approval_status = _NotifyOnly(bus, 2, protocol.APPROVAL_STATUS_UUID, self, encrypt)
        self.auth_challenge = AuthChallengeCharacteristic(
            bus, 3, protocol.AUTH_CHALLENGE_UUID, self, handler, encrypt
        )
        self.auth_response = AuthResponseCharacteristic(
            bus, 4, protocol.AUTH_RESPONSE_UUID, self, handler, encrypt
        )
        self.unlock_command = UnlockCommandCharacteristic(
            bus, 5, protocol.UNLOCK_COMMAND_UUID, self, handler, encrypt
        )
        self.operation_result = _NotifyOnly(bus, 6, protocol.OPERATION_RESULT_UUID, self, encrypt)

        for characteristic in (
            self.device_info,
            self.access_request,
            self.approval_status,
            self.auth_challenge,
            self.auth_response,
            self.unlock_command,
            self.operation_result,
        ):
            self.add_characteristic(characteristic)

    # ----------------------------------------------------------------- #

    def notify_approval(self, payload: bytes) -> None:
        self.approval_status.notify(payload)

    def notify_result(self, payload: bytes) -> None:
        self.operation_result.notify(payload)
