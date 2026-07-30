"""Fumaça do protocolo: o caminho feliz e as recusas que mais importam.

Sem BLE e sem GPIO — o serviço é exercitado pelas mesmas mensagens que chegariam
das características GATT.
"""

from __future__ import annotations

import base64
import itertools
import json
import tempfile
import unittest
from pathlib import Path

from smartlock import protocol
from smartlock.authentication import Authenticator, response_mac
from smartlock.config import Config
from smartlock.database import Database
from smartlock.lock_controller import LockController
from smartlock.service import SmartLockService

PHONE = "/org/bluez/hci0/dev_AA_AA_AA_AA_AA_AA"


class FakeScheduler:
    """Relógio virtual: o tempo passa quando o teste manda."""

    def __init__(self):
        self.now = 0.0
        self._timers = []
        self._sequence = itertools.count()

    def clock(self):
        return self.now

    def schedule(self, delay, callback):
        timer = [self.now + delay, next(self._sequence), callback]
        self._timers.append(timer)
        return timer

    def cancel(self, handle):
        if handle in self._timers:
            self._timers.remove(handle)

    def defer(self, callback):
        callback()

    def advance(self, seconds):
        target = self.now + seconds
        for timer in sorted([t for t in self._timers if t[0] <= target], key=lambda t: t[:2]):
            self._timers.remove(timer)
            self.now = timer[0]
            timer[2]()
        self.now = target


class FakeGPIO:
    def __init__(self):
        self.leds = {}
        self.actuator = False

    def watch_buttons(self, on_approve, on_deny):
        pass

    def set_led(self, name, on):
        self.leds[name] = on

    def set_actuator(self, engaged):
        self.actuator = engaged

    def cleanup(self):
        pass


class FakeNotifier:
    def __init__(self):
        self.approvals = []
        self.results = []

    def notify_approval(self, payload):
        self.approvals.append(json.loads(payload))

    def notify_result(self, payload):
        self.results.append(json.loads(payload))


class ServiceTest(unittest.TestCase):
    def setUp(self):
        tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(tempdir.cleanup)

        self.config = Config()
        self.config.database_path = str(Path(tempdir.name) / "smartlock.db")
        self.config.unlock_duration = 3600.0  # nenhum timer real dispara no teste

        self.scheduler = FakeScheduler()
        self.gpio = FakeGPIO()
        self.notifier = FakeNotifier()
        self.database = Database(self.config.database_path)
        self.addCleanup(self.database.close)
        self.controller = LockController(self.gpio, unlock_duration=self.config.unlock_duration)
        self.addCleanup(self.controller.shutdown)

        self.service = SmartLockService(
            self.config,
            self.database,
            Authenticator(
                secret_provider=self.database.secret_for,
                ttl=self.config.challenge_ttl,
                max_failed_attempts=self.config.max_failed_attempts,
                lockout_duration=self.config.lockout_duration,
                clock=self.scheduler.clock,
            ),
            self.controller,
            self.gpio,
            self.scheduler,
        )
        self.service.attach(self.notifier)

    # -- Auxiliares ---------------------------------------------------- #

    def enroll(self, device_id="device-a"):
        self.service.on_access_request(
            PHONE, protocol.encode({"v": 1, "deviceId": device_id, "deviceName": "iPhone"})
        )
        self.service.approve()
        return base64.b64decode(self.notifier.approvals[-1]["secret"])

    def unlock(self, secret, device_id="device-a"):
        nonce = base64.b64decode(json.loads(self.service.on_challenge_read(PHONE))["nonce"])
        self.service.on_auth_response(
            PHONE,
            protocol.encode(
                {
                    "v": 1,
                    "deviceId": device_id,
                    "mac": base64.b64encode(response_mac(secret, nonce, device_id)).decode(),
                }
            ),
        )
        if self.notifier.results[-1]["status"] != protocol.STATUS_OK:
            return self.notifier.results[-1]
        self.service.on_unlock_command(PHONE, protocol.encode({"v": 1, "deviceId": device_id}))
        return self.notifier.results[-1]

    # -- Cenários ------------------------------------------------------ #

    def test_celular_autorizado_desbloqueia(self):
        result = self.unlock(self.enroll())
        self.assertEqual(result["op"], protocol.OP_UNLOCK)
        self.assertEqual(result["status"], protocol.STATUS_OK)
        self.assertTrue(self.gpio.actuator)

    def test_celular_nao_autorizado_e_rejeitado(self):
        result = self.unlock(b"\x00" * 32, device_id="desconhecido")
        self.assertEqual(result["status"], protocol.STATUS_DENIED)
        self.assertFalse(self.gpio.actuator)

    def test_botao_negar_nao_cadastra(self):
        self.service.on_access_request(
            PHONE, protocol.encode({"v": 1, "deviceId": "device-a", "deviceName": "iPhone"})
        )
        self.service.deny()
        self.assertEqual(self.notifier.approvals[-1]["state"], protocol.APPROVAL_DENIED)
        self.assertIsNone(self.database.secret_for("device-a"))

    def test_nonce_expirado_e_rejeitado(self):
        secret = self.enroll()
        nonce = base64.b64decode(json.loads(self.service.on_challenge_read(PHONE))["nonce"])
        self.scheduler.advance(self.config.challenge_ttl + 1)

        self.service.on_auth_response(
            PHONE,
            protocol.encode(
                {
                    "v": 1,
                    "deviceId": "device-a",
                    "mac": base64.b64encode(response_mac(secret, nonce, "device-a")).decode(),
                }
            ),
        )
        self.assertEqual(self.notifier.results[-1]["reason"], "Desafio expirado.")

    def test_autenticacao_vale_para_um_unico_comando(self):
        self.unlock(self.enroll())
        self.service.on_unlock_command(PHONE, protocol.encode({"v": 1, "deviceId": "device-a"}))
        self.assertEqual(self.notifier.results[-1]["status"], protocol.STATUS_DENIED)

    def test_dispositivo_revogado_nao_desbloqueia(self):
        secret = self.enroll()
        self.service.revoke("device-a")
        self.assertEqual(self.unlock(secret)["status"], protocol.STATUS_DENIED)

    def test_reinicio_preserva_dispositivos_autorizados(self):
        secret = self.enroll()
        self.database.close()

        reopened = Database(self.config.database_path)
        self.addCleanup(reopened.close)
        self.assertEqual(reopened.secret_for("device-a"), secret)


if __name__ == "__main__":
    unittest.main()
