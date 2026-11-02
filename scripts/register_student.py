import argparse
from pathlib import Path

from asistencia_ia.recognition import InsightFaceExtractor
from asistencia_ia.services import AttendanceService
from asistencia_ia.storage import AttendanceDatabase


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Registra fotos, datos y embeddings de un estudiante."
    )
    parser.add_argument("--student-id", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--image", action="append", type=Path, required=True)
    parser.add_argument(
        "--database",
        type=Path,
        default=Path("data/processed/asistencia.db"),
    )
    parser.add_argument(
        "--append",
        action="store_true",
        help="Agrega embeddings sin eliminar los registros anteriores.",
    )
    args = parser.parse_args()

    database = AttendanceDatabase(args.database)
    database.initialize()
    service = AttendanceService(database, InsightFaceExtractor())
    contents = [path.read_bytes() for path in args.image]
    added = service.register_student(
        args.student_id,
        args.name,
        contents,
        replace_embeddings=not args.append,
        photo_mime_type="image/jpeg",
    )
    print(
        f"Estudiante {args.student_id} registrado con {added} embedding(s) "
        "y fotografia almacenada."
    )


if __name__ == "__main__":
    main()
