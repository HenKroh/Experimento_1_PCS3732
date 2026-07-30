"""Linha de comando do serviço.

    python -m smartlock run                # sobe o periférico BLE
    python -m smartlock devices            # lista os celulares cadastrados
    python -m smartlock revoke <deviceId>  # revoga um celular
    python -m smartlock log                # últimos acessos
"""

from __future__ import annotations

import argparse
import logging
import sys

from . import gpio as gpio_module
from .authentication import Authenticator
from .config import Config
from .database import Database
from .lock_controller import LockController
from .service import SmartLockService

LOGGER = logging.getLogger("smartlock")


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="smartlock", description=__doc__)
    parser.add_argument("--database", help="caminho do SQLite")
    parser.add_argument("--verbose", "-v", action="store_true", help="log em nível DEBUG")

    commands = parser.add_subparsers(dest="command")

    run = commands.add_parser("run", help="sobe o periférico BLE (padrão)")
    run.add_argument("--lock-name", help="nome exibido no app")
    run.add_argument("--advertised-name", help="nome curto no anúncio BLE")
    run.add_argument("--adapter", help="adaptador Bluetooth (padrão: hci0)")
    run.add_argument(
        "--no-gpio",
        action="store_true",
        help="não usa hardware; os botões viram as teclas 'a' (aprovar) e 'd' (negar)",
    )
    run.add_argument(
        "--require-encryption",
        action="store_true",
        help="exige emparelhamento com link criptografado nas características sensíveis",
    )

    commands.add_parser("devices", help="lista os celulares cadastrados")

    revoke = commands.add_parser("revoke", help="revoga um celular")
    revoke.add_argument("device_id", help="o public_identifier mostrado por 'devices'")

    log = commands.add_parser("log", help="últimos acessos registrados")
    log.add_argument("--limit", type=int, default=20)

    return parser


def _config_from(args: argparse.Namespace) -> Config:
    config = Config()
    if args.database:
        config.database_path = args.database
    for attribute in ("lock_name", "advertised_name", "adapter"):
        value = getattr(args, attribute, None)
        if value:
            setattr(config, attribute, value)
    if getattr(args, "no_gpio", False):
        config.use_gpio = False
        config.keyboard_buttons = True
    if getattr(args, "require_encryption", False):
        config.require_encryption = True
    return config


def _run(config: Config) -> int:
    from .runner import BLERunner, GLibScheduler

    try:
        from gi.repository import GLib
    except ImportError:
        LOGGER.error(
            "python3-gi e python3-dbus são necessários para o modo BLE. "
            "Na Raspberry: sudo apt install python3-gi python3-dbus"
        )
        return 1

    database = Database(config.database_path)
    scheduler = GLibScheduler(GLib)
    backend = gpio_module.create_backend(config)
    controller = LockController(backend, unlock_duration=config.unlock_duration)
    authenticator = Authenticator(
        secret_provider=database.secret_for,
        ttl=config.challenge_ttl,
        max_failed_attempts=config.max_failed_attempts,
        lockout_duration=config.lockout_duration,
    )
    service = SmartLockService(
        config, database, authenticator, controller, backend, scheduler
    )

    backend.watch_buttons(service.approve, service.deny)
    if config.keyboard_buttons:
        gpio_module.watch_keyboard(service.approve, service.deny)
        LOGGER.info("Botões pelo teclado: 'a' + Enter aprova, 'd' + Enter nega")

    try:
        BLERunner(config, service).run()
    finally:
        controller.shutdown()
        backend.cleanup()
        database.close()
        LOGGER.info("Serviço encerrado")
    return 0


def _devices(config: Config) -> int:
    database = Database(config.database_path)
    devices = database.list_devices()
    if not devices:
        print("Nenhum celular cadastrado.")
    for device in devices:
        estado = "ativo" if device.is_active else f"revogado em {device.revoked_at}"
        print(
            f"{device.public_identifier}  {device.device_name!r}  "
            f"cadastrado em {device.created_at}  último acesso {device.last_access_at or '—'}  [{estado}]"
        )
    database.close()
    return 0


def _revoke(config: Config, device_id: str) -> int:
    database = Database(config.database_path)
    revoked = database.revoke_device(device_id)
    database.close()
    if revoked:
        print(f"Dispositivo {device_id} revogado.")
        return 0
    print(f"Dispositivo {device_id} não encontrado ou já revogado.", file=sys.stderr)
    return 1


def _log(config: Config, limit: int) -> int:
    database = Database(config.database_path)
    for row in database.recent_access(limit):
        print(
            f"{row['at']}  {row['operation']:<7} {row['status']:<12} "
            f"{row['device_id'] or '—'}  {row['reason'] or ''}".rstrip()
        )
    database.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    config = _config_from(args)
    command = args.command or "run"

    if command == "run":
        return _run(config)
    if command == "devices":
        return _devices(config)
    if command == "revoke":
        return _revoke(config, args.device_id)
    if command == "log":
        return _log(config, args.limit)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
