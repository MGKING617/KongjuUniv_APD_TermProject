from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.metrics import (
    average_precision_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_curve,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

TARGET_COLUMN = "mh_PHQ_S"
DEFAULT_SHEET = "최종분석용"
DEFAULT_DECISION_THRESHOLD = 0.59
PHQ_COLUMNS = [f"BP_PHQ_{i}" for i in range(1, 10)]
ID_COLUMNS = {"ID", "id", "mod_d"}
MISSING_CODES = {8, 9, 88, 99, 888, 999}
DEFAULT_EXCLUDED_FEATURE_COLUMNS = {
    "source_dataset",
    "year",
    "monthly_household_income",
    "depressive_mood_experience",
    "mental_counseling_experience",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a youth depression-related risk reference model.")
    parser.add_argument("--input", required=True, help="XLSX/CSV file path. Use the personal-level raw data file.")
    parser.add_argument("--sheet", default=DEFAULT_SHEET, help="Excel sheet name.")
    parser.add_argument("--output-dir", default="ml_after_expert_consultation/artifacts", help="Directory for model and metadata artifacts.")
    parser.add_argument("--min-age", type=int, default=19)
    parser.add_argument("--max-age", type=int, default=39)
    parser.add_argument("--threshold", type=float, default=10.0, help="Reference label threshold for PHQ score.")
    parser.add_argument("--include-phq-items", action="store_true", help="Include BP_PHQ_1~9. Not recommended for PHQ target prediction.")
    parser.add_argument("--test-size", type=float, default=0.2, help="Held-out test split ratio.")
    parser.add_argument("--validation-size", type=float, default=0.2, help="Validation split ratio inside the train split.")
    parser.add_argument("--cv-folds", type=int, default=5, help="Stratified CV folds for robustness metrics. Use 0 to skip.")
    parser.add_argument(
        "--threshold-strategy",
        choices=["positive_f1", "target_recall"],
        default="target_recall",
        help="Decision-threshold selection strategy. target_recall favors screening sensitivity.",
    )
    parser.add_argument(
        "--target-recall",
        type=float,
        default=0.70,
        help="Minimum positive-class recall for --threshold-strategy target_recall.",
    )
    parser.add_argument(
        "--fixed-decision-threshold",
        type=float,
        default=DEFAULT_DECISION_THRESHOLD,
        help="Fixed model probability threshold. The expert-consultation experiment defaults to the prior LightGBM threshold rounded to 0.59.",
    )
    parser.add_argument(
        "--drop-columns",
        default=",".join(sorted(DEFAULT_EXCLUDED_FEATURE_COLUMNS)),
        help="Comma-separated feature columns to exclude before model training.",
    )
    return parser.parse_args()


def load_data(path: str, sheet: str) -> pd.DataFrame:
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(f"Input file does not exist: {source}")
    if source.suffix.lower() in {".xlsx", ".xls"}:
        return pd.read_excel(source, sheet_name=sheet)
    return pd.read_csv(source)


def clean_missing_codes(df: pd.DataFrame, preserve_columns: set[str] | None = None) -> pd.DataFrame:
    cleaned = df.copy()
    preserve_columns = preserve_columns or set()
    for column in cleaned.columns:
        if column in preserve_columns:
            continue
        if pd.api.types.is_numeric_dtype(cleaned[column]):
            cleaned[column] = cleaned[column].replace(list(MISSING_CODES), np.nan)
    return cleaned


def parse_drop_columns(raw: str | list[str] | tuple[str, ...] | set[str] | None) -> set[str]:
    if raw is None:
        return set(DEFAULT_EXCLUDED_FEATURE_COLUMNS)
    if isinstance(raw, str):
        if not raw.strip():
            return set()
        return {column.strip() for column in raw.split(",") if column.strip()}
    return {str(column).strip() for column in raw if str(column).strip()}


def make_dataset(
    df: pd.DataFrame,
    min_age: int,
    max_age: int,
    threshold: float,
    include_phq_items: bool,
    drop_feature_columns: str | list[str] | tuple[str, ...] | set[str] | None = None,
) -> tuple[pd.DataFrame, pd.Series, dict[str, Any]]:
    required = {"age", TARGET_COLUMN}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns: {sorted(missing)}")

    data = clean_missing_codes(df, preserve_columns={TARGET_COLUMN, "age", "year", "region"})
    data["age"] = pd.to_numeric(data["age"], errors="coerce")
    data[TARGET_COLUMN] = pd.to_numeric(data[TARGET_COLUMN], errors="coerce")
    data = data[(data["age"] >= min_age) & (data["age"] <= max_age)]
    data = data[data[TARGET_COLUMN].notna()]

    y = (data[TARGET_COLUMN] >= threshold).astype(int)
    requested_drop_columns = parse_drop_columns(drop_feature_columns)
    drop_columns = set(ID_COLUMNS) | {TARGET_COLUMN} | requested_drop_columns
    if not include_phq_items:
        drop_columns |= set(PHQ_COLUMNS)

    initial_feature_columns = [column for column in data.columns if column not in drop_columns]
    empty_feature_columns = [
        column
        for column in initial_feature_columns
        if data[column].isna().all()
    ]
    feature_columns = [column for column in initial_feature_columns if column not in empty_feature_columns]
    x = data[feature_columns].copy()

    metadata = {
        "source_rows": int(len(df)),
        "training_rows": int(len(data)),
        "positive_rows": int(y.sum()),
        "negative_rows": int((1 - y).sum()),
        "target_column": TARGET_COLUMN,
        "threshold": threshold,
        "age_filter": [min_age, max_age],
        "feature_columns": feature_columns,
        "excluded_columns": sorted(drop_columns & set(data.columns)),
        "dropped_empty_feature_columns": sorted(empty_feature_columns),
        "screening_feature_policy": {
            "kept_context_columns": ["region_group"] if "region_group" in feature_columns else [],
            "excluded_to_reduce_leakage_or_dataset_artifacts": sorted(requested_drop_columns & set(data.columns)),
        },
        "note": "This model estimates a reference risk group for expert review support. It is not a medical diagnosis."
    }
    if "source_dataset" in data.columns:
        metadata["source_dataset_counts"] = {
            str(key): int(value)
            for key, value in data["source_dataset"].value_counts(dropna=False).to_dict().items()
        }
        metadata["positive_rows_by_source"] = {
            str(key): int(value)
            for key, value in data.assign(_target=y).groupby("source_dataset")["_target"].sum().to_dict().items()
        }
    return x, y, metadata


def class_balance_ratio(y: pd.Series) -> float:
    positives = int(y.sum())
    negatives = int(len(y) - positives)
    if positives == 0:
        return 1.0
    return max(1.0, negatives / positives)


def choose_estimator(scale_pos_weight: float):
    try:
        from lightgbm import LGBMClassifier

        return LGBMClassifier(
            objective="binary",
            n_estimators=400,
            learning_rate=0.03,
            max_depth=-1,
            num_leaves=31,
            class_weight="balanced",
            random_state=42,
            verbosity=-1
        ), "lightgbm"
    except Exception:
        try:
            from xgboost import XGBClassifier

            return XGBClassifier(
                n_estimators=350,
                learning_rate=0.035,
                max_depth=4,
                subsample=0.9,
                colsample_bytree=0.9,
                eval_metric="logloss",
                tree_method="hist",
                scale_pos_weight=scale_pos_weight,
                random_state=42
            ), "xgboost"
        except Exception:
            return HistGradientBoostingClassifier(max_iter=300, learning_rate=0.04, random_state=42), "hist_gradient_boosting"


def build_pipeline(x: pd.DataFrame, y: pd.Series) -> Pipeline:
    numeric_features = [column for column in x.columns if pd.api.types.is_numeric_dtype(x[column])]
    categorical_features = [column for column in x.columns if column not in numeric_features]
    estimator, model_type = choose_estimator(class_balance_ratio(y))

    preprocessor = ColumnTransformer(
        transformers=[
            ("num", SimpleImputer(strategy="median"), numeric_features),
            ("cat", Pipeline(steps=[
                ("imputer", SimpleImputer(strategy="most_frequent")),
                ("onehot", OneHotEncoder(handle_unknown="ignore"))
            ]), categorical_features)
        ],
        remainder="drop"
    )

    pipeline = Pipeline(steps=[
        ("preprocess", preprocessor),
        ("model", estimator)
    ])
    pipeline.model_type = model_type  # type: ignore[attr-defined]
    return pipeline


def choose_f1_threshold(y_true: pd.Series, probabilities: np.ndarray) -> dict[str, Any]:
    precision, recall, thresholds = precision_recall_curve(y_true, probabilities)
    if len(thresholds) == 0:
        return {
            "threshold": 0.5,
            "precision": 0.0,
            "recall": 0.0,
            "f1": 0.0,
            "note": "No threshold candidates were available."
        }

    precision = precision[:-1]
    recall = recall[:-1]
    denominator = precision + recall
    f1_values = np.divide(
        2 * precision * recall,
        denominator,
        out=np.zeros_like(denominator),
        where=denominator > 0
    )
    best_index = int(np.nanargmax(f1_values))
    return {
        "threshold": float(thresholds[best_index]),
        "precision": float(precision[best_index]),
        "recall": float(recall[best_index]),
        "f1": float(f1_values[best_index]),
        "selection_metric": "positive_class_f1"
    }


def choose_target_recall_threshold(y_true: pd.Series, probabilities: np.ndarray, target_recall: float) -> dict[str, Any]:
    precision, recall, thresholds = precision_recall_curve(y_true, probabilities)
    if len(thresholds) == 0:
        return {
            "threshold": 0.5,
            "precision": 0.0,
            "recall": 0.0,
            "f1": 0.0,
            "target_recall": float(target_recall),
            "note": "No threshold candidates were available."
        }

    precision = precision[:-1]
    recall = recall[:-1]
    denominator = precision + recall
    f1_values = np.divide(
        2 * precision * recall,
        denominator,
        out=np.zeros_like(denominator),
        where=denominator > 0
    )
    candidates = np.where(recall >= target_recall)[0]
    if len(candidates) == 0:
        best_index = int(np.nanargmax(recall))
        note = "Target recall was not reachable on the validation split; selected the highest-recall threshold."
    else:
        candidate_order = np.lexsort((-f1_values[candidates], -precision[candidates]))
        best_index = int(candidates[candidate_order[0]])
        note = "Selected the highest-precision threshold among candidates meeting target recall."

    return {
        "threshold": float(thresholds[best_index]),
        "precision": float(precision[best_index]),
        "recall": float(recall[best_index]),
        "f1": float(f1_values[best_index]),
        "target_recall": float(target_recall),
        "selection_metric": "target_positive_recall",
        "note": note
    }


def choose_threshold(y_true: pd.Series, probabilities: np.ndarray, strategy: str, target_recall: float) -> dict[str, Any]:
    if strategy == "target_recall":
        return choose_target_recall_threshold(y_true, probabilities, target_recall)
    return choose_f1_threshold(y_true, probabilities)


def evaluate_predictions(y_true: pd.Series, probabilities: np.ndarray, threshold: float) -> dict[str, Any]:
    predictions = (probabilities >= threshold).astype(int)
    matrix = confusion_matrix(y_true, predictions, labels=[0, 1])
    return {
        "threshold": float(threshold),
        "roc_auc": float(roc_auc_score(y_true, probabilities)),
        "average_precision": float(average_precision_score(y_true, probabilities)),
        "positive_precision": float(precision_score(y_true, predictions, zero_division=0)),
        "positive_recall": float(recall_score(y_true, predictions, zero_division=0)),
        "positive_f1": float(f1_score(y_true, predictions, zero_division=0)),
        "confusion_matrix": {
            "tn": int(matrix[0, 0]),
            "fp": int(matrix[0, 1]),
            "fn": int(matrix[1, 0]),
            "tp": int(matrix[1, 1])
        },
        "classification_report": classification_report(y_true, predictions, output_dict=True, zero_division=0)
    }


def summarize_metric(values: list[float]) -> dict[str, float]:
    return {
        "mean": float(np.mean(values)),
        "std": float(np.std(values, ddof=1)) if len(values) > 1 else 0.0
    }


def cross_validate_model(x: pd.DataFrame, y: pd.Series, folds: int, decision_threshold: float) -> dict[str, Any] | None:
    positives = int(y.sum())
    negatives = int(len(y) - positives)
    if folds < 2 or positives < folds or negatives < folds:
        return None

    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=42)
    fold_metrics: list[dict[str, float]] = []
    for train_index, valid_index in splitter.split(x, y):
        x_train, x_valid = x.iloc[train_index], x.iloc[valid_index]
        y_train, y_valid = y.iloc[train_index], y.iloc[valid_index]
        pipeline = build_pipeline(x_train, y_train)
        pipeline.fit(x_train, y_train)
        probabilities = pipeline.predict_proba(x_valid)[:, 1]
        predictions = (probabilities >= decision_threshold).astype(int)
        fold_metrics.append({
            "roc_auc": float(roc_auc_score(y_valid, probabilities)),
            "average_precision": float(average_precision_score(y_valid, probabilities)),
            "positive_precision": float(precision_score(y_valid, predictions, zero_division=0)),
            "positive_recall": float(recall_score(y_valid, predictions, zero_division=0)),
            "positive_f1": float(f1_score(y_valid, predictions, zero_division=0))
        })

    return {
        "folds": folds,
        "decision_threshold": float(decision_threshold),
        "metrics": {
            key: summarize_metric([fold[key] for fold in fold_metrics])
            for key in fold_metrics[0]
        },
        "fold_details": fold_metrics
    }


def feature_importance(pipeline: Pipeline, x: pd.DataFrame, output_dir: Path) -> list[dict[str, Any]]:
    model = pipeline.named_steps["model"]
    preprocessor = pipeline.named_steps["preprocess"]
    transformed_names = preprocessor.get_feature_names_out()

    importances = None
    if hasattr(model, "feature_importances_"):
        importances = model.feature_importances_
    elif isinstance(model, RandomForestClassifier):
        importances = model.feature_importances_

    if importances is None:
        return []

    ranked = sorted(
        [{"feature": str(name), "importance": float(value)} for name, value in zip(transformed_names, importances)],
        key=lambda item: item["importance"],
        reverse=True
    )
    (output_dir / "feature_importance.json").write_text(json.dumps(ranked[:50], ensure_ascii=False, indent=2), encoding="utf-8")
    return ranked[:20]


def shap_summary(pipeline: Pipeline, x_sample: pd.DataFrame, output_dir: Path) -> None:
    try:
        import shap

        preprocessor = pipeline.named_steps["preprocess"]
        model = pipeline.named_steps["model"]
        transformed = preprocessor.transform(x_sample)
        names = preprocessor.get_feature_names_out()
        explainer = shap.TreeExplainer(model)
        values = explainer.shap_values(transformed)
        if isinstance(values, list):
            values = values[-1]
        mean_abs = np.abs(values).mean(axis=0)
        ranked = sorted(
            [{"feature": str(name), "mean_abs_shap": float(value)} for name, value in zip(names, mean_abs)],
            key=lambda item: item["mean_abs_shap"],
            reverse=True
        )
        (output_dir / "shap_summary.json").write_text(json.dumps(ranked[:50], ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as error:
        (output_dir / "shap_summary_error.txt").write_text(str(error), encoding="utf-8")


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    df = load_data(args.input, args.sheet)
    x, y, metadata = make_dataset(
        df,
        args.min_age,
        args.max_age,
        args.threshold,
        args.include_phq_items,
        args.drop_columns,
    )

    x_train_valid, x_test, y_train_valid, y_test = train_test_split(
        x, y, test_size=args.test_size, random_state=42, stratify=y
    )
    x_train, x_valid, y_train, y_valid = train_test_split(
        x_train_valid,
        y_train_valid,
        test_size=args.validation_size,
        random_state=42,
        stratify=y_train_valid
    )

    eval_pipeline = build_pipeline(x_train, y_train)
    eval_pipeline.fit(x_train, y_train)

    validation_probabilities = eval_pipeline.predict_proba(x_valid)[:, 1]
    validation_fixed_metrics = evaluate_predictions(y_valid, validation_probabilities, args.fixed_decision_threshold)
    threshold_selection = {
        "threshold": float(args.fixed_decision_threshold),
        "precision": validation_fixed_metrics["positive_precision"],
        "recall": validation_fixed_metrics["positive_recall"],
        "f1": validation_fixed_metrics["positive_f1"],
        "selection_metric": "fixed_decision_threshold",
        "note": "Fixed at 0.59 to match the prior LightGBM decision threshold requested after expert consultation.",
    }
    selected_threshold = threshold_selection["threshold"]

    test_probabilities = eval_pipeline.predict_proba(x_test)[:, 1]
    default_test_metrics = evaluate_predictions(y_test, test_probabilities, 0.5)
    optimized_test_metrics = evaluate_predictions(y_test, test_probabilities, selected_threshold)
    cross_validation = cross_validate_model(x, y, args.cv_folds, selected_threshold)
    metrics = {
        "model_type": getattr(eval_pipeline, "model_type", "unknown"),
        "split": {
            "test_size": args.test_size,
            "validation_size_inside_train": args.validation_size,
            "random_state": 42
        },
        "threshold_strategy": "fixed_decision_threshold",
        "target_recall": None,
        "decision_threshold": selected_threshold,
        "validation_threshold_selection": threshold_selection,
        "test_default_threshold": default_test_metrics,
        "test_optimized_threshold": optimized_test_metrics,
        "cross_validation": cross_validation,
        "roc_auc": default_test_metrics["roc_auc"],
        "average_precision": default_test_metrics["average_precision"],
        "classification_report": optimized_test_metrics["classification_report"]
    }

    final_pipeline = build_pipeline(x_train_valid, y_train_valid)
    final_pipeline.fit(x_train_valid, y_train_valid)

    top_features = feature_importance(final_pipeline, x_train_valid, output_dir)
    shap_summary(final_pipeline, x_train_valid.sample(min(500, len(x_train_valid)), random_state=42), output_dir)

    joblib.dump(final_pipeline, output_dir / "risk_model.joblib")
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "feature_schema.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "top_features.json").write_text(json.dumps(top_features, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({"metrics": metrics, "metadata": metadata}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
