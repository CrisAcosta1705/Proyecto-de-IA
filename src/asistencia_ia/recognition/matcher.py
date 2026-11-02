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
    """Compara embeddings sin imponer todavia FaceNet o ArcFace."""

    def __init__(
        self,
        references: Mapping[str, Sequence[ArrayLike]],
        threshold: float,
    ):
        if not -1.0 <= threshold <= 1.0:
            raise ValueError("El umbral debe estar entre -1 y 1.")

        self._threshold = threshold
        self._references = {
            student_id: tuple(normalize_embedding(item) for item in embeddings)
            for student_id, embeddings in references.items()
            if embeddings
        }

    def match(self, query: ArrayLike) -> RecognitionMatch:
        query_vector = normalize_embedding(query)
        best_student: str | None = None
        best_similarity = -1.0

        for student_id, embeddings in self._references.items():
            student_similarity = max(
                cosine_similarity(query_vector, reference) for reference in embeddings
            )
            if student_similarity > best_similarity:
                best_student = student_id
                best_similarity = student_similarity

        accepted = best_student is not None and best_similarity >= self._threshold
        return RecognitionMatch(
            student_id=best_student if accepted else None,
            similarity=best_similarity,
            accepted=accepted,
        )

