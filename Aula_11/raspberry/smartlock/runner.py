"""Sobe o periférico: registra a aplicação GATT e o anúncio no BlueZ.

Tudo corre num único loop GLib. Callbacks vindos de outras threads (botões do
`RPi.GPIO`, timers do atuador) voltam para esse loop pelo `GLibScheduler`, então
o estado do serviço nunca é tocado de dois lugares ao mesmo tempo.
"""

from __future__ import annotations

import logging
import re
import signal
import subprocess
from typing import Any, Callable

from .config import Config
from .service import SmartLockService

LOGGER = logging.getLogger(__name__)

# Índice da instância de anúncio usada pelo contorno via `btmgmt` (ver
# `_advertise_with_btmgmt`). O controlador do Pi 3B+ aceita instâncias de 1 a 5
# (`btmgmt advinfo` → "Max instances: 5"); 0 não é um identificador válido.
BTMGMT_ADV_INSTANCE = 1

# Tempo máximo para o `btmgmt` responder. Ele fala MGMT direto com o kernel e
# retorna em milissegundos; se travar, é melhor desistir do que pendurar o loop.
BTMGMT_TIMEOUT = 10.0

# Escapes ANSI que o `btmgmt` emite mesmo quando a saída não é um terminal.
_ANSI = re.compile(r"\x1b\[[0-9;]*m")


class GLibScheduler:
    """`Scheduler` do serviço em cima do loop principal do GLib."""

    def __init__(self, glib) -> None:
        self._glib = glib

    def schedule(self, delay: float, callback: Callable[[], None]) -> Any:
        return self._glib.timeout_add(int(delay * 1000), self._once(callback))

    def cancel(self, handle: Any) -> None:
        try:
            self._glib.source_remove(handle)
        except (ValueError, TypeError):
            # A fonte já disparou ou já foi removida; nada a fazer.
            pass

    def defer(self, callback: Callable[[], None]) -> None:
        self._glib.idle_add(self._once(callback))

    @staticmethod
    def _once(callback: Callable[[], None]):
        def run() -> bool:
            try:
                callback()
            except Exception:  # noqa: BLE001 - um callback não pode derrubar o loop
                LOGGER.exception("Erro em callback agendado")
            return False  # não repetir

        return run


class BLERunner:
    def __init__(self, config: Config, service: SmartLockService) -> None:
        self._config = config
        self._service = service
        self._loop = None
        self._bus = None
        self._advertisement = None
        self._ad_manager = None
        self._gatt_manager = None
        self._application = None
        self._service_uuid = None
        # Preenchidos conforme o caminho que der certo, para o `_unregister`
        # desfazer só o que de fato foi feito.
        self._advertisement_registered = False
        self._btmgmt_instance: int | None = None

    # ------------------------------------------------------------------ #

    def run(self) -> None:
        import dbus
        import dbus.mainloop.glib
        from gi.repository import GLib

        from .ble_server import LockAdvertisement, SmartLockGATTService
        from .ble_server.bluez import (
            ADAPTER_IFACE,
            BLUEZ_SERVICE,
            DBUS_PROP_IFACE,
            GATT_MANAGER_IFACE,
            LE_ADVERTISING_MANAGER_IFACE,
            Application,
            find_adapter,
        )

        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        self._bus = bus = dbus.SystemBus()
        self._loop = loop = GLib.MainLoop()

        adapter_path = find_adapter(bus, GATT_MANAGER_IFACE, self._config.adapter)
        self._power_on(bus, adapter_path, ADAPTER_IFACE, DBUS_PROP_IFACE, BLUEZ_SERVICE, dbus)

        self._application = application = Application(bus)
        gatt_service = SmartLockGATTService(
            bus, 0, self._service, encrypt=self._config.require_encryption
        )
        application.add_service(gatt_service)
        self._service.attach(gatt_service)

        self._gatt_manager = dbus.Interface(
            bus.get_object(BLUEZ_SERVICE, adapter_path), GATT_MANAGER_IFACE
        )
        self._ad_manager = dbus.Interface(
            bus.get_object(BLUEZ_SERVICE, adapter_path), LE_ADVERTISING_MANAGER_IFACE
        )
        self._service_uuid = gatt_service.uuid
        self._advertisement = LockAdvertisement(
            bus, 0, gatt_service.uuid, self._config.advertised_name
        )

        self._watch_disconnects(bus, DBUS_PROP_IFACE)

        self._gatt_manager.RegisterApplication(
            application.get_path(),
            {},
            reply_handler=lambda: LOGGER.info("Serviço GATT registrado"),
            error_handler=lambda error: self._fail("registrar o serviço GATT", error),
        )
        self._ad_manager.RegisterAdvertisement(
            self._advertisement.get_path(),
            {},
            reply_handler=self._on_advertisement_registered,
            error_handler=self._on_advertisement_error,
        )

        for received in (signal.SIGINT, signal.SIGTERM):
            GLib.unix_signal_add(GLib.PRIORITY_HIGH, received, self._quit)

        LOGGER.info(
            "Fechadura %r (%s) no ar; aguardando celulares",
            self._config.lock_name,
            self._config.lock_id,
        )
        loop.run()
        self._unregister()

    # ------------------------------------------------------------------ #

    def _power_on(self, bus, adapter_path, adapter_iface, prop_iface, bluez, dbus) -> None:
        properties = dbus.Interface(bus.get_object(bluez, adapter_path), prop_iface)
        if not properties.Get(adapter_iface, "Powered"):
            LOGGER.info("Ligando o adaptador %s", self._config.adapter)
            properties.Set(adapter_iface, "Powered", dbus.Boolean(True))
        # O nome do anúncio vem do `LocalName` da LEAdvertisement1; ajustar o
        # Alias mantém a coerência para quem inspecionar o adaptador.
        properties.Set(adapter_iface, "Alias", dbus.String(self._config.advertised_name))

    def _watch_disconnects(self, bus, prop_iface: str) -> None:
        def on_properties_changed(interface, changed, invalidated, path=None):
            if interface != "org.bluez.Device1":
                return
            if "Connected" in changed and not bool(changed["Connected"]):
                self._service.on_peer_disconnected(str(path))

        bus.add_signal_receiver(
            on_properties_changed,
            dbus_interface=prop_iface,
            signal_name="PropertiesChanged",
            arg0="org.bluez.Device1",
            path_keyword="path",
        )

    # ---------------------- anúncio: D-Bus e contorno ----------------------- #
    #
    # CONTORNO TEMPORÁRIO (julho de 2026) — bug de terceiro, remover quando a
    # combinação kernel/BlueZ for corrigida. Ver a seção correspondente no
    # README deste diretório.
    #
    # O caminho normal é o `LEAdvertisingManager1.RegisterAdvertisement` do
    # `bluetoothd`, e é o que continuamos tentando primeiro. Na bancada (Pi 3B+,
    # kernel 6.18.34+rpt-rpi-v8, BlueZ 5.82) ele responde
    # `org.bluez.Error.Failed`. Diagnóstico com `btmon`:
    #
    #   * o `bluetoothd` 5.82 escolhe o caminho MGMT de *extended advertising*:
    #     `Add Ext Adv Params` (0x0054) e depois `Add Ext Adv Data` (0x0055);
    #   * o params retorna `Success`, mas o data retorna
    #     `Invalid Parameters (0x0d)` com payload de **qualquer** tamanho,
    #     inclusive 0 bytes — logo não é tamanho nem conteúdo do pacote;
    #   * o controlador BCM4345C0 do Pi 3B+ anuncia HCI version 9 (BT 5.0) mas
    #     tem o bit 12 de LE Features (LE Extended Advertising) em zero
    #     (`LE: 3f 00 00 08 00 00 00 00`): ele simplesmente não suporta o
    #     recurso que o `bluetoothd` está tentando usar.
    #
    # O caminho MGMT **legado** (`Add Advertising`, 0x003e) funciona perfeitamente
    # no mesmo controlador, e é o que o `btmgmt add-adv` usa. Então, quando o
    # D-Bus falha, chamamos o `btmgmt` para pôr o anúncio no ar. O serviço GATT
    # continua registrado pelo `bluetoothd` normalmente: só o anúncio muda de
    # caminho, e o iOS não vê diferença.

    def _on_advertisement_registered(self) -> None:
        self._advertisement_registered = True
        LOGGER.info("Anunciando como %r", self._config.advertised_name)

    def _on_advertisement_error(self, error) -> None:
        LOGGER.warning(
            "RegisterAdvertisement falhou (%s); tentando o contorno pelo btmgmt", error
        )
        ok, detail = self._advertise_with_btmgmt()
        if ok:
            LOGGER.info(
                "Anunciando como %r pelo btmgmt (instância %d) — contorno do "
                "extended advertising quebrado neste controlador",
                self._config.advertised_name,
                BTMGMT_ADV_INSTANCE,
            )
            return
        LOGGER.error(
            "Não foi possível registrar o anúncio por nenhum caminho. "
            "D-Bus (RegisterAdvertisement): %s. Contorno (btmgmt add-adv): %s",
            error,
            detail,
        )
        self._quit()

    def _adapter_index(self) -> int:
        """`"hci0"` → `0`. O `btmgmt` fala por índice, não por nome."""
        digits = "".join(char for char in self._config.adapter if char.isdigit())
        return int(digits) if digits else 0

    def _advertise_with_btmgmt(self) -> tuple[bool, str]:
        """Põe o anúncio no ar pelo caminho MGMT legado. Devolve (ok, detalhe)."""
        command = [
            "btmgmt",
            "--index",
            str(self._adapter_index()),
            "add-adv",
            "-u",
            str(self._service_uuid),  # o app filtra o scan por este UUID
            "-c",  # connectable
            "-g",  # general-discoverable
            # Põe o nome do adaptador (o `Alias` que `_power_on` acabou de
            # ajustar) no scan response — é o texto que o app lista.
            "-n",
            str(BTMGMT_ADV_INSTANCE),
        ]
        ok, output = self._run_btmgmt(command)
        if not ok:
            return False, output
        # O `btmgmt` sai com código 0 mesmo quando o MGMT recusa o comando, então
        # o que vale é a mensagem de sucesso.
        if "Instance added" not in output:
            return False, output or "o btmgmt não confirmou 'Instance added'"
        self._btmgmt_instance = BTMGMT_ADV_INSTANCE
        return True, output

    def _remove_btmgmt_advertisement(self) -> None:
        if self._btmgmt_instance is None:
            return
        command = [
            "btmgmt",
            "--index",
            str(self._adapter_index()),
            "rm-adv",
            str(self._btmgmt_instance),
        ]
        ok, output = self._run_btmgmt(command)
        if ok and "Instance removed" in output:
            LOGGER.info("Anúncio do btmgmt (instância %d) removido", self._btmgmt_instance)
        else:
            LOGGER.warning(
                "Não foi possível remover o anúncio do btmgmt (instância %d): %s",
                self._btmgmt_instance,
                output,
            )
        self._btmgmt_instance = None

    @staticmethod
    def _run_btmgmt(command: list[str]) -> tuple[bool, str]:
        """Executa o `btmgmt` sem deixar exceção vazar. Devolve (executou, saída)."""
        try:
            completed = subprocess.run(
                command,
                # O `btmgmt` roda em cima do bt_shell: mesmo em modo não
                # interativo ele só encerra ao ver EOF na entrada. Um pipe vazio
                # e fechado (é o que `input=""` faz) resolve; `/dev/null` ou o
                # stdin herdado do serviço fazem o processo pendurar até o
                # timeout, mesmo com o comando já executado.
                input="",
                capture_output=True,
                text=True,
                timeout=BTMGMT_TIMEOUT,
                check=False,
            )
        except FileNotFoundError:
            # Máquina sem BlueZ instalado: não é motivo para stack trace.
            return False, "o utilitário 'btmgmt' (pacote bluez) não está instalado"
        except subprocess.TimeoutExpired:
            return False, f"o btmgmt não respondeu em {BTMGMT_TIMEOUT:.0f}s"
        except OSError as error:
            return False, f"não foi possível executar o btmgmt: {error}"
        # O btmgmt colore a saída mesmo sem terminal; tira os escapes ANSI para
        # o log não ficar com lixo.
        output = _ANSI.sub("", f"{completed.stdout}\n{completed.stderr}").strip()
        if completed.returncode != 0:
            return False, output or f"o btmgmt terminou com código {completed.returncode}"
        return True, output

    # ------------------------------------------------------------------ #

    def _fail(self, what: str, error) -> None:
        LOGGER.error("Não foi possível %s: %s", what, error)
        self._quit()

    def _quit(self) -> bool:
        if self._loop is not None:
            self._loop.quit()
        return False

    def _unregister(self) -> None:
        # O contorno do btmgmt vive fora do bluetoothd: se ninguém remover a
        # instância, ela continua anunciando depois que o serviço morre.
        self._remove_btmgmt_advertisement()
        try:
            if (
                self._advertisement_registered
                and self._ad_manager is not None
                and self._advertisement is not None
            ):
                self._ad_manager.UnregisterAdvertisement(self._advertisement.get_path())
            if self._gatt_manager is not None and self._application is not None:
                self._gatt_manager.UnregisterApplication(self._application.get_path())
        except Exception:  # noqa: BLE001 - já estamos encerrando
            LOGGER.debug("Falha ao desregistrar do BlueZ", exc_info=True)
