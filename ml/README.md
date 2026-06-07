# ML 학습 파이프라인

이 폴더는 첨부 XLSX 원자료에서 청년층 행을 추출해 `mh_PHQ_S >= 10` 여부를 참고 라벨로 만들고, 생활습관·인구사회학 변수 기반 위험도 추정 모델을 학습합니다.

중요한 점:

- 이 모델은 우울증 진단기가 아니라 전문가 검토 보조용 참고 위험도 추정 모델입니다.
- 기본 설정은 `BP_PHQ_1~9` 문항을 입력 변수에서 제외합니다. `mh_PHQ_S`가 PHQ 문항 합산값이므로, 문항을 넣으면 데이터 누수가 발생합니다.
- 현재 메인 모델은 `0515) 지역사회건강조사 코드북 (추가).xlsx`의 `통합` 시트만 사용합니다. 자료원 간 변수 정의 차이를 줄이기 위해 0414 국건영 자료는 메인 모델 학습에서 제외했습니다.
- 0515 추가 자료에서 구강건강, 미충족 의료, 사고·중독 경험, 직업분류 변수를 추가 반영했습니다. 월 가구소득 변수는 응답 유효 비율이 낮아 CSV에는 보존하지만 기본 학습 feature에서는 제외합니다.
- 기본 선별 변수 정책은 `source_dataset`, `year`, `monthly_household_income`, `depressive_mood_experience`, `mental_counseling_experience`를 입력 변수에서 제외합니다. 데이터 출처, 조사연도, 낮은 유효응답 변수, 직접 우울 경험 문항, 상담 경험처럼 정답 힌트나 조사 구조를 학습할 수 있는 변수를 줄이기 위해서입니다.
- `region_group`은 지역 맥락을 반영하는 보조 변수로 유지하지만, 개인의 직접 위험요인처럼 해석하지 않습니다.
- 현재 기본 판별 임계값 정책은 `target_recall`입니다. 검증 세트에서 positive recall 70% 이상을 먼저 만족하는 후보 중 precision이 가장 높은 임계값을 선택해, 조기 선별에서 놓치는 사례를 줄이는 방향으로 평가합니다.
- 학습 후 `risk_model.joblib`, `feature_schema.json`, `metrics.json`, `shap_summary.json`이 `ml/artifacts`에 생성됩니다.

학습 예시:

```bash
cd /Users/jang/Documents/New\ project
python3 -m venv .venv
source .venv/bin/activate
pip install -r ml/requirements.txt
python ml/src/train_model.py \
  --input data/processed/chs_training_0515.csv \
  --min-age 19 \
  --max-age 39
```

기본 학습 범위는 표본 수 확보를 위해 19~39세로 설정되어 있습니다. `--min-age`, `--max-age` 값을 바꾸면 같은 원자료에서 다른 연령 범위로 다시 학습할 수 있습니다.

모델 API 실행:

```bash
uvicorn ml.src.serve_model:app --host 0.0.0.0 --port 8000
```

Spring Boot 백엔드는 `ML_API_URL=http://localhost:8000`으로 이 API를 호출합니다. 모델 서버가 꺼져 있어도 백엔드는 규칙 기반 fallback으로 참고 점수를 산출합니다.

## 모델 벤치마크 리포트 생성

보고서와 발표 자료에 넣을 모델 비교표와 그래프는 아래 명령으로 생성합니다. Logistic Regression, Random Forest, XGBoost, LightGBM을 같은 데이터 분할에서 학습하고 AUROC, PR-AUC, Precision, Recall, F1-score, balanced accuracy, confusion matrix를 비교합니다. 참고 고위험군 비율이 낮기 때문에 단순 Accuracy는 핵심 판단 지표로 강조하지 않습니다. 기본 설정은 조기 선별 목적에 맞춰 recall 중심 threshold를 사용합니다.

```bash
cd /Users/jang/Documents/New\ project
source .venv/bin/activate
python ml/src/benchmark_models.py \
  --input data/processed/chs_training_0515.csv \
  --min-age 19 \
  --max-age 39 \
  --output-dir ml/reports/benchmark
```

생성되는 주요 파일:

- `ml/reports/benchmark/benchmark_report.md`: 보고서용 성능 비교 요약
- `ml/reports/benchmark/benchmark_metrics.csv`: 모델별 성능표
- `ml/reports/benchmark/confusion_matrices.png`: 모델별 혼동행렬
- `ml/reports/benchmark/confusion_matrices_normalized.png`: 실제 클래스별 비율 혼동행렬
- `ml/reports/benchmark/metrics_comparison.png`: 주요 성능지표 비교 그래프
- `ml/reports/benchmark/roc_curves.png`: ROC 곡선
- `ml/reports/benchmark/precision_recall_curves.png`: Precision-Recall 곡선
- `ml/reports/benchmark/feature_importance_*.png`: 모델별 주요 변수 그래프
