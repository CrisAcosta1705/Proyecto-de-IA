import argparse
import csv
from datetime import datetime
from pathlib import Path

import cv2

from asistencia_ia.detection import YoloPersonDetector
from asistencia_ia.domain import PersonDetection
from asistencia_ia.pipeline import ClassroomPipeline


def parse_args():
    parser = argparse.ArgumentParser(
        description="Conteo de personas con YOLO usando webcam, video local o stream de red."
    )
    parser.add_argument(
        "--source",
        choices=("webcam", "video", "stream"),
        default="webcam",
        help="Fuente de video a usar.",
    )
    parser.add_argument(
        "--camera-index",
        type=int,
        default=0,
        help="Indice de la webcam local. Normalmente 0.",
    )
    parser.add_argument(
        "--video-path",
        help="Ruta de un archivo de video cuando --source video.",
    )
    parser.add_argument(
        "--stream-url",
        help="URL de un stream de video, por ejemplo una camara IP o fuente HTTP.",
    )
    parser.add_argument(
        "--model",
        default="yolov8n.pt",
        help="Modelo YOLO a usar.",
    )
    parser.add_argument(
        "--confidence",
        type=float,
        default=0.5,
        help="Confianza minima para contar una deteccion como persona.",
    )
    parser.add_argument(
        "--csv",
        help="Archivo CSV opcional para guardar conteos por frame.",
    )
    return parser.parse_args()


def get_capture_source(args):
    if args.source == "webcam":
        return args.camera_index

    if args.source == "video":
        if not args.video_path:
            raise ValueError("Debes indicar --video-path cuando usas --source video.")
        return args.video_path

    if args.source == "stream":
        if not args.stream_url:
            raise ValueError("Debes indicar --stream-url cuando usas --source stream.")
        return args.stream_url

    raise ValueError(f"Fuente no soportada: {args.source}")


def open_csv_writer(csv_path):
    if not csv_path:
        return None, None

    path = Path(csv_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    file = path.open("w", newline="", encoding="utf-8")
    writer = csv.writer(file)
    writer.writerow(["timestamp", "frame", "person_count"])
    return file, writer


def draw_detection(frame, detection: PersonDetection):
    box = detection.box
    x1, y1, x2, y2 = box.x1, box.y1, box.x2, box.y2
    cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 190, 80), 2)
    cv2.putText(
        frame,
        f"Persona {detection.confidence:.2f}",
        (x1, max(y1 - 8, 20)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        (0, 190, 80),
        2,
    )


def main():
    args = parse_args()
    capture_source = get_capture_source(args)

    pipeline = ClassroomPipeline(
        YoloPersonDetector(args.model, min_confidence=args.confidence)
    )
    cap = cv2.VideoCapture(capture_source)

    if not cap.isOpened():
        raise RuntimeError(
            "No se pudo abrir la fuente de video. "
            "Revisa la webcam, el archivo de video o la URL del stream."
        )

    csv_file, csv_writer = open_csv_writer(args.csv)
    frame_number = 0

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break

            frame_number += 1
            analysis = pipeline.analyze(frame)
            person_count = analysis.person_count

            for detection in analysis.person_detections:
                draw_detection(frame, detection)

            cv2.putText(
                frame,
                f"Personas: {person_count}",
                (20, 42),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.1,
                (0, 0, 255),
                3,
            )

            if csv_writer:
                csv_writer.writerow(
                    [datetime.now().isoformat(timespec="seconds"), frame_number, person_count]
                )

            cv2.imshow("Conteo de personas", frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
    finally:
        cap.release()
        if csv_file:
            csv_file.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
