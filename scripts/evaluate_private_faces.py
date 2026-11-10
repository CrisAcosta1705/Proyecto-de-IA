"""Evalua fotos privadas contra la API sin escribir registros de asistencia."""

from __future__ import annotations

import argparse
from io import BytesIO
import json
from pathlib import Path
import secrets
import sys
from time import perf_counter
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from PIL import Image, ImageOps


def _multipart(image: bytes) -> tuple[bytes, str]:
    boundary = f"----face-eval-{secrets.token_hex(12)}"
    body = BytesIO()
    body.write(f"--{boundary}\r\n".encode())
    body.write(
        b'Content-Disposition: form-data; name="image"; filename="eval.jpg"\r\n'
    )
    body.write(b"Content-Type: image/jpeg\r\n\r\n")
    body.write(image)
    body.write(f"\r\n--{boundary}--\r\n".encode())
    return body.getvalue(), boundary


def _request(base_url: str, api_key: str, image: bytes) -> dict[str, object]:
    body, boundary = _multipart(image)
    request = Request(
        f"{base_url.rstrip('/')}/evaluation/recognize",
        data=body,
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-API-Key": api_key,
        },
    )
    try:
        with urlopen(request, timeout=180) as response:
            return json.loads(response.read())
    except HTTPError as error:
        raise RuntimeError(error.read().decode("utf-8", errors="replace")) from error


def _mirrored_jpeg(content: bytes) -> bytes:
    with Image.open(BytesIO(content)) as source:
        image = ImageOps.exif_transpose(source).convert("RGB")
        output = BytesIO()
        ImageOps.mirror(image).save(output, format="JPEG", quality=95)
        return output.getvalue()


def _evaluate_orientation(
    base_url: str,
    api_key: str,
    root: Path,
    images: list[dict[str, object]],
    mirrored: bool,
) -> dict[str, object]:
    detected = correct_known = rejected_unknown = 0
    expected_known = expected_unknown = expected_faces = 0
    failures: list[dict[str, object]] = []
    latencies: list[float] = []
    for item in images:
        path = root / str(item["file"])
        content = path.read_bytes()
        expected = list(item["expected"])
        if mirrored:
            content = _mirrored_jpeg(content)
            expected.reverse()
        expected_faces += len(expected)
        expected_known += sum(value is not None for value in expected)
        expected_unknown += sum(value is None for value in expected)

        started = perf_counter()
        result = _request(base_url, api_key, content)
        latencies.append(perf_counter() - started)
        faces = sorted(result["faces"], key=lambda face: face["box"]["x1"])
        detected += min(len(faces), len(expected))
        actual = [face["student_id"] if face["recognized"] else None for face in faces]
        for position, expected_id in enumerate(expected):
            actual_id = actual[position] if position < len(actual) else "MISSING"
            if expected_id is None:
                rejected_unknown += int(actual_id is None)
            else:
                correct_known += int(actual_id == expected_id)
        if actual != expected:
            failures.append(
                {"file": str(item["file"]), "expected": expected, "actual": actual}
            )

    return {
        "mirrored": mirrored,
        "detected": detected,
        "expected_faces": expected_faces,
        "correct_known": correct_known,
        "expected_known": expected_known,
        "rejected_unknown": rejected_unknown,
        "expected_unknown": expected_unknown,
        "maximum_hot_latency_seconds": max(latencies, default=0.0),
        "failures": failures,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--api-key", required=True)
    args = parser.parse_args()
    manifest = json.loads((args.dataset / "manifest.json").read_text("utf-8"))
    images = manifest["images"]
    missing = [str(item["file"]) for item in images if not (args.dataset / item["file"]).is_file()]
    if missing:
        raise SystemExit("Faltan imagenes privadas: " + ", ".join(missing))

    # Calentamiento fuera de las mediciones.
    _request(args.base_url, args.api_key, (args.dataset / images[0]["file"]).read_bytes())
    reports = [
        _evaluate_orientation(args.base_url, args.api_key, args.dataset, images, False),
        _evaluate_orientation(args.base_url, args.api_key, args.dataset, images, True),
    ]
    print(json.dumps({"orientations": reports}, indent=2, ensure_ascii=False))
    passed = all(
        report["detected"] == report["expected_faces"] == 20
        and report["correct_known"] == report["expected_known"] == 17
        and report["rejected_unknown"] == report["expected_unknown"] == 3
        and report["maximum_hot_latency_seconds"] < 5.0
        and not report["failures"]
        for report in reports
    )
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
