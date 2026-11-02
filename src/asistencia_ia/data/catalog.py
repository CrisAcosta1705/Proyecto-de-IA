import argparse
import csv
from dataclasses import asdict, dataclass
from pathlib import Path


SUPPORTED_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


@dataclass(frozen=True)
class ImageRecord:
    image_id: str
    subset: str
    relative_path: str
    student_id: str | None = None


def _image_files(folder: Path) -> list[Path]:
    if not folder.exists():
        return []
    return sorted(
        path
        for path in folder.rglob("*")
        if path.is_file() and path.suffix.lower() in SUPPORTED_IMAGE_SUFFIXES
    )


def build_catalog(data_root: Path) -> list[ImageRecord]:
    """Inventaria aulas y rostros sin abrir ni modificar los archivos originales."""

    data_root = data_root.resolve()
    records: list[ImageRecord] = []

    classroom_root = data_root / "raw" / "classroom"
    for path in _image_files(classroom_root):
        records.append(
            ImageRecord(
                image_id=path.stem,
                subset="classroom",
                relative_path=path.relative_to(data_root).as_posix(),
            )
        )

    students_root = data_root / "raw" / "registered_students"
    for path in _image_files(students_root):
        relative = path.relative_to(students_root)
        student_id = relative.parts[0] if len(relative.parts) > 1 else None
        records.append(
            ImageRecord(
                image_id=path.stem,
                subset="registered_students",
                relative_path=path.relative_to(data_root).as_posix(),
                student_id=student_id,
            )
        )

    return records


def write_catalog_csv(records: list[ImageRecord], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=ImageRecord.__dataclass_fields__)
        writer.writeheader()
        writer.writerows(asdict(record) for record in records)


def main() -> None:
    parser = argparse.ArgumentParser(description="Crea el catalogo inicial del dataset.")
    parser.add_argument("--data-root", type=Path, default=Path("data"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/processed/metadata.csv"),
    )
    args = parser.parse_args()

    records = build_catalog(args.data_root)
    write_catalog_csv(records, args.output)
    print(f"Catalogo creado con {len(records)} imagenes: {args.output}")


if __name__ == "__main__":
    main()

