"""Contrato compartilhado entre Raspberry Pi, Android e iOS.

Espelha `ios/SmartLock/Protocol/LockProtocol.swift`. Qualquer mudança aqui
precisa ser refletida nas outras duas implementações.

Todas as mensagens trafegam como JSON UTF-8 dentro das características GATT;
campos binários viajam em Base64, que é como o `JSONEncoder` do Swift codifica
`Data` por padrão.
"""

from __future__ import annotations

import base64
import binascii
import json
from typing import Any

# Versão do protocolo. Mensagens com versão desconhecida são rejeitadas.
VERSION = 1

# Contexto de domínio usado no HMAC do desbloqueio. Amarrar o comando à prova
# impede reaproveitar um MAC para outra operação.
UNLOCK_CONTEXT = "unlock"

# Tamanho do segredo emitido no cadastro. O app recusa segredo de outro tamanho.
SECRET_LENGTH = 32
NONCE_LENGTH = 16

# UUIDs do serviço e das características. BlueZ espera minúsculas.
SERVICE_UUID = "a1b20001-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
DEVICE_INFO_UUID = "a1b20002-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
ACCESS_REQUEST_UUID = "a1b20003-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
APPROVAL_STATUS_UUID = "a1b20004-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
AUTH_CHALLENGE_UUID = "a1b20005-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
AUTH_RESPONSE_UUID = "a1b20006-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
UNLOCK_COMMAND_UUID = "a1b20007-5f6d-4c3e-9a2b-7e8f0d1c2b3a"
OPERATION_RESULT_UUID = "a1b20008-5f6d-4c3e-9a2b-7e8f0d1c2b3a"

# Estados de aprovação (característica Approval Status).
APPROVAL_PENDING = "pending"
APPROVAL_APPROVED = "approved"
APPROVAL_DENIED = "denied"
APPROVAL_TIMEOUT = "timeout"

# Desfechos de operação (característica Operation Result).
STATUS_OK = "ok"
STATUS_DENIED = "denied"
STATUS_ERROR = "error"
STATUS_RATE_LIMITED = "rate_limited"

# Operações reportadas em Operation Result.
OP_ENROLL = "enroll"
OP_AUTH = "auth"
OP_UNLOCK = "unlock"


class ProtocolError(ValueError):
    """Mensagem malformada ou de versão incompatível vinda do celular."""


# --------------------------------------------------------------------------- #
# Serialização
# --------------------------------------------------------------------------- #


def encode(message: dict[str, Any]) -> bytes:
    """Serializa uma mensagem para o formato que trafega na característica."""
    return json.dumps(message, separators=(",", ":")).encode("utf-8")


def decode(payload: bytes) -> dict[str, Any]:
    """Desserializa e valida o envelope comum a todas as mensagens."""
    try:
        message = json.loads(bytes(payload).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProtocolError(f"payload não é JSON UTF-8 válido: {error}") from error

    if not isinstance(message, dict):
        raise ProtocolError("payload não é um objeto JSON")

    version = message.get("v")
    if version != VERSION:
        raise ProtocolError(f"versão de protocolo não suportada: {version!r}")

    return message


def _b64encode(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _b64decode(value: Any, field: str, expected_length: int | None = None) -> bytes:
    if not isinstance(value, str):
        raise ProtocolError(f"campo '{field}' deve ser uma string Base64")
    try:
        raw = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ProtocolError(f"campo '{field}' não é Base64 válido") from error
    if expected_length is not None and len(raw) != expected_length:
        raise ProtocolError(
            f"campo '{field}' deve ter {expected_length} bytes, veio com {len(raw)}"
        )
    return raw


def _require_str(message: dict[str, Any], field: str, max_length: int) -> str:
    value = message.get(field)
    if not isinstance(value, str) or not value:
        raise ProtocolError(f"campo '{field}' ausente ou inválido")
    if len(value) > max_length:
        raise ProtocolError(f"campo '{field}' excede {max_length} caracteres")
    return value


# --------------------------------------------------------------------------- #
# Mensagens recebidas do celular
# --------------------------------------------------------------------------- #


def parse_access_request(payload: bytes) -> tuple[str, str]:
    """`Access Request` → (deviceId, deviceName)."""
    message = decode(payload)
    return (
        _require_str(message, "deviceId", max_length=64),
        _require_str(message, "deviceName", max_length=64),
    )


def parse_auth_response(payload: bytes) -> tuple[str, bytes]:
    """`Authentication Response` → (deviceId, mac)."""
    message = decode(payload)
    device_id = _require_str(message, "deviceId", max_length=64)
    mac = _b64decode(message.get("mac"), "mac", expected_length=32)
    return device_id, mac


def parse_unlock_command(payload: bytes) -> str:
    """`Unlock Command` → deviceId."""
    message = decode(payload)
    return _require_str(message, "deviceId", max_length=64)


# --------------------------------------------------------------------------- #
# Mensagens enviadas pela Raspberry
# --------------------------------------------------------------------------- #


def device_info(lock_id: str, lock_name: str, firmware: str | None) -> bytes:
    return encode({"v": VERSION, "lockId": lock_id, "lockName": lock_name, "firmware": firmware})


def approval_status(
    state: str,
    device_id: str,
    secret: bytes | None = None,
    lock_name: str | None = None,
) -> bytes:
    return encode(
        {
            "v": VERSION,
            "state": state,
            "deviceId": device_id,
            "secret": _b64encode(secret) if secret is not None else None,
            "lockName": lock_name,
        }
    )


def challenge(nonce: bytes, ttl: float) -> bytes:
    return encode({"v": VERSION, "nonce": _b64encode(nonce), "ttl": ttl})


def operation_result(op: str, status: str, reason: str | None = None) -> bytes:
    return encode({"v": VERSION, "op": op, "status": status, "reason": reason})
