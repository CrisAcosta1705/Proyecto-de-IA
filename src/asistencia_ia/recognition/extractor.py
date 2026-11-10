from dataclasses import dataclass
import hashlib
from pathlib import Path
from threading import Lock
from typing import Any, Protocol

import cv2
import numpy as np
from numpy.typing import NDArray

from asistencia_ia.domain import BoundingBox
from asistencia_ia.recognition.matcher import normalize_embedding
from asistencia_ia.recognition.profile import MODEL_SHA256


@dataclass(frozen=True)
class ExtractedFace:
    box: BoundingBox
    detection_confidence: float
    embedding: NDArray[np.float32]


class FaceEmbeddingExtractor(Protocol):
    def extract(self, image: NDArray[np.uint8]) -> list[ExtractedFace]:
        """Detecta, alinea y genera embeddings para todos los rostros."""


class InsightFaceExtractor:
    """Extractor ArcFace local con carga diferida del modelo InsightFace."""

    def __init__(
        self,
        model_name: str = "buffalo_l",
        model_root: Path | str = Path("models/insightface"),
        detection_threshold: float = 0.45,
        detection_size: tuple[int, int] = (640, 640),
        recognition_model_path: Path | str | None = None,
        expected_model_sha256: str = MODEL_SHA256,
        onnx_threads: int = 2,
    ):
        if not 1 <= onnx_threads <= 8:
            raise ValueError("onnx_threads debe estar entre 1 y 8.")
        self.model_name = model_name
        self.model_root = Path(model_root)
        self.detection_threshold = detection_threshold
        self.detection_size = detection_size
        self.recognition_model_path = (
            Path(recognition_model_path) if recognition_model_path else None
        )
        self.expected_model_sha256 = expected_model_sha256.lower()
        self.onnx_threads = onnx_threads
        self._app = None
        self._recognition_session: Any | None = None
        self._recognition_input_name: str | None = None
        self._lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._app is not None

    def _load(self):
        if self._app is None:
            from insightface.app import FaceAnalysis

            self.model_root.mkdir(parents=True, exist_ok=True)
            allowed_modules = ["detection"]
            if self.recognition_model_path is None:
                allowed_modules.append("recognition")
            app = FaceAnalysis(
                name=self.model_name,
                root=str(self.model_root),
                allowed_modules=allowed_modules,
                providers=["CPUExecutionProvider"],
                sess_options=self._new_session_options(),
            )
            app.prepare(
                ctx_id=-1,
                det_thresh=self.detection_threshold,
                det_size=self.detection_size,
            )
            self._app = app
            if self.recognition_model_path is not None:
                self._load_recognition_model()
        return self._app

    def _load_recognition_model(self) -> None:
        model_path = self.recognition_model_path
        if model_path is None or self._recognition_session is not None:
            return
        if not model_path.is_file():
            raise FileNotFoundError(
                f"No se encontro el modelo facial unificado en {model_path}."
            )
        digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
        if digest != self.expected_model_sha256:
            raise RuntimeError(
                "El modelo facial no coincide con el perfil de reconocimiento."
            )
        import onnxruntime as ort

        session = ort.InferenceSession(
            str(model_path),
            sess_options=self._new_session_options(),
            providers=["CPUExecutionProvider"],
        )
        self._recognition_session = session
        self._recognition_input_name = session.get_inputs()[0].name

    def _new_session_options(self):
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = self.onnx_threads
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        return options

    def extract(self, image: NDArray[np.uint8]) -> list[ExtractedFace]:
        if image.ndim != 3 or image.shape[2] != 3:
            raise ValueError("La imagen debe tener tres canales BGR.")

        height, width = image.shape[:2]
        with self._lock:
            app = self._load()
            primary_faces = self._convert_faces(
                app.get(image),
                image,
                width,
                height,
            )
            supplemental_faces: list[ExtractedFace] = []
            if self._needs_supplemental_tiles(
                primary_faces,
                width,
                height,
            ):
                tile_width = min(width, max(height, int(width * 0.58)))
                step = max(1, int(tile_width * 0.68))
                offsets = list(range(0, max(1, width - tile_width + 1), step))
                offsets.append(width - tile_width)
                for x_offset in sorted(set(offsets)):
                    tile = image[:, x_offset : x_offset + tile_width]
                    supplemental_faces.extend(
                        self._convert_faces(
                            app.get(tile),
                            tile,
                            width,
                            height,
                            x_offset=x_offset,
                        )
                    )

        return self._merge_supplemental_faces(primary_faces, supplemental_faces)

    @staticmethod
    def _needs_supplemental_tiles(
        primary_faces: list[ExtractedFace],
        width: int,
        height: int,
    ) -> bool:
        if width < 800 or width / height < 1.25:
            return False
        if len(primary_faces) <= 1:
            return True

        face_sizes = sorted(
            min(face.box.x2 - face.box.x1, face.box.y2 - face.box.y1)
            for face in primary_faces
        )
        median_size = face_sizes[len(face_sizes) // 2]
        return median_size < max(64, int(min(width, height) * 0.09))

    def extract_reference_variants(
        self,
        image: NDArray[np.uint8],
    ) -> tuple[list[ExtractedFace], list[ExtractedFace]]:
        """Extrae la captura y su reflejo con el mismo motor y perfil."""
        original = self.extract(image)
        mirrored = self.extract(cv2.flip(image, 1))
        return original, mirrored

    def _convert_faces(
        self,
        faces,
        source_image: NDArray[np.uint8],
        image_width: int,
        image_height: int,
        x_offset: int = 0,
        y_offset: int = 0,
    ) -> list[ExtractedFace]:
        extracted: list[ExtractedFace] = []
        embeddings = self._face_embeddings(source_image, faces)

        for face, embedding in zip(faces, embeddings, strict=True):
            if embedding is None:
                continue
            x1, y1, x2, y2 = face.bbox.astype(int).tolist()
            x1 = max(0, min(x1 + x_offset, image_width - 1))
            y1 = max(0, min(y1 + y_offset, image_height - 1))
            x2 = max(x1 + 1, min(x2 + x_offset, image_width))
            y2 = max(y1 + 1, min(y2 + y_offset, image_height))
            extracted.append(
                ExtractedFace(
                    box=BoundingBox(x1, y1, x2, y2),
                    detection_confidence=float(face.det_score),
                    embedding=normalize_embedding(embedding),
                )
            )
        return extracted

    def _face_embeddings(
        self,
        image: NDArray[np.uint8],
        faces,
    ) -> list[NDArray[np.float32] | None]:
        session = self._recognition_session
        input_name = self._recognition_input_name
        if session is None or input_name is None:
            return [getattr(face, "normed_embedding", None) for face in faces]

        from insightface.utils.face_align import norm_crop

        tensors: list[NDArray[np.float32]] = []
        valid_indices: list[int] = []
        embeddings: list[NDArray[np.float32] | None] = [None] * len(faces)
        for index, face in enumerate(faces):
            landmarks = getattr(face, "kps", None)
            if landmarks is None or np.asarray(landmarks).shape != (5, 2):
                continue
            aligned = norm_crop(
                image,
                landmark=np.asarray(landmarks),
                image_size=112,
            )
            rgb = cv2.cvtColor(aligned, cv2.COLOR_BGR2RGB)
            tensor = (rgb.astype(np.float32) - 127.5) / 127.5
            tensors.append(np.transpose(tensor, (2, 0, 1)))
            valid_indices.append(index)

        if not tensors:
            return embeddings

        batch = np.stack(tensors, axis=0).astype(np.float32, copy=False)
        output = np.asarray(
            session.run(None, {input_name: batch})[0],
            dtype=np.float32,
        )
        for index, vector in zip(valid_indices, output, strict=True):
            embeddings[index] = vector.reshape(-1)
        return embeddings

    @classmethod
    def _remove_duplicates(
        cls,
        faces: list[ExtractedFace],
    ) -> list[ExtractedFace]:
        accepted: list[ExtractedFace] = []
        for candidate in sorted(
            faces,
            key=lambda item: item.detection_confidence,
            reverse=True,
        ):
            if any(
                cls._faces_are_duplicates(candidate, kept)
                for kept in accepted
            ):
                continue
            accepted.append(candidate)
        return sorted(accepted, key=lambda item: (item.box.x1, item.box.y1))

    @classmethod
    def _merge_supplemental_faces(
        cls,
        primary_faces: list[ExtractedFace],
        supplemental_faces: list[ExtractedFace],
    ) -> list[ExtractedFace]:
        accepted = cls._remove_duplicates(primary_faces)
        for candidate in sorted(
            supplemental_faces,
            key=lambda item: item.detection_confidence,
            reverse=True,
        ):
            if any(
                cls._faces_are_duplicates(candidate, kept)
                for kept in accepted
            ):
                continue
            accepted.append(candidate)
        return sorted(accepted, key=lambda item: (item.box.x1, item.box.y1))

    @classmethod
    def _faces_are_duplicates(
        cls,
        first: ExtractedFace,
        second: ExtractedFace,
    ) -> bool:
        overlap = cls._overlap_over_smaller(first.box, second.box)
        if overlap >= 0.55:
            return True
        similarity = float(np.dot(first.embedding, second.embedding))
        return overlap >= 0.25 and similarity >= 0.95

    @staticmethod
    def _overlap_over_smaller(first: BoundingBox, second: BoundingBox) -> float:
        intersection_width = max(
            0,
            min(first.x2, second.x2) - max(first.x1, second.x1),
        )
        intersection_height = max(
            0,
            min(first.y2, second.y2) - max(first.y1, second.y1),
        )
        intersection = intersection_width * intersection_height
        first_area = max(0, first.x2 - first.x1) * max(0, first.y2 - first.y1)
        second_area = max(0, second.x2 - second.x1) * max(0, second.y2 - second.y1)
        smaller_area = min(first_area, second_area)
        return 0.0 if smaller_area == 0 else intersection / smaller_area

