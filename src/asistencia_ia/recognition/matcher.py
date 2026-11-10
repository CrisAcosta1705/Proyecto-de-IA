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
class ReferenceEmbedding:
    """Una variante de embedding perteneciente a una captura logica."""

    capture_id: str
    embedding: ArrayLike
    variant: str = "ORIGINAL"


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
    """Compara galerias logicas y asigna identidades globalmente por imagen."""

    def __init__(
        self,
        references: Mapping[str, Sequence[ArrayLike | ReferenceEmbedding]],
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
        self._references: dict[str, dict[str, tuple[Embedding, ...]]] = {}
        for student_id, items in references.items():
            captures: dict[str, list[Embedding]] = {}
            for index, item in enumerate(items):
                if isinstance(item, ReferenceEmbedding):
                    capture_id = item.capture_id
                    embedding = item.embedding
                else:
                    capture_id = f"legacy-{index}"
                    embedding = item
                captures.setdefault(capture_id, []).append(
                    normalize_embedding(embedding)
                )
            if captures:
                self._references[student_id] = {
                    capture_id: tuple(variants)
                    for capture_id, variants in captures.items()
                }

    def match(self, query: ArrayLike) -> RecognitionMatch:
        scores = self._ranked_scores(query)
        return self._match_from_scores(scores)

    def match_batch(self, queries: Sequence[ArrayLike]) -> list[RecognitionMatch]:
        """Maximiza el conjunto completo sin repetir identidades ni forzar caras."""
        ranked_scores = [self._ranked_scores(query) for query in queries]
        if not ranked_scores:
            return []

        student_ids = sorted(self._references)
        student_positions = {
            student_id: index for index, student_id in enumerate(student_ids)
        }
        score_maps = [
            {score.student_id: score for score in scores}
            for scores in ranked_scores
        ]
        face_count = len(queries)
        invalid = -1_000_000.0
        weights: list[list[float]] = []
        for face_index, score_map in enumerate(score_maps):
            row = [invalid] * (len(student_ids) + face_count)
            for student_id, position in student_positions.items():
                score = score_map.get(student_id)
                if score is None or not score.has_reference_support:
                    continue
                threshold = self._threshold_for(score)
                if score.similarity >= threshold:
                    row[position] = score.similarity - threshold + 1e-6
            row[len(student_ids) + face_index] = 0.0
            weights.append(row)

        assignments = _maximum_weight_assignment(weights)
        claimed = {
            student_ids[column]
            for column in assignments
            if 0 <= column < len(student_ids)
        }
        for _ in range(face_count + 1):
            accepted_claims: set[str] = set()
            for face_index, column in enumerate(assignments):
                if column < 0 or column >= len(student_ids):
                    continue
                student_id = student_ids[column]
                selected = score_maps[face_index][student_id]
                competitors = [
                    score.similarity
                    for score in ranked_scores[face_index]
                    if score.student_id != student_id
                    and score.student_id not in claimed
                ]
                if (
                    not competitors
                    or selected.similarity - max(competitors)
                    >= self._minimum_margin
                ):
                    accepted_claims.add(student_id)
            if accepted_claims == claimed:
                break
            claimed = accepted_claims
        results: list[RecognitionMatch] = []
        for face_index, column in enumerate(assignments):
            scores = ranked_scores[face_index]
            rejected = self._rejected_match(scores)
            if column < 0 or column >= len(student_ids):
                results.append(rejected)
                continue
            student_id = student_ids[column]
            selected = score_maps[face_index][student_id]
            available_competitors = [
                score.similarity
                for score in scores
                if score.student_id != student_id
                and score.student_id not in claimed
            ]
            has_margin = (
                not available_competitors
                or selected.similarity - max(available_competitors)
                >= self._minimum_margin
            )
            if not has_margin or student_id not in claimed:
                results.append(rejected)
                continue
            results.append(
                RecognitionMatch(student_id, selected.similarity, True)
            )
        return results

    def _ranked_scores(self, query: ArrayLike) -> list[_StudentScore]:
        query_vector = normalize_embedding(query)
        scores: list[_StudentScore] = []
        for student_id, captures in self._references.items():
            capture_similarities = sorted(
                (
                    max(
                        cosine_similarity(query_vector, variant)
                        for variant in variants
                    )
                    for variants in captures.values()
                ),
                reverse=True,
            )
            best_similarity = capture_similarities[0]
            second_similarity = (
                capture_similarities[1]
                if len(capture_similarities) > 1
                else None
            )
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
                    reference_count=len(capture_similarities),
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
        accepted = (
            best.similarity >= self._threshold_for(best)
            and (
                len(scores) == 1
                or best.similarity - second_similarity >= self._minimum_margin
            )
            and best.has_reference_support
        )
        return RecognitionMatch(
            student_id=best.student_id if accepted else None,
            similarity=best.similarity,
            accepted=accepted,
        )

    def _threshold_for(self, score: _StudentScore) -> float:
        return (
            self._single_reference_threshold
            if score.reference_count == 1
            else self._threshold
        )

    @staticmethod
    def _rejected_match(
        scores: Sequence[_StudentScore],
    ) -> RecognitionMatch:
        similarity = scores[0].similarity if scores else -1.0
        return RecognitionMatch(None, similarity, False)


def _maximum_weight_assignment(weights: Sequence[Sequence[float]]) -> list[int]:
    """Algoritmo hungaro rectangular; requiere tantas columnas como filas."""
    row_count = len(weights)
    if row_count == 0:
        return []
    column_count = len(weights[0])
    if column_count < row_count or any(len(row) != column_count for row in weights):
        raise ValueError("La matriz de asignacion debe ser rectangular y ancha.")

    max_weight = max(max(row) for row in weights)
    costs = [[max_weight - value for value in row] for row in weights]
    u = [0.0] * (row_count + 1)
    v = [0.0] * (column_count + 1)
    p = [0] * (column_count + 1)
    way = [0] * (column_count + 1)

    for row in range(1, row_count + 1):
        p[0] = row
        column0 = 0
        minimum = [float("inf")] * (column_count + 1)
        used = [False] * (column_count + 1)
        while True:
            used[column0] = True
            current_row = p[column0]
            delta = float("inf")
            column1 = 0
            for column in range(1, column_count + 1):
                if used[column]:
                    continue
                current = (
                    costs[current_row - 1][column - 1]
                    - u[current_row]
                    - v[column]
                )
                if current < minimum[column]:
                    minimum[column] = current
                    way[column] = column0
                if minimum[column] < delta:
                    delta = minimum[column]
                    column1 = column
            for column in range(column_count + 1):
                if used[column]:
                    u[p[column]] += delta
                    v[column] -= delta
                else:
                    minimum[column] -= delta
            column0 = column1
            if p[column0] == 0:
                break
        while True:
            column1 = way[column0]
            p[column0] = p[column1]
            column0 = column1
            if column0 == 0:
                break

    assignment = [-1] * row_count
    for column in range(1, column_count + 1):
        if p[column] != 0:
            assignment[p[column] - 1] = column - 1
    return assignment
