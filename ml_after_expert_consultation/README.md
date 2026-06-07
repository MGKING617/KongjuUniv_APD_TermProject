# 전문가 상담 후 ML 실험 폴더

이 폴더는 기존 `ml` 폴더를 삭제하거나 수정하지 않고, 전문가 상담 내용을 반영해 따로 만든 재전처리 및 재학습 실험 폴더이다.

## 변경한 내용

- 새 폴더 이름: `ml_after_expert_consultation`
- 새 전처리 데이터: `data/processed/chs_training_0519_recoded_expert_consultation.csv`
- 새 모델 산출물 위치: `ml_after_expert_consultation/artifacts`
- 새 벤치마크 리포트 위치: `ml_after_expert_consultation/reports/benchmark`
- 참고 라벨 기준은 기존과 동일하게 `mh_PHQ_S >= 10`으로 유지하였다.
- 모델 판정 임계값은 요청 조건에 맞춰 `0.59`로 고정하였다.

## 변수 구성

유지한 변수:

- `age`
- `single_person_household`
- `smoking_status`
- `alcohol_frequency`
- `walking_days`
- `weight_control`
- `hypertension_diagnosis`
- `diabetes_diagnosis`
- `accident_poisoning_experience`
- `basic_livelihood_recipient`
- `economic_activity`
- `sex`
- `region_group`
- `marital_status`
- `stress_level`

변경한 변수:

- 기존 `household_size`는 인원수 그대로 사용하지 않고 `single_person_household`로 재범주화하였다.
- 1인 가구면 `1`, 2인 이상 가구면 `0`으로 처리하였다.

제외한 변수:

- `subjective_health`
- `body_self_thin`
- `body_self_overweight`
- `breakfast_frequency`
- `education_level`

## 주요 실행 명령

```powershell
& 'C:\Users\abmak\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' ml_after_expert_consultation\src\prepare_0519_recoded_training_data.py

$env:PYTHONPATH=(Resolve-Path 'outputs\python_deps').Path
& 'C:\Users\abmak\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' ml_after_expert_consultation\src\train_model.py --input data\processed\chs_training_0519_recoded_expert_consultation.csv --output-dir ml_after_expert_consultation\artifacts --min-age 19 --max-age 39

$env:PYTHONPATH=(Resolve-Path 'outputs\python_deps').Path
& 'C:\Users\abmak\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' ml_after_expert_consultation\src\benchmark_models.py --input data\processed\chs_training_0519_recoded_expert_consultation.csv --output-dir ml_after_expert_consultation\reports\benchmark --min-age 19 --max-age 39 --fixed-decision-threshold 0.59
```

## 해석 주의

이 폴더의 결과는 기존 `ml` 폴더를 대체한 것이 아니라, 전문가 상담 이후 변수 구성을 바꿨을 때 성능과 변수 중요도가 어떻게 달라지는지 확인하기 위한 별도 실험이다.
