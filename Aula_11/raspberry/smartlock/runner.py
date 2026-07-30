"""Sobe o periférico: registra a aplicação GATT e o anúncio no BlueZ.

Tudo corre num único loop GLib. Callbacks vindos de outras threads (botões do
`RPi.GPIO`, timers do atuador) voltam para esse loop pelo `GLibScheduler`, então
o estado do serviço nunca é tocado de dois lugares ao mesmo tempo.
"""

from __future__ import annotations

import logging
import signal
from typing import Any, Callable

from .config import Config
from .service import SmartLockService

LOGGER = logging.getLogger(__name__)


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
            reply_handler=lambda: LOGGER.info(
                "Anunciando como %r", self._config.advertised_name
            ),
            error_handler=lambda error: self._fail("registrar o anúncio", error),
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

    def _fail(self, what: str, error) -> None:
        LOGGER.error("Não foi possível %s: %s", what, error)
        self._quit()

    def _quit(self) -> bool:
        if self._loop is not None:
            self._loop.quit()
        return False

    def _unregister(self) -> None:
        try:
            if self._ad_manager is not None and self._advertisement is not None:
                self._ad_manager.UnregisterAdvertisement(self._advertisement.get_path())
            if self._gatt_manager is not None and self._application is not None:
                self._gatt_manager.UnregisterApplication(self._application.get_path())
        except Exception:  # noqa: BLE001 - já estamos encerrando
            LOGGER.debug("Falha ao desregistrar do BlueZ", exc_info=True)
