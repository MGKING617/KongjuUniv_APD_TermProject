from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "config" / "feature_schema.json"
MODEL_DIR = ROOT / "models"


def main() -> None:
    try:
        import joblib
        import lightgbm as lgb
        import shap
        from sklearn.compose import ColumnTransformer
        from sklearn.impute import SimpleImputer
        from sklearn.metrics import classification_report, roc_auc_score
        from sklearn.model_selection import train_test_split
        from sklearn.pipeline import Pipeline
        from sklearn.preprocessing import OneHotEncoder, StandardScaler
    except ImportError as exc:
        raise SystemExit(
            "LightGBM/SHAP 학습에는 requirements-ml.txt 패키지가 필요합니다. "
            "먼저 `pip install -r requirements-ml.txt`를 실행하세요.\n"
            f"누락된 패키지: {exc}"
        )

    parser = argparse.ArgumentParser()
    parser.add_argument("--xlsx", required=True, help="국건영 XLSX 원자료 경로")
    parser.add_argument("--sheet", default="최종분석용")
    parser.add_argument("--sample-shap", type=int, default=800)
    args = parser.parse_args()

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    df = pd.read_excel(args.xlsx, sheet_name=args.sheet)
    if "id" in df.columns and "ID" not in df.columns:
        df = df.rename(columns={"id": "ID"})

    target_col = schema["target"]["column"]
    df = df[df[target_col].notna()].copy()
    y = (pd.to_numeric(df[target_col], errors="coerce") >= schema["target"]["positive_cutoff"]).astype(int)

    features = schema["numeric_features"] + schema["categorical_features"]
    x = df[features].copy()
    for col, codes in schema.get("missing_numeric_codes", {}).items():
        if col in x.columns:
            x[col] = pd.to_numeric(x[col], errors="coerce")
            x.loc[x[col].isin(codes), col] = pd.NA

    x_train, x_valid, y_train, y_valid = train_test_split(
        x, y, test_size=0.2, random_state=42, stratify=y
    )

    preprocessor = ColumnTransformer(
        transformers=[
            (
                "num",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="median")),
                        ("scaler", StandardScaler()),
                    ]
                ),
                schema["numeric_features"],
            ),
            (
                "cat",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="most_frequent")),
                        ("onehot", OneHotEncoder(handle_unknown="ignore")),
                    ]
                ),
                schema["categorical_features"],
            ),
        ]
    )

    classifier = lgb.LGBMClassifier(
        objective="binary",
        n_estimators=400,
        learning_rate=0.035,
        num_leaves=24,
        class_weight="balanced",
        random_state=42,
    )
    pipeline = Pipeline(steps=[("preprocess", preprocessor), ("model", classifier)])
    pipeline.fit(x_train, y_train)

    valid_proba = pipeline.predict_proba(x_valid)[:, 1]
    report = {
        "roc_auc": float(roc_auc_score(y_valid, valid_proba)),
        "classification_report_at_0_5": classification_report(
            y_valid, valid_proba >= 0.5, output_dict=True, zero_division=0
        ),
        "rows": int(len(df)),
        "positive_rows": int(y.sum()),
        "negative_rows": int(len(y) - y.sum()),
        "source_file": str(Path(args.xlsx).resolve()),
        "source_sheet": args.sheet,
    }

    transformed = pipeline.named_steps["preprocess"].transform(
        x_valid.sample(min(args.sample_shap, len(x_valid)), random_state=42)
    )
    feature_names = pipeline.named_steps["preprocess"].get_feature_names_out()
    explainer = shap.TreeExplainer(pipeline.named_steps["model"])
    shap_values = explainer.shap_values(transformed)
    if isinstance(shap_values, list):
        shap_values = shap_values[1]
    shap_summary = (
        pd.DataFrame({"feature": feature_names, "mean_abs_shap": abs(shap_values).mean(axis=0)})
        .sort_values("mean_abs_shap", ascending=False)
        .head(50)
    )

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(pipeline, MODEL_DIR / "lightgbm_pipeline.joblib")
    (MODEL_DIR / "lightgbm_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    shap_summary.to_csv(MODEL_DIR / "lightgbm_shap_summary.csv", index=False, encoding="utf-8-sig")
    print(f"saved: {MODEL_DIR / 'lightgbm_pipeline.joblib'}")
    print(f"saved: {MODEL_DIR / 'lightgbm_report.json'}")
    print(f"saved: {MODEL_DIR / 'lightgbm_shap_summary.csv'}")


if __name__ == "__main__":
    main()

