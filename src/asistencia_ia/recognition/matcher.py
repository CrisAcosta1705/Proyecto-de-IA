from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import ArrayLike, NDArray

from asistencia_ia.recognition.profile import (
    MINIMUM_REFERENCE_SUPPORT,
    MINIMUM_SCORE_MARGIN,
    SECOND_REFERENCE_WEIGHT,
    SIMILARITY_THRESHOLD,
    SINGLE_REFERENCE_THRESHOLD,
)


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


@dataclass(frozen=True)
class _StudentScore:
    student_id: str
    similarity: float
    reference_count: int
    has_reference_support: bool


class EmbeddingMatcher:
    """Compara embeddings y evita repetir identidades en una misma imagen."""

    def __init__(
        self,
        references: Mapping[str, Sequence[ArrayLike]],
        threshold: float = SIMILARITY_THRESHOLD,
        minimum_margin: float = MINIMUM_SCORE_MARGIN,
        single_reference_threshold: float | None = None,
        minimum_reference_support: float = MINIMUM_REFERENCE_SUPPORT,
        second_reference_weight: float = SECOND_REFERENCE_WEIGHT,
    ):
        if not -1.0 <= threshold <= 1.0:
            raise ValueError("El umbral debe estar entre -1 y 1.")
        if not 0.0 <= minimum_margin <= 2.0:
            raise ValueError("El margen minimo debe estar entre 0 y 2.")
        if (
            single_reference_threshold is not None
            and not -1.0 <= single_reference_threshold <= 1.0
        ):
            raise ValueError("El umbral para una referencia debe estar entre -1 y 1.")
        if not -1.0 <= minimum_reference_support <= 1.0:
            raise ValueError("El soporte minimo debe estar entre -1 y 1.")
        if not 0.0 <= second_reference_weight <= 1.0:
            raise ValueError("El peso de la segunda referencia debe estar entre 0 y 1.")

        self._threshold = threshold
        self._minimum_margin = minimum_margin
        self._single_reference_threshold = (
            max(threshold, SINGLE_REFERENCE_THRESHOLD)
            if single_reference_threshold is None
            else single_reference_threshold
        )
        self._minimum_reference_support = minimum_reference_support
        self._second_reference_weight = second_reference_weight
        self._references = {
            student_id: tuple(normalize_embedding(item) for item in embeddings)
            for student_id, embeddings in references.items()
            if embeddings
        }

    def match(self, query: ArrayLike) -> RecognitionMatch:
        scores = self._ranked_scores(query)
        return self._match_from_scores(scores)

    def match_batch(self, queries: Sequence[ArrayLike]) -> list[RecognitionMatch]:
        """Conserva solo la mejor cara por identidad sin reasignar perdedores."""
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
            key=lambda item: (-item[0], item[1], item[2]),
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

    def _ranked_scores(self, query: ArrayLike) -> list[_StudentScore]:
        query_vector = normalize_embedding(query)
        scores: list[_StudentScore] = []
        for student_id, embeddings in self._references.items():
            similarities = sorted(
                (
                    cosine_similarity(query_vector, reference)
                    for reference in embeddings
                ),
                reverse=True,
            )
            best_similarity = similarities[0]
            second_similarity = similarities[1] if len(similarities) > 1 else None
            combined_similarity = (
                best_similarity
                if second_similarity is None
                else (
                    best_similarity * (1.0 - self._second_reference_weight)
                    + second_similarity * self._second_reference_weight
                )
            )
            scores.append(
                _StudentScore(
                    student_id=student_id,
                    similarity=combined_similarity,
                    reference_count=len(similarities),
                    has_reference_support=(
                        second_similarity is None
                        or second_similarity >= self._minimum_reference_support
                    ),
                )
            )
        return sorted(scores, key=lambda item: (-item.similarity, item.student_id))

    def _match_from_scores(
        self,
        scores: Sequence[_StudentScore],
    ) -> RecognitionMatch:
        if not scores:
            return RecognitionMatch(None, -1.0, False)

        best = scores[0]
        second_similarity = scores[1].similarity if len(scores) > 1 else -1.0
        has_margin = (
            len(scores) == 1
            or best.similarity - second_similarity >= self._minimum_margin
        )
        threshold = (
            self._single_reference_threshold
            if best.reference_count == 1
            else self._threshold
        )
        accepted = (
            best.similarity >= threshold
            and has_margin
            and best.has_reference_support
        )
        return RecognitionMatch(
            student_id=best.student_id if accepted else None,
            similarity=best.similarity,
            accepted=accepted,
        )

    @staticmethod
    def _rejected_match(
        scores: Sequence[_StudentScore],
    ) -> RecognitionMatch:
        similarity = scores[0].similarity if scores else -1.0
        return RecognitionMatch(None, similarity, False)

