"""Botões físicos e LEDs de status.

`RPi.GPIO` só existe na Raspberry. Fora dela — no PC, nos testes — cai numa
implementação de mentira que apenas registra o que faria, para o resto do
serviço poder rodar sem hardware.
"""

from __future__ import annotations

import logging
import sys
import threading
from typing import Callable, Protocol

LOGGER = logging.getLogger(__name__)


class GPIOBackend(Protocol):
    """Superfície mínima que o serviço usa; permite trocar por um duplo nos testes."""

    def watch_buttons(self, on_approve: Callable[[], None], on_deny: Callable[[], None]) -> None: ...
    def set_led(self, name: str, on: bool) -> None: ...
    def set_actuator(self, engaged: bool) -> None: ...
    def door_is_open(self) -> bool | None: ...
    def cleanup(self) -> None: ...


class NullGPIO:
    """Sem hardware: só loga. Deixa o protocolo BLE testável em qualquer máquina."""

    def __init__(self) -> None:
        self._on_approve: Callable[[], None] | None = None
        self._on_deny: Callable[[], None] | None = None

    def watch_buttons(self, on_approve: Callable[[], None], on_deny: Callable[[], None]) -> None:
        self._on_approve = on_approve
        self._on_deny = on_deny
        LOGGER.warning(
            "GPIO indisponível: use press_approve()/press_deny() ou o teclado para simular os botões"
        )

    # Ganchos para simular o proprietário sem hardware (teclado, testes).
    def press_approve(self) -> None:
        if self._on_approve:
            self._on_approve()

    def press_deny(self) -> None:
        if self._on_deny:
            self._on_deny()

    def set_led(self, name: str, on: bool) -> None:
        LOGGER.info("LED %s -> %s", name, "aceso" if on else "apagado")

    def set_actuator(self, engaged: bool) -> None:
        LOGGER.info("Atuador -> %s", "DESTRAVADO" if engaged else "travado")

    def door_is_open(self) -> bool | None:
        return None

    def cleanup(self) -> None:
        pass


class RaspberryGPIO:
    """Implementação real sobre `RPi.GPIO`.

    Botões com pull-up interno: repouso em nível alto, pressionado em nível
    baixo, detecção na borda de descida com debounce por software.
    """

    def __init__(
        self,
        *,
        button_approve: int,
        button_deny: int,
        leds: dict[str, int],
        leds_active_low: bool = False,
        actuator_pin: int,
        actuator_active_high: bool = True,
        door_sensor_pin: int | None = None,
        bounce_time_ms: int = 300,
    ) -> None:
        import RPi.GPIO as GPIO  # importado aqui para o módulo carregar fora da Pi

        self._gpio = GPIO
        self._button_approve = button_approve
        self._button_deny = button_deny
        self._leds = dict(leds)
        self._leds_active_low = leds_active_low
        self._actuator_pin = actuator_pin
        self._actuator_active_high = actuator_active_high
        self._door_sensor_pin = door_sensor_pin
        self._bounce_time_ms = bounce_time_ms

        GPIO.setmode(GPIO.BCM)
        GPIO.setwarnings(False)
        GPIO.setup(button_approve, GPIO.IN, pull_up_down=GPIO.PUD_UP)
        GPIO.setup(button_deny, GPIO.IN, pull_up_down=GPIO.PUD_UP)
        # Mesmo cuidado do atuador: nascer apagado, sem pulso na configuração.
        for pin in self._leds.values():
            GPIO.setup(pin, GPIO.OUT, initial=GPIO.HIGH if leds_active_low else GPIO.LOW)

        # O atuador começa travado. `initial` evita o pulso espúrio que
        # aconteceria se configurássemos a saída e só depois escrevêssemos o nível.
        GPIO.setup(
            actuator_pin,
            GPIO.OUT,
            initial=GPIO.LOW if actuator_active_high else GPIO.HIGH,
        )
        if door_sensor_pin is not None:
            GPIO.setup(door_sensor_pin, GPIO.IN, pull_up_down=GPIO.PUD_UP)

    def watch_buttons(self, on_approve: Callable[[], None], on_deny: Callable[[], None]) -> None:
        # Os callbacks correm numa thread do RPi.GPIO; quem os recebe é
        # responsável por devolver o trabalho ao loop principal.
        self._gpio.add_event_detect(
            self._button_approve,
            self._gpio.FALLING,
            callback=lambda _channel: on_approve(),
            bouncetime=self._bounce_time_ms,
        )
        self._gpio.add_event_detect(
            self._button_deny,
            self._gpio.FALLING,
            callback=lambda _channel: on_deny(),
            bouncetime=self._bounce_time_ms,
        )

    def set_led(self, name: str, on: bool) -> None:
        pin = self._leds.get(name)
        if pin is None:
            return
        lit = self._gpio.LOW if self._leds_active_low else self._gpio.HIGH
        dark = self._gpio.HIGH if self._leds_active_low else self._gpio.LOW
        self._gpio.output(pin, lit if on else dark)

    def set_actuator(self, engaged: bool) -> None:
        active = self._gpio.HIGH if self._actuator_active_high else self._gpio.LOW
        idle = self._gpio.LOW if self._actuator_active_high else self._gpio.HIGH
        self._gpio.output(self._actuator_pin, active if engaged else idle)

    def door_is_open(self) -> bool | None:
        if self._door_sensor_pin is None:
            return None
        # Reed switch com pull-up: fechado curto-circuita para o terra.
        return bool(self._gpio.input(self._door_sensor_pin))

    def cleanup(self) -> None:
        self._gpio.cleanup()


def create_backend(config) -> GPIOBackend:
    """Escolhe a implementação real quando há hardware, o duplo quando não há."""
    if not config.use_gpio:
        return NullGPIO()
    try:
        return RaspberryGPIO(
            button_approve=config.button_approve_pin,
            button_deny=config.button_deny_pin,
            leds=config.led_pins,
            leds_active_low=config.led_active_low,
            actuator_pin=config.actuator_pin,
            actuator_active_high=config.actuator_active_high,
            door_sensor_pin=config.door_sensor_pin,
        )
    except (ImportError, RuntimeError) as error:
        LOGGER.warning("GPIO indisponível (%s); seguindo sem hardware", error)
        return NullGPIO()


def watch_keyboard(on_approve: Callable[[], None], on_deny: Callable[[], None]) -> None:
    """Deixa 'a' e 'd' no terminal fazerem o papel dos botões físicos.

    Serve para testar o fluxo de cadastro numa máquina sem GPIO. Roda numa
    thread daemon, então não segura o encerramento do processo.
    """

    def loop() -> None:
        for line in sys.stdin:
            command = line.strip().lower()
            if command in ("a", "approve", "aprovar"):
                on_approve()
            elif command in ("d", "deny", "negar"):
                on_deny()

    threading.Thread(target=loop, name="keyboard-buttons", daemon=True).start()
