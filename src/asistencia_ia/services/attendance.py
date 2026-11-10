import re
from datetime import date
from io import BytesIO
from dataclasses import asdict, dataclass
from typing import Sequence

import cv2
import numpy as np
from PIL import Image, ImageOps

from asistencia_ia.recognition import (
    EmbeddingMatcher,
    FaceEmbeddingExtractor,
)
from asistencia_ia.recognition.matcher import cosine_similarity
from asistencia_ia.recognition.profile import (
    MINIMUM_REFERENCE_SUPPORT,
    MINIMUM_SCORE_MARGIN,
    SECOND_REFERENCE_WEIGHT,
    SIMILARITY_THRESHOLD,
    SINGLE_REFERENCE_THRESHOLD,
)
from asistencia_ia.storage import AttendanceDatabase


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
        embeddings = []
        valid_images = []
        rejected_images = 0
        for content in image_contents:
            faces = self.extractor.extract(decode_image(content))
            if len(faces) != 1:
                rejected_images += 1
                continue
            embeddings.append(faces[0].embedding)
            valid_images.append(content)

        if not embeddings:
            raise FaceProcessingError(
                "Ninguna foto contiene exactamente un rostro detectable; "
                f"se rechazaron {rejected_images}."
            )
        if not replace_embeddings:
            existing_references = self.database.references().get(student_id, [])
            novel_embeddings = []
            known_embeddings = list(existing_references)
            for embedding in embeddings:
                if any(
                    cosine_similarity(embedding, reference) >= 0.999
                    for reference in known_embeddings
                ):
                    continue
                novel_embeddings.append(embedding)
                known_embeddings.append(embedding)
            embeddings = novel_embeddings

            if len(existing_references) + len(embeddings) > 20:
                raise FaceProcessingError(
                    "Un estudiante no puede tener mas de 20 embeddings registrados."
                )
            if not embeddings:
                self.database.update_student_profile(student_id, name, sex)
                return 0

        return self.database.save_student(
            student_id,
            name,
            embeddings,
            replace_embeddings=replace_embeddings,
            photo=valid_images[0],
            photo_mime_type=photo_mime_type,
            sex=sex,
        )

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

        image = decode_image(image_content)
        image_height, image_width = image.shape[:2]
        faces = self.extractor.extract(image)
        references = self.database.references()
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
        recognized_in_frame: set[str] = set()
        results: list[FaceRecognitionResult] = []

        for face, match in zip(faces, matches, strict=True):
            recorded = False
            student_id = match.student_id
            if match.accepted and student_id is not None:
                recorded = self.database.record_attendance(
                    session_id,
                    student_id,
                    match.similarity,
                )
                recognized_in_frame.add(student_id)

            results.append(
                FaceRecognitionResult(
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
                    attendance_recorded=recorded,
                )
            )

        attendance = self.database.attendance(session_id)
        return {
            "session_id": session_id,
            "image_width": image_width,
            "image_height": image_height,
            "faces_detected": len(faces),
            "recognized_count": len(recognized_in_frame),
            "attendance_count": len(attendance),
            "faces": [item.to_dict() for item in results],
        }

