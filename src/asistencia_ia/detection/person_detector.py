from typing import Protocol

import numpy as np
from numpy.typing import NDArray

from asistencia_ia.domain import BoundingBox, PersonDetection


Frame = NDArray[np.uint8]
PERSON_CLASS_ID = 0


class PersonDetector(Protocol):
    def detect(self, frame: Frame) -> list[PersonDetection]:
        """Devuelve las personas encontradas en una imagen completa de aula."""


class YoloPersonDetector:
    """Adaptador pequeno para usar un modelo YOLO como detector de personas."""

    def __init__(self, model_path: str = "yolov8n.pt", min_confidence: float = 0.5):
        from ultralytics import YOLO

        if not 0.0 <= min_confidence <= 1.0:
            raise ValueError("min_confidence debe estar entre 0 y 1.")

        self._model = YOLO(model_path)
        self._min_confidence = min_confidence

    def detect(self, frame: Frame) -> list[PersonDetection]:
        detections: list[PersonDetection] = []
        results = self._model(frame, verbose=False, classes=[PERSON_CLASS_ID])

        for result in results:
            for box in result.boxes:
                class_id = int(box.cls[0])
                confidence = float(box.conf[0])
                if class_id != PERSON_CLASS_ID or confidence < self._min_confidence:
                    continue

                x1, y1, x2, y2 = (int(value) for value in box.xyxy[0].tolist())
                detections.append(
                    PersonDetection(
                        box=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2),
                        confidence=confidence,
                    )
                )

        return detections
