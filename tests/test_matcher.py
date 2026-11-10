import unittest

import numpy as np

from asistencia_ia.recognition.matcher import (
    EmbeddingMatcher,
    ReferenceEmbedding,
    cosine_similarity,
)


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

    def test_batch_uses_global_assignment_for_competing_faces(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [np.array([1.0, 0.0, 0.0])],
                "estudiante_002": [np.array([0.8, 0.6, 0.0])],
            },
            threshold=0.7,
            minimum_margin=0.0,
        )

        results = matcher.match_batch(
            [
                np.array([1.0, 0.0, 0.0]),
                np.array([0.95, 0.312, 0.0]),
            ]
        )

        self.assertEqual(results[0].student_id, "estudiante_001")
        self.assertTrue(results[0].accepted)
        self.assertEqual(results[1].student_id, "estudiante_002")
        self.assertTrue(results[1].accepted)

    def test_global_assignment_does_not_hide_an_ambiguous_face(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [np.array([1.0, 0.0, 0.0])],
                "estudiante_002": [np.array([0.8, 0.6, 0.0])],
                "estudiante_003": [np.array([0.79, 0.613, 0.0])],
            },
            threshold=0.7,
            minimum_margin=0.02,
        )

        results = matcher.match_batch(
            [
                np.array([1.0, 0.0, 0.0]),
                np.array([0.95, 0.312, 0.0]),
            ]
        )

        self.assertTrue(results[0].accepted)
        self.assertFalse(results[1].accepted)

    def test_mirror_variants_count_as_one_logical_capture(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [
                    ReferenceEmbedding("capture-1", np.array([1.0, 0.0])),
                    ReferenceEmbedding(
                        "capture-1", np.array([0.99, 0.01]), "MIRRORED"
                    ),
                ]
            },
            threshold=0.5,
            single_reference_threshold=0.8,
        )

        result = matcher.match([1.0, 0.0])

        self.assertTrue(result.accepted)
        self.assertEqual(result.student_id, "estudiante_001")

    def test_mirror_variants_do_not_fake_second_capture_support(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [
                    ReferenceEmbedding("capture-1", np.array([1.0, 0.0, 0.0])),
                    ReferenceEmbedding(
                        "capture-1", np.array([0.0, 1.0, 0.0]), "MIRRORED"
                    ),
                    ReferenceEmbedding("capture-2", np.array([0.0, 0.0, 1.0])),
                ]
            },
            threshold=0.5,
            minimum_reference_support=0.4,
        )

        result = matcher.match([1.0, 0.0, 0.0])

        self.assertFalse(result.accepted)

    def test_multiple_references_need_support_from_a_second_reference(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [
                    np.array([1.0, 0.0, 0.0]),
                    np.array([0.0, 1.0, 0.0]),
                ],
            },
            threshold=0.5,
            minimum_reference_support=0.4,
        )

        result = matcher.match([1.0, 0.0, 0.0])

        self.assertFalse(result.accepted)
        self.assertIsNone(result.student_id)

    def test_multiple_consistent_references_are_accepted(self) -> None:
        matcher = EmbeddingMatcher(
            {
                "estudiante_001": [
                    np.array([1.0, 0.0, 0.0]),
                    np.array([0.95, 0.31, 0.0]),
                ],
            },
            threshold=0.5,
            minimum_reference_support=0.4,
        )

        result = matcher.match([1.0, 0.0, 0.0])

        self.assertTrue(result.accepted)
        self.assertEqual(result.student_id, "estudiante_001")

    def test_zero_embedding_is_invalid(self) -> None:
        with self.assertRaises(ValueError):
            self.matcher.match([0.0, 0.0])


if __name__ == "__main__":
    unittest.main()
