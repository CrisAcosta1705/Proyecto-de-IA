from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import ArrayLike, NDArray


Embedding = NDArray[np.float32]


def normalize_embedding(embedding: ArrayLike) -> Embedding:
    vector = np.asarray(embedding, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(vector))
    if norm == 0.0:
        raise ValueError("Un embedding no puede ser un vector cero.")
    return vector / norm


def cosine_similarity(first: ArrayLike, second: ArrayLike) -> float:
    first_vector = normalize_embedding(first)
    second_vector = normalize_embedding(second)
    if first_vector.shape != second_vector.shape:
        raise ValueError("Los embeddings deben tener la misma dimension.")
    return float(np.dot(first_vector, second_vector))


@dataclass(frozen=True)
class RecognitionMatch:
    student_id: str | None
    similarity: float
    accepted: bool


class EmbeddingMatcher:
    """Compara embeddings y evita repetir identidades en una misma imagen."""

    def __init__(
        self,
        references: Mapping[str, Sequence[ArrayLike]],
        threshold: float,
        minimum_margin: float = 0.07,
    ):
        if not -1.0 <= threshold <= 1.0:
            raise ValueError("El umbral debe estar entre -1 y 1.")
        if not 0.0 <= minimum_margin <= 2.0:
            raise ValueError("El margen minimo debe estar entre 0 y 2.")

        self._threshold = threshold
        self._minimum_margin = minimum_margin
        self._references = {
            student_id: tuple(normalize_embedding(item) for item in embeddings)
            for student_id, embeddings in references.items()
            if embeddings
        }

    def match(self, query: ArrayLike) -> RecognitionMatch:
        scores = self._ranked_scores(query)
        return self._match_from_scores(scores)

    def match_batch(self, queries: Sequence[ArrayLike]) -> list[RecognitionMatch]:
        """Asigna cada estudiante a un solo rostro, priorizando la mejor similitud."""
        ranked_scores = [self._ranked_scores(query) for query in queries]
        results = [self._rejected_match(scores) for scores in ranked_scores]
        candidates: list[tuple[float, int, str]] = []

        for face_index, scores in enumerate(ranked_scores):
            candidate = self._match_from_scores(scores)
            if candidate.accepted and candidate.student_id is not None:
                candidates.append(
                    (candidate.similarity, face_index, candidate.student_id)
                )

        claimed_students: set[str] = set()
        for similarity, face_index, student_id in sorted(
            candidates,
            key=lambda item: item[0],
            reverse=True,
        ):
            if student_id in claimed_students:
                continue
            claimed_students.add(student_id)
            results[face_index] = RecognitionMatch(
                student_id=student_id,
                similarity=similarity,
                accepted=True,
            )

        return results

    def _ranked_scores(self, query: ArrayLike) -> list[tuple[str, float]]:
        query_vector = normalize_embedding(query)
        scores = [
            (
                student_id,
                max(
                    cosine_similarity(query_vector, reference)
                    for reference in embeddings
                ),
            )
            for student_id, embeddings in self._references.items()
        ]
        return sorted(scores, key=lambda item: item[1], reverse=True)

    def _match_from_scores(
        self,
        scores: Sequence[tuple[str, float]],
    ) -> RecognitionMatch:
        if not scores:
            return RecognitionMatch(None, -1.0, False)

        best_student, best_similarity = scores[0]
        second_similarity = scores[1][1] if len(scores) > 1 else -1.0
        has_margin = (
            len(scores) == 1
            or best_similarity - second_similarity >= self._minimum_margin
        )
        accepted = best_similarity >= self._threshold and has_margin
        return RecognitionMatch(
            student_id=best_student if accepted else None,
            similarity=best_similarity,
            accepted=accepted,
        )

    @staticmethod
    def _rejected_match(
        scores: Sequence[tuple[str, float]],
    ) -> RecognitionMatch:
        similarity = scores[0][1] if scores else -1.0
        return RecognitionMatch(None, similarity, False)

