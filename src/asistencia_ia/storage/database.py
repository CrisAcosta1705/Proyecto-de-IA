import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timezone
import hashlib
from pathlib import Path
from typing import Iterator
import unicodedata
import uuid

import numpy as np
from numpy.typing import ArrayLike, NDArray

from asistencia_ia.recognition.matcher import ReferenceEmbedding, normalize_embedding
from asistencia_ia.recognition.profile import PROFILE_ID
from asistencia_ia.security import DataCipher


Embedding = NDArray[np.float32]
LEGACY_PROFILE_ID = "arcface_w600k_mbf_112x112_v3"


@dataclass(frozen=True)
class FaceReferenceCapture:
    original_embedding: ArrayLike
    mirrored_embedding: ArrayLike
    original_photo: bytes
    mirrored_photo: bytes
    capture_id: str = ""
    photo_mime_type: str = "image/jpeg"
    source_camera: str = "UNKNOWN"
    pose: str = "UNSPECIFIED"
    quality: float = 1.0


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _canonical_student_identity(value: str) -> str:
    decomposed = unicodedata.normalize("NFD", value.strip())
    without_marks = "".join(
        character
        for character in decomposed
        if not unicodedata.combining(character)
    )
    return " ".join(without_marks.casefold().split())


class AttendanceDatabase:
    """Persistencia local de estudiantes, embeddings y asistencias."""

    def __init__(self, path: Path | str, data_cipher: DataCipher | None = None):
        self.path = Path(path)
        self.data_cipher = data_cipher

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._restrict_permissions(self.path.parent, 0o700)
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA secure_delete = ON")
        connection.execute("PRAGMA temp_store = MEMORY")
        connection.execute("PRAGMA trusted_schema = OFF")
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
            self._restrict_database_files()

    @staticmethod
    def _restrict_permissions(path: Path, mode: int) -> None:
        try:
            path.chmod(mode)
        except (FileNotFoundError, PermissionError, OSError):
            pass

    def _restrict_database_files(self) -> None:
        for suffix in ("", "-wal", "-shm"):
            self._restrict_permissions(Path(f"{self.path}{suffix}"), 0o600)

    @staticmethod
    def _photo_aad(student_id: str) -> bytes:
        return f"student-photo:v1:{student_id}".encode("utf-8")

    @staticmethod
    def _embedding_aad(student_id: str) -> bytes:
        return f"face-embedding:v1:{student_id}".encode("utf-8")

    def _encrypt_photo(self, student_id: str, photo: bytes) -> bytes:
        if self.data_cipher is None:
            return photo
        return self.data_cipher.encrypt(photo, self._photo_aad(student_id))

    def _decrypt_photo(self, student_id: str, photo: bytes) -> bytes:
        if self.data_cipher is None:
            return photo
        return self.data_cipher.decrypt(photo, self._photo_aad(student_id))

    def _encrypt_embedding(self, student_id: str, embedding: bytes) -> bytes:
        if self.data_cipher is None:
            return embedding
        return self.data_cipher.encrypt(
            embedding,
            self._embedding_aad(student_id),
        )

    def _decrypt_embedding(self, student_id: str, embedding: bytes) -> bytes:
        if self.data_cipher is None:
            return embedding
        return self.data_cipher.decrypt(
            embedding,
            self._embedding_aad(student_id),
        )

    def _migrate_sensitive_data(self, connection: sqlite3.Connection) -> bool:
        if self.data_cipher is None:
            return False
        migrated = False
        photos = connection.execute(
            "SELECT student_id, photo FROM students WHERE photo IS NOT NULL"
        ).fetchall()
        for row in photos:
            student_id = str(row["student_id"])
            stored = bytes(row["photo"])
            if not self.data_cipher.needs_reencryption(stored):
                continue
            plaintext = self._decrypt_photo(student_id, stored)
            connection.execute(
                "UPDATE students SET photo = ? WHERE student_id = ?",
                (self._encrypt_photo(student_id, plaintext), student_id),
            )
            migrated = True

        embeddings = connection.execute(
            "SELECT id, student_id, embedding FROM face_embeddings"
        ).fetchall()
        for row in embeddings:
            student_id = str(row["student_id"])
            stored = bytes(row["embedding"])
            if not self.data_cipher.needs_reencryption(stored):
                continue
            plaintext = self._decrypt_embedding(student_id, stored)
            connection.execute(
                "UPDATE face_embeddings SET embedding = ? WHERE id = ?",
                (self._encrypt_embedding(student_id, plaintext), row["id"]),
            )
            migrated = True

        capture_photos = connection.execute(
            """
            SELECT capture_id, student_id, original_photo, mirrored_photo
            FROM face_reference_captures
            """
        ).fetchall()
        for row in capture_photos:
            student_id = str(row["student_id"])
            updates: list[tuple[str, bytes]] = []
            for column in ("original_photo", "mirrored_photo"):
                value = row[column]
                if value is None:
                    continue
                stored = bytes(value)
                if self.data_cipher.needs_reencryption(stored):
                    plaintext = self._decrypt_photo(student_id, stored)
                    updates.append((column, self._encrypt_photo(student_id, plaintext)))
            for column, value in updates:
                connection.execute(
                    f"UPDATE face_reference_captures SET {column} = ? WHERE capture_id = ?",
                    (value, row["capture_id"]),
                )
                migrated = True
        return migrated

    def _secure_compact(self) -> None:
        connection = sqlite3.connect(self.path, timeout=15)
        try:
            connection.execute("PRAGMA secure_delete = ON")
            connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.execute("VACUUM")
        finally:
            connection.close()
            self._restrict_database_files()

    def initialize(self) -> None:
        migrated_sensitive_data = False
        with self._connection() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode = WAL;

                CREATE TABLE IF NOT EXISTS students (
                    student_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    sex TEXT NOT NULL DEFAULT 'NO_ESPECIFICADO',
                    photo BLOB,
                    photo_mime_type TEXT,
                    photo_sha256 TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS face_embeddings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    student_id TEXT NOT NULL,
                    embedding BLOB NOT NULL,
                    dimension INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    capture_id TEXT,
                    variant TEXT NOT NULL DEFAULT 'ORIGINAL',
                    model_profile TEXT NOT NULL DEFAULT 'arcface_w600k_mbf_112x112_v3',
                    active INTEGER NOT NULL DEFAULT 1,
                    quality REAL,
                    quarantine_reason TEXT,
                    FOREIGN KEY (student_id) REFERENCES students(student_id)
                        ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS face_reference_captures (
                    capture_id TEXT PRIMARY KEY,
                    student_id TEXT NOT NULL,
                    original_photo BLOB,
                    mirrored_photo BLOB,
                    photo_mime_type TEXT NOT NULL DEFAULT 'image/jpeg',
                    source_camera TEXT NOT NULL DEFAULT 'UNKNOWN',
                    pose TEXT NOT NULL DEFAULT 'UNSPECIFIED',
                    quality REAL NOT NULL DEFAULT 1.0,
                    active INTEGER NOT NULL DEFAULT 1,
                    quarantine_reason TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (student_id) REFERENCES students(student_id)
                        ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS student_aliases (
                    alias_student_id TEXT PRIMARY KEY,
                    canonical_student_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (canonical_student_id) REFERENCES students(student_id)
                        ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS attendance_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    course TEXT NOT NULL,
                    attendance_date TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    ended_at TEXT
                );

                CREATE TABLE IF NOT EXISTS attendance_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    student_id TEXT NOT NULL,
                    similarity REAL NOT NULL,
                    recorded_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES attendance_sessions(id)
                        ON DELETE CASCADE,
                    FOREIGN KEY (student_id) REFERENCES students(student_id)
                        ON DELETE RESTRICT,
                    UNIQUE (session_id, student_id)
                );
                """
            )
            student_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(students)").fetchall()
            }
            student_migrations = {
                "sex": (
                    "ALTER TABLE students ADD COLUMN sex TEXT NOT NULL "
                    "DEFAULT 'NO_ESPECIFICADO'"
                ),
                "photo": "ALTER TABLE students ADD COLUMN photo BLOB",
                "photo_mime_type": (
                    "ALTER TABLE students ADD COLUMN photo_mime_type TEXT"
                ),
                "photo_sha256": "ALTER TABLE students ADD COLUMN photo_sha256 TEXT",
            }
            for column, statement in student_migrations.items():
                if column not in student_columns:
                    connection.execute(statement)

            embedding_columns = {
                row["name"]
                for row in connection.execute(
                    "PRAGMA table_info(face_embeddings)"
                ).fetchall()
            }
            embedding_migrations = {
                "capture_id": "ALTER TABLE face_embeddings ADD COLUMN capture_id TEXT",
                "variant": (
                    "ALTER TABLE face_embeddings ADD COLUMN variant TEXT NOT NULL "
                    "DEFAULT 'ORIGINAL'"
                ),
                "model_profile": (
                    "ALTER TABLE face_embeddings ADD COLUMN model_profile TEXT NOT NULL "
                    f"DEFAULT '{LEGACY_PROFILE_ID}'"
                ),
                "active": (
                    "ALTER TABLE face_embeddings ADD COLUMN active INTEGER NOT NULL "
                    "DEFAULT 1"
                ),
                "quality": "ALTER TABLE face_embeddings ADD COLUMN quality REAL",
                "quarantine_reason": (
                    "ALTER TABLE face_embeddings ADD COLUMN quarantine_reason TEXT"
                ),
            }
            for column, statement in embedding_migrations.items():
                if column not in embedding_columns:
                    connection.execute(statement)
            connection.execute(
                """
                UPDATE face_embeddings
                SET capture_id = 'legacy-' || id
                WHERE capture_id IS NULL OR capture_id = ''
                """
            )
            if PROFILE_ID != LEGACY_PROFILE_ID:
                connection.execute(
                    """
                    UPDATE face_embeddings
                    SET active = 0
                    WHERE model_profile = ?
                    """,
                    (LEGACY_PROFILE_ID,),
                )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_face_embeddings_profile "
                "ON face_embeddings(model_profile, active, student_id)"
            )

            session_columns = {
                row["name"]
                for row in connection.execute(
                    "PRAGMA table_info(attendance_sessions)"
                ).fetchall()
            }
            added_attendance_date = "attendance_date" not in session_columns
            if added_attendance_date:
                connection.execute(
                    "ALTER TABLE attendance_sessions "
                    "ADD COLUMN attendance_date TEXT NOT NULL DEFAULT ''"
                )
                sessions = connection.execute(
                    "SELECT id, started_at FROM attendance_sessions"
                ).fetchall()
                for session in sessions:
                    started_at = datetime.fromisoformat(session["started_at"])
                    local_date = started_at.astimezone().date().isoformat()
                    connection.execute(
                        "UPDATE attendance_sessions SET attendance_date = ? WHERE id = ?",
                        (local_date, session["id"]),
                    )
            connection.execute(
                """
                UPDATE attendance_sessions
                SET attendance_date = substr(started_at, 1, 10)
                WHERE attendance_date = ''
                """
            )
            migrated_sensitive_data = self._migrate_sensitive_data(connection)
        if migrated_sensitive_data:
            self._secure_compact()

    def save_student(
        self,
        student_id: str,
        name: str,
        embeddings: list[ArrayLike],
        replace_embeddings: bool = True,
        photo: bytes | None = None,
        photo_mime_type: str | None = None,
        sex: str = "NO_ESPECIFICADO",
    ) -> int:
        normalized = [normalize_embedding(item) for item in embeddings]
        if not normalized:
            raise ValueError("Se requiere al menos un embedding facial.")

        now = _utc_now()
        photo_sha256 = hashlib.sha256(photo).hexdigest() if photo else None
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO students(
                    student_id, name, sex, photo, photo_mime_type, photo_sha256,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(student_id) DO UPDATE SET
                    name = excluded.name,
                    sex = excluded.sex,
                    photo = COALESCE(excluded.photo, students.photo),
                    photo_mime_type = COALESCE(
                        excluded.photo_mime_type, students.photo_mime_type
                    ),
                    photo_sha256 = COALESCE(
                        excluded.photo_sha256, students.photo_sha256
                    ),
                    updated_at = excluded.updated_at
                """,
                (
                    student_id,
                    name,
                    sex,
                    self._encrypt_photo(student_id, photo) if photo else None,
                    photo_mime_type,
                    photo_sha256,
                    now,
                    now,
                ),
            )
            if replace_embeddings:
                connection.execute(
                    "DELETE FROM face_embeddings "
                    "WHERE student_id = ? AND model_profile = ?",
                    (student_id, PROFILE_ID),
                )
            connection.executemany(
                """
                INSERT INTO face_embeddings(
                    student_id, embedding, dimension, created_at, capture_id,
                    variant, model_profile, active, quality
                ) VALUES (?, ?, ?, ?, ?, 'ORIGINAL', ?, 1, 1.0)
                """,
                [
                    (
                        student_id,
                        self._encrypt_embedding(
                            student_id,
                            vector.astype("<f4", copy=False).tobytes(),
                        ),
                        vector.size,
                        now,
                        f"legacy-api-{uuid.uuid4().hex}",
                        PROFILE_ID,
                    )
                    for vector in normalized
                ],
            )
        return len(normalized)

    def save_student_captures(
        self,
        student_id: str,
        name: str,
        captures: list[FaceReferenceCapture],
        replace_embeddings: bool = True,
        sex: str = "NO_ESPECIFICADO",
    ) -> int:
        if not captures:
            raise ValueError("Se requiere al menos una captura facial.")
        now = _utc_now()
        first_photo = captures[0].original_photo
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO students(
                    student_id, name, sex, photo, photo_mime_type, photo_sha256,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(student_id) DO UPDATE SET
                    name = excluded.name,
                    sex = excluded.sex,
                    photo = excluded.photo,
                    photo_mime_type = excluded.photo_mime_type,
                    photo_sha256 = excluded.photo_sha256,
                    updated_at = excluded.updated_at
                """,
                (
                    student_id,
                    name,
                    sex,
                    self._encrypt_photo(student_id, first_photo),
                    captures[0].photo_mime_type,
                    hashlib.sha256(first_photo).hexdigest(),
                    now,
                    now,
                ),
            )
            if replace_embeddings:
                old_capture_ids = [
                    str(row[0])
                    for row in connection.execute(
                        """
                        SELECT DISTINCT capture_id FROM face_embeddings
                        WHERE student_id = ? AND model_profile = ?
                        """,
                        (student_id, PROFILE_ID),
                    ).fetchall()
                    if row[0]
                ]
                connection.execute(
                    "DELETE FROM face_embeddings "
                    "WHERE student_id = ? AND model_profile = ?",
                    (student_id, PROFILE_ID),
                )
                connection.executemany(
                    """
                    DELETE FROM face_reference_captures
                    WHERE capture_id = ?
                      AND NOT EXISTS(
                          SELECT 1 FROM face_embeddings
                          WHERE face_embeddings.capture_id =
                                face_reference_captures.capture_id
                      )
                    """,
                    [(capture_id,) for capture_id in old_capture_ids],
                )

            for capture in captures:
                capture_id = capture.capture_id.strip() or uuid.uuid4().hex
                owner = connection.execute(
                    """
                    SELECT student_id FROM face_reference_captures
                    WHERE capture_id = ?
                    """,
                    (capture_id,),
                ).fetchone()
                if owner is not None and str(owner["student_id"]) != student_id:
                    raise ValueError(
                        "El identificador de la captura pertenece a otro estudiante."
                    )
                original = normalize_embedding(capture.original_embedding)
                mirrored = normalize_embedding(capture.mirrored_embedding)
                connection.execute(
                    """
                    INSERT OR REPLACE INTO face_reference_captures(
                        capture_id, student_id, original_photo, mirrored_photo,
                        photo_mime_type, source_camera, pose, quality, active,
                        quarantine_reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, NULL, ?)
                    """,
                    (
                        capture_id,
                        student_id,
                        self._encrypt_photo(student_id, capture.original_photo),
                        self._encrypt_photo(student_id, capture.mirrored_photo),
                        capture.photo_mime_type,
                        capture.source_camera.upper(),
                        capture.pose.upper(),
                        float(capture.quality),
                        now,
                    ),
                )
                for variant, vector in (
                    ("ORIGINAL", original),
                    ("MIRRORED", mirrored),
                ):
                    connection.execute(
                        """
                        INSERT INTO face_embeddings(
                            student_id, embedding, dimension, created_at,
                            capture_id, variant, model_profile, active, quality
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                        """,
                        (
                            student_id,
                            self._encrypt_embedding(
                                student_id,
                                vector.astype("<f4", copy=False).tobytes(),
                            ),
                            vector.size,
                            now,
                            capture_id,
                            variant,
                            PROFILE_ID,
                            float(capture.quality),
                        ),
                    )
        return len(captures)

    def references(
        self,
        model_profile: str | None = None,
    ) -> dict[str, list[Embedding]]:
        references: dict[str, list[Embedding]] = {}
        with self._connection() as connection:
            query = """
                SELECT student_id, embedding, dimension
                FROM face_embeddings
                WHERE active = 1
            """
            parameters: tuple[object, ...] = ()
            if model_profile is not None:
                query += " AND model_profile = ?"
                parameters = (model_profile,)
            query += " ORDER BY student_id, id"
            rows = connection.execute(
                query,
                parameters,
            ).fetchall()

        for row in rows:
            embedding = self._decrypt_embedding(
                str(row["student_id"]),
                bytes(row["embedding"]),
            )
            vector = np.frombuffer(embedding, dtype="<f4").copy()
            if vector.size != row["dimension"]:
                raise ValueError(
                    f"Embedding corrupto para el estudiante {row['student_id']}."
                )
            references.setdefault(row["student_id"], []).append(vector)
        return references

    def reference_records(
        self,
        model_profile: str = PROFILE_ID,
    ) -> dict[str, list[ReferenceEmbedding]]:
        references: dict[str, list[ReferenceEmbedding]] = {}
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT student_id, capture_id, variant, embedding, dimension
                FROM face_embeddings
                WHERE model_profile = ? AND active = 1
                ORDER BY student_id, capture_id, variant
                """,
                (model_profile,),
            ).fetchall()
        for row in rows:
            student_id = str(row["student_id"])
            plaintext = self._decrypt_embedding(student_id, bytes(row["embedding"]))
            vector = np.frombuffer(plaintext, dtype="<f4").copy()
            if vector.size != row["dimension"]:
                raise ValueError(
                    f"Embedding corrupto para el estudiante {student_id}."
                )
            references.setdefault(student_id, []).append(
                ReferenceEmbedding(
                    capture_id=str(row["capture_id"]),
                    embedding=vector,
                    variant=str(row["variant"]),
                )
            )
        return references

    def student_names(self) -> dict[str, str]:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT student_id, name FROM students ORDER BY student_id"
            ).fetchall()
        return {row["student_id"]: row["name"] for row in rows}

    def list_students(self) -> list[dict[str, object]]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT s.student_id, s.name, s.sex, s.created_at, s.updated_at,
                       CASE WHEN s.photo IS NULL THEN 0 ELSE 1 END AS has_photo,
                       s.photo_sha256,
                       COUNT(DISTINCT CASE WHEN e.active = 1 THEN e.capture_id END)
                           AS embedding_count
                FROM students AS s
                LEFT JOIN face_embeddings AS e
                    ON e.student_id = s.student_id
                   AND e.active = 1
                   AND e.model_profile = ?
                GROUP BY s.student_id
                ORDER BY s.name COLLATE NOCASE, s.student_id
                """,
                (PROFILE_ID,),
            ).fetchall()
        return [dict(row) for row in rows]

    def student_identity_conflict(
        self,
        student_id: str,
        name: str,
        existing_student_id: str | None = None,
    ) -> str | None:
        excluded_id = (
            _canonical_student_identity(existing_student_id)
            if existing_student_id
            else None
        )
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT student_id, name FROM students"
            ).fetchall()
        available = [
            row
            for row in rows
            if _canonical_student_identity(str(row["student_id"])) != excluded_id
        ]
        canonical_id = _canonical_student_identity(student_id)
        if any(
            _canonical_student_identity(str(row["student_id"])) == canonical_id
            for row in available
        ):
            return "carnet"
        canonical_name = _canonical_student_identity(name)
        if any(
            _canonical_student_identity(str(row["name"])) == canonical_name
            for row in available
        ):
            return "name"
        return None

    def student_photo(self, student_id: str) -> tuple[bytes, str] | None:
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT photo, photo_mime_type
                FROM students
                WHERE student_id = ? AND photo IS NOT NULL
                """,
                (student_id,),
            ).fetchone()
        if row is None:
            return None
        photo = self._decrypt_photo(student_id, bytes(row["photo"]))
        return photo, str(row["photo_mime_type"] or "image/jpeg")

    def update_student_sex(self, student_id: str, sex: str) -> str | None:
        with self._connection() as connection:
            student = connection.execute(
                "SELECT name FROM students WHERE student_id = ?",
                (student_id,),
            ).fetchone()
            if student is None:
                return None
            connection.execute(
                "UPDATE students SET sex = ?, updated_at = ? WHERE student_id = ?",
                (sex, _utc_now(), student_id),
            )
        return str(student["name"])

    def update_student_profile(
        self,
        student_id: str,
        name: str,
        sex: str,
    ) -> dict[str, str] | None:
        with self._connection() as connection:
            exists = connection.execute(
                "SELECT 1 FROM students WHERE student_id = ?",
                (student_id,),
            ).fetchone()
            if exists is None:
                return None
            connection.execute(
                """
                UPDATE students
                SET name = ?, sex = ?, updated_at = ?
                WHERE student_id = ?
                """,
                (name, sex, _utc_now(), student_id),
            )
        return {"student_id": student_id, "name": name, "sex": sex}

    def clear_student_recognition(self, student_id: str) -> str | None:
        """Elimina datos biometricos sin borrar el historial de asistencia."""
        with self._connection() as connection:
            student = connection.execute(
                "SELECT name FROM students WHERE student_id = ?",
                (student_id,),
            ).fetchone()
            if student is None:
                return None
            connection.execute(
                "DELETE FROM face_embeddings WHERE student_id = ?",
                (student_id,),
            )
            connection.execute(
                "DELETE FROM face_reference_captures WHERE student_id = ?",
                (student_id,),
            )
            connection.execute(
                """
                UPDATE students
                SET photo = NULL,
                    photo_mime_type = NULL,
                    photo_sha256 = NULL,
                    updated_at = ?
                WHERE student_id = ?
                """,
                (_utc_now(), student_id),
            )
        return str(student["name"])

    def delete_student(self, student_id: str) -> str | None:
        """Elimina el perfil, sus biometricos y sus registros de asistencia."""
        with self._connection() as connection:
            student = connection.execute(
                "SELECT name FROM students WHERE student_id = ?",
                (student_id,),
            ).fetchone()
            if student is None:
                return None
            connection.execute(
                "DELETE FROM attendance_records WHERE student_id = ?",
                (student_id,),
            )
            connection.execute(
                "DELETE FROM students WHERE student_id = ?",
                (student_id,),
            )
        return str(student["name"])

    def embedding_count(self, student_id: str) -> int:
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT COUNT(DISTINCT capture_id) AS count
                FROM face_embeddings
                WHERE student_id = ? AND active = 1 AND model_profile = ?
                """,
                (student_id, PROFILE_ID),
            ).fetchone()
        return int(row["count"])

    def list_reference_captures(
        self,
        student_id: str,
        include_inactive: bool = True,
    ) -> list[dict[str, object]]:
        where_active = "" if include_inactive else " AND c.active = 1"
        with self._connection() as connection:
            rows = connection.execute(
                f"""
                SELECT c.capture_id, c.student_id, c.photo_mime_type,
                       c.source_camera, c.pose, c.quality, c.active,
                       c.quarantine_reason, c.created_at,
                       COUNT(e.id) AS variant_count
                FROM face_reference_captures AS c
                LEFT JOIN face_embeddings AS e
                    ON e.capture_id = c.capture_id
                   AND e.model_profile = ?
                WHERE c.student_id = ?{where_active}
                GROUP BY c.capture_id
                ORDER BY c.created_at, c.capture_id
                """,
                (PROFILE_ID, student_id),
            ).fetchall()
        return [dict(row) for row in rows]

    def reference_photo(
        self,
        student_id: str,
        capture_id: str,
        variant: str,
    ) -> tuple[bytes, str] | None:
        column = "mirrored_photo" if variant.upper() == "MIRRORED" else "original_photo"
        with self._connection() as connection:
            row = connection.execute(
                f"""
                SELECT {column} AS photo, photo_mime_type
                FROM face_reference_captures
                WHERE student_id = ? AND capture_id = ?
                """,
                (student_id, capture_id),
            ).fetchone()
        if row is None or row["photo"] is None:
            return None
        return (
            self._decrypt_photo(student_id, bytes(row["photo"])),
            str(row["photo_mime_type"] or "image/jpeg"),
        )

    def quarantine_capture(self, capture_id: str, reason: str) -> bool:
        with self._connection() as connection:
            updated = connection.execute(
                """
                UPDATE face_reference_captures
                SET active = 0, quarantine_reason = ?
                WHERE capture_id = ?
                """,
                (reason, capture_id),
            ).rowcount
            connection.execute(
                """
                UPDATE face_embeddings
                SET active = 0, quarantine_reason = ?
                WHERE capture_id = ?
                """,
                (reason, capture_id),
            )
        return updated > 0

    def restore_capture(self, capture_id: str) -> bool:
        with self._connection() as connection:
            updated = connection.execute(
                """
                UPDATE face_reference_captures
                SET active = 1, quarantine_reason = NULL
                WHERE capture_id = ?
                """,
                (capture_id,),
            ).rowcount
            connection.execute(
                """
                UPDATE face_embeddings
                SET active = 1, quarantine_reason = NULL
                WHERE capture_id = ?
                """,
                (capture_id,),
            )
        return updated > 0

    def merge_students(self, source_id: str, canonical_id: str) -> bool:
        """Fusiona una identidad duplicada sin perder asistencias ni biometricos."""
        if source_id == canonical_id:
            return False
        with self._connection() as connection:
            source = connection.execute(
                "SELECT 1 FROM students WHERE student_id = ?",
                (source_id,),
            ).fetchone()
            target = connection.execute(
                "SELECT 1 FROM students WHERE student_id = ?",
                (canonical_id,),
            ).fetchone()
            if source is None or target is None:
                return False
            rows = connection.execute(
                """
                SELECT session_id, similarity, recorded_at
                FROM attendance_records WHERE student_id = ?
                """,
                (source_id,),
            ).fetchall()
            for row in rows:
                connection.execute(
                    """
                    INSERT INTO attendance_records(
                        session_id, student_id, similarity, recorded_at
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT(session_id, student_id) DO UPDATE SET
                        similarity = MAX(similarity, excluded.similarity)
                    """,
                    (
                        row["session_id"],
                        canonical_id,
                        row["similarity"],
                        row["recorded_at"],
                    ),
                )
            connection.execute(
                "DELETE FROM attendance_records WHERE student_id = ?",
                (source_id,),
            )
            source_embeddings = connection.execute(
                "SELECT id, embedding FROM face_embeddings WHERE student_id = ?",
                (source_id,),
            ).fetchall()
            for row in source_embeddings:
                plaintext = self._decrypt_embedding(
                    source_id,
                    bytes(row["embedding"]),
                )
                connection.execute(
                    "UPDATE face_embeddings SET embedding = ? WHERE id = ?",
                    (self._encrypt_embedding(canonical_id, plaintext), row["id"]),
                )
            source_captures = connection.execute(
                """
                SELECT capture_id, original_photo, mirrored_photo
                FROM face_reference_captures WHERE student_id = ?
                """,
                (source_id,),
            ).fetchall()
            for row in source_captures:
                values: list[bytes | None] = []
                for column in ("original_photo", "mirrored_photo"):
                    stored = row[column]
                    values.append(
                        None
                        if stored is None
                        else self._encrypt_photo(
                            canonical_id,
                            self._decrypt_photo(source_id, bytes(stored)),
                        )
                    )
                connection.execute(
                    """
                    UPDATE face_reference_captures
                    SET original_photo = ?, mirrored_photo = ?
                    WHERE capture_id = ?
                    """,
                    (values[0], values[1], row["capture_id"]),
                )
            connection.execute(
                "UPDATE face_embeddings SET student_id = ? WHERE student_id = ?",
                (canonical_id, source_id),
            )
            connection.execute(
                "UPDATE face_reference_captures SET student_id = ? WHERE student_id = ?",
                (canonical_id, source_id),
            )
            connection.execute(
                "DELETE FROM students WHERE student_id = ?",
                (source_id,),
            )
            connection.execute(
                """
                INSERT OR REPLACE INTO student_aliases(
                    alias_student_id, canonical_student_id, created_at
                ) VALUES (?, ?, ?)
                """,
                (source_id, canonical_id, _utc_now()),
            )
        return True

    def attendance_status(
        self,
        course: str,
        attendance_date: str,
    ) -> dict[str, object] | None:
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT s.id AS session_id, s.course, s.attendance_date,
                       s.started_at, s.ended_at, COUNT(r.id) AS attendance_count
                FROM attendance_sessions AS s
                LEFT JOIN attendance_records AS r ON r.session_id = s.id
                WHERE s.course = ? AND s.attendance_date = ?
                GROUP BY s.id
                ORDER BY s.id DESC
                LIMIT 1
                """,
                (course, attendance_date),
            ).fetchone()
        return dict(row) if row is not None else None

    def create_session(
        self,
        course: str,
        attendance_date: str | None = None,
    ) -> int:
        selected_date = attendance_date or date.today().isoformat()
        with self._connection() as connection:
            existing = connection.execute(
                """
                SELECT s.id, COUNT(r.id) AS attendance_count
                FROM attendance_sessions AS s
                LEFT JOIN attendance_records AS r ON r.session_id = s.id
                WHERE s.course = ? AND s.attendance_date = ?
                GROUP BY s.id
                ORDER BY s.id DESC
                LIMIT 1
                """,
                (course, selected_date),
            ).fetchone()
            if existing is not None:
                session_id = int(existing["id"])
                connection.execute(
                    """
                    UPDATE attendance_sessions
                    SET ended_at = NULL
                    WHERE id = ?
                    """,
                    (session_id,),
                )
                return session_id

            cursor = connection.execute(
                """
                INSERT INTO attendance_sessions(course, attendance_date, started_at)
                VALUES (?, ?, ?)
                """,
                (course, selected_date, _utc_now()),
            )
            return int(cursor.lastrowid)

    def get_session(self, session_id: int) -> dict[str, object] | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM attendance_sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
        return dict(row) if row is not None else None

    def finish_session(self, session_id: int) -> bool:
        with self._connection() as connection:
            cursor = connection.execute(
                """
                UPDATE attendance_sessions
                SET ended_at = COALESCE(ended_at, ?)
                WHERE id = ?
                """,
                (_utc_now(), session_id),
            )
            return cursor.rowcount == 1

    def record_attendance(
        self,
        session_id: int,
        student_id: str,
        similarity: float,
    ) -> bool:
        with self._connection() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO attendance_records(
                    session_id, student_id, similarity, recorded_at
                ) VALUES (?, ?, ?, ?)
                """,
                (session_id, student_id, similarity, _utc_now()),
            )
            return cursor.rowcount == 1

    def attendance(self, session_id: int) -> list[dict[str, object]]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT r.student_id, s.name, s.sex,
                       r.similarity, r.recorded_at
                FROM attendance_records AS r
                JOIN students AS s ON s.student_id = r.student_id
                WHERE r.session_id = ?
                ORDER BY r.recorded_at, r.student_id
                """,
                (session_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def attendance_report(
        self,
        course: str,
        attendance_date: str,
    ) -> dict[str, object]:
        status = self.attendance_status(course, attendance_date)
        records = [] if status is None else self.attendance(int(status["session_id"]))
        return {
            "course": course,
            "attendance_date": attendance_date,
            "session_id": None if status is None else status["session_id"],
            "attendance": records,
            "count": len(records),
            "statistics": {
                "masculino": sum(item["sex"] == "MASCULINO" for item in records),
                "femenino": sum(item["sex"] == "FEMENINO" for item in records),
                "no_especificado": sum(
                    item["sex"] == "NO_ESPECIFICADO" for item in records
                ),
            },
        }

    def attendance_summaries(self) -> list[dict[str, object]]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT sessions.id AS session_id,
                       sessions.course,
                       sessions.attendance_date,
                       COUNT(records.student_id) AS total,
                       SUM(CASE WHEN students.sex = 'MASCULINO' THEN 1 ELSE 0 END) AS male,
                       SUM(CASE WHEN students.sex = 'FEMENINO' THEN 1 ELSE 0 END) AS female,
                       SUM(CASE WHEN records.student_id IS NOT NULL
                                AND students.sex NOT IN ('MASCULINO', 'FEMENINO')
                                THEN 1 ELSE 0 END) AS unspecified
                FROM attendance_sessions AS sessions
                LEFT JOIN attendance_records AS records
                    ON records.session_id = sessions.id
                LEFT JOIN students ON students.student_id = records.student_id
                GROUP BY sessions.id, sessions.course, sessions.attendance_date
                ORDER BY sessions.attendance_date DESC, sessions.id DESC
                """
            ).fetchall()
        return [dict(row) for row in rows]

