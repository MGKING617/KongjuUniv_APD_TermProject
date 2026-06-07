from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import calibration_curve
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    balanced_accuracy_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    precision_recall_curve,
    precision_recall_fscore_support,
    precision_score,
    recall_score,
    roc_auc_score,
    roc_curve,
)
from sklearn.model_selection import StratifiedKFold, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

os.environ.setdefault("LOKY_MAX_CPU_COUNT", "4")
mpl_cache_dir = Path(tempfile.gettempdir()) / "term-project-matplotlib-cache"
mpl_cache_dir.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", str(mpl_cache_dir))
warnings.filterwarnings("ignore", message="X does not have valid feature names.*")
warnings.filterwarnings("ignore", message="LightGBM binary classifier.*")
warnings.filterwarnings("ignore", message="Could not find the number of physical cores.*")

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import seaborn as sns  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from ml.src.train_model import (  # noqa: E402
    DEFAULT_EXCLUDED_FEATURE_COLUMNS,
    DEFAULT_SHEET,
    class_balance_ratio,
    choose_threshold,
    load_data,
    make_dataset,
    parse_drop_columns,
)


@dataclass(frozen=True)
class ModelSpec:
    key: str
    display_name: str
    estimator: Any
    scale_numeric: bool = False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark tabular models for youth depression-related reference risk.")
    parser.add_argument("--input", required=True, help="XLSX/CSV file path. Use the personal-level raw data file.")
    parser.add_argument("--sheet", default=DEFAULT_SHEET, help="Excel sheet name.")
    parser.add_argument("--output-dir", default="ml/reports/benchmark", help="Directory for benchmark outputs.")
    parser.add_argument("--min-age", type=int, default=19)
    parser.add_argument("--max-age", type=int, default=39)
    parser.add_argument("--threshold", type=float, default=10.0, help="Reference label threshold for PHQ score.")
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--validation-size", type=float, default=0.2)
    parser.add_argument("--cv-folds", type=int, default=5)
    parser.add_argument("--random-state", type=int, default=42)
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
        "--drop-columns",
        default=",".join(sorted(DEFAULT_EXCLUDED_FEATURE_COLUMNS)),
        help="Comma-separated feature columns to exclude before benchmarking.",
    )
    parser.add_argument(
        "--learning-curve-max-rows",
        type=int,
        default=40000,
        help="Maximum rows used for learning-curve diagnostics to keep report generation practical.",
    )
    return parser.parse_args()


def build_preprocessor(x: pd.DataFrame, scale_numeric: bool) -> ColumnTransformer:
    numeric_features = [column for column in x.columns if pd.api.types.is_numeric_dtype(x[column])]
    categorical_features = [column for column in x.columns if column not in numeric_features]

    numeric_steps: list[tuple[str, Any]] = [("imputer", SimpleImputer(strategy="median"))]
    if scale_numeric:
        numeric_steps.append(("scaler", StandardScaler()))

    return ColumnTransformer(
        transformers=[
            ("num", Pipeline(steps=numeric_steps), numeric_features),
            (
                "cat",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="most_frequent")),
                        ("onehot", OneHotEncoder(handle_unknown="ignore")),
                    ]
                ),
                categorical_features,
            ),
        ],
        remainder="drop",
    )


def build_pipeline(spec: ModelSpec, x: pd.DataFrame) -> Pipeline:
    return Pipeline(
        steps=[
            ("preprocess", build_preprocessor(x, spec.scale_numeric)),
            ("model", spec.estimator),
        ]
    )


def model_specs(y: pd.Series, random_state: int) -> list[ModelSpec]:
    scale_pos_weight = class_balance_ratio(y)
    from lightgbm import LGBMClassifier
    from xgboost import XGBClassifier

    return [
        ModelSpec(
            key="logistic_regression",
            display_name="Logistic Regression",
            scale_numeric=True,
            estimator=LogisticRegression(
                class_weight="balanced",
                max_iter=3000,
                solver="lbfgs",
                random_state=random_state,
            ),
        ),
        ModelSpec(
            key="random_forest",
            display_name="Random Forest",
            estimator=RandomForestClassifier(
                n_estimators=450,
                min_samples_leaf=3,
                class_weight="balanced_subsample",
                n_jobs=-1,
                random_state=random_state,
            ),
        ),
        ModelSpec(
            key="xgboost",
            display_name="XGBoost",
            estimator=XGBClassifier(
                n_estimators=450,
                learning_rate=0.035,
                max_depth=4,
                subsample=0.9,
                colsample_bytree=0.9,
                eval_metric="logloss",
                tree_method="hist",
                scale_pos_weight=scale_pos_weight,
                random_state=random_state,
            ),
        ),
        ModelSpec(
            key="lightgbm",
            display_name="LightGBM",
            estimator=LGBMClassifier(
                objective="binary",
                n_estimators=450,
                learning_rate=0.03,
                num_leaves=31,
                class_weight="balanced",
                random_state=random_state,
                verbosity=-1,
            ),
        ),
    ]


def evaluate(y_true: pd.Series, probabilities: np.ndarray, threshold: float) -> dict[str, Any]:
    predictions = (probabilities >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, predictions, labels=[0, 1]).ravel()
    precision_values, recall_values, f1_values, _ = precision_recall_fscore_support(
        y_true,
        predictions,
        labels=[0, 1],
        zero_division=0,
    )
    return {
        "threshold": float(threshold),
        "auroc": float(roc_auc_score(y_true, probabilities)),
        "pr_auc": float(average_precision_score(y_true, probabilities)),
        "accuracy": float(accuracy_score(y_true, predictions)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, predictions)),
        "precision": float(precision_score(y_true, predictions, zero_division=0)),
        "recall": float(recall_score(y_true, predictions, zero_division=0)),
        "f1": float(f1_score(y_true, predictions, zero_division=0)),
        "specificity": float(tn / (tn + fp)) if (tn + fp) else 0.0,
        "negative_precision": float(precision_values[0]),
        "negative_recall": float(recall_values[0]),
        "negative_f1": float(f1_values[0]),
        "positive_precision": float(precision_values[1]),
        "positive_recall": float(recall_values[1]),
        "positive_f1": float(f1_values[1]),
        "tn": int(tn),
        "fp": int(fp),
        "fn": int(fn),
        "tp": int(tp),
    }


def cross_validate(spec: ModelSpec, x: pd.DataFrame, y: pd.Series, folds: int, random_state: int) -> dict[str, Any] | None:
    if folds < 2 or y.sum() < folds or (len(y) - y.sum()) < folds:
        return None

    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=random_state)
    records: list[dict[str, Any]] = []
    for fold_index, (train_index, valid_index) in enumerate(splitter.split(x, y), start=1):
        x_train, x_valid = x.iloc[train_index], x.iloc[valid_index]
        y_train, y_valid = y.iloc[train_index], y.iloc[valid_index]
        pipeline = build_pipeline(spec, x_train)
        pipeline.fit(x_train, y_train)
        probabilities = pipeline.predict_proba(x_valid)[:, 1]
        metrics = evaluate(y_valid, probabilities, 0.5)
        metrics["fold"] = fold_index
        records.append(metrics)

    frame = pd.DataFrame(records)
    return {
        "folds": folds,
        "fold_metrics": records,
        "mean": frame.drop(columns=["fold"]).mean(numeric_only=True).to_dict(),
        "std": frame.drop(columns=["fold"]).std(numeric_only=True).fillna(0.0).to_dict(),
    }


def transformed_feature_names(pipeline: Pipeline) -> list[str]:
    preprocessor = pipeline.named_steps["preprocess"]
    return [str(name).replace("num__", "").replace("cat__", "") for name in preprocessor.get_feature_names_out()]


def transformed_feature_groups(pipeline: Pipeline) -> list[str]:
    preprocessor = pipeline.named_steps["preprocess"]
    groups: list[str] = []
    for name, transformer, columns in preprocessor.transformers_:
        if name == "remainder" or transformer == "drop":
            continue
        column_names = [str(column) for column in columns]
        if name == "num":
            groups.extend(column_names)
            continue
        if name == "cat":
            if not column_names:
                continue
            onehot = transformer.named_steps["onehot"]
            encoded_names = [str(feature) for feature in onehot.get_feature_names_out(column_names)]
            for encoded_name in encoded_names:
                source = next(
                    (column for column in sorted(column_names, key=len, reverse=True) if encoded_name.startswith(f"{column}_")),
                    encoded_name,
                )
                groups.append(source)
    return groups


def save_lightgbm_outcome_contributions(
    pipeline: Pipeline,
    x_test: pd.DataFrame,
    y_test: pd.Series,
    probabilities: np.ndarray,
    threshold: float,
    output_dir: Path,
    max_rows: int = 12000,
    top_n: int = 18,
) -> None:
    try:
        import shap
    except Exception as error:
        (output_dir / "lightgbm_outcome_group_contributions.error.txt").write_text(str(error), encoding="utf-8")
        return

    if len(x_test) > max_rows:
        sample = x_test.sample(n=max_rows, random_state=42)
        y_sample = y_test.loc[sample.index]
        probability_sample = pd.Series(probabilities, index=x_test.index).loc[sample.index].to_numpy()
    else:
        sample = x_test
        y_sample = y_test
        probability_sample = probabilities

    preprocessor = pipeline.named_steps["preprocess"]
    model = pipeline.named_steps["model"]
    transformed = preprocessor.transform(sample)
    if hasattr(transformed, "toarray"):
        transformed = transformed.toarray()

    explainer = shap.TreeExplainer(model)
    shap_values = explainer.shap_values(transformed)
    if isinstance(shap_values, list):
        shap_values = shap_values[-1]
    shap_values = np.asarray(shap_values)
    if shap_values.ndim == 3:
        shap_values = shap_values[:, :, -1]

    groups = transformed_feature_groups(pipeline)
    if shap_values.shape[1] != len(groups):
        groups = transformed_feature_names(pipeline)

    contribution_frame = pd.DataFrame(shap_values, columns=groups, index=sample.index)
    contribution_frame = contribution_frame.T.groupby(level=0).sum().T

    predictions = (probability_sample >= threshold).astype(int)
    actual = y_sample.to_numpy()
    outcome_groups = np.select(
        [
            (actual == 1) & (predictions == 1),
            (actual == 0) & (predictions == 1),
            (actual == 1) & (predictions == 0),
            (actual == 0) & (predictions == 0),
        ],
        ["TP", "FP", "FN", "TN"],
        default="unknown",
    )

    top_features = contribution_frame.abs().mean().sort_values(ascending=False).head(top_n).index.tolist()
    grouped = contribution_frame[top_features].assign(outcome_group=outcome_groups).groupby("outcome_group").mean()
    ordered_groups = [group for group in ["TP", "FP", "FN", "TN"] if group in grouped.index]
    grouped = grouped.loc[ordered_groups]
    grouped.to_csv(output_dir / "lightgbm_outcome_group_contributions.csv", encoding="utf-8-sig")

    long_frame = grouped.reset_index().melt(
        id_vars="outcome_group",
        var_name="feature",
        value_name="mean_signed_shap",
    )
    long_frame["feature"] = pd.Categorical(long_frame["feature"], categories=list(reversed(top_features)), ordered=True)

    plt.figure(figsize=(11, 8))
    sns.barplot(
        data=long_frame,
        y="feature",
        x="mean_signed_shap",
        hue="outcome_group",
        hue_order=ordered_groups,
        palette={"TP": "#d64b4b", "FP": "#8c5fd7", "FN": "#4f6bdc", "TN": "#1b9e77"},
    )
    plt.axvline(0, color="#333333", linewidth=1)
    plt.title("LightGBM Mean SHAP Contribution by Outcome Group")
    plt.xlabel("Mean signed SHAP contribution")
    plt.ylabel("")
    plt.legend(title="Outcome group", loc="lower right")
    plt.tight_layout()
    plt.savefig(output_dir / "lightgbm_outcome_group_contributions.png", dpi=180)
    plt.close()


def export_feature_importance(spec: ModelSpec, pipeline: Pipeline, output_dir: Path) -> Path | None:
    model = pipeline.named_steps["model"]
    names = transformed_feature_names(pipeline)
    values: np.ndarray | None = None

    if hasattr(model, "feature_importances_"):
        values = np.asarray(model.feature_importances_, dtype=float)
    elif hasattr(model, "coef_"):
        signed_values = np.asarray(model.coef_[0], dtype=float)
        signed_frame = pd.DataFrame(
            {
                "feature": names,
                "signed_coefficient": signed_values,
                "absolute_coefficient": np.abs(signed_values),
                "direction": np.where(signed_values >= 0, "higher_model_score", "lower_model_score"),
            }
        )
        signed_frame = signed_frame.sort_values("absolute_coefficient", ascending=False)
        signed_frame.to_csv(output_dir / f"feature_coefficients_{spec.key}.csv", index=False, encoding="utf-8-sig")
        values = np.abs(signed_values)

    if values is None or len(values) != len(names):
        return None

    frame = pd.DataFrame({"feature": names, "importance": values})
    frame = frame.sort_values("importance", ascending=False).head(15)
    path = output_dir / f"feature_importance_{spec.key}.csv"
    frame.to_csv(path, index=False, encoding="utf-8-sig")

    plt.figure(figsize=(9, 6))
    sns.barplot(data=frame, y="feature", x="importance", color="#167f75")
    plt.title(f"{spec.display_name} Feature Importance")
    plt.xlabel("Importance")
    plt.ylabel("")
    plt.tight_layout()
    plt.savefig(output_dir / f"feature_importance_{spec.key}.png", dpi=180)
    plt.close()
    return path


def save_metric_plots(metrics: pd.DataFrame, curve_data: dict[str, dict[str, Any]], output_dir: Path) -> None:
    plot_frame = metrics.melt(
        id_vars=["model"],
        value_vars=["auroc", "pr_auc", "positive_precision", "positive_recall", "positive_f1"],
        var_name="metric",
        value_name="score",
    )

    plt.figure(figsize=(12, 6))
    sns.barplot(data=plot_frame, x="metric", y="score", hue="model")
    plt.ylim(0, 1)
    plt.title("Model Performance Comparison")
    plt.xlabel("")
    plt.ylabel("Score")
    plt.legend(loc="lower right")
    plt.tight_layout()
    plt.savefig(output_dir / "metrics_comparison.png", dpi=180)
    plt.close()

    positive_frame = metrics.melt(
        id_vars=["model"],
        value_vars=["positive_precision", "positive_recall", "positive_f1"],
        var_name="metric",
        value_name="score",
    )
    plt.figure(figsize=(10, 5))
    sns.barplot(data=positive_frame, x="model", y="score", hue="metric")
    plt.ylim(0, 1)
    plt.title("Positive Class Metrics")
    plt.xlabel("")
    plt.ylabel("Score")
    plt.tight_layout()
    plt.savefig(output_dir / "positive_class_metrics.png", dpi=180)
    plt.close()

    fig, axes = plt.subplots(2, 2, figsize=(10, 8))
    for axis, row in zip(axes.flat, metrics.itertuples(index=False)):
        matrix = np.array([[row.tn, row.fp], [row.fn, row.tp]])
        sns.heatmap(matrix, annot=True, fmt="d", cmap="Blues", cbar=False, ax=axis)
        axis.set_title(row.model)
        axis.set_xlabel("Predicted")
        axis.set_ylabel("Actual")
        axis.set_xticklabels(["Low", "High"])
        axis.set_yticklabels(["Low", "High"], rotation=0)
    plt.tight_layout()
    plt.savefig(output_dir / "confusion_matrices.png", dpi=180)
    plt.close()

    fig, axes = plt.subplots(2, 2, figsize=(10, 8))
    for axis, row in zip(axes.flat, metrics.itertuples(index=False)):
        matrix = np.array([[row.tn, row.fp], [row.fn, row.tp]], dtype=float)
        row_sums = matrix.sum(axis=1, keepdims=True)
        normalized = np.divide(matrix, row_sums, out=np.zeros_like(matrix), where=row_sums > 0)
        labels = np.array([
            [f"{normalized[0, 0]:.1%}\n({int(matrix[0, 0]):,})", f"{normalized[0, 1]:.1%}\n({int(matrix[0, 1]):,})"],
            [f"{normalized[1, 0]:.1%}\n({int(matrix[1, 0]):,})", f"{normalized[1, 1]:.1%}\n({int(matrix[1, 1]):,})"],
        ])
        sns.heatmap(normalized, annot=labels, fmt="", cmap="Blues", cbar=False, ax=axis, vmin=0, vmax=1)
        axis.set_title(row.model)
        axis.set_xlabel("Predicted")
        axis.set_ylabel("Actual")
        axis.set_xticklabels(["Low", "High"])
        axis.set_yticklabels(["Low", "High"], rotation=0)
    plt.tight_layout()
    plt.savefig(output_dir / "confusion_matrices_normalized.png", dpi=180)
    plt.close()

    plt.figure(figsize=(8, 6))
    for model_name, data in curve_data.items():
        fpr, tpr, _ = roc_curve(data["y_true"], data["probabilities"])
        plt.plot(fpr, tpr, label=f"{model_name} (AUROC={data['auroc']:.3f})")
    plt.plot([0, 1], [0, 1], linestyle="--", color="#999999")
    plt.title("ROC Curves")
    plt.xlabel("False Positive Rate")
    plt.ylabel("True Positive Rate")
    plt.legend(loc="lower right")
    plt.tight_layout()
    plt.savefig(output_dir / "roc_curves.png", dpi=180)
    plt.close()

    plt.figure(figsize=(8, 6))
    for model_name, data in curve_data.items():
        precision, recall, _ = precision_recall_curve(data["y_true"], data["probabilities"])
        plt.plot(recall, precision, label=f"{model_name} (PR-AUC={data['pr_auc']:.3f})")
    baseline = float(np.mean(next(iter(curve_data.values()))["y_true"]))
    plt.axhline(baseline, linestyle="--", color="#999999", label=f"Baseline={baseline:.3f}")
    plt.title("Precision-Recall Curves")
    plt.xlabel("Recall")
    plt.ylabel("Precision")
    plt.legend(loc="upper right")
    plt.tight_layout()
    plt.savefig(output_dir / "precision_recall_curves.png", dpi=180)
    plt.close()


def save_publication_tables(metrics: pd.DataFrame, output_dir: Path) -> None:
    performance_columns = ["model", "auroc", "pr_auc", "positive_recall", "positive_precision", "positive_f1", "balanced_accuracy"]
    performance = metrics[performance_columns].copy()
    performance.columns = ["Model", "AUROC", "PR-AUC", "Recall", "Precision", "F1", "Balanced Acc."]
    for column in performance.columns[1:]:
        performance[column] = performance[column].map(lambda value: f"{float(value):.3f}")

    confusion = metrics[["model", "tn", "fp", "fn", "tp"]].copy()
    confusion["Detected high"] = confusion["tp"].astype(str) + "/" + (confusion["tp"] + confusion["fn"]).astype(str)
    confusion["False alerts"] = confusion["fp"]
    confusion = confusion[["model", "tn", "fp", "fn", "tp", "Detected high", "False alerts"]]
    confusion.columns = ["Model", "TN", "FP", "FN", "TP", "Detected High", "False Alerts"]

    fig, axes = plt.subplots(2, 1, figsize=(12, 6.8))
    for axis, table_frame, title in [
        (axes[0], performance, "Hold-out Performance"),
        (axes[1], confusion, "Confusion Matrix Summary"),
    ]:
        axis.axis("off")
        axis.set_title(title, loc="left", fontsize=14, fontweight="bold", pad=12)
        table = axis.table(
            cellText=table_frame.values,
            colLabels=table_frame.columns,
            cellLoc="center",
            colLoc="center",
            loc="center",
        )
        table.auto_set_font_size(False)
        table.set_fontsize(10)
        table.scale(1, 1.35)
        for (row, _column), cell in table.get_celld().items():
            cell.set_edgecolor("#263238")
            cell.set_linewidth(0.8)
            if row == 0:
                cell.set_facecolor("#e9f2f0")
                cell.set_text_props(weight="bold")
    plt.tight_layout()
    plt.savefig(output_dir / "publication_benchmark_tables.png", dpi=220)
    plt.close()


def save_calibration_plots(curve_data: dict[str, dict[str, Any]], output_dir: Path) -> None:
    records: list[dict[str, Any]] = []
    plt.figure(figsize=(8, 7))
    plt.plot([0, 1], [0, 1], linestyle="--", color="#777777", label="Ideal")
    for model_name, data in curve_data.items():
        y_true = np.asarray(data["y_true"])
        probabilities = np.asarray(data["probabilities"])
        observed, predicted = calibration_curve(y_true, probabilities, n_bins=10, strategy="quantile")
        brier = brier_score_loss(y_true, probabilities)
        for bin_index, (predicted_value, observed_value) in enumerate(zip(predicted, observed), start=1):
            records.append(
                {
                    "model": model_name,
                    "bin": bin_index,
                    "mean_predicted_probability": float(predicted_value),
                    "observed_positive_rate": float(observed_value),
                    "brier_score": float(brier),
                }
            )
        plt.plot(predicted, observed, marker="o", linewidth=2, label=f"{model_name} (Brier={brier:.3f})")
    pd.DataFrame(records).to_csv(output_dir / "calibration_curves.csv", index=False, encoding="utf-8-sig")
    plt.title("Calibration Curves")
    plt.xlabel("Mean predicted probability")
    plt.ylabel("Observed positive rate")
    plt.legend(loc="upper left")
    plt.tight_layout()
    plt.savefig(output_dir / "calibration_curves.png", dpi=180)
    plt.close()


def save_threshold_tradeoff(metrics: pd.DataFrame, curve_data: dict[str, dict[str, Any]], output_dir: Path, model_name: str = "LightGBM") -> None:
    if model_name not in curve_data:
        return
    data = curve_data[model_name]
    y_true = np.asarray(data["y_true"])
    probabilities = np.asarray(data["probabilities"])
    selected_threshold = float(metrics.loc[metrics["model"] == model_name, "threshold"].iloc[0])

    thresholds = np.linspace(0.01, 0.99, 99)
    records: list[dict[str, Any]] = []
    for threshold in thresholds:
        predictions = (probabilities >= threshold).astype(int)
        tn, fp, fn, tp = confusion_matrix(y_true, predictions, labels=[0, 1]).ravel()
        precision = precision_score(y_true, predictions, zero_division=0)
        recall = recall_score(y_true, predictions, zero_division=0)
        f1 = f1_score(y_true, predictions, zero_division=0)
        records.append(
            {
                "threshold": float(threshold),
                "precision": float(precision),
                "recall": float(recall),
                "f1": float(f1),
                "tp": int(tp),
                "fp": int(fp),
                "fn": int(fn),
                "tn": int(tn),
            }
        )
    frame = pd.DataFrame(records)
    frame.to_csv(output_dir / "lightgbm_threshold_tradeoff.csv", index=False, encoding="utf-8-sig")

    fig, axes = plt.subplots(2, 1, figsize=(10, 8), sharex=True)
    axes[0].plot(frame["threshold"], frame["precision"], label="Precision", color="#d64b4b")
    axes[0].plot(frame["threshold"], frame["recall"], label="Recall", color="#167f75")
    axes[0].plot(frame["threshold"], frame["f1"], label="F1", color="#315fdc")
    axes[0].axvline(selected_threshold, linestyle="--", color="#111111", label=f"Selected={selected_threshold:.3f}")
    axes[0].set_ylim(0, 1)
    axes[0].set_ylabel("Score")
    axes[0].set_title("LightGBM Threshold Trade-off")
    axes[0].legend(loc="upper right")

    axes[1].plot(frame["threshold"], frame["tp"], label="TP", color="#d64b4b")
    axes[1].plot(frame["threshold"], frame["fp"], label="FP", color="#8c5fd7")
    axes[1].plot(frame["threshold"], frame["fn"], label="FN", color="#315fdc")
    axes[1].axvline(selected_threshold, linestyle="--", color="#111111")
    axes[1].set_xlabel("Decision threshold")
    axes[1].set_ylabel("Rows")
    axes[1].legend(loc="upper right")
    plt.tight_layout()
    plt.savefig(output_dir / "lightgbm_threshold_tradeoff.png", dpi=180)
    plt.close()


def save_probability_distribution(curve_data: dict[str, dict[str, Any]], output_dir: Path, model_name: str = "LightGBM") -> None:
    if model_name not in curve_data:
        return
    data = curve_data[model_name]
    frame = pd.DataFrame(
        {
            "predicted_probability": np.asarray(data["probabilities"]),
            "actual_class": np.where(np.asarray(data["y_true"]) == 1, "Reference high", "Reference low"),
        }
    )
    frame.to_csv(output_dir / "lightgbm_probability_distribution.csv", index=False, encoding="utf-8-sig")

    plt.figure(figsize=(9, 6))
    sns.histplot(
        data=frame,
        x="predicted_probability",
        hue="actual_class",
        bins=40,
        stat="density",
        common_norm=False,
        element="step",
        fill=True,
        alpha=0.28,
        palette={"Reference low": "#167f75", "Reference high": "#d64b4b"},
    )
    plt.title("LightGBM Predicted Probability Distribution")
    plt.xlabel("Predicted reference high probability")
    plt.ylabel("Density")
    plt.tight_layout()
    plt.savefig(output_dir / "lightgbm_probability_distribution.png", dpi=180)
    plt.close()


def stratified_sample(x: pd.DataFrame, y: pd.Series, n_rows: int, random_state: int) -> tuple[pd.DataFrame, pd.Series]:
    if len(x) <= n_rows:
        return x, y
    sample_size = n_rows / len(x)
    sampled_x, _, sampled_y, _ = train_test_split(
        x,
        y,
        train_size=sample_size,
        random_state=random_state,
        stratify=y,
    )
    return sampled_x, sampled_y


def save_learning_curves(
    specs: list[ModelSpec],
    x_train_valid: pd.DataFrame,
    y_train_valid: pd.Series,
    x_test: pd.DataFrame,
    y_test: pd.Series,
    output_dir: Path,
    max_rows: int,
    random_state: int,
) -> None:
    sampled_x, sampled_y = stratified_sample(x_train_valid, y_train_valid, max_rows, random_state)
    fractions = [0.1, 0.25, 0.5, 0.75, 1.0]
    records: list[dict[str, Any]] = []

    for spec in specs:
        for fraction in fractions:
            train_size = max(100, int(len(sampled_x) * fraction))
            train_size = min(train_size, len(sampled_x))
            if train_size >= len(sampled_x):
                subset_x = sampled_x
                subset_y = sampled_y
            else:
                subset_x, _, subset_y, _ = train_test_split(
                    sampled_x,
                    sampled_y,
                    train_size=train_size,
                    random_state=random_state + train_size,
                    stratify=sampled_y,
                )
            pipeline = build_pipeline(spec, subset_x)
            pipeline.fit(subset_x, subset_y)
            train_probabilities = pipeline.predict_proba(subset_x)[:, 1]
            test_probabilities = pipeline.predict_proba(x_test)[:, 1]
            records.append(
                {
                    "model": spec.display_name,
                    "model_key": spec.key,
                    "train_rows": int(len(subset_x)),
                    "train_pr_auc": float(average_precision_score(subset_y, train_probabilities)),
                    "test_pr_auc": float(average_precision_score(y_test, test_probabilities)),
                    "train_auroc": float(roc_auc_score(subset_y, train_probabilities)),
                    "test_auroc": float(roc_auc_score(y_test, test_probabilities)),
                }
            )

    frame = pd.DataFrame(records)
    frame.to_csv(output_dir / "learning_curves.csv", index=False, encoding="utf-8-sig")

    fig, axes = plt.subplots(2, 2, figsize=(12, 8), sharex=False, sharey=True)
    for axis, (model_name, group) in zip(axes.flat, frame.groupby("model", sort=False)):
        axis.plot(group["train_rows"], group["train_pr_auc"], marker="o", label="Train PR-AUC", color="#d64b4b")
        axis.plot(group["train_rows"], group["test_pr_auc"], marker="o", label="Hold-out PR-AUC", color="#167f75")
        axis.set_title(model_name)
        axis.set_xlabel("Training rows")
        axis.set_ylabel("PR-AUC")
        axis.grid(alpha=0.2)
        axis.legend(loc="lower right")
    plt.suptitle("Learning Curves by Model", fontsize=14, fontweight="bold")
    plt.tight_layout()
    plt.savefig(output_dir / "learning_curves.png", dpi=180)
    plt.close()


def save_decision_curve(curve_data: dict[str, dict[str, Any]], output_dir: Path) -> None:
    thresholds = np.linspace(0.01, 0.80, 160)
    records: list[dict[str, Any]] = []
    plt.figure(figsize=(9, 6))
    first_data = next(iter(curve_data.values()))
    prevalence = float(np.mean(first_data["y_true"]))
    treat_all = prevalence - (1 - prevalence) * thresholds / (1 - thresholds)
    plt.plot(thresholds, np.zeros_like(thresholds), linestyle="--", color="#777777", label="Treat none")
    plt.plot(thresholds, treat_all, linestyle=":", color="#555555", label="Treat all")

    for threshold, treat_all_value in zip(thresholds, treat_all):
        records.append({"model": "Treat none", "threshold": float(threshold), "net_benefit": 0.0})
        records.append({"model": "Treat all", "threshold": float(threshold), "net_benefit": float(treat_all_value)})

    for model_name, data in curve_data.items():
        y_true = np.asarray(data["y_true"])
        probabilities = np.asarray(data["probabilities"])
        net_benefits: list[float] = []
        for threshold in thresholds:
            predictions = (probabilities >= threshold).astype(int)
            tn, fp, fn, tp = confusion_matrix(y_true, predictions, labels=[0, 1]).ravel()
            net_benefit = (tp / len(y_true)) - (fp / len(y_true)) * (threshold / (1 - threshold))
            net_benefits.append(float(net_benefit))
            records.append({"model": model_name, "threshold": float(threshold), "net_benefit": float(net_benefit)})
        plt.plot(thresholds, net_benefits, linewidth=2, label=model_name)

    pd.DataFrame(records).to_csv(output_dir / "decision_curve_analysis.csv", index=False, encoding="utf-8-sig")
    plt.title("Decision Curve Analysis")
    plt.xlabel("Threshold probability")
    plt.ylabel("Net benefit")
    plt.legend(loc="upper right")
    plt.tight_layout()
    plt.savefig(output_dir / "decision_curve_analysis.png", dpi=180)
    plt.close()


def save_class_distribution(y: pd.Series, output_dir: Path) -> None:
    counts = y.value_counts().sort_index().rename(index={0: "Reference low", 1: "Reference high"})
    frame = counts.rename_axis("class").reset_index(name="count")
    frame["ratio"] = frame["count"] / frame["count"].sum()
    frame.to_csv(output_dir / "class_distribution.csv", index=False, encoding="utf-8-sig")

    plt.figure(figsize=(7, 5))
    sns.barplot(data=frame, x="class", y="count", color="#167f75")
    for index, row in frame.iterrows():
        plt.text(index, row["count"], f"{int(row['count']):,}\n{row['ratio']:.1%}", ha="center", va="bottom")
    plt.title("Target Class Distribution")
    plt.xlabel("")
    plt.ylabel("Rows")
    plt.tight_layout()
    plt.savefig(output_dir / "class_distribution.png", dpi=180)
    plt.close()


def markdown_table(frame: pd.DataFrame, columns: list[str]) -> str:
    table = frame[columns].copy()
    for column in columns:
        if column != "model":
            table[column] = table[column].map(lambda value: format_table_cell(column, value))
    widths = {
        column: max(len(column), *(len(str(value)) for value in table[column]))
        for column in columns
    }
    header = "| " + " | ".join(column.ljust(widths[column]) for column in columns) + " |"
    separator = "| " + " | ".join("-" * widths[column] for column in columns) + " |"
    rows = [
        "| " + " | ".join(str(row[column]).ljust(widths[column]) for column in columns) + " |"
        for _, row in table.iterrows()
    ]
    return "\n".join([header, separator, *rows])


def format_table_cell(column: str, value: Any) -> str:
    if column in {"tn", "fp", "fn", "tp"}:
        return str(int(value))
    if isinstance(value, str):
        return value
    if pd.isna(value):
        return ""
    try:
        return f"{float(value):.3f}"
    except (TypeError, ValueError):
        return str(value)


def cv_summary_table(cv_frame: pd.DataFrame | None) -> str:
    if cv_frame is None or cv_frame.empty:
        return "교차검증 결과가 생성되지 않았습니다."

    rows: list[dict[str, str]] = []
    for model, group in cv_frame.groupby("model"):
        row: dict[str, str] = {"model": str(model)}
        for metric in ["auroc", "pr_auc", "positive_precision", "positive_recall", "positive_f1", "accuracy"]:
            row[metric] = f"{group[metric].mean():.3f} ± {group[metric].std(ddof=1):.3f}"
        rows.append(row)
    return markdown_table(pd.DataFrame(rows), ["model", "auroc", "pr_auc", "positive_precision", "positive_recall", "positive_f1", "accuracy"])


def write_report(metrics: pd.DataFrame, metadata: dict[str, Any], output_dir: Path, cv_frame: pd.DataFrame | None) -> None:
    ranked = metrics.sort_values(["positive_recall", "positive_f1", "pr_auc"], ascending=False).reset_index(drop=True)
    best_overall = ranked.iloc[0]
    best_pr = metrics.sort_values(["pr_auc", "positive_f1", "positive_recall"], ascending=False).iloc[0]
    best_recall = metrics.sort_values(["positive_recall", "positive_f1", "pr_auc"], ascending=False).iloc[0]
    best_f1 = metrics.sort_values(["positive_f1", "pr_auc", "positive_recall"], ascending=False).iloc[0]
    lightgbm = ranked[ranked["model"] == "LightGBM"].iloc[0] if (ranked["model"] == "LightGBM").any() else None
    positive_rate = metadata["positive_rows"] / metadata["training_rows"]
    excluded_policy = metadata.get("screening_feature_policy", {})
    excluded_policy_columns = excluded_policy.get("excluded_to_reduce_leakage_or_dataset_artifacts", [])
    kept_context_columns = excluded_policy.get("kept_context_columns", [])
    empty_feature_columns = metadata.get("dropped_empty_feature_columns", [])
    source_counts = metadata.get("source_dataset_counts", {})
    source_summary = ", ".join(f"{key}: {value:,}명" for key, value in source_counts.items()) if source_counts else "단일 입력 데이터"
    metric_table = markdown_table(
        ranked,
        ["model", "auroc", "pr_auc", "positive_precision", "positive_recall", "positive_f1", "balanced_accuracy"],
    )
    confusion_table = markdown_table(ranked, ["model", "tn", "fp", "fn", "tp", "threshold"])
    cv_table = cv_summary_table(cv_frame)
    threshold_strategy = str(metrics.iloc[0].get("threshold_strategy", ""))
    target_recall = metrics.iloc[0].get("target_recall", None)
    threshold_policy = (
        f"검증 세트에서 positive recall {float(target_recall):.0%} 이상을 먼저 만족하고, 그 안에서 precision이 가장 높은 임계값을 선택했습니다."
        if threshold_strategy == "target_recall" and pd.notna(target_recall)
        else "검증 세트에서 positive F1이 가장 높은 임계값을 선택했습니다."
    )
    lightgbm_note = ""
    if lightgbm is not None and best_overall["model"] != "LightGBM":
        lightgbm_note = (
            f"\n\n웹앱에 현재 연결된 LightGBM은 recall 기준 1위는 아니지만, "
            f"positive recall {lightgbm['positive_recall']:.3f}, PR-AUC {lightgbm['pr_auc']:.3f}로 비교 후보들과 함께 검토할 수 있습니다. "
            f"따라서 발표에서는 '현재 운영 모델은 LightGBM이며, 조기 선별 목적에 맞춰 recall 중심 임계값을 사용했다'고 설명하는 편이 가장 정직합니다."
        )

    report = f"""# ML 모델 벤치마크 리포트

## 목적

이 리포트는 청년 우울 관련 참고 위험도 추정 모델 후보를 같은 데이터 분할에서 비교하기 위해 생성했습니다. 의료 진단 목적이 아니라, 웹앱에서 전문가 검토를 보조할 수 있는 참고 지표 모델을 고르기 위한 실험입니다.

## 데이터 설정

- 전체 원자료 행 수: {metadata["source_rows"]:,}
- 학습 대상 행 수: {metadata["training_rows"]:,}
- 연령 범위: {metadata["age_filter"][0]}~{metadata["age_filter"][1]}세
- 참고 라벨: `{metadata["target_column"]} >= {metadata["threshold"]}`
- 참고 고위험군: {metadata["positive_rows"]:,}명 ({positive_rate:.1%})
- 참고 저위험군: {metadata["negative_rows"]:,}명 ({1 - positive_rate:.1%})
- 자료원 구성: {source_summary}
- 제외 변수: PHQ-9 개별 문항과 PHQ-9 총점은 입력 변수에서 제외했습니다. 총점으로 만든 라벨을 다시 입력으로 넣는 데이터 누수를 막기 위해서입니다.
- 선별 변수 정책: 데이터 출처, 조사연도, 직접 우울 경험 문항, 정신건강 상담 경험처럼 정답 힌트 또는 조사 구조를 학습할 수 있는 변수는 제외했습니다.
- 제외한 선별 변수: {", ".join(f"`{column}`" for column in excluded_policy_columns) if excluded_policy_columns else "없음"}
- 단일 자료원에서 값이 없어 제외한 변수: {", ".join(f"`{column}`" for column in empty_feature_columns) if empty_feature_columns else "없음"}
- 유지한 맥락 변수: {", ".join(f"`{column}`" for column in kept_context_columns) if kept_context_columns else "없음"}
- 임계값 선택 정책: {threshold_policy}

## 비교 모델

- Logistic Regression: 해석이 쉽고 기준선 모델로 적합합니다.
- Random Forest: 비선형 관계와 변수 상호작용을 비교적 안정적으로 잡습니다.
- XGBoost: 불균형 tabular 데이터에서 강한 성능을 보이는 gradient boosting 계열 모델입니다.
- LightGBM: 대규모 tabular 데이터에서 빠르고 성능이 좋은 gradient boosting 계열 모델입니다.

## 성능 비교

{metric_table}

## 혼동행렬

{confusion_table}

## 5-Fold 교차검증 요약

아래 값은 기본 임계값 0.5 기준의 평균 ± 표준편차입니다. 검증 분할에서 조정한 임계값 기반 테스트 성능과 직접 1:1로 같지는 않지만, 모델의 전반적 안정성을 확인하는 용도입니다.

{cv_table}

## 종합 판단

이번 실험에서는 임의의 종합 점수 대신 positive recall, PR-AUC, positive F1, 혼동행렬을 함께 보았습니다. 조기 선별 목적에서는 참고 고위험군을 놓치지 않는 것이 중요하므로, 검증 세트에서 recall 목표를 먼저 만족하는 방향으로 임계값을 조정했습니다. 단순 Accuracy는 참고 저위험군 비율이 높을 때 과대평가될 수 있어 핵심 판단 지표로 강조하지 않았습니다.

- Recall 기준 상위 모델: **{best_recall["model"]}** ({best_recall["positive_recall"]:.3f})
- PR-AUC 기준 상위 모델: **{best_pr["model"]}** ({best_pr["pr_auc"]:.3f})
- F1 기준 상위 모델: **{best_f1["model"]}** ({best_f1["positive_f1"]:.3f})

다만 이 결과는 현재 XLSX 원자료와 현재 변수 조합 안에서의 비교이므로, 외부 데이터 검증이나 추가 원자료가 들어오면 다시 비교해야 합니다.{lightgbm_note}

## 추가 시각화 자료

- `publication_benchmark_tables.png`: 발표용 성능표와 혼동행렬 요약표입니다.
- `calibration_curves.png`: 모델이 출력한 확률과 실제 참고 고위험군 비율이 얼마나 맞는지 확인하는 보정 곡선입니다.
- `lightgbm_threshold_tradeoff.png`: LightGBM 임계값을 바꿀 때 Precision, Recall, F1, TP/FP/FN이 어떻게 변하는지 보여줍니다.
- `lightgbm_probability_distribution.png`: 실제 참고 저위험군/고위험군의 예측확률 분포가 얼마나 분리되는지 보여주는 히스토그램입니다.
- `learning_curves.png`: 학습 데이터 크기가 늘어날 때 모델별 train/hold-out PR-AUC가 어떻게 변하는지 보여줍니다.
- `decision_curve_analysis.png`: 여러 임계값에서 모델 사용 전략의 순편익을 확인하는 보조 그래프입니다. 현재 결과는 실사용 이득을 단정하기보다 참고 분석으로 해석합니다.
- `lightgbm_outcome_group_contributions.png`: TP/FP/FN/TN 집단별 평균 SHAP 기여도 비교 그래프입니다.

## 보고서에 넣기 좋은 해석

본 프로젝트에서는 Logistic Regression, Random Forest, XGBoost, LightGBM을 동일한 전처리 조건에서 비교하였다. 참고 고위험군 비율이 낮은 불균형 데이터 특성을 고려하여 Accuracy만 보지 않고 AUROC, PR-AUC, Precision, Recall, F1-score, Confusion Matrix를 함께 확인하였다. 또한 데이터 출처, 조사연도, 직접 우울 경험 문항, 정신건강 상담 경험처럼 정답 힌트 또는 조사 구조를 학습할 수 있는 변수는 제외하여 실제 설문·건강행태·생활습관 변수 중심의 참고 위험도 추정 모델이 되도록 조정하였다. 조기 선별 목적을 반영하기 위해 판별 임계값은 positive recall을 우선 확보하는 방향으로 설정하였다.

## 한계

- 현재 데이터는 횡단자료이므로 미래 발병 예측보다는 현재 우울 관련 신호의 참고 위험도 추정에 가깝습니다.
- PHQ-9 기반 참고 라벨은 유용하지만 의료 진단 라벨은 아닙니다.
- 사회적 고립, 사회적 지지, 주거 안정성, 자아존중감 같은 문헌상 중요한 변수가 충분히 포함되지 않았습니다.
- 외부 검증 데이터가 없으므로 일반화 성능은 추가 검증이 필요합니다.
"""
    (output_dir / "benchmark_report.md").write_text(report, encoding="utf-8")


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
        include_phq_items=False,
        drop_feature_columns=args.drop_columns,
    )
    save_class_distribution(y, output_dir)

    x_train_valid, x_test, y_train_valid, y_test = train_test_split(
        x,
        y,
        test_size=args.test_size,
        random_state=args.random_state,
        stratify=y,
    )
    x_train, x_valid, y_train, y_valid = train_test_split(
        x_train_valid,
        y_train_valid,
        test_size=args.validation_size,
        random_state=args.random_state,
        stratify=y_train_valid,
    )

    metric_records: list[dict[str, Any]] = []
    cv_records: list[dict[str, Any]] = []
    curve_data: dict[str, dict[str, Any]] = {}

    specs = model_specs(y_train, args.random_state)

    for spec in specs:
        eval_pipeline = build_pipeline(spec, x_train)
        eval_pipeline.fit(x_train, y_train)
        validation_probabilities = eval_pipeline.predict_proba(x_valid)[:, 1]
        threshold_selection = choose_threshold(
            y_valid,
            validation_probabilities,
            args.threshold_strategy,
            args.target_recall,
        )
        selected_threshold = threshold_selection["threshold"]
        test_probabilities = eval_pipeline.predict_proba(x_test)[:, 1]
        metrics = evaluate(y_test, test_probabilities, selected_threshold)
        metrics.update(
            {
                "model": spec.display_name,
                "model_key": spec.key,
                "threshold_strategy": args.threshold_strategy,
                "target_recall": args.target_recall if args.threshold_strategy == "target_recall" else np.nan,
                "validation_threshold_precision": threshold_selection.get("precision"),
                "validation_threshold_recall": threshold_selection.get("recall"),
                "validation_threshold_f1": threshold_selection.get("f1"),
            }
        )
        metric_records.append(metrics)
        curve_data[spec.display_name] = {
            "y_true": y_test,
            "probabilities": test_probabilities,
            "auroc": metrics["auroc"],
            "pr_auc": metrics["pr_auc"],
        }
        if spec.key == "lightgbm":
            save_lightgbm_outcome_contributions(
                eval_pipeline,
                x_test,
                y_test,
                test_probabilities,
                selected_threshold,
                output_dir,
            )

        final_pipeline = build_pipeline(spec, x_train_valid)
        final_pipeline.fit(x_train_valid, y_train_valid)
        joblib.dump(final_pipeline, output_dir / f"{spec.key}_pipeline.joblib")
        export_feature_importance(spec, final_pipeline, output_dir)

        cv_result = cross_validate(spec, x, y, args.cv_folds, args.random_state)
        if cv_result is not None:
            for fold in cv_result["fold_metrics"]:
                cv_records.append({"model": spec.display_name, "model_key": spec.key, **fold})

    metrics_frame = pd.DataFrame(metric_records)
    metrics_frame = metrics_frame.sort_values(["positive_recall", "positive_f1", "pr_auc"], ascending=False)
    metrics_frame.to_csv(output_dir / "benchmark_metrics.csv", index=False, encoding="utf-8-sig")
    (output_dir / "benchmark_metrics.json").write_text(
        json.dumps(metric_records, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    cv_frame = pd.DataFrame(cv_records) if cv_records else None
    if cv_frame is not None:
        cv_frame.to_csv(output_dir / "cross_validation_metrics.csv", index=False, encoding="utf-8-sig")

    confusion_frame = metrics_frame[["model", "tn", "fp", "fn", "tp", "threshold"]]
    confusion_frame.to_csv(output_dir / "confusion_matrices.csv", index=False, encoding="utf-8-sig")
    (output_dir / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    save_metric_plots(metrics_frame, curve_data, output_dir)
    save_publication_tables(metrics_frame, output_dir)
    save_calibration_plots(curve_data, output_dir)
    save_threshold_tradeoff(metrics_frame, curve_data, output_dir)
    save_probability_distribution(curve_data, output_dir)
    save_learning_curves(
        specs,
        x_train_valid,
        y_train_valid,
        x_test,
        y_test,
        output_dir,
        args.learning_curve_max_rows,
        args.random_state,
    )
    save_decision_curve(curve_data, output_dir)
    write_report(metrics_frame, metadata, output_dir, cv_frame)

    print(
        json.dumps(
            {
                "output_dir": str(output_dir),
                "primary_ranking_metric": "positive_recall",
                "top_recall_model": metrics_frame.iloc[0]["model"],
                "threshold_strategy": args.threshold_strategy,
                "target_recall": args.target_recall if args.threshold_strategy == "target_recall" else None,
                "dropped_columns": sorted(parse_drop_columns(args.drop_columns)),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
