import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

from asistencia_ia.api import create_app
from asistencia_ia.domain import BoundingBox
from asistencia_ia.recognition.extractor import ExtractedFace
from asistencia_ia.services.attendance import AttendanceService, FaceProcessingError
from asistencia_ia.storage import AttendanceDatabase, FaceReferenceCapture


def _image_bytes() -> bytes:
    ok, encoded = cv2.imencode(".jpg", np.zeros((32, 32, 3), dtype=np.uint8))
    if not ok:
        raise RuntimeError("No se pudo crear la imagen de prueba.")
    return encoded.tobytes()


class FakeExtractor:
    def __init__(self):
        self.faces = []

    def extract(self, image):
        return list(self.faces)


def _face(embedding) -> ExtractedFace:
    return ExtractedFace(
        box=BoundingBox(1, 1, 20, 20),
        detection_confidence=0.99,
        embedding=np.asarray(embedding, dtype=np.float32),
    )


class AttendanceServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = AttendanceDatabase(Path(self.directory.name) / "test.db")
        self.database.initialize()
        self.extractor = FakeExtractor()
        self.service = AttendanceService(
            self.database,
            self.extractor,
            similarity_threshold=0.8,
        )

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_register_and_mark_attendance_only_once(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        added = self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        session_id = self.service.create_session("Inteligencia artificial")

        first = self.service.recognize(session_id, _image_bytes())
        second = self.service.recognize(session_id, _image_bytes())

        self.assertEqual(added, 1)
        stored_photo = self.database.student_photo("estudiante_001")
        self.assertIsNotNone(stored_photo)
        self.assertEqual(stored_photo[0], _image_bytes())
        self.assertEqual(first["attendance_count"], 1)
        self.assertEqual(first["image_width"], 32)
        self.assertEqual(first["image_height"], 32)
        self.assertTrue(first["faces"][0]["attendance_recorded"])
        self.assertFalse(second["faces"][0]["attendance_recorded"])
        self.assertEqual(len(self.database.attendance(session_id)), 1)

    def test_unknown_face_is_not_registered_as_attendance(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        self.extractor.faces = [_face([0.0, 1.0])]
        session_id = self.service.create_session("Programacion")

        result = self.service.recognize(session_id, _image_bytes())

        self.assertEqual(result["recognized_count"], 0)
        self.assertEqual(result["attendance_count"], 0)
        self.assertFalse(result["faces"][0]["recognized"])

    def test_evaluation_analysis_never_writes_attendance(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        session_id = self.service.create_session("Programacion")

        result = self.service.analyze(_image_bytes())

        self.assertEqual(result["recognized_count"], 1)
        self.assertEqual(result["attendance_count"], 0)
        self.assertFalse(result["faces"][0]["attendance_recorded"])
        self.assertEqual(self.database.attendance(session_id), [])

    def test_reference_variants_share_one_logical_capture(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]

        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )

        captures = self.database.list_reference_captures("estudiante_001")
        self.assertEqual(self.database.embedding_count("estudiante_001"), 1)
        self.assertEqual(len(captures), 1)
        self.assertEqual(captures[0]["variant_count"], 2)
        capture_id = str(captures[0]["capture_id"])
        self.assertIsNotNone(
            self.database.reference_photo(
                "estudiante_001", capture_id, "ORIGINAL"
            )
        )
        self.assertIsNotNone(
            self.database.reference_photo(
                "estudiante_001", capture_id, "MIRRORED"
            )
        )

    def test_reference_quarantine_is_reversible(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        capture_id = str(
            self.database.list_reference_captures("estudiante_001")[0]["capture_id"]
        )

        self.assertTrue(self.database.quarantine_capture(capture_id, "prueba"))
        self.assertEqual(self.database.embedding_count("estudiante_001"), 0)
        self.assertTrue(self.database.restore_capture(capture_id))
        self.assertEqual(self.database.embedding_count("estudiante_001"), 1)

    def test_registration_rejects_a_face_owned_by_another_student(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )

        with self.assertRaisesRegex(FaceProcessingError, "otro estudiante"):
            self.service.register_student(
                "estudiante_002",
                "Estudiante Dos",
                [_image_bytes()],
            )

    def test_reference_audit_quarantines_an_outlier_without_deleting_it(self) -> None:
        image = _image_bytes()
        captures = [
            FaceReferenceCapture(
                original_embedding=embedding,
                mirrored_embedding=embedding,
                original_photo=image,
                mirrored_photo=image,
                capture_id=capture_id,
            )
            for capture_id, embedding in (
                ("consistent-1", [1.0, 0.0, 0.0]),
                ("consistent-2", [0.99, 0.1, 0.0]),
                ("outlier", [0.0, 0.0, 1.0]),
            )
        ]
        self.database.save_student_captures(
            "estudiante_001",
            "Estudiante Uno",
            captures,
        )

        quarantined = self.service.audit_reference_outliers()

        self.assertEqual([item["capture_id"] for item in quarantined], ["outlier"])
        stored = self.database.list_reference_captures("estudiante_001")
        outlier = next(item for item in stored if item["capture_id"] == "outlier")
        self.assertEqual(outlier["active"], 0)
        self.assertEqual(self.database.embedding_count("estudiante_001"), 2)

    def test_group_photo_recognizes_five_unique_students(self) -> None:
        embeddings = np.eye(5, dtype=np.float32)
        for index, embedding in enumerate(embeddings):
            self.extractor.faces = [_face(embedding)]
            self.service.register_student(
                f"estudiante_{index}",
                f"Estudiante {index}",
                [_image_bytes()],
            )
        self.extractor.faces = [_face(embedding) for embedding in embeddings]
        session_id = self.service.create_session("Inteligencia artificial")

        result = self.service.recognize(session_id, _image_bytes())

        recognized_ids = {
            face["student_id"]
            for face in result["faces"]
            if face["recognized"]
        }
        self.assertEqual(result["faces_detected"], 5)
        self.assertEqual(result["recognized_count"], 5)
        self.assertEqual(result["attendance_count"], 5)
        self.assertEqual(
            recognized_ids,
            {f"estudiante_{index}" for index in range(5)},
        )

    def test_group_photo_does_not_repeat_an_identity(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        self.extractor.faces = [
            _face([1.0, 0.0]),
            _face([0.99, 0.01]),
        ]
        session_id = self.service.create_session("Programacion")

        result = self.service.recognize(session_id, _image_bytes())

        self.assertEqual(result["recognized_count"], 1)
        self.assertEqual(result["attendance_count"], 1)
        self.assertEqual(
            sum(face["recognized"] for face in result["faces"]),
            1,
        )

    def test_registration_requires_exactly_one_face_per_photo(self) -> None:
        self.extractor.faces = []

        with self.assertRaises(FaceProcessingError):
            self.service.register_student(
                "estudiante_001",
                "Estudiante Uno",
                [_image_bytes()],
            )

    def test_registration_keeps_valid_references_when_another_photo_fails(self) -> None:
        valid_face = _face([1.0, 0.0])
        responses = iter([[valid_face], []])
        self.extractor.extract = lambda image: next(responses)

        added = self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes(), _image_bytes()],
        )

        self.assertEqual(added, 1)
        self.assertEqual(self.database.embedding_count("estudiante_001"), 1)

    def test_appending_same_reference_is_idempotent(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )

        added = self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
            replace_embeddings=False,
            allow_existing_student=True,
        )

        self.assertEqual(added, 0)
        self.assertEqual(self.database.embedding_count("estudiante_001"), 1)

    def test_registration_rejects_duplicate_carnet(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "2023-001U",
            "Estudiante Uno",
            [_image_bytes()],
        )

        with self.assertRaisesRegex(FaceProcessingError, "carnet"):
            self.service.register_student(
                "2023-001u",
                "Estudiante Diferente",
                [_image_bytes()],
            )

    def test_registration_rejects_duplicate_normalized_name(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "2023-001U",
            "Jose Perez",
            [_image_bytes()],
        )

        with self.assertRaisesRegex(FaceProcessingError, "nombre"):
            self.service.register_student(
                "2023-002U",
                "  Jose   Perez  ",
                [_image_bytes()],
            )

    def test_reference_update_allows_the_same_student(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "2023-001U",
            "Estudiante Uno",
            [_image_bytes()],
        )
        self.extractor.faces = [_face([0.9, 0.1])]

        added = self.service.register_student(
            "2023-001U",
            "Estudiante Uno",
            [_image_bytes()],
            allow_existing_student=True,
        )

        self.assertEqual(added, 1)
        self.assertEqual(self.database.embedding_count("2023-001U"), 1)

    def test_clear_recognition_preserves_student_and_removes_biometrics(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
        )
        session_id = self.service.create_session("Programacion")
        self.service.recognize(session_id, _image_bytes())

        name = self.database.clear_student_recognition("estudiante_001")

        self.assertEqual(name, "Estudiante Uno")
        self.assertEqual(self.database.embedding_count("estudiante_001"), 0)
        self.assertIsNone(self.database.student_photo("estudiante_001"))
        self.assertEqual(self.database.student_names()["estudiante_001"], name)
        students = self.database.list_students()
        self.assertEqual(len(students), 1)
        self.assertEqual(students[0]["student_id"], "estudiante_001")
        self.assertEqual(students[0]["embedding_count"], 0)
        self.assertEqual(students[0]["has_photo"], 0)
        self.assertEqual(len(self.database.attendance(session_id)), 1)

    def test_api_permanently_deletes_student_and_attendance_records(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
            sex="MASCULINO",
        )
        session_id = self.service.create_session("Programacion")
        self.service.recognize(session_id, _image_bytes())
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )

        with TestClient(app) as client:
            deleted = client.delete("/students/estudiante_001")
            students = client.get("/students")
            photo = client.get("/students/estudiante_001/photo")

        self.assertEqual(deleted.status_code, 200)
        self.assertTrue(deleted.json()["deleted"])
        self.assertEqual(students.json(), [])
        self.assertEqual(photo.status_code, 404)
        self.assertEqual(self.database.embedding_count("estudiante_001"), 0)
        self.assertEqual(self.database.attendance(session_id), [])

    def test_api_exposes_photo_for_mobile_migration(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        image = _image_bytes()
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [image],
        )
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )

        with TestClient(app) as client:
            students = client.get("/students")
            photo = client.get("/students/estudiante_001/photo")

        self.assertEqual(students.status_code, 200)
        self.assertEqual(len(students.json()), 1)
        self.assertTrue(students.json()[0]["has_photo"])
        self.assertEqual(len(students.json()[0]["photo_sha256"]), 64)
        self.assertEqual(photo.status_code, 200)
        self.assertEqual(photo.headers["content-type"], "image/jpeg")
        self.assertIn("no-store", photo.headers["cache-control"])
        self.assertEqual(photo.content, image)

    def test_api_key_protects_biometric_endpoints(self) -> None:
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
            api_key="clave-de-prueba",
        )

        with TestClient(app) as client:
            health = client.get("/health")
            unauthorized = client.get("/students")
            authorized = client.get(
                "/students",
                headers={"X-API-Key": "clave-de-prueba"},
            )

        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.headers["cache-control"], "no-store, max-age=0")
        self.assertEqual(health.headers["x-content-type-options"], "nosniff")
        profile = health.json()["recognition_profile"]
        self.assertEqual(profile["profile_id"], "arcface_w600k_r50_112x112_v1")
        self.assertEqual(profile["input_size"], 112)
        self.assertEqual(profile["embedding_size"], 512)
        self.assertEqual(profile["similarity_threshold"], 0.8)
        self.assertEqual(profile["single_reference_threshold"], 0.8)
        offline_profile = health.json()["offline_recognition_profile"]
        self.assertEqual(
            offline_profile["profile_id"],
            "arcface_w600k_mbf_112x112_v3",
        )
        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(unauthorized.headers["x-frame-options"], "DENY")
        self.assertEqual(authorized.status_code, 200)

    def test_railway_requires_api_key(self) -> None:
        with patch.dict(
            "os.environ",
            {"RAILWAY_PROJECT_ID": "project-test", "ASISTENCIA_API_KEY": ""},
        ):
            with self.assertRaisesRegex(RuntimeError, "ASISTENCIA_API_KEY"):
                create_app(
                    database_path=self.database.path,
                    extractor=self.extractor,
                    similarity_threshold=0.8,
                )

    def test_railway_requires_data_encryption_key(self) -> None:
        with patch.dict(
            "os.environ",
            {
                "RAILWAY_PROJECT_ID": "project-test",
                "ASISTENCIA_API_KEY": "a" * 32,
                "ASISTENCIA_DATA_ENCRYPTION_KEY": "",
            },
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "ASISTENCIA_DATA_ENCRYPTION_KEY",
            ):
                create_app(
                    database_path=self.database.path,
                    extractor=self.extractor,
                    similarity_threshold=0.8,
                )

    def test_api_edits_student_profile_without_changing_biometrics(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Nombre Anterior",
            [_image_bytes()],
            sex="MASCULINO",
        )
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )

        with TestClient(app) as client:
            updated = client.patch(
                "/students/estudiante_001",
                json={"name": "Nombre Actualizado", "sex": "FEMENINO"},
            )
            students = client.get("/students")

        self.assertEqual(updated.status_code, 200)
        self.assertEqual(updated.json()["name"], "Nombre Actualizado")
        self.assertEqual(updated.json()["sex"], "FEMENINO")
        self.assertEqual(students.json()[0]["name"], "Nombre Actualizado")
        self.assertEqual(students.json()[0]["sex"], "FEMENINO")
        self.assertEqual(students.json()[0]["embedding_count"], 1)
        self.assertEqual(self.database.embedding_count("estudiante_001"), 1)
        self.assertIsNotNone(self.database.student_photo("estudiante_001"))

    def test_api_registration_rejects_duplicates_and_allows_reference_update(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "2023-001U",
            "Estudiante Uno",
            [_image_bytes()],
            sex="MASCULINO",
        )
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )
        files = [("images", ("rostro.jpg", _image_bytes(), "image/jpeg"))]

        with TestClient(app) as client:
            duplicate = client.post(
                "/students/register",
                data={
                    "student_id": "2023-001U",
                    "name": "Estudiante Uno",
                    "sex": "MASCULINO",
                },
                files=files,
            )
            updated = client.post(
                "/students/register",
                data={
                    "student_id": "2023-001U",
                    "name": "Estudiante Uno",
                    "sex": "MASCULINO",
                    "allow_existing_student": "true",
                },
                files=files,
            )

        self.assertEqual(duplicate.status_code, 422)
        self.assertIn("carnet", duplicate.json()["detail"])
        self.assertEqual(updated.status_code, 200)

    def test_api_profile_edit_rejects_another_students_name(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "2023-001U",
            "Estudiante Uno",
            [_image_bytes()],
            sex="MASCULINO",
        )
        self.extractor.faces = [_face([0.0, 1.0])]
        self.service.register_student(
            "2023-002U",
            "Estudiante Dos",
            [_image_bytes()],
            sex="FEMENINO",
        )
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )

        with TestClient(app) as client:
            response = client.patch(
                "/students/2023-002U",
                json={"name": "Estudiante Uno", "sex": "FEMENINO"},
            )

        self.assertEqual(response.status_code, 422)
        self.assertIn("nombre", response.json()["detail"])

    def test_attendance_by_date_accumulates_photos_and_groups_by_sex(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_m",
            "Estudiante Masculino",
            [_image_bytes()],
            sex="MASCULINO",
        )
        self.extractor.faces = [_face([0.0, 1.0])]
        self.service.register_student(
            "estudiante_f",
            "Estudiante Femenina",
            [_image_bytes()],
            sex="FEMENINO",
        )
        session_id = self.service.create_session(
            "Inteligencia artificial",
            "2026-11-01",
        )
        self.extractor.faces = [_face([1.0, 0.0])]

        first = self.service.recognize(session_id, _image_bytes())
        continued_id = self.service.create_session(
            "Inteligencia artificial",
            "2026-11-01",
        )
        self.extractor.faces = [_face([1.0, 0.0]), _face([0.0, 1.0])]
        second = self.service.recognize(continued_id, _image_bytes())
        report = self.service.attendance_report(
            "Inteligencia artificial",
            "2026-11-01",
        )
        summaries = self.database.attendance_summaries()

        self.assertEqual(continued_id, session_id)
        self.assertEqual(first["attendance_count"], 1)
        self.assertEqual(second["attendance_count"], 2)
        self.assertFalse(second["faces"][0]["attendance_recorded"])
        self.assertTrue(second["faces"][1]["attendance_recorded"])
        self.assertEqual(report["statistics"]["masculino"], 1)
        self.assertEqual(report["statistics"]["femenino"], 1)
        self.assertEqual(report["statistics"]["no_especificado"], 0)
        self.assertEqual(summaries[0]["total"], 2)
        self.assertEqual(summaries[0]["male"], 1)
        self.assertEqual(summaries[0]["female"], 1)
    def test_api_reuses_session_and_preserves_attendance_statistics(self) -> None:
        self.extractor.faces = [_face([1.0, 0.0])]
        self.service.register_student(
            "estudiante_001",
            "Estudiante Uno",
            [_image_bytes()],
            sex="MASCULINO",
        )
        app = create_app(
            database_path=self.database.path,
            extractor=self.extractor,
            similarity_threshold=0.8,
        )
        payload = {
            "course": "Programacion",
            "attendance_date": "2026-11-01",
        }

        with TestClient(app) as client:
            created = client.post("/sessions", json=payload)
            session_id = created.json()["session_id"]
            recognized = client.post(
                f"/sessions/{session_id}/recognize",
                files={"image": ("aula.jpg", _image_bytes(), "image/jpeg")},
            )
            continued = client.post("/sessions", json=payload)
            recognized_again = client.post(
                f"/sessions/{session_id}/recognize",
                files={"image": ("aula-2.jpg", _image_bytes(), "image/jpeg")},
            )
            report = client.get(
                "/attendance/report",
                params={
                    "course": "Programacion",
                    "attendance_date": "2026-11-01",
                },
            )
            summaries = client.get("/attendance/summaries")

        self.assertEqual(created.status_code, 200)
        self.assertEqual(recognized.status_code, 200)
        self.assertEqual(continued.status_code, 200)
        self.assertEqual(continued.json()["session_id"], session_id)
        self.assertEqual(recognized_again.status_code, 200)
        self.assertEqual(recognized_again.json()["attendance_count"], 1)
        self.assertFalse(recognized_again.json()["faces"][0]["attendance_recorded"])
        self.assertEqual(report.status_code, 200)
        self.assertEqual(report.json()["count"], 1)
        self.assertEqual(report.json()["statistics"]["masculino"], 1)
        self.assertEqual(summaries.status_code, 200)
        self.assertEqual(summaries.json()[0]["total"], 1)
        self.assertEqual(summaries.json()[0]["male"], 1)


if __name__ == "__main__":
    unittest.main()
