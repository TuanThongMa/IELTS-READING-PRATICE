FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgl1 libglib2.0-0 \
    && pip install --no-cache-dir torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir "opendataloader-pdf[hybrid]==2.5.17" \
    && rm -rf /var/lib/apt/lists/*

ENV EASYOCR_MODULE_PATH=/models/easyocr \
    XDG_CACHE_HOME=/models/cache

EXPOSE 5002
HEALTHCHECK --interval=15s --timeout=5s --retries=20 --start-period=180s \
    CMD curl -fsS http://localhost:5002/health || exit 1

CMD ["opendataloader-pdf-hybrid", "--host", "0.0.0.0", "--port", "5002", "--force-ocr", "--ocr-lang", "en"]
