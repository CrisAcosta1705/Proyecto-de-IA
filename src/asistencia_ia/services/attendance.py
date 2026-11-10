import re
from datetime import date
from io import BytesIO
from dataclasses import asdict, dataclass
import uuid
from typing import Sequence

import cv2
import numpy as np
from PIL import Image, ImageOps

from asistencia_ia.recognition import (
    EmbeddingMatcher,
    FaceEmbeddingExtractor,
)
from asistencia_ia.recognition.matcher import cosine_similarity, normalize_embedding
from asistencia_ia.recognition.profile import (
    MINIMUM_REFERENCE_SUPPORT,
    MINIMUM_SCORE_MARGIN,
    SECOND_REFERENCE_WEIGHT,
    SIMILARITY_THRESHOLD,
    SINGLE_REFERENCE_THRESHOLD,
    PROFILE_ID,
)
from asistencia_ia.storage import AttendanceDatabase, FaceReferenceCapture


STUDENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{2,64}$")
ALLOWED_SEX_VALUES = {"MASCULINO", "FEMENINO", "NO_ESPECIFICADO"}


class FaceProcessingError(ValueError):
    pass


def decode_image(content: bytes) -> np.ndarray:
    if not content:
        raise FaceProcessingError("La imagen esta vacia.")
    try:
        with Image.open(BytesIO(content)) as source:
            rgb = ImageOps.exif_transpose(source).convert("RGB")
            image = cv2.cvtColor(np.asarray(rgb), cv2.COLOR_RGB2BGR)
    except Exception as error:
        raise FaceProcessingError(
            "El archivo no contiene una imagen valida."
        ) from error
    if image.size == 0:
        raise FaceProcessingError("El archivo no contiene una imagen valida.")
    return image


@dataclass(frozen=True)
class FaceRecognitionResult:
    face_id: str
    box: dict[str, int]
    detection_confidence: float
    student_id: str | None
    student_name: str | None
    similarity: float
    recognized: bool
    attendance_recorded: bool

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


class AttendanceService:
    def __init__(
        self,
        database: AttendanceDatabase,
        extractor: FaceEmbeddingExtractor,
        similarity_threshold: float = SIMILARITY_THRESHOLD,
        minimum_score_margin: float = MINIMUM_SCORE_MARGIN,
        single_reference_threshold: float | None = None,
        minimum_reference_support: float = MINIMUM_REFERENCE_SUPPORT,
        second_reference_weight: float = SECOND_REFERENCE_WEIGHT,
    ):
        self.database = database
        self.extractor = extractor
        self.similarity_threshold = similarity_threshold
        self.minimum_score_margin = minimum_score_margin
        self.single_reference_threshold = (
            SINGLE_REFERENCE_THRESHOLD
            if single_reference_threshold is None
            else single_reference_threshold
        )
        self.minimum_reference_support = minimum_reference_support
        self.second_reference_weight = second_reference_weight

    def register_student(
        self,
        student_id: str,
        name: str,
        image_contents: Sequence[bytes],
        replace_embeddings: bool = True,
        photo_mime_type: str = "image/jpeg",
        sex: str = "NO_ESPECIFICADO",
        allow_existing_student: bool = False,
        reference_metadata: Sequence[dict[str, object]] | None = None,
    ) -> int:
        student_id = student_id.strip()
        name = name.strip()
        sex = sex.strip().upper()
        if not STUDENT_ID_PATTERN.fullmatch(student_id):
            raise FaceProcessingError(
                "El carnet debe usar entre 2 y 64 letras, numeros, _ o -."
            )
        if not 2 <= len(name) <= 100:
            raise FaceProcessingError("El nombre debe tener entre 2 y 100 caracteres.")
        if sex not in ALLOWED_SEX_VALUES:
            raise FaceProcessingError("El sexo debe ser MASCULINO o FEMENINO.")
        if not 1 <= len(image_contents) <= 20:
            raise FaceProcessingError("Se requieren entre 1 y 20 fotos de registro.")
        conflict = self.database.student_identity_conflict(
            student_id,
            name,
            existing_student_id=student_id if allow_existing_student else None,
        )
        if conflict == "carnet":
            raise FaceProcessingError("Ya existe un estudiante con ese carnet.")
        if conflict == "name":
            raise FaceProcessingError("Ya existe un estudiante con ese nombre.")
        captures: list[FaceReferenceCapture] = []
        rejected_images = 0
        metadata = list(reference_metadata or [])
        for index, content in enumerate(image_contents):
            image = decode_image(content)
            mirrored_image = cv2.flip(image, 1)
            extract_variants = getattr(
                self.extractor,
                "extract_reference_variants",
                None,
            )
            if callable(extract_variants):
                faces, mirrored_faces = extract_variants(image)
            else:
                faces = self.extractor.extract(image)
                # Extractores alternativos antiguos no exponen variantes. La
                # referencia conserva una sola captura logica hasta regenerarse.
                mirrored_faces = faces
            if len(faces) != 1:
                rejected_images += 1
                continue
            if len(mirrored_faces) != 1:
                rejected_images += 1
                continue
            encoded, mirrored_jpeg = cv2.imencode(
                ".jpg", mirrored_image, [cv2.IMWRITE_JPEG_QUALITY, 92]
            )
            if not encoded:
                rejected_images += 1
                continue
            face = faces[0]
            image_area = max(1, image.shape[0] * image.shape[1])
            face_ratio = face.box.area / image_area
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
            brightness = float(np.mean(gray))
            item_metadata = metadata[index] if index < len(metadata) else {}
            size_quality = min(1.0, max(0.0, face_ratio * 8.0))
            sharpness_quality = min(1.0, max(0.0, sharpness / 80.0))
            brightness_quality = max(
                0.0,
                1.0 - abs(brightness - 127.5) / 127.5,
            )
            captures.append(
                FaceReferenceCapture(
                    capture_id=str(item_metadata.get("capture_id") or uuid.uuid4().hex),
                    original_embedding=face.embedding,
                    mirrored_embedding=mirrored_faces[0].embedding,
                    original_photo=content,
                    mirrored_photo=mirrored_jpeg.tobytes(),
                    photo_mime_type=photo_mime_type,
                    source_camera=str(item_metadata.get("source_camera") or "UNKNOWN"),
                    pose=str(item_metadata.get("pose") or "UNSPECIFIED"),
                    quality=(
                        0.50 * size_quality
                        + 0.35 * sharpness_quality
                        + 0.15 * brightness_quality
                    ),
                )
            )

        if not captures:
            raise FaceProcessingError(
                "Ninguna foto contiene exactamente un rostro detectable; "
                f"se rechazaron {rejected_images}."
            )
        self._reject_cross_identity_collision(student_id, captures)
        if not replace_embeddings:
            existing_references = self.database.reference_records(PROFILE_ID).get(
                student_id,
                [],
            )
            existing_capture_count = len(
                {reference.capture_id for reference in existing_references}
            )
            novel_captures = []
            known_embeddings = [
                normalize_embedding(reference.embedding)
                for reference in existing_references
            ]
            for capture in captures:
                embedding = normalize_embedding(capture.original_embedding)
                if any(
                    cosine_similarity(embedding, reference) >= 0.999
                    for reference in known_embeddings
                ):
                    continue
                novel_captures.append(capture)
                known_embeddings.append(embedding)
            captures = novel_captures

            if existing_capture_count + len(captures) > 20:
                raise FaceProcessingError(
                    "Un estudiante no puede tener mas de 20 embeddings registrados."
                )
            if not captures:
                self.database.update_student_profile(student_id, name, sex)
                return 0

        added = self.database.save_student_captures(
            student_id,
            name,
            captures,
            replace_embeddings=replace_embeddings,
            sex=sex,
        )
        self.audit_reference_outliers()
        return added

    def _reject_cross_identity_collision(
        self,
        student_id: str,
        captures: Sequence[FaceReferenceCapture],
    ) -> None:
        references = self.database.reference_records(PROFILE_ID)
        for capture in captures:
            candidate = normalize_embedding(capture.original_embedding)
            for registered_id, items in references.items():
                if registered_id == student_id:
                    continue
                best = max(
                    cosine_similarity(candidate, item.embedding)
                    for item in items
                )
                if best >= 0.68:
                    raise FaceProcessingError(
                        "La referencia coincide con otro estudiante registrado; "
                        "revise la identidad antes de guardarla."
                    )

    def audit_reference_outliers(self) -> list[dict[str, object]]:
        references = self.database.reference_records(PROFILE_ID)
        captures_by_student: dict[str, dict[str, list[np.ndarray]]] = {}
        for student_id, items in references.items():
            grouped: dict[str, list[np.ndarray]] = {}
            for item in items:
                grouped.setdefault(item.capture_id, []).append(
                    normalize_embedding(item.embedding)
                )
            captures_by_student[student_id] = grouped

        quarantined: list[dict[str, object]] = []
        for student_id, captures in captures_by_student.items():
            if len(captures) < 3:
                continue
            remaining = len(captures)
            candidates: list[tuple[float, str, float, float]] = []
            other_students = [
                variants
                for other_id, other_captures in captures_by_student.items()
                if other_id != student_id
                for variants in other_captures.values()
            ]
            for capture_id, variants in captures.items():
                peers = [
                    peer_variants
                    for peer_id, peer_variants in captures.items()
                    if peer_id != capture_id
                ]
                best_same = max(
                    cosine_similarity(variant, peer)
                    for variant in variants
                    for peer_variants in peers
                    for peer in peer_variants
                )
                best_other = max(
                    (
                        cosine_similarity(variant, peer)
                        for variant in variants
                        for peer_variants in other_students
                        for peer in peer_variants
                    ),
                    default=-1.0,
                )
                suspicious = best_same < 0.15 or (
                    best_other >= 0.55 and best_other - best_same >= 0.12
                )
                if suspicious:
                    candidates.append((best_same, capture_id, best_same, best_other))
            for _, capture_id, best_same, best_other in sorted(candidates):
                if remaining <= 2:
                    break
                reason = (
                    f"atipica: misma_identidad={best_same:.3f}; "
                    f"otra_identidad={best_other:.3f}"
                )
                if self.database.quarantine_capture(capture_id, reason):
                    remaining -= 1
                    quarantined.append(
                        {
                            "student_id": student_id,
                            "capture_id": capture_id,
                            "same_identity_similarity": round(best_same, 4),
                            "other_identity_similarity": round(best_other, 4),
                            "reason": reason,
                        }
                    )
        return quarantined

    def create_session(
        self,
        course: str,
        attendance_date: str | None = None,
    ) -> int:
        course = course.strip()
        if not 2 <= len(course) <= 100:
            raise ValueError("La clase debe tener entre 2 y 100 caracteres.")
        selected_date = attendance_date or date.today().isoformat()
        try:
            date.fromisoformat(selected_date)
        except ValueError as error:
            raise ValueError("La fecha debe tener el formato AAAA-MM-DD.") from error
        return self.database.create_session(course, selected_date)

    def attendance_status(
        self,
        course: str,
        attendance_date: str,
    ) -> dict[str, object]:
        status = self.database.attendance_status(course.strip(), attendance_date)
        return status or {
            "session_id": None,
            "course": course.strip(),
            "attendance_date": attendance_date,
            "attendance_count": 0,
        }

    def attendance_report(
        self,
        course: str,
        attendance_date: str,
    ) -> dict[str, object]:
        return self.database.attendance_report(course.strip(), attendance_date)

    def recognize(self, session_id: int, image_content: bytes) -> dict[str, object]:
        session = self.database.get_session(session_id)
        if session is None:
            raise LookupError("La sesion de asistencia no existe.")
        if session["ended_at"] is not None:
            raise ValueError("La sesion de asistencia ya esta cerrada.")

        result = self.analyze(image_content)
        for face in result["faces"]:
            student_id = face["student_id"]
            if not face["recognized"] or student_id is None:
                continue
            face["attendance_recorded"] = self.database.record_attendance(
                session_id,
                student_id,
                float(face["similarity"]),
            )
        attendance = self.database.attendance(session_id)
        result["session_id"] = session_id
        result["attendance_count"] = len(attendance)
        return result

    def analyze(self, image_content: bytes) -> dict[str, object]:
        """Reconoce una imagen sin crear ni modificar asistencias."""

        image = decode_image(image_content)
        image_height, image_width = image.shape[:2]
        faces = self.extractor.extract(image)
        references = self.database.reference_records(PROFILE_ID)
        names = self.database.student_names()
        matcher = EmbeddingMatcher(
            references,
            self.similarity_threshold,
            self.minimum_score_margin,
            self.single_reference_threshold,
            self.minimum_reference_support,
            self.second_reference_weight,
        )
        matches = matcher.match_batch([face.embedding for face in faces])
        results: list[FaceRecognitionResult] = []

        for face, match in zip(faces, matches, strict=True):
            student_id = match.student_id
            results.append(
                FaceRecognitionResult(
                    face_id=(
                        f"face-{face.box.x1}-{face.box.y1}-"
                        f"{face.box.x2}-{face.box.y2}"
                    ),
                    box={
                        "x1": face.box.x1,
                        "y1": face.box.y1,
                        "x2": face.box.x2,
                        "y2": face.box.y2,
                    },
                    detection_confidence=face.detection_confidence,
                    student_id=student_id,
                    student_name=names.get(student_id) if student_id else None,
                    similarity=match.similarity,
                    recognized=match.accepted,
                    attendance_recorded=False,
                )
            )

        return {
            "session_id": None,
            "image_width": image_width,
            "image_height": image_height,
            "faces_detected": len(faces),
            "recognized_count": sum(item.recognized for item in results),
            "attendance_count": 0,
            "faces": [item.to_dict() for item in results],
        }

