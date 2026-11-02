from dataclasses import dataclass


@dataclass(frozen=True)
class BoundingBox:
    """Caja delimitadora expresada en pixeles."""

    x1: int
    y1: int
    x2: int
    y2: int

    def __post_init__(self) -> None:
        if self.x2 <= self.x1 or self.y2 <= self.y1:
            raise ValueError("La caja delimitadora debe tener un area positiva.")

    @property
    def area(self) -> int:
        return (self.x2 - self.x1) * (self.y2 - self.y1)


@dataclass(frozen=True)
class PersonDetection:
    """Persona localizada por el detector de objetos."""

    box: BoundingBox
    confidence: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("La confianza debe estar entre 0 y 1.")

