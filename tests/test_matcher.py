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

    def test_matcher_rejects_an_ambiguous_identity(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [np.array([1.0, 0.0])],
                "estudiante_002": [np.array([0.0, 1.0])],
            },
            threshold=0.5,
            minimum_margin=0.07,
        )

        result = matcher.match([1.0, 1.0])

        self.assertFalse(result.accepted)
        self.assertIsNone(result.student_id)

    def test_batch_assigns_a_student_to_only_one_face(self) -> None:
        results = self.matcher.match_batch(
            [
                np.array([1.0, 0.0]),
                np.array([0.99, 0.01]),
            ]
        )

        self.assertTrue(results[0].accepted)
        self.assertEqual(results[0].student_id, "estudiante_001")
        self.assertFalse(results[1].accepted)
        self.assertIsNone(results[1].student_id)

    def test_batch_recognizes_five_distinct_students(self) -> None:
        references = {
            f"estudiante_{index}": [np.eye(5, dtype=np.float32)[index]]
            for index in range(5)
        }
        matcher = EmbeddingMatcher(references, threshold=0.8)

        results = matcher.match_batch(np.eye(5, dtype=np.float32))

        self.assertEqual(len(results), 5)
        self.assertTrue(all(result.accepted for result in results))
        self.assertEqual(
            {result.student_id for result in results},
            set(references),
        )

    def test_zero_embedding_is_invalid(self) -> None:
        with self.assertRaises(ValueError):
            self.matcher.match([0.0, 0.0])


if __name__ == "__main__":
    unittest.main()
