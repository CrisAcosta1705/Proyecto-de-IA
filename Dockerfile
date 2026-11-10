FROM python:3.12-slim AS face-models

COPY scripts/fetch_insightface_models.py /tmp/fetch_insightface_models.py
RUN python /tmp/fetch_insightface_models.py /models/insightface/models/buffalo_l

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    ARC_FACE_MODEL_PATH=/app/models/insightface/models/buffalo_l/w600k_r50.onnx \
    ASISTENCIA_DB=/var/data/asistencia.db \
    INSIGHTFACE_ROOT=/app/models/insightface \
    INSIGHTFACE_MODEL=buffalo_l \
    INSIGHTFACE_DETECTION_SIZE=960 \
    INSIGHTFACE_DETECTION_THRESHOLD=0.45

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        build-essential \
        cmake \
        gosu \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml README.md requirements-server.txt ./
COPY src ./src
COPY --from=face-models /models ./models

RUN python -m pip install --upgrade pip \
    && python -m pip install --requirement requirements-server.txt \
    && python -m pip install --no-deps .

RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /var/data \
    && chown -R appuser:appuser /var/data

EXPOSE 10000

CMD ["sh", "-c", "umask 077; chown -R appuser:appuser /var/data; exec gosu appuser uvicorn asistencia_ia.api:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1"]
