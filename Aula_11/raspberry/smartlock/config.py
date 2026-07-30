"""Configuração do serviço.

Valores padrão pensados para a bancada; tudo pode ser sobrescrito por variável
de ambiente (prefixo `SMARTLOCK_`) ou pelos argumentos de linha de comando.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env_str(name: str, default: str) -> str:
    return os.environ.get(f"SMARTLOCK_{name}", default)


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(f"SMARTLOCK_{name}")
    return int(raw) if raw else default


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(f"SMARTLOCK_{name}")
    return float(raw) if raw else default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(f"SMARTLOCK_{name}")
    if raw is None:
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on", "sim")


def _default_leds() -> dict[str, int]:
    # `unlocked` fica no LED azul soldado na placa (GPIO17); os outros três saem
    # no conector do módulo RGB (R=5, G=6, B=13). Ver a tabela do README.
    return {
        "waiting": _env_int("LED_WAITING_PIN", 5),
        "approved": _env_int("LED_APPROVED_PIN", 6),
        "denied": _env_int("LED_DENIED_PIN", 13),
        "unlocked": _env_int("LED_UNLOCKED_PIN", 17),
    }


@dataclass
class Config:
    # Identidade anunciada e devolvida em Device Information.
    lock_id: str = field(default_factory=lambda: _env_str("LOCK_ID", "lock-01"))
    lock_name: str = field(default_factory=lambda: _env_str("LOCK_NAME", "Fechadura da Sala"))
    firmware: str = field(default_factory=lambda: _env_str("FIRMWARE", "1.0"))
    # Nome curto no anúncio BLE. O app mostra este texto na lista.
    advertised_name: str = field(default_factory=lambda: _env_str("ADVERTISED_NAME", "SmartLock-Sala"))
    adapter: str = field(default_factory=lambda: _env_str("ADAPTER", "hci0"))

    database_path: str = field(default_factory=lambda: _env_str("DATABASE", "smartlock.db"))

    # Hardware
    #
    # Padrões para a Freenove Projects Board for Raspberry Pi: os periféricos
    # dela estão em pinos fixos, então estes valores não são livres — botões
    # S4/S5, relé e LED azul. Ver "Ligações" no README.
    use_gpio: bool = field(default_factory=lambda: _env_bool("USE_GPIO", True))
    button_approve_pin: int = field(default_factory=lambda: _env_int("BUTTON_APPROVE_PIN", 26))
    button_deny_pin: int = field(default_factory=lambda: _env_int("BUTTON_DENY_PIN", 21))
    led_pins: dict[str, int] = field(default_factory=_default_leds)
    # O módulo RGB da placa é de anodo comum (ligado ao 5V): acende em nível
    # baixo. Ligue isto se os LEDs de status estiverem nesse conector.
    led_active_low: bool = field(default_factory=lambda: _env_bool("LED_ACTIVE_LOW", False))
    actuator_pin: int = field(default_factory=lambda: _env_int("ACTUATOR_PIN", 12))
    actuator_active_high: bool = field(default_factory=lambda: _env_bool("ACTUATOR_ACTIVE_HIGH", True))
    door_sensor_pin: int | None = field(
        default_factory=lambda: (
            _env_int("DOOR_SENSOR_PIN", 0) or None if os.environ.get("SMARTLOCK_DOOR_SENSOR_PIN") else None
        )
    )
    # Teclado como substituto dos botões quando não há GPIO.
    keyboard_buttons: bool = field(default_factory=lambda: _env_bool("KEYBOARD_BUTTONS", False))

    # Tempos
    #
    # `challenge_ttl` de 5s casa com o `MockLockTransport` do iOS; o app espera
    # até 60s pelo botão físico, então o timeout de cadastro não pode passar disso.
    challenge_ttl: float = field(default_factory=lambda: _env_float("CHALLENGE_TTL", 5.0))
    enrollment_timeout: float = field(default_factory=lambda: _env_float("ENROLLMENT_TIMEOUT", 55.0))
    unlock_duration: float = field(default_factory=lambda: _env_float("UNLOCK_DURATION", 5.0))

    # Limite de tentativas inválidas antes do bloqueio temporário.
    max_failed_attempts: int = field(default_factory=lambda: _env_int("MAX_FAILED_ATTEMPTS", 3))
    lockout_duration: float = field(default_factory=lambda: _env_float("LOCKOUT_DURATION", 30.0))

    # Exige emparelhamento com link criptografado nas características sensíveis.
    # Desligado por padrão: o pareamento LE na bancada costuma atrapalhar os
    # primeiros testes. Ver `protocol/security.md`.
    require_encryption: bool = field(default_factory=lambda: _env_bool("REQUIRE_ENCRYPTION", False))
