import sqlite3
from contextlib import contextmanager
from datetime import date, datetime, timezone
import hashlib
from pathlib import Path
from typing import Iterator
import unicodedata

import numpy as np
from numpy.typing import ArrayLike, NDArray

from asistencia_ia.recognition.matcher import normalize_embedding


Embedding = NDArray[np.float32]


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

    def __init__(self, path: Path | str):
        self.path = Path(path)

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def initialize(self) -> None:
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
                    FOREIGN KEY (student_id) REFERENCES students(student_id)
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
                    photo,
                    photo_mime_type,
                    photo_sha256,
                    now,
                    now,
                ),
            )
            if replace_embeddings:
                connection.execute(
                    "DELETE FROM face_embeddings WHERE student_id = ?",
                    (student_id,),
                )
            connection.executemany(
                """
                INSERT INTO face_embeddings(
                    student_id, embedding, dimension, created_at
                ) VALUES (?, ?, ?, ?)
                """,
                [
                    (
                        student_id,
                        vector.astype("<f4", copy=False).tobytes(),
                        vector.size,
                        now,
                    )
                    for vector in normalized
                ],
            )
        return len(normalized)

    def references(self) -> dict[str, list[Embedding]]:
        references: dict[str, list[Embedding]] = {}
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT student_id, embedding, dimension
                FROM face_embeddings
                ORDER BY student_id, id
                """
            ).fetchall()

        for row in rows:
            vector = np.frombuffer(row["embedding"], dtype="<f4").copy()
            if vector.size != row["dimension"]:
                raise ValueError(
                    f"Embedding corrupto para el estudiante {row['student_id']}."
                )
            references.setdefault(row["student_id"], []).append(vector)
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
                       COUNT(e.id) AS embedding_count
                FROM students AS s
                LEFT JOIN face_embeddings AS e ON e.student_id = s.student_id
                GROUP BY s.student_id
                ORDER BY s.name COLLATE NOCASE, s.student_id
                """
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
        return bytes(row["photo"]), str(row["photo_mime_type"] or "image/jpeg")

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
                SELECT COUNT(*) AS count
                FROM face_embeddings
                WHERE student_id = ?
                """,
                (student_id,),
            ).fetchone()
        return int(row["count"])

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

