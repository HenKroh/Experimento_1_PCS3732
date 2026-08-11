"""Persistência dos dispositivos autorizados e do log de acessos.

Um SQLite local basta: o volume é de dezenas de linhas e a Raspberry precisa
preservar os cadastros entre reinícios.

O segredo do dispositivo fica na tabela em claro porque o HMAC do desafio–resposta
é simétrico: a Raspberry precisa do mesmo segredo para recalcular a prova, então
não dá para guardar só um hash. O arquivo deve ficar com permissão 0600 e o
segredo nunca aparece em log — ver `protocol/security.md`.
"""

from __future__ import annotations

import os
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    public_identifier TEXT    NOT NULL UNIQUE,
    secret            BLOB    NOT NULL,
    device_name       TEXT    NOT NULL,
    created_at        TEXT    NOT NULL,
    revoked_at        TEXT,
    last_access_at    TEXT
);

CREATE TABLE IF NOT EXISTS access_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          TEXT NOT NULL,
    peer        TEXT,
    device_id   TEXT,
    operation   TEXT NOT NULL,
    status      TEXT NOT NULL,
    reason      TEXT
);

CREATE INDEX IF NOT EXISTS idx_access_log_at ON access_log (at);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass(frozen=True)
class Device:
    public_identifier: str
    device_name: str
    created_at: str
    revoked_at: str | None
    last_access_at: str | None

    @property
    def is_active(self) -> bool:
        return self.revoked_at is None


class Database:
    """Acesso ao SQLite, seguro para uso a partir de mais de uma thread."""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        new_file = not self._path.exists()

        self._lock = threading.RLock()
        self._connection = sqlite3.connect(self._path, check_same_thread=False)
        self._connection.row_factory = sqlite3.Row
        self._connection.executescript(SCHEMA)
        self._connection.commit()

        if new_file:
            # O arquivo guarda segredos; ninguém além do dono precisa lê-lo.
            os.chmod(self._path, 0o600)

    def close(self) -> None:
        with self._lock:
            self._connection.close()

    # ----------------------------------------------------------------- #
    # Dispositivos
    # ----------------------------------------------------------------- #

    def register_device(self, public_identifier: str, secret: bytes, device_name: str) -> None:
        """Cria ou substitui a credencial de um celular.

        O app reaproveita o mesmo `deviceId` ao recadastrar na mesma fechadura,
        então um novo cadastro sobrescreve o registro antigo — e reativa um
        dispositivo que havia sido revogado.
        """
        with self._lock:
            self._connection.execute(
                """
                INSERT INTO devices (public_identifier, secret, device_name, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (public_identifier) DO UPDATE SET
                    secret         = excluded.secret,
                    device_name    = excluded.device_name,
                    created_at     = excluded.created_at,
                    revoked_at     = NULL,
                    last_access_at = NULL
                """,
                (public_identifier, secret, device_name, _now()),
            )
            self._connection.commit()

    def secret_for(self, public_identifier: str) -> bytes | None:
        """Segredo de um dispositivo ativo, ou None se desconhecido ou revogado."""
        with self._lock:
            row = self._connection.execute(
                "SELECT secret FROM devices WHERE public_identifier = ? AND revoked_at IS NULL",
                (public_identifier,),
            ).fetchone()
        return bytes(row["secret"]) if row is not None else None

    def revoke_device(self, public_identifier: str) -> bool:
        with self._lock:
            cursor = self._connection.execute(
                "UPDATE devices SET revoked_at = ? WHERE public_identifier = ? AND revoked_at IS NULL",
                (_now(), public_identifier),
            )
            self._connection.commit()
        return cursor.rowcount > 0

    def touch_device(self, public_identifier: str) -> None:
        with self._lock:
            self._connection.execute(
                "UPDATE devices SET last_access_at = ? WHERE public_identifier = ?",
                (_now(), public_identifier),
            )
            self._connection.commit()

    def list_devices(self) -> list[Device]:
        with self._lock:
            rows = self._connection.execute(
                """
                SELECT public_identifier, device_name, created_at, revoked_at, last_access_at
                FROM devices ORDER BY id
                """
            ).fetchall()
        return [
            Device(
                public_identifier=row["public_identifier"],
                device_name=row["device_name"],
                created_at=row["created_at"],
                revoked_at=row["revoked_at"],
                last_access_at=row["last_access_at"],
            )
            for row in rows
        ]

    # ----------------------------------------------------------------- #
    # Log de acessos
    # ----------------------------------------------------------------- #

    def log_access(
        self,
        operation: str,
        status: str,
        *,
        peer: str | None = None,
        device_id: str | None = None,
        reason: str | None = None,
    ) -> None:
        with self._lock:
            self._connection.execute(
                """
                INSERT INTO access_log (at, peer, device_id, operation, status, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (_now(), peer, device_id, operation, status, reason),
            )
            self._connection.commit()

    def recent_access(self, limit: int = 50) -> list[sqlite3.Row]:
        with self._lock:
            return self._connection.execute(
                "SELECT * FROM access_log ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
