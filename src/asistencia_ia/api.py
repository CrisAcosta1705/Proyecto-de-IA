import argparse
from datetime import date
import hmac
import json
import os
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.openapi.utils import get_openapi
from pydantic import BaseModel, Field
from starlette.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
import uvicorn

from asistencia_ia.discovery import DISCOVERY_PORT, DiscoveryResponder
from asistencia_ia.recognition import InsightFaceExtractor, recognition_profile
from asistencia_ia.recognition.profile import (
    MINIMUM_REFERENCE_SUPPORT,
    MINIMUM_SCORE_MARGIN,
    SECOND_REFERENCE_WEIGHT,
    SIMILARITY_THRESHOLD,
    SINGLE_REFERENCE_THRESHOLD,
    MOBILE_PROFILE,
)
from asistencia_ia.security import DataCipher
from asistencia_ia.services.attendance import AttendanceService, FaceProcessingError
from asistencia_ia.storage import AttendanceDatabase


MAX_IMAGE_BYTES = 10 * 1024 * 1024


class SessionRequest(BaseModel):
    course: str = Field(min_length=2, max_length=100)
    attendance_date: date = Field(default_factory=date.today)


class StudentSexRequest(BaseModel):
    sex: str


class StudentProfileRequest(BaseModel):
    name: str = Field(min_length=2, max_length=150)
    sex: str


class MergeStudentsRequest(BaseModel):
    source_student_id: str
    canonical_student_id: str


class ReferenceQuarantineRequest(BaseModel):
    reason: str = Field(min_length=3, max_length=300)


async def _read_image(upload: UploadFile) -> bytes:
    if upload.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(415, "Solo se aceptan imagenes JPEG, PNG o WebP.")
    content = await upload.read(MAX_IMAGE_BYTES + 1)
    if len(content) > MAX_IMAGE_BYTES:
        raise HTTPException(413, "La imagen supera el limite de 10 MB.")
    return content


def create_app(
    database_path: Path | str | None = None,
    extractor=None,
    similarity_threshold: float | None = None,
    api_key: str | None = None,
) -> FastAPI:
    is_production = bool(os.environ.get("RAILWAY_PROJECT_ID"))
    configured_api_key = (
        api_key if api_key is not None else os.environ.get("ASISTENCIA_API_KEY", "")
    ).strip()
    if is_production and not configured_api_key:
        raise RuntimeError(
            "ASISTENCIA_API_KEY debe estar configurada para ejecutar la API en Railway."
        )
    if is_production and len(configured_api_key) < 32:
        raise RuntimeError("ASISTENCIA_API_KEY debe tener al menos 32 caracteres.")

    encoded_data_key = os.environ.get("ASISTENCIA_DATA_ENCRYPTION_KEY", "").strip()
    if is_production and not encoded_data_key:
        raise RuntimeError(
            "ASISTENCIA_DATA_ENCRYPTION_KEY debe estar configurada en Railway."
        )
    previous_data_keys = [
        item.strip()
        for item in os.environ.get(
            "ASISTENCIA_DATA_ENCRYPTION_KEY_PREVIOUS",
            "",
        ).split(",")
        if item.strip()
    ]
    data_cipher = (
        DataCipher.from_base64(encoded_data_key, previous_data_keys)
        if encoded_data_key
        else None
    )
    db_path = Path(
        database_path
        or os.environ.get("ASISTENCIA_DB", "data/processed/asistencia.db")
    )
    threshold = (
        similarity_threshold
        if similarity_threshold is not None
        else float(
            os.environ.get(
                "FACE_SIMILARITY_THRESHOLD",
                str(SIMILARITY_THRESHOLD),
            )
        )
    )
    minimum_score_margin = float(
        os.environ.get("FACE_MINIMUM_SCORE_MARGIN", str(MINIMUM_SCORE_MARGIN))
    )
    single_reference_threshold = float(
        os.environ.get(
            "FACE_SINGLE_REFERENCE_THRESHOLD",
            str(max(threshold, SINGLE_REFERENCE_THRESHOLD)),
        )
    )
    minimum_reference_support = float(
        os.environ.get(
            "FACE_MINIMUM_REFERENCE_SUPPORT",
            str(MINIMUM_REFERENCE_SUPPORT),
        )
    )
    second_reference_weight = float(
        os.environ.get(
            "FACE_SECOND_REFERENCE_WEIGHT",
            str(SECOND_REFERENCE_WEIGHT),
        )
    )
    database = AttendanceDatabase(db_path, data_cipher=data_cipher)
    database.initialize()
    detection_size = int(os.environ.get("INSIGHTFACE_DETECTION_SIZE", "960"))
    if not 320 <= detection_size <= 2048:
        raise ValueError("INSIGHTFACE_DETECTION_SIZE debe estar entre 320 y 2048.")
    detection_threshold = float(
        os.environ.get("INSIGHTFACE_DETECTION_THRESHOLD", "0.45")
    )
    if not 0.2 <= detection_threshold <= 0.95:
        raise ValueError(
            "INSIGHTFACE_DETECTION_THRESHOLD debe estar entre 0.2 y 0.95."
        )
    face_extractor = extractor or InsightFaceExtractor(
        model_name=os.environ.get("INSIGHTFACE_MODEL", "buffalo_l"),
        model_root=Path(os.environ.get("INSIGHTFACE_ROOT", "models/insightface")),
        detection_threshold=detection_threshold,
        detection_size=(detection_size, detection_size),
        recognition_model_path=os.environ.get("ARC_FACE_MODEL_PATH") or None,
    )
    active_recognition_profile = recognition_profile(
        similarity_threshold=threshold,
        single_reference_threshold=single_reference_threshold,
        minimum_score_margin=minimum_score_margin,
        minimum_reference_support=minimum_reference_support,
        second_reference_weight=second_reference_weight,
    )
    service = AttendanceService(
        database,
        face_extractor,
        threshold,
        minimum_score_margin,
        single_reference_threshold,
        minimum_reference_support,
        second_reference_weight,
    )
    app = FastAPI(
        title="Asistencia estudiantil con IA",
        version="0.9.0",
        description="API para registro facial y asistencia sin duplicados.",
        docs_url=None if is_production else "/docs",
        redoc_url=None if is_production else "/redoc",
    )
    app.state.database = database
    app.state.extractor = face_extractor
    app.state.service = service

    public_paths = {"/health"}
    if not is_production:
        public_paths.update({"/docs", "/openapi.json", "/redoc"})

    def add_security_headers(response: Response) -> Response:
        response.headers["Cache-Control"] = "no-store, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = (
            "camera=(), microphone=(), geolocation=()"
        )
        if is_production:
            response.headers["Strict-Transport-Security"] = (
                "max-age=31536000; includeSubDomains"
            )
            response.headers["Content-Security-Policy"] = (
                "default-src 'none'; frame-ancestors 'none'"
            )
        return response

    @app.middleware("http")
    async def require_api_key(request: Request, call_next):
        if request.url.path in public_paths or not configured_api_key:
            return add_security_headers(await call_next(request))
        received_api_key = request.headers.get("X-API-Key", "").strip()
        if not received_api_key or not hmac.compare_digest(
            received_api_key,
            configured_api_key,
        ):
            return add_security_headers(JSONResponse(
                status_code=401,
                content={"detail": "La clave de acceso de la API no es valida."},
            ))
        return add_security_headers(await call_next(request))

    def secured_openapi() -> dict[str, object]:
        if app.openapi_schema is not None:
            return app.openapi_schema
        schema = get_openapi(
            title=app.title,
            version=app.version,
            description=app.description,
            routes=app.routes,
        )
        schema.setdefault("components", {}).setdefault("securitySchemes", {})[
            "ApiKeyAuth"
        ] = {
            "type": "apiKey",
            "in": "header",
            "name": "X-API-Key",
        }
        schema["security"] = [{"ApiKeyAuth": []}]
        for operation in schema.get("paths", {}).get("/health", {}).values():
            operation["security"] = []
        app.openapi_schema = schema
        return schema

    app.openapi = secured_openapi

    @app.get("/health")
    def health() -> dict[str, object]:
        return {
            "status": "ok",
            "model_loaded": bool(getattr(face_extractor, "loaded", True)),
            "storage_encrypted": data_cipher is not None,
            "recognition_profile": active_recognition_profile.to_dict(),
            "canonical_recognition_profile": active_recognition_profile.to_dict(),
            "offline_recognition_profile": MOBILE_PROFILE,
        }

    @app.get("/students")
    def students() -> list[dict[str, object]]:
        return database.list_students()

    @app.get("/students/{student_id}/photo")
    def student_photo(student_id: str) -> Response:
        stored_photo = database.student_photo(student_id.strip())
        if stored_photo is None:
            raise HTTPException(404, "El estudiante no tiene una fotografia registrada.")
        content, media_type = stored_photo
        return Response(
            content=content,
            media_type=media_type,
            headers={"Cache-Control": "no-store"},
        )

    @app.get("/students/{student_id}/references")
    def student_references(student_id: str) -> list[dict[str, object]]:
        return database.list_reference_captures(student_id.strip())

    @app.get("/students/{student_id}/references/{capture_id}/{variant}")
    def student_reference_photo(
        student_id: str,
        capture_id: str,
        variant: str,
    ) -> Response:
        if variant.upper() not in {"ORIGINAL", "MIRRORED"}:
            raise HTTPException(422, "La variante debe ser ORIGINAL o MIRRORED.")
        stored = database.reference_photo(
            student_id.strip(),
            capture_id,
            variant,
        )
        if stored is None:
            raise HTTPException(404, "La referencia facial no existe.")
        content, media_type = stored
        return Response(
            content=content,
            media_type=media_type,
            headers={"Cache-Control": "no-store"},
        )

    @app.post("/students/{student_id}/references/{capture_id}/quarantine")
    def quarantine_reference(
        student_id: str,
        capture_id: str,
        request: ReferenceQuarantineRequest,
    ) -> dict[str, object]:
        captures = database.list_reference_captures(student_id.strip())
        if not any(item["capture_id"] == capture_id for item in captures):
            raise HTTPException(404, "La referencia facial no existe.")
        database.quarantine_capture(capture_id, request.reason.strip())
        return {"capture_id": capture_id, "active": False}

    @app.post("/students/{student_id}/references/{capture_id}/restore")
    def restore_reference(student_id: str, capture_id: str) -> dict[str, object]:
        captures = database.list_reference_captures(student_id.strip())
        if not any(item["capture_id"] == capture_id for item in captures):
            raise HTTPException(404, "La referencia facial no existe.")
        database.restore_capture(capture_id)
        return {"capture_id": capture_id, "active": True}

    @app.patch("/students/{student_id}/sex")
    def update_student_sex(
        student_id: str,
        request: StudentSexRequest,
    ) -> dict[str, object]:
        sex = request.sex.strip().upper()
        if sex not in {"MASCULINO", "FEMENINO"}:
            raise HTTPException(422, "El sexo debe ser MASCULINO o FEMENINO.")
        name = database.update_student_sex(student_id.strip(), sex)
        if name is None:
            raise HTTPException(404, "El estudiante no existe.")
        return {"student_id": student_id.strip(), "name": name, "sex": sex}

    @app.patch("/students/{student_id}")
    def update_student_profile(
        student_id: str,
        request: StudentProfileRequest,
    ) -> dict[str, str]:
        clean_id = student_id.strip()
        name = request.name.strip()
        sex = request.sex.strip().upper()
        if len(name) < 2:
            raise HTTPException(422, "El nombre debe contener al menos 2 caracteres.")
        if sex not in {"MASCULINO", "FEMENINO"}:
            raise HTTPException(422, "El sexo debe ser MASCULINO o FEMENINO.")
        conflict = database.student_identity_conflict(
            clean_id,
            name,
            existing_student_id=clean_id,
        )
        if conflict == "name":
            raise HTTPException(422, "Ya existe un estudiante con ese nombre.")
        if conflict == "carnet":
            raise HTTPException(422, "Ya existe un estudiante con ese carnet.")
        profile = database.update_student_profile(clean_id, name, sex)
        if profile is None:
            raise HTTPException(404, "El estudiante no existe.")
        return profile

    @app.delete("/students/{student_id}/recognition")
    def delete_student_recognition(student_id: str) -> dict[str, object]:
        name = database.clear_student_recognition(student_id.strip())
        if name is None:
            raise HTTPException(404, "El estudiante no existe.")
        return {
            "student_id": student_id.strip(),
            "name": name,
            "recognition_deleted": True,
        }

    @app.delete("/students/{student_id}")
    def delete_student(student_id: str) -> dict[str, object]:
        clean_id = student_id.strip()
        name = database.delete_student(clean_id)
        if name is None:
            raise HTTPException(404, "El estudiante no existe.")
        return {
            "student_id": clean_id,
            "name": name,
            "deleted": True,
        }

    @app.post("/students/register")
    async def register_student(
        student_id: str = Form(...),
        name: str = Form(...),
        sex: str = Form("NO_ESPECIFICADO"),
        replace_embeddings: bool = Form(True),
        allow_existing_student: bool = Form(False),
        reference_metadata: str | None = Form(None),
        images: list[UploadFile] = File(...),
    ) -> dict[str, object]:
        try:
            contents = [await _read_image(image) for image in images]
            mime_type = images[0].content_type or "image/jpeg"
            parsed_metadata: list[dict[str, object]] | None = None
            if reference_metadata:
                try:
                    decoded = json.loads(reference_metadata)
                except json.JSONDecodeError as error:
                    raise FaceProcessingError(
                        "Los metadatos de referencias no contienen JSON valido."
                    ) from error
                if not isinstance(decoded, list) or not all(
                    isinstance(item, dict) for item in decoded
                ):
                    raise FaceProcessingError(
                        "Los metadatos de referencias deben ser una lista JSON."
                    )
                parsed_metadata = decoded
            added = await run_in_threadpool(
                service.register_student,
                student_id,
                name,
                contents,
                replace_embeddings,
                mime_type,
                sex,
                allow_existing_student,
                parsed_metadata,
            )
            return {
                "student_id": student_id.strip(),
                "name": name.strip(),
                "sex": sex.strip().upper(),
                "embeddings_added": added,
                "embedding_variants_added": added * 2,
                "photo_stored": True,
            }
        except FaceProcessingError as error:
            raise HTTPException(422, str(error)) from error

    @app.post("/maintenance/rebuild-references")
    def rebuild_references() -> dict[str, object]:
        rebuilt: list[str] = []
        failed: dict[str, str] = {}
        for student in database.list_students():
            student_id = str(student["student_id"])
            stored = database.student_photo(student_id)
            if stored is None:
                failed[student_id] = "sin fotografia"
                continue
            photo, mime_type = stored
            try:
                service.register_student(
                    student_id,
                    str(student["name"]),
                    [photo],
                    replace_embeddings=True,
                    photo_mime_type=mime_type,
                    sex=str(student["sex"]),
                    allow_existing_student=True,
                    reference_metadata=[
                        {
                            "source_camera": "SERVER_MIGRATION",
                            "pose": "FRONT",
                        }
                    ],
                )
                rebuilt.append(student_id)
            except (FaceProcessingError, ValueError) as error:
                failed[student_id] = str(error)
        return {"rebuilt": rebuilt, "failed": failed}

    @app.post("/maintenance/merge-students")
    def merge_students(request: MergeStudentsRequest) -> dict[str, object]:
        merged = database.merge_students(
            request.source_student_id.strip(),
            request.canonical_student_id.strip(),
        )
        if not merged:
            raise HTTPException(404, "No fue posible fusionar los estudiantes.")
        return {
            "merged": True,
            "source_student_id": request.source_student_id.strip(),
            "canonical_student_id": request.canonical_student_id.strip(),
        }

    @app.post("/maintenance/audit-references")
    def audit_references() -> dict[str, object]:
        quarantined = service.audit_reference_outliers()
        return {
            "quarantined": quarantined,
            "quarantined_count": len(quarantined),
        }

    @app.post("/sessions")
    def create_session(request: SessionRequest) -> dict[str, object]:
        try:
            selected_date = request.attendance_date.isoformat()
            session_id = service.create_session(
                request.course,
                selected_date,
            )
            return {
                "session_id": session_id,
                "course": request.course.strip(),
                "attendance_date": selected_date,
            }
        except ValueError as error:
            raise HTTPException(422, str(error)) from error

    @app.get("/attendance/status")
    def attendance_status(
        course: str,
        attendance_date: date,
    ) -> dict[str, object]:
        return service.attendance_status(course, attendance_date.isoformat())

    @app.get("/attendance/report")
    def attendance_report(
        course: str,
        attendance_date: date,
    ) -> dict[str, object]:
        return service.attendance_report(course, attendance_date.isoformat())

    @app.get("/attendance/summaries")
    def attendance_summaries() -> list[dict[str, object]]:
        return database.attendance_summaries()

    @app.post("/sessions/{session_id}/recognize")
    async def recognize(
        session_id: int,
        image: UploadFile = File(...),
    ) -> dict[str, object]:
        try:
            content = await _read_image(image)
            return await run_in_threadpool(service.recognize, session_id, content)
        except LookupError as error:
            raise HTTPException(404, str(error)) from error
        except (FaceProcessingError, ValueError) as error:
            raise HTTPException(422, str(error)) from error

    @app.post("/evaluation/recognize")
    async def evaluate_recognition(image: UploadFile = File(...)) -> dict[str, object]:
        try:
            content = await _read_image(image)
            return await run_in_threadpool(service.analyze, content)
        except (FaceProcessingError, ValueError) as error:
            raise HTTPException(422, str(error)) from error

    @app.get("/sessions/{session_id}/attendance")
    def attendance(session_id: int) -> dict[str, object]:
        session = database.get_session(session_id)
        if session is None:
            raise HTTPException(404, "La sesion de asistencia no existe.")
        records = database.attendance(session_id)
        return {"session": session, "attendance": records, "count": len(records)}

    @app.post("/sessions/{session_id}/finish")
    def finish_session(session_id: int) -> dict[str, object]:
        if not database.finish_session(session_id):
            raise HTTPException(404, "La sesion de asistencia no existe.")
        return {"session_id": session_id, "finished": True}

    return app


app = create_app()


def main() -> None:
    parser = argparse.ArgumentParser(description="Servidor local de asistencia con IA.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument(
        "--discovery-port",
        type=int,
        default=DISCOVERY_PORT,
        help="Puerto UDP utilizado por la aplicacion para descubrir la API.",
    )
    args = parser.parse_args()
    discovery = DiscoveryResponder(args.port, args.discovery_port)
    try:
        discovery.start()
        print(
            f"Descubrimiento automatico disponible en UDP {discovery.discovery_port}.",
            flush=True,
        )
        uvicorn.run(app, host=args.host, port=args.port)
    finally:
        discovery.stop()


if __name__ == "__main__":
    main()
