import unittest
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import ModuleType, SimpleNamespace
from unittest.mock import patch

import numpy as np

from asistencia_ia.recognition.extractor import InsightFaceExtractor


def _face(box, embedding, confidence=0.9):
    return SimpleNamespace(
        bbox=np.asarray(box, dtype=np.float32),
        normed_embedding=np.asarray(embedding, dtype=np.float32),
        det_score=confidence,
    )


class _BatchRecognitionSession:
    def __init__(self):
        self.batch_sizes = []

    def run(self, output_names, inputs):
        batch = inputs["input"]
        self.batch_sizes.append(batch.shape[0])
        return [
            np.asarray(
                [[1.0, float(index), 0.5] for index in range(batch.shape[0])],
                dtype=np.float32,
            )
        ]


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

    def test_recognition_embeddings_are_computed_in_one_batch(self):
        session = _BatchRecognitionSession()
        extractor = InsightFaceExtractor()
        extractor._recognition_session = session
        extractor._recognition_input_name = "input"
        image = np.zeros((300, 300, 3), dtype=np.uint8)
        landmarks = np.asarray(
            [[90, 100], [150, 100], [120, 135], [95, 170], [145, 170]],
            dtype=np.float32,
        )
        faces = [
            SimpleNamespace(kps=landmarks),
            SimpleNamespace(kps=landmarks + 10),
        ]

        insightface_module = ModuleType("insightface")
        utils_module = ModuleType("insightface.utils")
        face_align_module = ModuleType("insightface.utils.face_align")
        face_align_module.norm_crop = lambda *args, **kwargs: np.zeros(
            (112, 112, 3),
            dtype=np.uint8,
        )
        with patch.dict(
            sys.modules,
            {
                "insightface": insightface_module,
                "insightface.utils": utils_module,
                "insightface.utils.face_align": face_align_module,
            },
        ):
            embeddings = extractor._face_embeddings(image, faces)

        self.assertEqual(session.batch_sizes, [2])
        self.assertEqual(len(embeddings), 2)
        np.testing.assert_allclose(embeddings[0], [1.0, 0.0, 0.5])
        np.testing.assert_allclose(embeddings[1], [1.0, 1.0, 0.5])


if __name__ == "__main__":
    unittest.main()
