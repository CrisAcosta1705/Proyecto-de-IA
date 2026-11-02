import unittest

import numpy as np

from asistencia_ia.domain import BoundingBox, PersonDetection
from asistencia_ia.pipeline import ClassroomPipeline


class FakeDetector:
    def detect(self, frame):
        return [
            PersonDetection(BoundingBox(0, 0, 20, 40), 0.95),
            PersonDetection(BoundingBox(30, 0, 50, 40), 0.90),
        ]


class ClassroomPipelineTest(unittest.TestCase):
    def test_pipeline_counts_person_detections(self) -> None:
        frame = np.zeros((60, 80, 3), dtype=np.uint8)

        result = ClassroomPipeline(FakeDetector()).analyze(frame)

        self.assertEqual(result.person_count, 2)


if __name__ == "__main__":
    unittest.main()
