FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    LOKY_MAX_CPU_COUNT=2

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY ml/requirements.txt ml/requirements.txt
RUN pip install -r ml/requirements.txt

COPY ml ml

EXPOSE 8000

CMD ["uvicorn", "ml.src.serve_model:app", "--host", "0.0.0.0", "--port", "8000"]
