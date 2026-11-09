import base64
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest

import numpy as np

from asistencia_ia.security import DataCipher, DataEncryptionError
from asistencia_ia.storage import AttendanceDatabase


def _encoded_key() -> str:
    return base64.b64encode(os.urandom(32)).decode("ascii")


class DataCipherTest(unittest.TestCase):
    def test_encrypts_and_authenticates_sensitive_data(self) -> None:
        cipher = DataCipher.from_base64(_encoded_key())
        plaintext = b"fotografia-biometrica"
        envelope = cipher.encrypt(plaintext, b"photo:student-1")

        self.assertNotEqual(envelope, plaintext)
        self.assertTrue(cipher.is_encrypted(envelope))
        self.assertEqual(
            cipher.decrypt(envelope, b"photo:student-1"),
            plaintext,
        )

        tampered = bytearray(envelope)
        tampered[-1] ^= 0x01
        with self.assertRaises(DataEncryptionError):
            cipher.decrypt(bytes(tampered), b"photo:student-1")

    def test_rejects_the_wrong_identity_context(self) -> None:
        cipher = DataCipher.from_base64(_encoded_key())
        envelope = cipher.encrypt(b"embedding", b"embedding:student-1")

        with self.assertRaises(DataEncryptionError):
            cipher.decrypt(envelope, b"embedding:student-2")


class EncryptedDatabaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = Path(self.temporary_directory.name) / "attendance.db"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _save_plaintext_student(self) -> tuple[bytes, np.ndarray]:
        photo = b"\xff\xd8\xffprivate-jpeg-content\xff\xd9"
        embedding = np.asarray([1.0, 0.0, 0.0], dtype=np.float32)
        database = AttendanceDatabase(self.database_path)
        database.initialize()
        database.save_student(
            "student-1",
            "Student One",
            [embedding],
            photo=photo,
            photo_mime_type="image/jpeg",
        )
        return photo, embedding

    def test_migrates_existing_photos_and_embeddings_without_data_loss(self) -> None:
        photo, embedding = self._save_plaintext_student()
        cipher = DataCipher.from_base64(_encoded_key())
        encrypted_database = AttendanceDatabase(self.database_path, cipher)

        encrypted_database.initialize()

        self.assertEqual(encrypted_database.student_photo("student-1")[0], photo)
        np.testing.assert_allclose(
            encrypted_database.references()["student-1"][0],
            embedding,
        )
        with closing(sqlite3.connect(self.database_path)) as connection:
            stored_photo = connection.execute(
                "SELECT photo FROM students WHERE student_id = 'student-1'"
            ).fetchone()[0]
            stored_embedding = connection.execute(
                "SELECT embedding FROM face_embeddings WHERE student_id = 'student-1'"
            ).fetchone()[0]
        self.assertTrue(cipher.is_encrypted(stored_photo))
        self.assertTrue(cipher.is_encrypted(stored_embedding))
        self.assertNotIn(photo, stored_photo)
        self.assertNotIn(embedding.tobytes(), stored_embedding)

    def test_rotates_encryption_key_using_a_previous_key(self) -> None:
        self._save_plaintext_student()
        previous_key = _encoded_key()
        old_database = AttendanceDatabase(
            self.database_path,
            DataCipher.from_base64(previous_key),
        )
        old_database.initialize()

        current_key = _encoded_key()
        rotating_database = AttendanceDatabase(
            self.database_path,
            DataCipher.from_base64(current_key, [previous_key]),
        )
        rotating_database.initialize()

        current_database = AttendanceDatabase(
            self.database_path,
            DataCipher.from_base64(current_key),
        )
        current_database.initialize()
        self.assertIsNotNone(current_database.student_photo("student-1"))

    def test_refuses_to_open_encrypted_biometrics_with_wrong_key(self) -> None:
        self._save_plaintext_student()
        encrypted_database = AttendanceDatabase(
            self.database_path,
            DataCipher.from_base64(_encoded_key()),
        )
        encrypted_database.initialize()

        wrong_database = AttendanceDatabase(
            self.database_path,
            DataCipher.from_base64(_encoded_key()),
        )
        with self.assertRaises(DataEncryptionError):
            wrong_database.initialize()


if __name__ == "__main__":
    unittest.main()
