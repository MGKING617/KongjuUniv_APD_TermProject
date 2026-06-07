from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "config" / "feature_schema.json"
MODEL_DIR = ROOT / "models"
DEFAULT_XLSX_CANDIDATES = [
    ROOT / "data" / "raw" / "knhanes_source.xlsx",
    Path(r"C:\Users\abmak\OneDrive\문서\카카오톡 받은 파일\0414)국건영 자료 정리_v1.xlsx"),
]


def load_schema() -> dict[str, Any]:
    with SCHEMA_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


def resolve_xlsx(path_arg: str | None) -> Path:
    if path_arg:
        path = Path(path_arg)
        if not path.exists():
            raise SystemExit(f"XLSX 파일을 찾을 수 없습니다: {path}")
        return path

    for candidate in DEFAULT_XLSX_CANDIDATES:
        if candidate.exists():
            return candidate
    raise SystemExit(
        "XLSX 파일을 찾을 수 없습니다. --xlsx 옵션으로 원본 파일 경로를 지정하세요."
    )


def normalize_category(value: Any) -> str:
    if pd.isna(value):
        return "MISSING"
    if isinstance(value, (int, np.integer)):
        return str(int(value))
    if isinstance(value, (float, np.floating)) and value.is_integer():
        return str(int(value))
    return str(value).strip() or "MISSING"


def to_numeric_feature(series: pd.Series, missing_codes: list[int | float]) -> pd.Series:
    numeric = pd.to_numeric(series, errors="coerce")
    if missing_codes:
        numeric = numeric.mask(numeric.isin(missing_codes))
    return numeric


def prepare_dataframe(path: Path, sheet: str, schema: dict[str, Any]) -> pd.DataFrame:
    df = pd.read_excel(path, sheet_name=sheet)
    if "id" in df.columns and "ID" not in df.columns:
        df = df.rename(columns={"id": "ID"})

    target_col = schema["target"]["column"]
    if target_col not in df.columns:
        raise SystemExit(f"'{target_col}' 열이 없습니다. 시트명과 원자료를 확인하세요.")

    usable = df.copy()
    usable[target_col] = pd.to_numeric(usable[target_col], errors="coerce")
    usable = usable[usable[target_col].notna()].copy()
    usable["depression_risk"] = (
        usable[target_col] >= schema["target"]["positive_cutoff"]
    ).astype(int)

    required = schema["numeric_features"] + schema["categorical_features"]
    missing = [c for c in required if c not in usable.columns]
    if missing:
        raise SystemExit(f"학습에 필요한 열이 없습니다: {', '.join(missing)}")
    return usable


def stratified_split(y: np.ndarray, test_size: float, seed: int) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    train_indices: list[int] = []
    valid_indices: list[int] = []
    for value in [0, 1]:
        indices = np.where(y == value)[0]
        rng.shuffle(indices)
        n_valid = max(1, int(round(len(indices) * test_size)))
        valid_indices.extend(indices[:n_valid].tolist())
        train_indices.extend(indices[n_valid:].tolist())
    rng.shuffle(train_indices)
    rng.shuffle(valid_indices)
    return np.array(train_indices), np.array(valid_indices)


def fit_transform(
    df: pd.DataFrame, schema: dict[str, Any]
) -> tuple[np.ndarray, dict[str, Any]]:
    feature_labels = schema.get("feature_labels", {})
    numeric_features = schema["numeric_features"]
    categorical_features = schema["categorical_features"]
    missing_numeric_codes = schema.get("missing_numeric_codes", {})

    parts: list[np.ndarray] = []
    design_columns: list[dict[str, Any]] = []
    numeric_stats: dict[str, dict[str, float]] = {}
    category_levels: dict[str, list[str]] = {}

    for col in numeric_features:
        values = to_numeric_feature(df[col], missing_numeric_codes.get(col, []))
        median = float(values.median()) if values.notna().any() else 0.0
        filled = values.fillna(median).to_numpy(dtype=float)
        std = float(np.std(filled))
        if not math.isfinite(std) or std < 1e-8:
            std = 1.0
        transformed = ((filled - median) / std).reshape(-1, 1)
        missing_indicator = values.isna().astype(float).to_numpy().reshape(-1, 1)
        parts.extend([transformed, missing_indicator])
        numeric_stats[col] = {"median": median, "scale": std}
        design_columns.append(
            {
                "encoded": col,
                "source": col,
                "kind": "numeric",
                "label": feature_labels.get(col, col),
                "center": median,
                "scale": std,
                "missing_codes": missing_numeric_codes.get(col, []),
            }
        )
        design_columns.append(
            {
                "encoded": f"{col}__missing",
                "source": col,
                "kind": "numeric_missing",
                "label": f"{feature_labels.get(col, col)} 결측",
            }
        )

    for col in categorical_features:
        normalized = df[col].map(normalize_category)
        levels = sorted(set(normalized.tolist()))
        if "OTHER" not in levels:
            levels.append("OTHER")
        category_levels[col] = levels
        one_hot = np.zeros((len(df), len(levels)), dtype=float)
        level_index = {level: i for i, level in enumerate(levels)}
        for row_idx, value in enumerate(normalized):
            one_hot[row_idx, level_index.get(value, level_index["OTHER"])] = 1.0
        parts.append(one_hot)
        for level in levels:
            design_columns.append(
                {
                    "encoded": f"{col}={level}",
                    "source": col,
                    "kind": "category",
                    "value": level,
                    "label": feature_labels.get(col, col),
                }
            )

    matrix = np.hstack(parts).astype(float)
    metadata = {
        "numeric_stats": numeric_stats,
        "category_levels": category_levels,
        "design_columns": design_columns,
    }
    return matrix, metadata


def transform_with_metadata(
    df: pd.DataFrame, schema: dict[str, Any], metadata: dict[str, Any]
) -> np.ndarray:
    parts: list[np.ndarray] = []
    missing_numeric_codes = schema.get("missing_numeric_codes", {})

    for col in schema["numeric_features"]:
        values = to_numeric_feature(df[col], missing_numeric_codes.get(col, []))
        stats = metadata["numeric_stats"][col]
        filled = values.fillna(stats["median"]).to_numpy(dtype=float)
        transformed = ((filled - stats["median"]) / stats["scale"]).reshape(-1, 1)
        missing_indicator = values.isna().astype(float).to_numpy().reshape(-1, 1)
        parts.extend([transformed, missing_indicator])

    for col in schema["categorical_features"]:
        levels = metadata["category_levels"][col]
        level_index = {level: i for i, level in enumerate(levels)}
        one_hot = np.zeros((len(df), len(levels)), dtype=float)
        normalized = df[col].map(normalize_category)
        for row_idx, value in enumerate(normalized):
            if value not in level_index:
                value = "OTHER"
            one_hot[row_idx, level_index[value]] = 1.0
        parts.append(one_hot)
    return np.hstack(parts).astype(float)


def sigmoid(values: np.ndarray) -> np.ndarray:
    values = np.clip(values, -35, 35)
    return 1.0 / (1.0 + np.exp(-values))


def fit_logistic_regression(
    x_train: np.ndarray,
    y_train: np.ndarray,
    learning_rate: float,
    l2: float,
    iterations: int,
) -> tuple[float, np.ndarray, list[float]]:
    x_bias = np.hstack([np.ones((x_train.shape[0], 1)), x_train])
    weights = np.zeros(x_bias.shape[1], dtype=float)
    losses: list[float] = []
    n = float(len(y_train))

    for iteration in range(iterations):
        predictions = sigmoid(x_bias @ weights)
        error = predictions - y_train
        gradient = (x_bias.T @ error) / n
        gradient[1:] += l2 * weights[1:]
        weights -= learning_rate * gradient

        if iteration % 50 == 0 or iteration == iterations - 1:
            eps = 1e-12
            log_loss = -np.mean(
                y_train * np.log(predictions + eps)
                + (1 - y_train) * np.log(1 - predictions + eps)
            )
            penalty = 0.5 * l2 * float(np.sum(weights[1:] ** 2))
            losses.append(float(log_loss + penalty))

    return float(weights[0]), weights[1:], losses


def roc_auc(y_true: np.ndarray, scores: np.ndarray) -> float:
    y_true = y_true.astype(int)
    positives = int(y_true.sum())
    negatives = int(len(y_true) - positives)
    if positives == 0 or negatives == 0:
        return float("nan")

    order = np.argsort(scores)
    ranks = np.empty_like(order, dtype=float)
    ranks[order] = np.arange(1, len(scores) + 1)
    pos_rank_sum = float(ranks[y_true == 1].sum())
    return (pos_rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


def metrics_at_threshold(
    y_true: np.ndarray, scores: np.ndarray, threshold: float
) -> dict[str, float]:
    pred = (scores >= threshold).astype(int)
    tp = int(((pred == 1) & (y_true == 1)).sum())
    fp = int(((pred == 1) & (y_true == 0)).sum())
    tn = int(((pred == 0) & (y_true == 0)).sum())
    fn = int(((pred == 0) & (y_true == 1)).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    specificity = tn / (tn + fp) if tn + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    accuracy = (tp + tn) / len(y_true) if len(y_true) else 0.0
    return {
        "threshold": float(threshold),
        "accuracy": float(accuracy),
        "precision": float(precision),
        "recall": float(recall),
        "specificity": float(specificity),
        "f1": float(f1),
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
    }


def best_f1_threshold(y_true: np.ndarray, scores: np.ndarray) -> tuple[float, dict[str, float]]:
    candidates = np.unique(np.quantile(scores, np.linspace(0.01, 0.99, 250)))
    best_threshold = 0.5
    best_metrics = metrics_at_threshold(y_true, scores, best_threshold)
    for threshold in candidates:
        current = metrics_at_threshold(y_true, scores, float(threshold))
        if current["f1"] > best_metrics["f1"]:
            best_threshold = float(threshold)
            best_metrics = current
    return best_threshold, best_metrics


def build_version(path: Path, rows: int, positives: int) -> str:
    payload = f"{path.resolve()}|{path.stat().st_mtime_ns}|{rows}|{positives}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:12]


def train(args: argparse.Namespace) -> None:
    schema = load_schema()
    xlsx_path = resolve_xlsx(args.xlsx)
    df = prepare_dataframe(xlsx_path, args.sheet, schema)

    y = df["depression_risk"].to_numpy(dtype=int)
    train_idx, valid_idx = stratified_split(y, args.test_size, args.seed)
    train_df = df.iloc[train_idx].reset_index(drop=True)
    valid_df = df.iloc[valid_idx].reset_index(drop=True)
    y_train = y[train_idx]
    y_valid = y[valid_idx]

    x_train, metadata = fit_transform(train_df, schema)
    x_valid = transform_with_metadata(valid_df, schema, metadata)
    intercept, weights, losses = fit_logistic_regression(
        x_train,
        y_train,
        learning_rate=args.learning_rate,
        l2=args.l2,
        iterations=args.iterations,
    )

    train_scores = sigmoid(intercept + x_train @ weights)
    valid_scores = sigmoid(intercept + x_valid @ weights)
    threshold, threshold_metrics = best_f1_threshold(y_valid, valid_scores)

    all_metrics = {
        "train": {
            "roc_auc": roc_auc(y_train, train_scores),
            "at_0_5": metrics_at_threshold(y_train, train_scores, 0.5),
        },
        "validation": {
            "roc_auc": roc_auc(y_valid, valid_scores),
            "at_0_5": metrics_at_threshold(y_valid, valid_scores, 0.5),
            "best_f1": threshold_metrics,
        },
        "loss_trace": losses,
    }

    rows = int(len(df))
    positives = int(y.sum())
    bundle = {
        "model_type": "numpy_logistic_regression",
        "model_version": build_version(xlsx_path, rows, positives),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "source_file": str(xlsx_path.resolve()),
        "source_sheet": args.sheet,
        "target": schema["target"],
        "training_rows": rows,
        "positive_rows": positives,
        "negative_rows": int(rows - positives),
        "target_prevalence": float(positives / rows),
        "decision_threshold": threshold,
        "leakage_excluded": schema["leakage_columns"],
        "schema": schema,
        "metadata": metadata,
        "intercept": intercept,
        "weights": weights.tolist(),
        "metrics": all_metrics,
        "notes": [
            "이 모델은 시연용 기준 모델입니다.",
            "PHQ-9 문항과 점수는 입력 변수에서 제외해 라벨 누수를 방지했습니다.",
            "의료적 진단이 아니라 조기탐지 참고용 위험도 산출입니다."
        ],
    }

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    model_path = MODEL_DIR / "model_bundle.json"
    report_path = MODEL_DIR / "model_report.json"
    report_md_path = MODEL_DIR / "model_report.md"
    with model_path.open("w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, indent=2)
    with report_path.open("w", encoding="utf-8") as f:
        json.dump(
            {
                "model_version": bundle["model_version"],
                "rows": rows,
                "positive_rows": positives,
                "negative_rows": int(rows - positives),
                "target_prevalence": bundle["target_prevalence"],
                "decision_threshold": threshold,
                "metrics": all_metrics,
                "source_file": bundle["source_file"],
                "source_sheet": args.sheet,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    report_md_path.write_text(
        "\n".join(
            [
                "# 모델 학습 리포트",
                "",
                f"- 버전: `{bundle['model_version']}`",
                f"- 원본: `{bundle['source_file']}` / `{args.sheet}`",
                f"- 학습 대상 행: `{rows}`",
                f"- 우울 위험군(PHQ-9 >= 10): `{positives}`",
                f"- 비위험군: `{rows - positives}`",
                f"- 기준 유병률: `{bundle['target_prevalence']:.4f}`",
                f"- 검증 ROC-AUC: `{all_metrics['validation']['roc_auc']:.4f}`",
                f"- 사용 임계값(best F1): `{threshold:.4f}`",
                f"- 검증 F1: `{threshold_metrics['f1']:.4f}`",
                f"- 검증 재현율: `{threshold_metrics['recall']:.4f}`",
                f"- 검증 정밀도: `{threshold_metrics['precision']:.4f}`",
                "",
                "주의: 이 결과는 진단이 아니라 시연/보조자료용 위험도 모델입니다.",
            ]
        ),
        encoding="utf-8",
    )
    print(f"saved: {model_path}")
    print(f"saved: {report_path}")
    print(f"saved: {report_md_path}")
    print(json.dumps(report_path.read_text(encoding="utf-8"), ensure_ascii=False)[:500])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train youth depression-risk demo model.")
    parser.add_argument("--xlsx", help="국건영 XLSX 원자료 경로")
    parser.add_argument("--sheet", default="최종분석용", help="학습에 사용할 시트명")
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--learning-rate", type=float, default=0.08)
    parser.add_argument("--l2", type=float, default=0.01)
    parser.add_argument("--iterations", type=int, default=1400)
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())

