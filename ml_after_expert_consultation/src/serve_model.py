from __future__ import annotations

import json
import os
import threading
import warnings
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI
from fastapi import HTTPException
from pydantic import BaseModel

ARTIFACT_DIR = Path(__file__).resolve().parents[1] / "artifacts"
MODEL_PATH = ARTIFACT_DIR / "risk_model.joblib"
SCHEMA_PATH = ARTIFACT_DIR / "feature_schema.json"
TOP_FEATURES_PATH = ARTIFACT_DIR / "top_features.json"
SHAP_SUMMARY_PATH = ARTIFACT_DIR / "shap_summary.json"

os.environ.setdefault("LOKY_MAX_CPU_COUNT", "4")
warnings.filterwarnings("ignore", message="X does not have valid feature names.*")
warnings.filterwarnings("ignore", message="LightGBM binary classifier.*")
warnings.filterwarnings("ignore", message="Could not find the number of physical cores.*")

app = FastAPI(title="Youth Depression-Related Risk Reference Model API")

DEFAULT_EMBEDDING_MODEL = os.getenv("SEMANTIC_CACHE_EMBEDDING_MODEL", "intfloat/multilingual-e5-small")
PRELOAD_EMBEDDING = os.getenv("SEMANTIC_CACHE_PRELOAD_EMBEDDING", "false").lower() == "true"
_embedding_model: Any | None = None
_embedding_model_name: str | None = None
_embedding_lock = threading.Lock()


class PredictRequest(BaseModel):
    features: dict[str, Any]


class EmbeddingRequest(BaseModel):
    text: str
    model: str | None = None


class EmbeddingResponse(BaseModel):
    embedding: list[float]
    model: str


class FactorContribution(BaseModel):
    feature: str
    contribution: float


class GlobalImportance(BaseModel):
    feature: str
    importance: float


class PredictResponse(BaseModel):
    riskPercent: float
    topFactors: list[str]
    topContributions: list[FactorContribution]
    globalImportance: list[GlobalImportance]
    note: str


def load_embedding_model(model_name: str) -> Any:
    global _embedding_model, _embedding_model_name
    with _embedding_lock:
        if _embedding_model is not None and _embedding_model_name == model_name:
            return _embedding_model
        try:
            from sentence_transformers import SentenceTransformer

            _embedding_model = SentenceTransformer(model_name)
            _embedding_model_name = model_name
            return _embedding_model
        except Exception as error:
            raise RuntimeError(f"Embedding model is unavailable: {error}") from error


@app.on_event("startup")
def preload_embedding_model() -> None:
    if PRELOAD_EMBEDDING:
        load_embedding_model(DEFAULT_EMBEDDING_MODEL)


def load_schema() -> dict[str, Any]:
    if not SCHEMA_PATH.exists():
        return {"feature_columns": []}
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def load_top_features() -> list[str]:
    if not TOP_FEATURES_PATH.exists():
        return []
    items = json.loads(TOP_FEATURES_PATH.read_text(encoding="utf-8"))
    return [item["feature"] for item in items[:5]]


def load_global_importance() -> list[GlobalImportance]:
    if not SHAP_SUMMARY_PATH.exists():
        return []
    items = json.loads(SHAP_SUMMARY_PATH.read_text(encoding="utf-8"))
    return [
        GlobalImportance(
            feature=str(item["feature"]),
            importance=float(item.get("mean_abs_shap", item.get("importance", 0.0)))
        )
        for item in items[:20]
    ]


def local_contributions(model: Any, frame: pd.DataFrame) -> list[FactorContribution]:
    try:
        import shap

        preprocessor = model.named_steps["preprocess"]
        estimator = model.named_steps["model"]
        transformed = preprocessor.transform(frame)
        feature_names = preprocessor.get_feature_names_out()
        if hasattr(transformed, "toarray"):
            transformed = transformed.toarray()
        transformed_frame = pd.DataFrame(transformed, columns=feature_names)
        explainer = shap.TreeExplainer(estimator)
        values = explainer.shap_values(transformed_frame)
        if isinstance(values, list):
            values = values[-1]
        if getattr(values, "ndim", 0) == 3:
            values = values[:, :, -1]
        row_values = np.asarray(values)[0]
        ranked = sorted(
            [
                FactorContribution(feature=str(name), contribution=float(value))
                for name, value in zip(feature_names, row_values)
            ],
            key=lambda item: abs(item.contribution),
            reverse=True
        )
        return ranked[:20]
    except Exception:
        return []


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "modelLoaded": MODEL_PATH.exists(),
        "schemaLoaded": SCHEMA_PATH.exists(),
        "embeddingModel": _embedding_model_name,
        "embeddingPreloadEnabled": PRELOAD_EMBEDDING
    }


@app.post("/semantic-cache/embed", response_model=EmbeddingResponse)
def embed_for_semantic_cache(request: EmbeddingRequest) -> EmbeddingResponse:
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text is required.")
    model_name = (request.model or DEFAULT_EMBEDDING_MODEL).strip()
    try:
        model = load_embedding_model(model_name)
        vector = model.encode(text, normalize_embeddings=True)
        return EmbeddingResponse(
            embedding=[float(value) for value in vector.tolist()],
            model=model_name
        )
    except Exception as error:
        raise HTTPException(status_code=503, detail=str(error)) from error


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    if not MODEL_PATH.exists():
        return PredictResponse(
            riskPercent=0.0,
            topFactors=[],
            topContributions=[],
            globalImportance=[],
            note="No trained model artifact found. Train the model first."
        )

    schema = load_schema()
    feature_columns = schema.get("feature_columns", [])
    model = joblib.load(MODEL_PATH)
    row = {column: request.features.get(column) for column in feature_columns}
    frame = pd.DataFrame([row])
    probability = float(model.predict_proba(frame)[0, 1]) * 100.0

    return PredictResponse(
        riskPercent=max(0.0, min(100.0, probability)),
        topFactors=load_top_features(),
        topContributions=local_contributions(model, frame),
        globalImportance=load_global_importance(),
        note="Reference risk estimate for expert review support, not a medical diagnosis."
    )
