const form = document.querySelector("#surveyForm");
const chatForm = document.querySelector("#chatForm");
const chatInput = document.querySelector("#chatInput");
const chatLog = document.querySelector("#chatLog");
const contribList = document.querySelector("#contribList");
const statusEl = document.querySelector("#modelStatus");
const riskNumber = document.querySelector("#riskNumber");
const riskLevel = document.querySelector("#riskLevel");
const meterNeedle = document.querySelector("#meterNeedle");
const trainRows = document.querySelector("#trainRows");
const baseRate = document.querySelector("#baseRate");
const modelVersion = document.querySelector("#modelVersion");
const phqResult = document.querySelector("#phqResult");
const disclaimer = document.querySelector("#disclaimer");

let lastPrediction = null;
let messages = [
  {
    role: "assistant",
    content: "안녕하세요. 최근 생활 리듬과 스트레스 상태를 차분히 같이 확인해볼게요.",
  },
];

const regions = [
  ["1", "서울"], ["2", "부산"], ["3", "대구"], ["4", "인천"], ["5", "광주"],
  ["6", "대전"], ["7", "울산"], ["8", "세종"], ["9", "경기"], ["10", "강원"],
  ["11", "충북"], ["12", "충남"], ["13", "전북"], ["14", "전남"], ["15", "경북"],
  ["16", "경남"], ["17", "제주"],
];

const phqLabels = [
  "흥미나 즐거움 저하",
  "가라앉음/우울감",
  "수면 문제",
  "피로감",
  "식욕 변화",
  "자책감",
  "집중 어려움",
  "느려짐/초조함",
  "자해 생각",
];

function fillRegions() {
  const select = document.querySelector("#regionSelect");
  select.innerHTML = regions.map(([value, label]) => `<option value="${value}">${label}</option>`).join("");
}

function buildPhq() {
  const strip = document.querySelector("#phqStrip");
  strip.innerHTML = phqLabels
    .map((label, idx) => `
      <label>${label}
        <select name="phq_${idx + 1}">
          <option value="0">전혀 없음</option>
          <option value="1">며칠 동안</option>
          <option value="2">일주일 이상</option>
          <option value="3">거의 매일</option>
        </select>
      </label>
    `)
    .join("");
}

function collectPayload() {
  const data = new FormData(form);
  const features = {};
  for (const [key, value] of data.entries()) {
    if (!key.startsWith("phq_")) {
      features[key] = value;
    }
  }
  return {
    features,
    phq_items: Array.from({ length: 9 }, (_, index) => Number(data.get(`phq_${index + 1}`) || 0)),
  };
}

function setSelect(name, value) {
  const field = form.elements[name];
  if (field) field.value = String(value);
}

function setInput(name, value) {
  const field = form.elements[name];
  if (field) field.value = String(value);
}

function fillSample() {
  setInput("age", 27);
  setSelect("year", 2024);
  setSelect("sex", 2);
  setSelect("region", 9);
  setSelect("incm", 2);
  setSelect("edu", 4);
  setInput("cfam", 1);
  setSelect("allownc", 20);
  setSelect("marri_1", 2);
  setSelect("D_1_1", 4);
  setSelect("EC1_1", 2);
  setInput("EC_wht_23", 0);
  setSelect("DI1_dg", 0);
  setSelect("DI2_dg", 0);
  setSelect("DE1_dg", 0);
  setSelect("BO1", 3);
  setSelect("BO1_1", 2);
  setSelect("BO2_1", 4);
  setSelect("BD1_11", 4);
  setSelect("BP1", 1);
  setSelect("BS3_1", 8);
  setSelect("BE3_31", 1);
  setSelect("BE5_1", 1);
  setSelect("L_BR_FQ", 4);
  setSelect("L_LN_FQ", 3);
  setSelect("L_DN_FQ", 2);
  setSelect("L_BR_TO", 2);
  setSelect("L_LN_TO", 2);
  setSelect("L_DN_TO", 2);
  [2, 2, 3, 2, 1, 2, 2, 1, 0].forEach((value, index) => setSelect(`phq_${index + 1}`, value));
}

function rotateMeter(percent) {
  const clamped = Math.max(0, Math.min(100, percent));
  const degree = -72 + clamped * 1.44;
  meterNeedle.style.transform = `rotate(${degree}deg)`;
}

function renderPrediction(result) {
  lastPrediction = result;
  riskNumber.textContent = `${result.risk_percent}%`;
  riskLevel.textContent = `${result.risk_level} · ${result.target_definition}`;
  rotateMeter(result.risk_percent);
  trainRows.textContent = Number(result.training_rows || 0).toLocaleString("ko-KR");
  baseRate.textContent = `${result.target_prevalence_percent}%`;
  modelVersion.textContent = result.model_version || "-";
  disclaimer.textContent = result.disclaimer;
  if (result.phq) {
    phqResult.textContent = `PHQ-9 ${result.phq.score}점 · ${result.phq.band}`;
  } else {
    phqResult.textContent = "";
  }
  contribList.innerHTML = (result.contributions || [])
    .map((item) => `
      <div class="contrib-item ${item.raw_effect > 0 ? "risk-up" : ""}">
        <strong>${item.label}</strong>
        <span>${item.direction} · 영향도 ${item.strength.toFixed(3)} ${item.details ? `· ${item.details}` : ""}</span>
      </div>
    `)
    .join("");
}

function appendMessage(role, content) {
  messages.push({ role, content });
  renderChat();
}

function renderChat() {
  chatLog.innerHTML = messages
    .map((message) => `<div class="bubble ${message.role === "user" ? "user" : "assistant"}">${escapeHtml(message.content).replaceAll("\n", "<br>")}</div>`)
    .join("");
  chatLog.scrollTop = chatLog.scrollHeight;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function loadModelInfo() {
  const response = await fetch("/api/model");
  const data = await response.json();
  if (!data.available && data.error) {
    statusEl.textContent = "모델 없음";
    riskLevel.textContent = "먼저 모델을 학습하세요";
    return;
  }
  statusEl.textContent = "모델 준비됨";
  trainRows.textContent = Number(data.training_rows || 0).toLocaleString("ko-KR");
  baseRate.textContent = `${data.target_prevalence_percent}%`;
  modelVersion.textContent = data.model_version || "-";
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  riskLevel.textContent = "분석 중";
  const response = await fetch("/api/predict", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(collectPayload()),
  });
  const data = await response.json();
  if (!response.ok) {
    riskLevel.textContent = data.error || "분석 실패";
    return;
  }
  renderPrediction(data);
});

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = chatInput.value.trim();
  if (!text) return;
  chatInput.value = "";
  appendMessage("user", text);
  appendMessage("assistant", "응답 생성 중...");
  const response = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ messages: messages.filter((m) => m.content !== "응답 생성 중..."), context: lastPrediction }),
  });
  const data = await response.json();
  messages.pop();
  appendMessage("assistant", data.message || data.error || "응답을 가져오지 못했습니다.");
});

document.querySelector("#sampleButton").addEventListener("click", fillSample);
document.querySelector("#clearChat").addEventListener("click", () => {
  messages = [{ role: "assistant", content: "대화를 새로 시작할게요. 지금 가장 크게 느껴지는 어려움부터 말해 주세요." }];
  renderChat();
});

fillRegions();
buildPhq();
renderChat();
loadModelInfo().catch(() => {
  statusEl.textContent = "모델 확인 실패";
});

