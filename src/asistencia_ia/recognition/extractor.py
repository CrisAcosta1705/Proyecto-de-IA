from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Protocol

import numpy as np
from numpy.typing import NDArray

from asistencia_ia.domain import BoundingBox
from asistencia_ia.recognition.matcher import normalize_embedding


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
        detection_size: tuple[int, int] = (1280, 1280),
    ):
        self.model_name = model_name
        self.model_root = Path(model_root)
        self.detection_threshold = detection_threshold
        self.detection_size = detection_size
        self._app = None
        self._lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._app is not None

    def _load(self):
        if self._app is None:
            from insightface.app import FaceAnalysis

            self.model_root.mkdir(parents=True, exist_ok=True)
            app = FaceAnalysis(
                name=self.model_name,
                root=str(self.model_root),
                allowed_modules=["detection", "recognition"],
                providers=["CPUExecutionProvider"],
            )
            app.prepare(
                ctx_id=-1,
                det_thresh=self.detection_threshold,
                det_size=self.detection_size,
            )
            self._app = app
        return self._app

    def extract(self, image: NDArray[np.uint8]) -> list[ExtractedFace]:
        if image.ndim != 3 or image.shape[2] != 3:
            raise ValueError("La imagen debe tener tres canales BGR.")

        height, width = image.shape[:2]
        with self._lock:
            app = self._load()
            extracted = self._convert_faces(app.get(image), width, height)
            if width >= 800 and width / height >= 1.35:
                tile_width = max(height, int(width * 0.65))
                tile_width = min(tile_width, width)
                for x_offset in sorted({0, width - tile_width}):
                    tile = image[:, x_offset : x_offset + tile_width]
                    extracted.extend(
                        self._convert_faces(
                            app.get(tile),
                            width,
                            height,
                            x_offset=x_offset,
                        )
                    )

        return self._remove_duplicates(extracted)

    @staticmethod
    def _convert_faces(
        faces,
        image_width: int,
        image_height: int,
        x_offset: int = 0,
    ) -> list[ExtractedFace]:
        extracted: list[ExtractedFace] = []

        for face in faces:
            embedding = face.normed_embedding
            if embedding is None:
                continue
            x1, y1, x2, y2 = face.bbox.astype(int).tolist()
            x1 = max(0, min(x1 + x_offset, image_width - 1))
            y1 = max(0, min(y1, image_height - 1))
            x2 = max(x1 + 1, min(x2 + x_offset, image_width))
            y2 = max(y1 + 1, min(y2, image_height))
            extracted.append(
                ExtractedFace(
                    box=BoundingBox(x1, y1, x2, y2),
                    detection_confidence=float(face.det_score),
                    embedding=normalize_embedding(embedding),
                )
            )
        return extracted

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
                cls._overlap_over_smaller(candidate.box, kept.box) >= 0.55
                for kept in accepted
            ):
                continue
            accepted.append(candidate)
        return sorted(accepted, key=lambda item: (item.box.x1, item.box.y1))

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

