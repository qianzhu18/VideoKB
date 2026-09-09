# dovideo 生产镜像:Python 3.12 + ffmpeg + tesseract(中英文)
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        tesseract-ocr-eng \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .

# 媒体与数据库落盘目录(挂卷)
RUN mkdir -p /data/media
ENV DOVIDEO_MEDIA_DIR=/data/media DOVIDEO_DB_PATH=/data/dovideo.db

EXPOSE 9101
HEALTHCHECK --interval=30s --timeout=5s --retries=5 \
    CMD curl -fsS http://127.0.0.1:9101/health || exit 1

CMD ["uvicorn", "dovideo.api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "9101", "--workers", "1"]
