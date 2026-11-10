import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

import numpy as np

from asistencia_ia.recognition.extractor import InsightFaceExtractor


def _face(box, embedding, confidence=0.9):
    return SimpleNamespace(
        bbox=np.asarray(box, dtype=np.float32),
        normed_embedding=np.asarray(embedding, dtype=np.float32),
        det_score=confidence,
    )


class _WideGroupApp:
    def __init__(self):
        self.calls = 0

    def get(self, image):
        self.calls += 1
        if self.calls == 1:
            return [_face([400, 100, 500, 220], [0.0, 1.0, 0.0])]
        if self.calls == 2:
            return [
                _face([100, 100, 200, 220], [1.0, 0.0, 0.0]),
                _face(
                    [400, 100, 500, 220],
                    [1.0, 1.0, 0.0],
                    confidence=0.99,
                ),
            ]
        return [
            _face([50, 100, 150, 220], [0.0, 1.0, 0.0]),
            _face([450, 100, 550, 220], [0.0, 0.0, 1.0]),
        ]


class _CompleteWideGroupApp:
    def __init__(self):
        self.calls = 0

    def get(self, image):
        self.calls += 1
        return [
            _face([100 + index * 190, 100, 220 + index * 190, 250], embedding)
            for index, embedding in enumerate(
                (
                    [1.0, 0.0, 0.0],
                    [0.0, 1.0, 0.0],
                    [0.0, 0.0, 1.0],
                    [1.0, 1.0, 0.0],
                )
            )
        ]


class InsightFaceExtractorTest(unittest.TestCase):
    def test_rejects_a_recognition_model_with_a_different_hash(self):
        with TemporaryDirectory() as directory:
            model = Path(directory) / "w600k_mbf.onnx"
            model.write_bytes(b"modelo-distinto")
            extractor = InsightFaceExtractor(recognition_model_path=model)

            with self.assertRaisesRegex(RuntimeError, "no coincide"):
                extractor._load_recognition_model()

    def test_wide_group_combines_overlapping_tiles_without_duplicates(self):
        extractor = InsightFaceExtractor()
        extractor._app = _WideGroupApp()
        image = np.zeros((500, 1000, 3), dtype=np.uint8)

        faces = extractor.extract(image)

        self.assertEqual(extractor._app.calls, 4)
        self.assertEqual(len(faces), 3)
        self.assertEqual(
            [(face.box.x1, face.box.x2) for face in faces],
            [(100, 200), (400, 500), (844, 944)],
        )
        center = next(face for face in faces if face.box.x1 == 400)
        np.testing.assert_allclose(center.embedding, [0.0, 1.0, 0.0])

    def test_portrait_image_uses_one_detection_pass(self):
        app = _WideGroupApp()
        extractor = InsightFaceExtractor()
        extractor._app = app
        image = np.zeros((1000, 500, 3), dtype=np.uint8)

        faces = extractor.extract(image)

        self.assertEqual(app.calls, 1)
        self.assertEqual(len(faces), 1)

    def test_complete_wide_group_does_not_repeat_detection_in_tiles(self):
        app = _CompleteWideGroupApp()
        extractor = InsightFaceExtractor()
        extractor._app = app
        image = np.zeros((500, 1000, 3), dtype=np.uint8)

        faces = extractor.extract(image)

        self.assertEqual(app.calls, 1)
        self.assertEqual(len(faces), 4)


if __name__ == "__main__":
    unittest.main()
