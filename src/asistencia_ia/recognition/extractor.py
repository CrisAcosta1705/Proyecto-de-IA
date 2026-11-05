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
        detection_threshold: float = 0.65,
        detection_size: tuple[int, int] = (1024, 1024),
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
        extracted: list[ExtractedFace] = []
        with self._lock:
            faces = self._load().get(image)

        for face in faces:
            embedding = face.normed_embedding
            if embedding is None:
                continue
            x1, y1, x2, y2 = face.bbox.astype(int).tolist()
            x1 = max(0, min(x1, width - 1))
            y1 = max(0, min(y1, height - 1))
            x2 = max(x1 + 1, min(x2, width))
            y2 = max(y1 + 1, min(y2, height))
            extracted.append(
                ExtractedFace(
                    box=BoundingBox(x1, y1, x2, y2),
                    detection_confidence=float(face.det_score),
                    embedding=normalize_embedding(embedding),
                )
            )
        return extracted

