export type Role = "USER" | "ADMIN" | "CLINICIAN";
export type ConversationTone = "polite" | "friendly";

export interface AuthUser {
  id: number;
  email: string;
  name: string;
  role: Role;
  guest?: boolean;
}

export interface AuthResponse {
  token: string;
  user: AuthUser;
}

export interface ChatSession {
  id: number;
  status: string;
  startedAt: string;
}

export interface ChatMessageResponse {
  userMessage: ChatMessage;
  botMessage: ChatMessage;
  riskEvent?: RiskEvent;
}

export interface LikertScoreResponse {
  score: number;
  confidence: number;
  label: string;
  rationale: string;
  validAnswer: boolean;
}

export interface SurveyChatStartResponse {
  botMessage: ChatMessage;
}

export interface SurveyChatTurnResponse {
  userMessage: ChatMessage;
  botMessage: ChatMessage;
  score: LikertScoreResponse;
  riskEvent?: RiskEvent;
  nextQuestionKey?: string;
  completed: boolean;
}

export interface RegenerateChatResponse {
  botMessage: ChatMessage;
  riskEvent?: RiskEvent;
}

export interface ChatMessage {
  id: number;
  sender: "USER" | "BOT";
  content: string;
  createdAt: string;
}

export interface RiskEvent {
  id: number;
  riskType: string;
  severity: "CAUTION" | "HIGH" | "EMERGENCY";
  evidenceText: string;
  actionTaken: string;
  createdAt: string;
}

export interface SurveyPayload {
  userId: number;
  answers: Record<string, number>;
  extraFeatures: Record<string, number | string>;
}

export interface DomainScore {
  key: string;
  label: string;
  score: number;
  description: string;
}

export interface FactorContribution {
  label: string;
  impact: number;
  direction: "INCREASE" | "DECREASE" | "INFO";
  explanation: string;
}

export interface GlobalImportance {
  label: string;
  importance: number;
}

export interface Assessment {
  id: number | null;
  userId: number;
  userName: string;
  phqLikeScore: number;
  mlRiskPercent: number;
  finalScore: number;
  riskLevel: "LOW" | "WATCH" | "HIGH" | "URGENT";
  detectedSignals: string[];
  domainScores?: DomainScore[];
  factorContributions?: FactorContribution[];
  globalImportance?: GlobalImportance[];
  summary: string;
  createdAt: string;
}

export interface AiSummaryResponse {
  summary: string;
  fallbackUsed: boolean;
}

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers ?? {})
    }
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `API request failed: ${response.status}`);
  }

  return response.json() as Promise<T>;
}

async function requestStream<T>(
  path: string,
  options: RequestInit = {},
  onDelta: (chunk: string) => void
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream",
      ...(options.headers ?? {})
    }
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `API stream request failed: ${response.status}`);
  }
  if (!response.body) {
    throw new Error("Streaming response is not available in this browser.");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let donePayload: T | null = null;

  function handleEvent(rawEvent: string) {
    const lines = rawEvent.replace(/\r\n/g, "\n").split("\n");
    let eventName = "message";
    const dataLines: string[] = [];
    for (const line of lines) {
      if (line.startsWith("event:")) {
        eventName = line.slice("event:".length).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice("data:".length).trimStart());
      }
    }
    if (dataLines.length === 0) return;

    const data = JSON.parse(dataLines.join("\n"));
    if (eventName === "delta") {
      const chunk = typeof data.content === "string" ? data.content : "";
      if (chunk) onDelta(chunk);
      return;
    }
    if (eventName === "done") {
      donePayload = data as T;
    }
  }

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const rawEvent = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      handleEvent(rawEvent);
      boundary = buffer.indexOf("\n\n");
    }
  }
  buffer += decoder.decode();
  if (buffer.trim()) {
    handleEvent(buffer);
  }

  if (!donePayload) {
    throw new Error("Streaming response ended before completion.");
  }
  return donePayload;
}

export const api = {
  register(payload: { loginId: string; password: string; role: Role }) {
    return request<AuthResponse>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  login(payload: { loginId: string; password: string }) {
    return request<AuthResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  createChatSession(userId: number) {
    return request<ChatSession>("/api/chat/sessions", {
      method: "POST",
      body: JSON.stringify({ userId })
    });
  },
  sendMessage(sessionId: number, content: string, tone: ConversationTone) {
    return request<ChatMessageResponse>(`/api/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      body: JSON.stringify({ content, tone })
    });
  },
  sendMessageStream(sessionId: number, content: string, tone: ConversationTone, onDelta: (chunk: string) => void) {
    return requestStream<ChatMessageResponse>(`/api/chat/sessions/${sessionId}/messages/stream`, {
      method: "POST",
      body: JSON.stringify({ content, tone })
    }, onDelta);
  },
  regenerateChat(sessionId: number, content: string, tone: ConversationTone) {
    return request<RegenerateChatResponse>(`/api/chat/sessions/${sessionId}/regenerate`, {
      method: "POST",
      body: JSON.stringify({ content, tone })
    });
  },
  regenerateChatStream(sessionId: number, content: string, tone: ConversationTone, onDelta: (chunk: string) => void) {
    return requestStream<RegenerateChatResponse>(`/api/chat/sessions/${sessionId}/regenerate/stream`, {
      method: "POST",
      body: JSON.stringify({ content, tone })
    }, onDelta);
  },
  startSurveyChat(sessionId: number, payload: { questionTitle: string; questionIndex: number; totalQuestions: number; tone: ConversationTone }) {
    return request<SurveyChatStartResponse>(`/api/chat/sessions/${sessionId}/survey/start`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  startGuestSurveyChat(payload: { questionTitle: string; questionIndex: number; totalQuestions: number; tone: ConversationTone }) {
    return request<SurveyChatStartResponse>("/api/guest/survey/start", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  sendSurveyTurn(sessionId: number, payload: {
    content: string;
    questionKey: string;
    questionTitle: string;
    nextQuestionTitle?: string;
    remainingQuestions: Array<{ key: string; title: string }>;
    questionIndex: number;
    totalQuestions: number;
    tone: ConversationTone;
  }) {
    return request<SurveyChatTurnResponse>(`/api/chat/sessions/${sessionId}/survey/turn`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  sendGuestSurveyTurn(payload: {
    content: string;
    questionKey: string;
    questionTitle: string;
    nextQuestionTitle?: string;
    remainingQuestions: Array<{ key: string; title: string }>;
    questionIndex: number;
    totalQuestions: number;
    tone: ConversationTone;
  }) {
    return request<SurveyChatTurnResponse>("/api/guest/survey/turn", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  sendGuestMessage(content: string, tone: ConversationTone) {
    return request<RegenerateChatResponse>("/api/guest/chat", {
      method: "POST",
      body: JSON.stringify({ content, tone })
    });
  },
  sendGuestMessageStream(content: string, tone: ConversationTone, onDelta: (chunk: string) => void) {
    return requestStream<RegenerateChatResponse>("/api/guest/chat/stream", {
      method: "POST",
      body: JSON.stringify({ content, tone })
    }, onDelta);
  },
  submitSurvey(payload: SurveyPayload) {
    return request<Assessment>("/api/assessments/survey", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  submitGuestSurvey(payload: SurveyPayload) {
    return request<Assessment>("/api/guest/assessment", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getMyAssessments(userId: number) {
    return request<Assessment[]>(`/api/assessments/users/${userId}`);
  },
  getAdminAssessments() {
    return request<Assessment[]>("/api/admin/assessments");
  },
  getAdminRiskEvents() {
    return request<RiskEvent[]>("/api/admin/risk-events");
  },
  getAiSummary(assessment: Assessment, tone: ConversationTone) {
    return request<AiSummaryResponse>("/api/llm/assessment-summary", {
      method: "POST",
      body: JSON.stringify({
        finalScore: assessment.finalScore,
        phqLikeScore: assessment.phqLikeScore,
        mlRiskPercent: assessment.mlRiskPercent,
        riskLevel: assessment.riskLevel,
        tone,
        detectedSignals: assessment.detectedSignals,
        domainScores: assessment.domainScores ?? [],
        factorContributions: assessment.factorContributions ?? [],
        globalImportance: assessment.globalImportance ?? []
      })
    });
  },
  getAiSummaryStream(assessment: Assessment, tone: ConversationTone, onDelta: (chunk: string) => void) {
    return requestStream<AiSummaryResponse>("/api/llm/assessment-summary/stream", {
      method: "POST",
      body: JSON.stringify({
        finalScore: assessment.finalScore,
        phqLikeScore: assessment.phqLikeScore,
        mlRiskPercent: assessment.mlRiskPercent,
        riskLevel: assessment.riskLevel,
        tone,
        detectedSignals: assessment.detectedSignals,
        domainScores: assessment.domainScores ?? [],
        factorContributions: assessment.factorContributions ?? [],
        globalImportance: assessment.globalImportance ?? []
      })
    }, onDelta);
  }
};
