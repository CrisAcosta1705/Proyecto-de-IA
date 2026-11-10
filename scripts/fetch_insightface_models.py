"""Descarga reproducible de los modelos canonicos usados por Railway."""

from __future__ import annotations

import hashlib
from pathlib import Path
import shutil
import sys
from tempfile import NamedTemporaryFile
from urllib.request import urlopen
from zipfile import ZipFile


MODEL_URL = (
    "https://github.com/deepinsight/insightface/releases/download/v0.7/"
    "buffalo_l.zip"
)
ARCHIVE_SHA256 = "80ffe37d8a5940d59a7384c201a2a38d4741f2f3c51eef46ebb28218a7b0ca2f"
MODEL_HASHES = {
    "det_10g.onnx": "5838f7fe053675b1c7a08b633df49e7af5495cee0493c7dcf6697200b85b5b91",
    "w600k_r50.onnx": "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    destination = Path(sys.argv[1] if len(sys.argv) > 1 else "models/buffalo_l")
    destination.mkdir(parents=True, exist_ok=True)
    with NamedTemporaryFile(suffix=".zip", delete=False) as temporary:
        archive = Path(temporary.name)
        with urlopen(MODEL_URL, timeout=120) as response:
            shutil.copyfileobj(response, temporary)
    try:
        if _sha256(archive) != ARCHIVE_SHA256:
            raise RuntimeError("El paquete buffalo_l no coincide con el SHA-256 fijado.")
        with ZipFile(archive) as bundle:
            members = {Path(info.filename).name: info for info in bundle.infolist()}
            for filename, expected_hash in MODEL_HASHES.items():
                info = members.get(filename)
                if info is None:
                    raise RuntimeError(f"El paquete no contiene {filename}.")
                target = destination / filename
                with bundle.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if _sha256(target) != expected_hash:
                    raise RuntimeError(f"El modelo {filename} no coincide con su SHA-256.")
    finally:
        archive.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
