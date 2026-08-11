"""Contorno de anúncio pelo `btmgmt` (ver `runner.py` e o README).

Não sobe D-Bus nem BLE: o `subprocess.run` é substituído por um dublê, então dá
para exercitar o contorno em qualquer máquina, inclusive sem BlueZ instalado.
"""

from __future__ import annotations

import subprocess
import unittest
from unittest import mock

from smartlock import runner
from smartlock.config import Config
from smartlock.runner import BLERunner

UUID = "a1b20001-5f6d-4c3e-9a2b-7e8f0d1c2b3a"


def _runner(adapter: str = "hci0") -> BLERunner:
    config = Config()
    config.adapter = adapter
    instance = BLERunner(config, service=None)
    instance._service_uuid = UUID
    return instance


def _completed(stdout: str = "", returncode: int = 0) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr="")


class AdapterIndexTest(unittest.TestCase):
    def test_extrai_o_indice_do_nome(self):
        self.assertEqual(_runner("hci0")._adapter_index(), 0)
        self.assertEqual(_runner("hci1")._adapter_index(), 1)

    def test_nome_sem_numero_cai_no_zero(self):
        self.assertEqual(_runner("hci")._adapter_index(), 0)


class BtmgmtFallbackTest(unittest.TestCase):
    def test_comando_leva_uuid_indice_e_flags(self):
        with mock.patch.object(subprocess, "run", return_value=_completed("Instance added: 1")) as run:
            ok, _ = _runner("hci1")._advertise_with_btmgmt()

        self.assertTrue(ok)
        command = run.call_args.args[0]
        self.assertEqual(command[:4], ["btmgmt", "--index", "1", "add-adv"])
        self.assertIn(UUID, command)
        for flag in ("-c", "-g", "-n"):  # connectable, discoverable, nome no scan-rsp
            self.assertIn(flag, command)
        # O índice da instância precisa estar na faixa aceita pelo controlador.
        self.assertIn(str(runner.BTMGMT_ADV_INSTANCE), command)
        self.assertIn(runner.BTMGMT_ADV_INSTANCE, range(1, 6))

    def test_sem_confirmacao_do_mgmt_e_falha(self):
        # O btmgmt sai com código 0 mesmo quando o MGMT recusa o comando.
        saida = "\x1b[0;91mAdd Advertising failed with status 0x0d (Invalid Parameters)\x1b[0m"
        with mock.patch.object(subprocess, "run", return_value=_completed(saida)):
            ok, detail = _runner()._advertise_with_btmgmt()

        self.assertFalse(ok)
        self.assertIn("Invalid Parameters", detail)
        self.assertNotIn("\x1b", detail)  # sem lixo ANSI no log

    def test_maquina_sem_btmgmt_nao_estoura(self):
        with mock.patch.object(subprocess, "run", side_effect=FileNotFoundError()):
            ok, detail = _runner()._advertise_with_btmgmt()

        self.assertFalse(ok)
        self.assertIn("btmgmt", detail)

    def test_remocao_so_acontece_se_houve_instancia(self):
        instance = _runner()
        with mock.patch.object(subprocess, "run") as run:
            instance._remove_btmgmt_advertisement()
        run.assert_not_called()

        with mock.patch.object(subprocess, "run", return_value=_completed("Instance added: 1")):
            instance._advertise_with_btmgmt()
        with mock.patch.object(
            subprocess, "run", return_value=_completed("Instance removed: 1")
        ) as run:
            instance._remove_btmgmt_advertisement()

        self.assertEqual(run.call_args.args[0][3:], ["rm-adv", str(runner.BTMGMT_ADV_INSTANCE)])
        self.assertIsNone(instance._btmgmt_instance)


class AdvertisementErrorTest(unittest.TestCase):
    def test_falha_do_dbus_aciona_o_contorno_e_o_loop_segue(self):
        instance = _runner()
        instance._quit = mock.Mock()
        with mock.patch.object(subprocess, "run", return_value=_completed("Instance added: 1")):
            with self.assertLogs(runner.LOGGER, level="WARNING"):
                instance._on_advertisement_error("org.bluez.Error.Failed")

        instance._quit.assert_not_called()
        self.assertEqual(instance._btmgmt_instance, runner.BTMGMT_ADV_INSTANCE)

    def test_as_duas_falhas_encerram_o_servico(self):
        instance = _runner()
        instance._quit = mock.Mock()
        with mock.patch.object(subprocess, "run", side_effect=FileNotFoundError()):
            with self.assertLogs(runner.LOGGER, level="ERROR") as logs:
                instance._on_advertisement_error("org.bluez.Error.Failed")

        instance._quit.assert_called_once()
        mensagem = logs.output[0]
        self.assertIn("org.bluez.Error.Failed", mensagem)  # falha do D-Bus
        self.assertIn("btmgmt", mensagem)  # e a do contorno


if __name__ == "__main__":
    unittest.main()
