import numpy as np

from asistencia_ia.recognition import InsightFaceExtractor


def main() -> None:
    extractor = InsightFaceExtractor()
    faces = extractor.extract(np.zeros((640, 640, 3), dtype=np.uint8))
    print(f"Modelo cargado correctamente. Rostros en imagen vacia: {len(faces)}")


if __name__ == "__main__":
    main()
