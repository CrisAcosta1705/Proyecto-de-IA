from dataclasses import dataclass

from numpy.typing import NDArray
import numpy as np

from asistencia_ia.detection import PersonDetector
from asistencia_ia.domain import PersonDetection


@dataclass(frozen=True)
class ClassroomAnalysis:
    person_detections: tuple[PersonDetection, ...]

    @property
    def person_count(self) -> int:
        return len(self.person_detections)


class ClassroomPipeline:
    """Primera etapa del flujo: deteccion y conteo en la imagen del aula."""

    def __init__(self, detector: PersonDetector):
        self._detector = detector

    def analyze(self, frame: NDArray[np.uint8]) -> ClassroomAnalysis:
        return ClassroomAnalysis(tuple(self._detector.detect(frame)))

