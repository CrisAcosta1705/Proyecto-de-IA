import tempfile
import unittest
from pathlib import Path

from asistencia_ia.data.catalog import build_catalog, write_catalog_csv


class DatasetCatalogTest(unittest.TestCase):
    def test_catalog_separates_classroom_and_registered_students(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data_root = Path(directory)
            classroom = data_root / "raw" / "classroom"
            student = data_root / "raw" / "registered_students" / "estudiante_001"
            classroom.mkdir(parents=True)
            student.mkdir(parents=True)
            (classroom / "aula_001.jpg").touch()
            (student / "frontal_001.png").touch()
            (student / "notas.txt").touch()

            records = build_catalog(data_root)

            self.assertEqual(len(records), 2)
            self.assertEqual(records[0].subset, "classroom")
            self.assertEqual(records[1].student_id, "estudiante_001")

    def test_catalog_can_be_written_to_csv(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "processed" / "metadata.csv"

            write_catalog_csv([], output)

            self.assertTrue(
                output.read_text(encoding="utf-8").startswith("image_id,subset")
            )


if __name__ == "__main__":
    unittest.main()
