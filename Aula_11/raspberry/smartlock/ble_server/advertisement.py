"""Anúncio BLE.

O app faz `scanForPeripherals(withServices: [LockGATT.service])`, então o UUID do
serviço precisa estar no pacote de anúncio — sem ele o iOS simplesmente não vê a
fechadura.
"""

from __future__ import annotations

import logging

import dbus
import dbus.service

from .bluez import (
    DBUS_PROP_IFACE,
    LE_ADVERTISEMENT_IFACE,
    Application,
    InvalidArgsException,
)

LOGGER = logging.getLogger(__name__)


class LockAdvertisement(dbus.service.Object):
    def __init__(self, bus, index: int, service_uuid: str, local_name: str) -> None:
        self.path = f"{Application.PATH}/advertisement{index}"
        self.bus = bus
        self.service_uuid = service_uuid
        self.local_name = local_name
        super().__init__(bus, self.path)

    def get_path(self) -> dbus.ObjectPath:
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {
            LE_ADVERTISEMENT_IFACE: {
                "Type": "peripheral",
                "ServiceUUIDs": dbus.Array([self.service_uuid], signature="s"),
                "LocalName": dbus.String(self.local_name),
                # Sem `Appearance`: não há categoria BLE padrão para fechadura
                # que o iOS aproveite, e o campo só ocuparia espaço no pacote.
                "Includes": dbus.Array(["tx-power"], signature="s"),
            }
        }

    @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != LE_ADVERTISEMENT_IFACE:
            raise InvalidArgsException()
        return self.get_properties()[LE_ADVERTISEMENT_IFACE]

    @dbus.service.method(LE_ADVERTISEMENT_IFACE, in_signature="", out_signature="")
    def Release(self):
        LOGGER.info("Anúncio liberado pelo BlueZ")
