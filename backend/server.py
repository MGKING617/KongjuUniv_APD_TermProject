from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from model_runtime import ModelUnavailable, model_metadata, predict


ROOT = Path(__file__).resolve().parents[1]
FRONTEND_DIR = ROOT / "frontend"
ENV_PATH = ROOT / ".env"


def load_env() -> None:
    if not ENV_PATH.exists():
        return
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def json_bytes(payload: dict[str, Any], status: int = 200) -> tuple[int, bytes, str]:
    return status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8"


def openai_chat(messages: list[dict[str, str]], context: dict[str, Any] | None) -> dict[str, Any]:
    api_key = os.getenv("OPENAI_API_KEY")
    model = os.getenv("OPENAI_MODEL", "gpt-5.4-mini")
    if not api_key:
        last = messages[-1]["content"] if messages else ""
        return {
            "mode": "local",
            "message": (
                "현재 OpenAI API 키가 설정되지 않아 로컬 안내 모드로 응답합니다. "
                "발표 시 실제 상담형 챗봇을 보여주려면 .env의 OPENAI_API_KEY를 입력하세요.\n\n"
                f"방금 입력한 내용: {last[:240]}"
            ),
        }

    prediction_summary = ""
    if context:
        prediction_summary = json.dumps(context, ensure_ascii=False)[:1800]

    system_prompt = (
        "너는 청년 우울 위험도 조기탐지 웹앱의 상담형 입력 도우미다. "
        "진단, 처방, 확정적 의학 판단을 하지 않는다. "
        "사용자의 최근 생활, 수면, 스트레스, 식사, 음주, 운동, 경제활동 상태를 차분히 묻고, "
        "위험 신호가 있으면 보호자, 상담자, 의료 전문가에게 연결하도록 권한다. "
        "자해나 즉각적 위험이 언급되면 즉시 지역 응급번호 또는 가까운 응급실/위기상담 자원을 안내한다. "
        "응답은 한국어로 짧고 따뜻하게 작성한다."
    )

    input_messages = [{"role": "system", "content": system_prompt}]
    if prediction_summary:
        input_messages.append(
            {
                "role": "developer",
                "content": f"현재 설문 모델 참고 결과 JSON: {prediction_summary}",
            }
        )
    input_messages.extend(messages[-12:])

    request_payload = {
        "model": model,
        "input": input_messages,
        "max_output_tokens": 500,
    }
    openai_base_url = os.getenv("OPENAI_BASE_URL", "").rstrip("/")
    if not openai_base_url:
        return {
            "mode": "error",
            "message": "OPENAI_BASE_URL을 .env에 설정한 뒤 다시 시도하세요.",
        }
    request = urllib.request.Request(
        f"{openai_base_url}/responses",
        data=json.dumps(request_payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        return {
            "mode": "error",
            "message": f"OpenAI API 오류가 발생했습니다. 상태코드 {exc.code}: {detail[:500]}",
        }
    except Exception as exc:
        return {"mode": "error", "message": f"OpenAI API 연결 실패: {exc}"}

    text = data.get("output_text")
    if not text:
        chunks: list[str] = []
        for item in data.get("output", []):
            for content in item.get("content", []):
                if content.get("type") in {"output_text", "text"} and content.get("text"):
                    chunks.append(content["text"])
        text = "\n".join(chunks).strip()
    return {"mode": "openai", "message": text or "응답 텍스트를 가져오지 못했습니다."}


class AppHandler(BaseHTTPRequestHandler):
    server_version = "YouthRiskDemo/0.1"

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")

    def send_payload(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw)

    def do_GET(self) -> None:
        path = unquote(self.path.split("?", 1)[0])
        if path == "/api/model":
            try:
                payload = model_metadata()
            except ModelUnavailable as exc:
                payload = {"available": False, "error": str(exc)}
            status, body, content_type = json_bytes(payload)
            self.send_payload(status, body, content_type)
            return

        if path in {"", "/"}:
            target = FRONTEND_DIR / "index.html"
        else:
            clean_path = path.lstrip("/")
            target = (FRONTEND_DIR / clean_path).resolve()
            if FRONTEND_DIR.resolve() not in target.parents and target != FRONTEND_DIR.resolve():
                self.send_error(HTTPStatus.FORBIDDEN)
                return

        if not target.exists() or not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        content_type = {
            ".html": "text/html; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
        }.get(target.suffix.lower(), "application/octet-stream")
        self.send_payload(200, target.read_bytes(), content_type)

    def do_POST(self) -> None:
        try:
            payload = self.read_json()
            if self.path == "/api/predict":
                result = predict(payload)
                status, body, content_type = json_bytes(result)
            elif self.path == "/api/chat":
                messages = payload.get("messages", [])
                if not isinstance(messages, list):
                    raise ValueError("messages must be a list")
                result = openai_chat(messages, payload.get("context"))
                status, body, content_type = json_bytes(result)
            else:
                status, body, content_type = json_bytes(
                    {"error": "unknown endpoint"}, HTTPStatus.NOT_FOUND
                )
        except ModelUnavailable as exc:
            status, body, content_type = json_bytes({"error": str(exc)}, 503)
        except Exception as exc:
            status, body, content_type = json_bytes({"error": str(exc)}, 400)
        self.send_payload(status, body, content_type)


def main() -> None:
    load_env()
    host = os.getenv("APP_HOST", "127.0.0.1")
    port = int(os.getenv("APP_PORT", "8000"))
    server = ThreadingHTTPServer((host, port), AppHandler)
    print(f"Serving http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
