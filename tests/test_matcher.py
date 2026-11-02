import unittest

import numpy as np

from asistencia_ia.recognition.matcher import EmbeddingMatcher, cosine_similarity


class EmbeddingMatcherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.matcher = EmbeddingMatcher(
            {
                "estudiante_001": [np.array([1.0, 0.0])],
                "estudiante_002": [np.array([0.0, 1.0])],
            },
            threshold=0.8,
        )

    def test_cosine_similarity_for_equal_vectors(self) -> None:
        self.assertAlmostEqual(
            cosine_similarity([1.0, 2.0], [1.0, 2.0]),
            1.0,
            places=6,
        )

    def test_matcher_accepts_best_registered_student(self) -> None:
        result = self.matcher.match([0.99, 0.01])

        self.assertTrue(result.accepted)
        self.assertEqual(result.student_id, "estudiante_001")

    def test_matcher_rejects_unknown_embedding(self) -> None:
        result = self.matcher.match([1.0, 1.0])

        self.assertFalse(result.accepted)
        self.assertIsNone(result.student_id)

    def test_zero_embedding_is_invalid(self) -> None:
        with self.assertRaises(ValueError):
            self.matcher.match([0.0, 0.0])


if __name__ == "__main__":
    unittest.main()
