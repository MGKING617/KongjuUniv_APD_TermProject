# 고급프로그래밍설계 Term Project

청년기 우울 위험 신호를 조기에 확인하기 위한 웹 애플리케이션입니다. 사용자는 챗봇 문답과 설문 입력을 통해 현재 상태를 기록하고, 시스템은 규칙 기반 점수와 ML 모델 참고 점수를 함께 사용해 위험도, 주요 신호, 후속 안내를 제공합니다.

이 프로젝트는 진단 도구가 아니라 상담 전 확인과 선별을 돕는 참고용 서비스입니다.

## 주요 기능

- 게스트 모드와 회원 로그인/가입
- 챗봇 기반 자유 문답 및 설문형 문답
- 설문 응답 기반 위험도 산출
- LightGBM 기반 ML 위험도 참고 점수 연동
- 결과 화면의 위험 단계, 관찰 신호, 요인 설명, 추세 차트
- 관리자 화면의 최근 평가 내역과 고위험 신호 확인
- LLM 응답과 의미 기반 캐시 옵션

## 기술 스택

- Frontend: React, TypeScript, Vite, Recharts
- Backend: Java 21, Spring Boot 3, Spring Web, Spring Data JPA, Redis
- ML API: Python, FastAPI, scikit-learn, LightGBM, SHAP
- Database: MySQL 8
- Deployment: Docker, Docker Compose, Nginx, AWS EC2

## 폴더 구조

```text
.
├── backend/                 # Spring Boot API 서버
├── frontend/                # React + Vite 웹앱
├── ml/                      # 모델 학습/서빙 코드와 기본 모델 산출물
├── ml_after_expert_consultation/ # 전문가 상담 반영 실험 폴더
├── database/init/           # MySQL 초기 스키마
├── scripts/                 # 운영/벤치마크 보조 스크립트
├── data/                    # 원자료 및 전처리 데이터
├── docker-compose.example.yml
└── .env.example
```

## 환경 변수 설정

민감한 키와 새 AWS 인스턴스 주소는 코드에 넣지 않고 `.env`로 관리합니다.

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
cp backend/.env.example backend/.env
```

주요 설정 항목:

- `VITE_API_BASE_URL`: 프론트엔드가 호출할 API base URL. 같은 도메인에서 Nginx가 `/api`를 프록시하면 비워 둡니다.
- `VITE_DEV_API_PROXY_TARGET`: 로컬 Vite 개발 서버가 `/api`를 전달할 백엔드 주소입니다.
- `CORS_ORIGIN`: 배포된 프론트엔드 origin입니다.
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: 새 AWS 인스턴스 또는 RDS의 MySQL 접속 정보입니다.
- `ML_API_URL`: ML 모델 API 주소입니다.
- `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`: LLM 제공자 설정입니다.
- `SEMANTIC_CACHE_REDIS_URL`: Redis 기반 의미 캐시 주소입니다.

## 로컬 실행

백엔드:

```bash
cd backend
mvn spring-boot:run
```

ML API:

```bash
pip install -r ml/requirements.txt
uvicorn ml.src.serve_model:app --host 0.0.0.0 --port 8000
```

프론트엔드:

```bash
cd frontend
npm install
npm run dev
```

기본 로컬 주소는 프론트엔드 `http://localhost:5173`, 백엔드 `http://localhost:8080`, ML API `http://localhost:8000`입니다.

## 빌드

```bash
cd frontend
npm run build

cd ../backend
mvn clean package
```

## Docker Compose 배포 예시

```bash
cp .env.example .env
# .env에서 DB_PASSWORD, MYSQL_ROOT_PASSWORD, CORS_ORIGIN, LLM_API_KEY 등을 실제 값으로 변경
docker compose -f docker-compose.example.yml --env-file .env up -d --build
```

같은 EC2 인스턴스에서 프론트엔드 Nginx가 백엔드를 프록시하는 구성이라면 `VITE_API_BASE_URL`은 비워 둘 수 있습니다. API 서버를 별도 인스턴스나 별도 도메인에 둘 경우 `VITE_API_BASE_URL=https://api.example.com`처럼 설정한 뒤 프론트엔드를 다시 빌드합니다.

## AWS 새 인스턴스 배포 방식

1. EC2 인스턴스를 생성하고 보안 그룹에서 HTTP 80, 백엔드 확인용 8080, SSH 22를 필요한 범위로 허용합니다.
2. Docker와 Docker Compose를 설치합니다.
3. 저장소를 clone하고 `.env.example`을 `.env`로 복사합니다.
4. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ORIGIN`, `VITE_API_BASE_URL`, `ML_API_URL`, `LLM_API_KEY`를 새 인스턴스/도메인 기준으로 설정합니다.
5. `docker compose -f docker-compose.example.yml --env-file .env up -d --build`로 실행합니다.
6. 도메인을 연결하는 경우 Route 53 또는 사용 중인 DNS에서 EC2 퍼블릭 IP를 바라보게 하고, HTTPS가 필요하면 Nginx 앞단에 인증서를 적용합니다.

## 주의 사항

- 원자료, 모델 joblib, 빌드 산출물, 압축 파일, `.env`는 Git에 올리지 않도록 `.gitignore`에 포함했습니다.
- LLM API 키, DB 비밀번호, 실제 API 도메인은 반드시 환경 변수로 주입합니다.
- 의료적 진단이나 처방을 제공하지 않으며, 긴급 위험이 있는 경우 지역 응급번호 또는 전문 상담 자원 연결을 우선합니다.
