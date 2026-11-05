import argparse
from datetime import date
import hmac
import os
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.openapi.utils import get_openapi
from pydantic import BaseModel, Field
from starlette.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
import uvicorn

from asistencia_ia.discovery import DISCOVERY_PORT, DiscoveryResponder
from asistencia_ia.recognition import InsightFaceExtractor
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
    db_path = Path(
        database_path
        or os.environ.get("ASISTENCIA_DB", "data/processed/asistencia.db")
    )
    threshold = (
        similarity_threshold
        if similarity_threshold is not None
        else float(os.environ.get("FACE_SIMILARITY_THRESHOLD", "0.45"))
    )
    minimum_score_margin = float(
        os.environ.get("FACE_MINIMUM_SCORE_MARGIN", "0.07")
    )
    database = AttendanceDatabase(db_path)
    database.initialize()
    detection_size = int(os.environ.get("INSIGHTFACE_DETECTION_SIZE", "1024"))
    if not 320 <= detection_size <= 2048:
        raise ValueError("INSIGHTFACE_DETECTION_SIZE debe estar entre 320 y 2048.")
    face_extractor = extractor or InsightFaceExtractor(
        model_name=os.environ.get("INSIGHTFACE_MODEL", "buffalo_l"),
        model_root=Path(os.environ.get("INSIGHTFACE_ROOT", "models/insightface")),
        detection_size=(detection_size, detection_size),
    )
    service = AttendanceService(
        database,
        face_extractor,
        threshold,
        minimum_score_margin,
    )
    configured_api_key = (
        api_key if api_key is not None else os.environ.get("ASISTENCIA_API_KEY", "")
    ).strip()
    if os.environ.get("RAILWAY_PROJECT_ID") and not configured_api_key:
        raise RuntimeError(
            "ASISTENCIA_API_KEY debe estar configurada para ejecutar la API en Railway."
        )

    app = FastAPI(
        title="Asistencia estudiantil con IA",
        version="0.7.0",
        description="API para registro facial y asistencia sin duplicados.",
    )
    app.state.database = database
    app.state.extractor = face_extractor
    app.state.service = service

    public_paths = {"/health", "/docs", "/openapi.json", "/redoc"}

    @app.middleware("http")
    async def require_api_key(request: Request, call_next):
        if request.url.path in public_paths or not configured_api_key:
            return await call_next(request)
        received_api_key = request.headers.get("X-API-Key", "")
        if not hmac.compare_digest(received_api_key, configured_api_key):
            return JSONResponse(
                status_code=401,
                content={"detail": "La clave de acceso de la API no es valida."},
            )
        return await call_next(request)

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
            "database": str(db_path),
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
        images: list[UploadFile] = File(...),
    ) -> dict[str, object]:
        try:
            contents = [await _read_image(image) for image in images]
            mime_type = images[0].content_type or "image/jpeg"
            added = await run_in_threadpool(
                service.register_student,
                student_id,
                name,
                contents,
                replace_embeddings,
                mime_type,
                sex,
            )
            return {
                "student_id": student_id.strip(),
                "name": name.strip(),
                "sex": sex.strip().upper(),
                "embeddings_added": added,
                "photo_stored": True,
            }
        except FaceProcessingError as error:
            raise HTTPException(422, str(error)) from error

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
