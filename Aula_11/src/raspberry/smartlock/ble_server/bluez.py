"""Camada fina sobre a API GATT do BlueZ via D-Bus.

O BlueZ expõe o papel de periférico por D-Bus: a aplicação publica objetos que
implementam `org.bluez.GattService1` e `org.bluez.GattCharacteristic1`, registra
a árvore no `GattManager1` e o daemon cuida do ATT.

Só há aqui a mecânica de D-Bus — a lógica da fechadura vive em `gatt.py` e
`service.py`.
"""

from __future__ import annotations

import logging

import dbus
import dbus.exceptions
import dbus.service

LOGGER = logging.getLogger(__name__)

BLUEZ_SERVICE = "org.bluez"
DBUS_OM_IFACE = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP_IFACE = "org.freedesktop.DBus.Properties"
ADAPTER_IFACE = "org.bluez.Adapter1"
DEVICE_IFACE = "org.bluez.Device1"
GATT_MANAGER_IFACE = "org.bluez.GattManager1"
GATT_SERVICE_IFACE = "org.bluez.GattService1"
GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"
LE_ADVERTISING_MANAGER_IFACE = "org.bluez.LEAdvertisingManager1"
LE_ADVERTISEMENT_IFACE = "org.bluez.LEAdvertisement1"


class InvalidArgsException(dbus.exceptions.DBusException):
    _dbus_error_name = "org.freedesktop.DBus.Error.InvalidArgs"


class FailedException(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.Failed"


class NotSupportedException(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.NotSupported"


def to_bytes(value: bytes) -> dbus.Array:
    """Converte um `bytes` para o `ay` que o BlueZ espera."""
    return dbus.Array([dbus.Byte(b) for b in value], signature="y")


def from_bytes(value) -> bytes:
    """Converte o `ay` recebido do BlueZ de volta para `bytes`."""
    return bytes(bytearray(value))


def peer_of(options) -> str:
    """Extrai o identificador do celular que fez a operação.

    O BlueZ passa em `options["device"]` o caminho D-Bus do dispositivo remoto,
    por exemplo `/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF`. É estável durante a
    conexão e distingue dois celulares falando ao mesmo tempo.
    """
    device = options.get("device") if options else None
    return str(device) if device else "desconhecido"


def find_adapter(bus, iface: str, adapter: str) -> str:
    """Caminho do adaptador que implementa `iface` (ex.: o GattManager1 do hci0)."""
    manager = dbus.Interface(bus.get_object(BLUEZ_SERVICE, "/"), DBUS_OM_IFACE)
    for path, interfaces in manager.GetManagedObjects().items():
        if iface in interfaces and path.endswith(adapter):
            return path
    raise FailedException(f"adaptador {adapter} não expõe {iface}; o bluetoothd está rodando?")


class Application(dbus.service.Object):
    """Raiz da árvore de objetos GATT registrada no BlueZ."""

    PATH = "/br/usp/pcs3732/smartlock"

    def __init__(self, bus) -> None:
        self.path = self.PATH
        self.services: list[Service] = []
        super().__init__(bus, self.path)

    def get_path(self) -> dbus.ObjectPath:
        return dbus.ObjectPath(self.path)

    def add_service(self, service: "Service") -> None:
        self.services.append(service)

    @dbus.service.method(DBUS_OM_IFACE, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        response = {}
        for service in self.services:
            response[service.get_path()] = service.get_properties()
            for characteristic in service.characteristics:
                response[characteristic.get_path()] = characteristic.get_properties()
        return response


class Service(dbus.service.Object):
    def __init__(self, bus, index: int, uuid: str, primary: bool = True) -> None:
        self.path = f"{Application.PATH}/service{index}"
        self.bus = bus
        self.uuid = uuid
        self.primary = primary
        self.characteristics: list[Characteristic] = []
        super().__init__(bus, self.path)

    def get_path(self) -> dbus.ObjectPath:
        return dbus.ObjectPath(self.path)

    def add_characteristic(self, characteristic: "Characteristic") -> None:
        self.characteristics.append(characteristic)

    def get_properties(self):
        return {
            GATT_SERVICE_IFACE: {
                "UUID": self.uuid,
                "Primary": self.primary,
                "Characteristics": dbus.Array(
                    [c.get_path() for c in self.characteristics], signature="o"
                ),
            }
        }

    @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != GATT_SERVICE_IFACE:
            raise InvalidArgsException()
        return self.get_properties()[GATT_SERVICE_IFACE]


class Characteristic(dbus.service.Object):
    """Característica GATT.

    As subclasses sobrescrevem `read` e `write`, que trabalham com `bytes` — o
    vaivém com o tipo `ay` do D-Bus fica contido aqui.
    """

    def __init__(self, bus, index: int, uuid: str, flags: list[str], service: Service) -> None:
        self.path = f"{service.path}/char{index}"
        self.bus = bus
        self.uuid = uuid
        self.flags = flags
        self.service = service
        self.notifying = False
        super().__init__(bus, self.path)

    def get_path(self) -> dbus.ObjectPath:
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {
            GATT_CHRC_IFACE: {
                "Service": self.service.get_path(),
                "UUID": self.uuid,
                "Flags": dbus.Array(self.flags, signature="s"),
            }
        }

    # -- Ganchos das subclasses ---------------------------------------- #

    def read(self, peer: str) -> bytes:
        raise NotSupportedException()

    def write(self, value: bytes, peer: str) -> None:
        raise NotSupportedException()

    # -- Notificação ---------------------------------------------------- #

    def notify(self, value: bytes) -> None:
        """Empurra um valor para as centrais inscritas.

        O BlueZ não permite endereçar a notificação a uma central específica:
        o `PropertiesChanged` vai para todas as que deram `StartNotify`. Por
        isso toda mensagem notificada carrega o `deviceId`, e o app descarta o
        que não é dele. Ver a ressalva em `protocol/security.md`.
        """
        if not self.notifying:
            LOGGER.debug("Ninguém inscrito em %s; notificação descartada", self.uuid)
            return
        self.PropertiesChanged(GATT_CHRC_IFACE, {"Value": to_bytes(value)}, [])

    # -- D-Bus ---------------------------------------------------------- #

    @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != GATT_CHRC_IFACE:
            raise InvalidArgsException()
        return self.get_properties()[GATT_CHRC_IFACE]

    @dbus.service.signal(DBUS_PROP_IFACE, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    @dbus.service.method(GATT_CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        return to_bytes(self.read(peer_of(options)))

    @dbus.service.method(GATT_CHRC_IFACE, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        self.write(from_bytes(value), peer_of(options))

    @dbus.service.method(GATT_CHRC_IFACE)
    def StartNotify(self):
        self.notifying = True

    @dbus.service.method(GATT_CHRC_IFACE)
    def StopNotify(self):
        self.notifying = False
