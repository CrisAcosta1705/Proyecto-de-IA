FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    ASISTENCIA_DB=/var/data/asistencia.db \
    INSIGHTFACE_ROOT=/var/data/models/insightface \
    INSIGHTFACE_MODEL=buffalo_sc

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

RUN python -m pip install --upgrade pip \
    && python -m pip install --requirement requirements-server.txt \
    && python -m pip install --no-deps .

RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /var/data/models/insightface \
    && chown -R appuser:appuser /var/data

EXPOSE 10000

CMD ["sh", "-c", "umask 077; mkdir -p /var/data/models/insightface; chown -R appuser:appuser /var/data; if [ \"$INSIGHTFACE_MODEL\" != \"buffalo_l\" ]; then rm -rf /var/data/models/insightface/models/buffalo_l /var/data/models/insightface/models/buffalo_l.zip; fi; exec gosu appuser uvicorn asistencia_ia.api:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1"]
