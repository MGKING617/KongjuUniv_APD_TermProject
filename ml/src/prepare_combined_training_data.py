from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd


KNHANES_REGION_MAP = {
    1: "seoul",
    2: "busan",
    3: "daegu",
    4: "incheon",
    5: "gwangju",
    6: "daejeon",
    7: "ulsan",
    8: "sejong",
    9: "gyeonggi",
    10: "gangwon",
    11: "chungbuk",
    12: "chungnam",
    13: "jeonbuk",
    14: "jeonnam",
    15: "gyeongbuk",
    16: "gyeongnam",
    17: "jeju",
}

CHS_REGION_MAP = {
    11: "seoul",
    26: "busan",
    27: "daegu",
    28: "incheon",
    29: "gwangju",
    30: "daejeon",
    31: "ulsan",
    36: "sejong",
    41: "gyeonggi",
    42: "gangwon",
    43: "chungbuk",
    44: "chungnam",
    45: "jeonbuk",
    46: "jeonnam",
    47: "gyeongbuk",
    48: "gyeongnam",
    50: "jeju",
}

OUTPUT_COLUMNS = [
    "source_dataset",
    "year",
    "region_group",
    "age",
    "sex",
    "household_size",
    "income_level",
    "monthly_household_income",
    "education_level",
    "marital_status",
    "basic_livelihood_recipient",
    "economic_activity",
    "occupation_category",
    "work_hours_per_week",
    "subjective_health",
    "subjective_oral_health",
    "unmet_medical_care",
    "accident_poisoning_experience",
    "hypertension_diagnosis",
    "dyslipidemia_diagnosis",
    "diabetes_diagnosis",
    "body_image",
    "weight_change",
    "weight_control",
    "alcohol_frequency",
    "smoking_status",
    "walking_days",
    "muscle_exercise_days",
    "breakfast_frequency",
    "lunch_frequency",
    "dinner_frequency",
    "breakfast_companion",
    "lunch_companion",
    "dinner_companion",
    "stress_level",
    "depressive_mood_experience",
    "mental_counseling_experience",
    "mh_PHQ_S",
]

KNHANES_PRESERVE_COLUMNS = {"year", "age", "sex", "region"}
KNHANES_MISSING_CODES = {8, 9, 88, 99, 888, 999}
CHS_MISSING_CODES = {7, 8, 9, 77, 88, 99, 888, 999}
CHS_VALID_EIGHT_MISSING_CODES = {77, 88, 99}
CHS_PHQ_COLUMNS = [f"mtb_07{suffix}1" for suffix in list("abcdefghi")]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare harmonized KNHANES and/or CHS training CSV.")
    parser.add_argument(
        "--source",
        choices=["combined", "chs", "knhanes"],
        default="combined",
        help="Which source data to export. Use chs for the Community Health Survey-only model.",
    )
    parser.add_argument("--knhanes", help="KNHANES XLSX/CSV path.")
    parser.add_argument("--knhanes-sheet", default="최종분석용")
    parser.add_argument("--chs", help="Community Health Survey XLSX path.")
    parser.add_argument("--chs-sheet", default="통합")
    parser.add_argument("--output", default="data/processed/combined_training_0515.csv")
    parser.add_argument("--min-age", type=int, default=19)
    parser.add_argument("--max-age", type=int, default=39)
    return parser.parse_args()


def read_table(path: str, sheet: str | None = None) -> pd.DataFrame:
    source = Path(path)
    if source.suffix.lower() in {".xlsx", ".xls"}:
        return pd.read_excel(source, sheet_name=sheet)
    return pd.read_csv(source)


def numeric(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce")


def replace_codes(series: pd.Series, missing_codes: set[int | float]) -> pd.Series:
    values = numeric(series)
    return values.mask(values.isin(missing_codes))


def optional_column(frame: pd.DataFrame, column: str) -> pd.Series:
    if column in frame.columns:
        return frame[column]
    return pd.Series(np.nan, index=frame.index, dtype="float")


def yes_no_from_codes(series: pd.Series, yes_codes: set[int], no_codes: set[int], missing_codes: set[int | float]) -> pd.Series:
    values = replace_codes(series, missing_codes)
    result = pd.Series(np.nan, index=series.index, dtype="float")
    result[values.isin(yes_codes)] = 1.0
    result[values.isin(no_codes)] = 0.0
    return result


def clamp_days(series: pd.Series) -> pd.Series:
    values = numeric(series)
    return values.where(values.between(0, 7))


def map_knhanes_walking(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    return values.map({1: 0, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6, 8: 7})


def map_knhanes_muscle(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    return values.map({1: 0, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5})


def map_knhanes_education(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    return values.where(values.between(1, 4))


def map_chs_education(series: pd.Series) -> pd.Series:
    values = replace_codes(series, CHS_MISSING_CODES)
    result = pd.Series(np.nan, index=series.index, dtype="float")
    result[values.isin([1, 2, 3])] = 1.0
    result[values.eq(4)] = 2.0
    result[values.eq(5)] = 3.0
    result[values.isin([6, 7, 8])] = 4.0
    return result


def map_knhanes_marital(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    return values.map({1: 1, 2: 5})


def map_chs_marital(series: pd.Series) -> pd.Series:
    values = replace_codes(series, CHS_MISSING_CODES)
    return values.where(values.between(1, 5))


def map_region(series: pd.Series, mapping: dict[int, str]) -> pd.Series:
    values = numeric(series)
    return values.map(mapping).fillna("unknown")


def map_knhanes_smoking(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    # 1/2: lifetime smoking exposure, 3: no exposure. Kept ordinal for screening use.
    return values.map({1: 2, 2: 3, 3: 0})


def map_chs_smoking(series: pd.Series) -> pd.Series:
    values = replace_codes(series, CHS_MISSING_CODES)
    return values.map({1: 3, 2: 2, 3: 1})


def map_weight_change(series: pd.Series) -> pd.Series:
    values = replace_codes(series, KNHANES_MISSING_CODES)
    return values.where(values.between(1, 3))


def prepare_knhanes(path: str, sheet: str, min_age: int, max_age: int) -> pd.DataFrame:
    raw = read_table(path, sheet)
    out = pd.DataFrame(index=raw.index)
    out["source_dataset"] = "knhanes"
    out["year"] = numeric(raw.get("year"))
    out["region_group"] = map_region(raw.get("region"), KNHANES_REGION_MAP)
    out["age"] = numeric(raw.get("age"))
    out["sex"] = numeric(raw.get("sex")).where(numeric(raw.get("sex")).isin([1, 2]))
    out["household_size"] = replace_codes(raw.get("cfam"), KNHANES_MISSING_CODES)
    out["income_level"] = replace_codes(raw.get("incm"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["monthly_household_income"] = np.nan
    out["education_level"] = map_knhanes_education(raw.get("edu"))
    out["marital_status"] = map_knhanes_marital(raw.get("marri_1"))
    out["basic_livelihood_recipient"] = yes_no_from_codes(raw.get("allownc"), {10}, {20}, {99})
    out["economic_activity"] = yes_no_from_codes(raw.get("EC1_1"), {1}, {2}, {8, 9})
    out["occupation_category"] = np.nan
    out["work_hours_per_week"] = replace_codes(raw.get("EC_wht_23"), KNHANES_MISSING_CODES)
    out["subjective_health"] = replace_codes(raw.get("D_1_1"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 5))
    out["subjective_oral_health"] = np.nan
    out["unmet_medical_care"] = np.nan
    out["accident_poisoning_experience"] = np.nan
    out["hypertension_diagnosis"] = yes_no_from_codes(raw.get("DI1_dg"), {1}, {0}, {8, 9})
    out["dyslipidemia_diagnosis"] = yes_no_from_codes(raw.get("DI2_dg"), {1}, {0}, {8, 9})
    out["diabetes_diagnosis"] = yes_no_from_codes(raw.get("DE1_dg"), {1}, {0}, {8, 9})
    out["body_image"] = replace_codes(raw.get("BO1"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 5))
    out["weight_change"] = map_weight_change(raw.get("BO1_1"))
    out["weight_control"] = replace_codes(raw.get("BO2_1"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["alcohol_frequency"] = replace_codes(raw.get("BD1_11"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 6))
    out["smoking_status"] = map_knhanes_smoking(raw.get("BS3_1"))
    out["walking_days"] = map_knhanes_walking(raw.get("BE3_31"))
    out["muscle_exercise_days"] = map_knhanes_muscle(raw.get("BE5_1"))
    out["breakfast_frequency"] = replace_codes(raw.get("L_BR_FQ"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["lunch_frequency"] = replace_codes(raw.get("L_LN_FQ"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["dinner_frequency"] = replace_codes(raw.get("L_DN_FQ"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["breakfast_companion"] = replace_codes(raw.get("L_BR_TO"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 3))
    out["lunch_companion"] = replace_codes(raw.get("L_LN_TO"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 3))
    out["dinner_companion"] = replace_codes(raw.get("L_DN_TO"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 3))
    out["stress_level"] = replace_codes(raw.get("BP1"), KNHANES_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["depressive_mood_experience"] = np.nan
    out["mental_counseling_experience"] = np.nan
    out["mh_PHQ_S"] = numeric(raw.get("mh_PHQ_S"))
    out = out[OUTPUT_COLUMNS]
    out = out[out["age"].between(min_age, max_age) & out["mh_PHQ_S"].notna()]
    return out


def prepare_chs(path: str, sheet: str, min_age: int, max_age: int) -> pd.DataFrame:
    raw = read_table(path, sheet)
    phq = raw[CHS_PHQ_COLUMNS].apply(numeric)
    valid_phq = phq.where(phq.isin([1, 2, 3, 4]))
    phq_score = (valid_phq - 1).sum(axis=1, min_count=len(CHS_PHQ_COLUMNS))

    out = pd.DataFrame(index=raw.index)
    out["source_dataset"] = "community_health_survey"
    out["year"] = numeric(raw.get("EXAMIN_YEAR"))
    out["region_group"] = map_region(raw.get("CTPRVN_CODE"), CHS_REGION_MAP)
    out["age"] = numeric(raw.get("age"))
    out["sex"] = numeric(raw.get("sex")).where(numeric(raw.get("sex")).isin([1, 2]))
    out["household_size"] = replace_codes(raw.get("mbhld_co"), CHS_MISSING_CODES)
    out["income_level"] = np.nan
    out["monthly_household_income"] = replace_codes(
        optional_column(raw, "fma_24z2"),
        CHS_VALID_EIGHT_MISSING_CODES,
    ).where(lambda s: s.between(1, 8))
    out["education_level"] = map_chs_education(raw.get("sob_01z1"))
    out["marital_status"] = map_chs_marital(raw.get("sod_02z3"))
    out["basic_livelihood_recipient"] = yes_no_from_codes(raw.get("fma_04z1"), {1, 2}, {3}, CHS_MISSING_CODES)
    out["economic_activity"] = yes_no_from_codes(raw.get("soa_01z1"), {1}, {2}, CHS_MISSING_CODES)
    out["occupation_category"] = replace_codes(
        optional_column(raw, "soa_06z2"),
        CHS_VALID_EIGHT_MISSING_CODES,
    ).where(lambda s: s.between(1, 10))
    out["work_hours_per_week"] = np.nan
    out["subjective_health"] = replace_codes(raw.get("qoa_01z1"), CHS_MISSING_CODES).where(lambda s: s.between(1, 5))
    out["subjective_oral_health"] = replace_codes(
        optional_column(raw, "ora_01z1"),
        CHS_MISSING_CODES,
    ).where(lambda s: s.between(1, 5))
    out["unmet_medical_care"] = yes_no_from_codes(
        optional_column(raw, "sra_01z3"),
        {1},
        {2, 3},
        CHS_MISSING_CODES,
    )
    out["accident_poisoning_experience"] = yes_no_from_codes(
        optional_column(raw, "ira_01z1"),
        {1},
        {2},
        CHS_MISSING_CODES,
    )
    out["hypertension_diagnosis"] = yes_no_from_codes(raw.get("hya_04z1"), {1}, {2}, CHS_MISSING_CODES)
    out["dyslipidemia_diagnosis"] = np.nan
    out["diabetes_diagnosis"] = yes_no_from_codes(raw.get("dia_04z1"), {1}, {2}, CHS_MISSING_CODES)
    out["body_image"] = replace_codes(raw.get("oba_01z1"), CHS_MISSING_CODES).where(lambda s: s.between(1, 5))
    out["weight_change"] = np.nan
    out["weight_control"] = replace_codes(raw.get("obb_01z1"), CHS_MISSING_CODES).where(lambda s: s.between(1, 5))
    out["alcohol_frequency"] = replace_codes(raw.get("drb_01z3"), CHS_MISSING_CODES).where(lambda s: s.between(1, 6))
    out["smoking_status"] = map_chs_smoking(raw.get("sma_03z2"))
    out["walking_days"] = clamp_days(raw.get("phb_01z1"))
    out["muscle_exercise_days"] = np.nan
    out["breakfast_frequency"] = replace_codes(raw.get("nua_01z2"), CHS_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["lunch_frequency"] = np.nan
    out["dinner_frequency"] = np.nan
    out["breakfast_companion"] = np.nan
    out["lunch_companion"] = np.nan
    out["dinner_companion"] = np.nan
    out["stress_level"] = replace_codes(raw.get("mta_01z1"), CHS_MISSING_CODES).where(lambda s: s.between(1, 4))
    out["depressive_mood_experience"] = yes_no_from_codes(raw.get("mtb_01z1"), {1}, {2}, CHS_MISSING_CODES)
    # Non-applicable means no counseling due to depressive symptoms in this survey flow.
    out["mental_counseling_experience"] = yes_no_from_codes(raw.get("mtb_02z1"), {1}, {2, 8}, {7, 9})
    out["mh_PHQ_S"] = phq_score
    out = out[OUTPUT_COLUMNS]
    out = out[out["age"].between(min_age, max_age) & out["mh_PHQ_S"].notna()]
    return out


def summarize(frame: pd.DataFrame) -> dict[str, Any]:
    target = (frame["mh_PHQ_S"] >= 10).astype(int)
    by_source = frame.assign(reference_high=target).groupby("source_dataset").agg(
        rows=("mh_PHQ_S", "size"),
        positive_rows=("reference_high", "sum"),
        mean_phq=("mh_PHQ_S", "mean"),
    )
    by_source["positive_rate"] = by_source["positive_rows"] / by_source["rows"]
    return {
        "rows": int(len(frame)),
        "positive_rows": int(target.sum()),
        "negative_rows": int((1 - target).sum()),
        "positive_rate": float(target.mean()),
        "columns": list(frame.columns),
        "by_source": by_source.reset_index().to_dict(orient="records"),
    }


def main() -> None:
    args = parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    frames: list[pd.DataFrame] = []
    if args.source in {"combined", "knhanes"}:
        if not args.knhanes:
            raise ValueError("--knhanes is required when --source is combined or knhanes.")
        frames.append(prepare_knhanes(args.knhanes, args.knhanes_sheet, args.min_age, args.max_age))
    if args.source in {"combined", "chs"}:
        if not args.chs:
            raise ValueError("--chs is required when --source is combined or chs.")
        frames.append(prepare_chs(args.chs, args.chs_sheet, args.min_age, args.max_age))

    combined = pd.concat(frames, ignore_index=True)
    combined.to_csv(output, index=False, encoding="utf-8-sig")

    summary = summarize(combined)
    summary_path = output.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "summary": summary}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
