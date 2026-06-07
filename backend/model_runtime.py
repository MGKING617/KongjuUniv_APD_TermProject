from __future__ import annotations

import json
import math
import os
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_PATH = ROOT / "models" / "model_bundle.json"


class ModelUnavailable(RuntimeError):
    pass


def normalize_category(value: Any) -> str:
    if value is None:
        return "MISSING"
    if isinstance(value, str):
        value = value.strip()
        if not value:
            return "MISSING"
        try:
            parsed = float(value)
            if parsed.is_integer():
                return str(int(parsed))
        except ValueError:
            return value
        return value
    if isinstance(value, bool):
        return str(int(value))
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def sigmoid(value: float) -> float:
    value = max(min(value, 35), -35)
    return 1.0 / (1.0 + math.exp(-value))


def load_model(path: str | os.PathLike[str] | None = None) -> dict[str, Any]:
    model_path = Path(path or os.getenv("MODEL_BUNDLE_PATH") or DEFAULT_MODEL_PATH)
    if not model_path.is_absolute():
        model_path = ROOT / model_path
    if not model_path.exists():
        raise ModelUnavailable(f"모델 파일이 없습니다: {model_path}")
    with model_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def clean_numeric(value: Any, missing_codes: list[int | float]) -> tuple[float | None, bool]:
    if value is None or value == "":
        return None, True
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None, True
    if any(number == float(code) for code in missing_codes):
        return None, True
    if not math.isfinite(number):
        return None, True
    return number, False


def vectorize(payload: dict[str, Any], bundle: dict[str, Any]) -> tuple[list[float], list[dict[str, Any]]]:
    metadata = bundle["metadata"]
    schema = bundle["schema"]
    values_by_encoded: dict[str, float] = {}
    raw_values = payload.get("features") or payload

    for col in schema["numeric_features"]:
        stats = metadata["numeric_stats"][col]
        missing_codes = schema.get("missing_numeric_codes", {}).get(col, [])
        number, missing = clean_numeric(raw_values.get(col), missing_codes)
        if missing:
            number = stats["median"]
        values_by_encoded[col] = (number - stats["median"]) / stats["scale"]
        values_by_encoded[f"{col}__missing"] = 1.0 if missing else 0.0

    for col in schema["categorical_features"]:
        levels = metadata["category_levels"][col]
        value = normalize_category(raw_values.get(col))
        if value not in levels:
            value = "OTHER"
        for level in levels:
            values_by_encoded[f"{col}={level}"] = 1.0 if value == level else 0.0

    vector: list[float] = []
    columns = metadata["design_columns"]
    for col in columns:
        vector.append(float(values_by_encoded.get(col["encoded"], 0.0)))
    return vector, columns


def classify_probability(probability: float, bundle: dict[str, Any]) -> str:
    threshold = float(bundle.get("decision_threshold", 0.5))
    prevalence = float(bundle.get("target_prevalence", 0.06))
    caution = max(prevalence, threshold * 0.6)
    if probability >= threshold:
        return "높음"
    if probability >= caution:
        return "주의"
    return "낮음"


def score_phq(phq_items: Any) -> dict[str, Any] | None:
    if not isinstance(phq_items, list) or len(phq_items) != 9:
        return None
    try:
        values = [int(v) for v in phq_items]
    except (TypeError, ValueError):
        return None
    if any(v < 0 or v > 3 for v in values):
        return None
    total = sum(values)
    if total >= 20:
        band = "심한 수준"
    elif total >= 15:
        band = "중등도 이상"
    elif total >= 10:
        band = "중등도"
    elif total >= 5:
        band = "가벼운 수준"
    else:
        band = "낮음"
    return {
        "score": total,
        "band": band,
        "risk_flag": total >= 10,
        "note": "PHQ-9 점수는 선별 참고용이며 의료적 진단이 아닙니다.",
    }


def contribution_summary(
    vector: list[float], bundle: dict[str, Any], columns: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    weights = bundle["weights"]
    grouped: dict[str, dict[str, Any]] = {}
    for value, weight, col in zip(vector, weights, columns):
        effect = float(value) * float(weight)
        if abs(effect) < 1e-10:
            continue
        source = col["source"]
        if source not in grouped:
            grouped[source] = {
                "feature": source,
                "label": col.get("label", source),
                "effect": 0.0,
                "details": [],
            }
        grouped[source]["effect"] += effect
        if col["kind"] == "category" and value:
            grouped[source]["details"].append(str(col.get("value")))
        elif col["kind"] == "numeric":
            grouped[source]["details"].append("연속값")
        elif col["kind"] == "numeric_missing" and value:
            grouped[source]["details"].append("결측")

    items = list(grouped.values())
    items.sort(key=lambda item: abs(item["effect"]), reverse=True)
    top = []
    for item in items[:8]:
        effect = item["effect"]
        top.append(
            {
                "feature": item["feature"],
                "label": item["label"],
                "direction": "위험 증가 쪽" if effect > 0 else "위험 감소 쪽",
                "strength": abs(effect),
                "raw_effect": effect,
                "details": ", ".join(item["details"][:3]),
            }
        )
    return top


def predict(payload: dict[str, Any]) -> dict[str, Any]:
    bundle = load_model()
    vector, columns = vectorize(payload, bundle)
    logit = float(bundle["intercept"]) + sum(
        float(v) * float(w) for v, w in zip(vector, bundle["weights"])
    )
    probability = sigmoid(logit)
    phq = score_phq(payload.get("phq_items"))

    return {
        "risk_probability": probability,
        "risk_percent": round(probability * 100, 1),
        "risk_level": classify_probability(probability, bundle),
        "decision_threshold": bundle.get("decision_threshold"),
        "target_definition": bundle["target"]["label"],
        "model_type": bundle["model_type"],
        "model_version": bundle["model_version"],
        "training_rows": bundle["training_rows"],
        "target_prevalence_percent": round(bundle["target_prevalence"] * 100, 1),
        "phq": phq,
        "contributions": contribution_summary(vector, bundle, columns),
        "disclaimer": "이 결과는 우울증 진단이 아니라 의사, 상담자, 보호자가 참고할 수 있는 조기탐지 보조자료입니다.",
    }


def model_metadata() -> dict[str, Any]:
    bundle = load_model()
    return {
        "available": True,
        "model_type": bundle["model_type"],
        "model_version": bundle["model_version"],
        "training_rows": bundle["training_rows"],
        "positive_rows": bundle["positive_rows"],
        "negative_rows": bundle["negative_rows"],
        "target_prevalence_percent": round(bundle["target_prevalence"] * 100, 1),
        "decision_threshold": bundle["decision_threshold"],
        "metrics": bundle.get("metrics", {}).get("validation", {}),
        "target": bundle["target"],
        "notes": bundle.get("notes", []),
    }

