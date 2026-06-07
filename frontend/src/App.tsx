import { useEffect, useRef, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api, Assessment, AuthUser, ChatMessage, ConversationTone, RiskEvent, Role } from "./lib/api";
import { buildModelFeatures, defaultSurveyAnswers, surveyQuestions } from "./lib/questionBank";

const CURRENT_SERVICE_YEAR = new Date().getFullYear();

function App() {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [guestAssessment, setGuestAssessment] = useState<Assessment | null>(null);
  const [activeView, setActiveView] = useState<"chat" | "survey" | "result" | "admin">("chat");
  const [conversationTone, setConversationTone] = useState<ConversationTone>("polite");
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [authError, setAuthError] = useState("");
  const [authForm, setAuthForm] = useState({
    loginId: "",
    password: "",
    role: "USER" as Role
  });
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [authBusy, setAuthBusy] = useState(false);
  const passwordMismatch = authMode === "register" && passwordConfirm.length > 0 && authForm.password !== passwordConfirm;
  const registerReady = authForm.loginId.trim().length > 0 && authForm.password.length > 0 && authForm.password === passwordConfirm;

  function completeAuth(authUser: AuthUser) {
    setGuestAssessment(null);
    setUser(authUser);
    setActiveView(authUser.role === "USER" ? "chat" : "admin");
  }

  async function loginWithTestAccount() {
    setAuthError("");
    setAuthBusy(true);
    try {
      const response = await api.login({ loginId: "test", password: "test" });
      setAuthMode("login");
      setPasswordConfirm("");
      setAuthForm((prev) => ({ ...prev, loginId: "test", password: "test" }));
      completeAuth(response.user);
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : "테스트 계정 로그인에 실패했습니다.");
    } finally {
      setAuthBusy(false);
    }
  }

  if (!user) {
    return (
      <main className="auth-shell">
        <section className="auth-panel">
          <p className="eyebrow">고급프로그래밍설계 텀 프로젝트</p>
          <h1>우울증 조기 탐지</h1>
          <p className="notice">
            챗봇 문답과 설문 응답을 바탕으로 우울증 조기 탐지
          </p>
          <div className="guest-start-box compact">
            <div>
              <strong>빠른 시연</strong>
              <p>게스트 모드는 기록이 DB에 저장되지 않습니다.</p>
            </div>
            <div className="quick-login-actions">
              <button
                className="secondary"
                type="button"
                onClick={() => {
                  const confirmed = window.confirm("게스트모드에서는 대화와 결과가 DB에 저장되지 않습니다. 페이지를 나가거나 새로고침하면 확인할 수 없어요. 계속할까요?");
                  if (!confirmed) return;
                  setGuestAssessment(null);
                  setUser({
                    id: 0,
                    email: "guest@youth.local",
                    name: "게스트 사용자",
                    role: "USER",
                    guest: true
                  });
                  setActiveView("chat");
                }}
              >
                게스트 시작
              </button>
              <button className="primary" type="button" onClick={loginWithTestAccount} disabled={authBusy}>
                테스트 계정 로그인
              </button>
            </div>
          </div>
          <div className="segmented">
            <button className={authMode === "register" ? "active" : ""} onClick={() => {
              setAuthMode("register");
              setAuthError("");
            }}>가입</button>
            <button className={authMode === "login" ? "active" : ""} onClick={() => {
              setAuthMode("login");
              setAuthError("");
              setPasswordConfirm("");
            }}>로그인</button>
          </div>
          <form
            className="auth-form"
            onSubmit={async (event) => {
              event.preventDefault();
              setAuthError("");
              if (authMode === "register" && !registerReady) {
                setAuthError(authForm.password !== passwordConfirm ? "비밀번호가 일치하지 않습니다." : "아이디와 비밀번호를 입력해 주세요.");
                return;
              }
              setAuthBusy(true);
              try {
                const response =
                  authMode === "register"
                    ? await api.register(authForm)
                    : await api.login({ loginId: authForm.loginId, password: authForm.password });
                completeAuth(response.user);
              } catch (error) {
                setAuthError(error instanceof Error ? error.message : "인증 요청에 실패했습니다.");
              } finally {
                setAuthBusy(false);
              }
            }}
          >
            <label>
              아이디
              <input autoComplete="username" value={authForm.loginId} onChange={(e) => setAuthForm({ ...authForm, loginId: e.target.value })} />
            </label>
            <label>
              비밀번호
              <input autoComplete={authMode === "register" ? "new-password" : "current-password"} type="password" value={authForm.password} onChange={(e) => setAuthForm({ ...authForm, password: e.target.value })} />
            </label>
            {authMode === "register" && (
              <label>
                비밀번호 확인
                <input autoComplete="new-password" type="password" value={passwordConfirm} onChange={(e) => setPasswordConfirm(e.target.value)} />
                {passwordMismatch && <span className="field-help">비밀번호가 일치하지 않습니다.</span>}
              </label>
            )}
            {authMode === "register" && (
              <label>
                역할
                <select value={authForm.role} onChange={(e) => setAuthForm({ ...authForm, role: e.target.value as Role })}>
                  <option value="USER">사용자</option>
                  <option value="ADMIN">관리자</option>
                  <option value="CLINICIAN">상담자/의료인</option>
                </select>
              </label>
            )}
            {authError && <p className="error">{authError}</p>}
            <button className="primary" type="submit" disabled={authBusy || (authMode === "register" && !registerReady)}>{authMode === "register" ? "동의 후 시작" : "로그인"}</button>
          </form>
        </section>
      </main>
    );
  }

  function finishAssessment(assessment?: Assessment) {
    if (user?.guest && assessment) {
      setGuestAssessment(assessment);
    }
    setActiveView("result");
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <p className="eyebrow">고급프로그래밍설계 텀 프로젝트</p>
          <h2>우울증 조기 탐지</h2>
        </div>
        <nav className="sidebar-menu">
          <button className={activeView === "chat" ? "active" : ""} onClick={() => setActiveView("chat")}>챗봇 문답</button>
          <button className={activeView === "survey" ? "active" : ""} onClick={() => setActiveView("survey")}>설문 입력</button>
          <button className={activeView === "result" ? "active" : ""} onClick={() => setActiveView("result")}>내 결과</button>
          {!user.guest && user.role !== "USER" && <button className={activeView === "admin" ? "active" : ""} onClick={() => setActiveView("admin")}>관리자</button>}
        </nav>
        <section className="tone-panel" aria-label="대화 말투 설정">
          <div>
            <span>대화 말투</span>
          </div>
          <div className="tone-toggle">
            <button
              className={conversationTone === "polite" ? "active" : ""}
              onClick={() => setConversationTone("polite")}
            >
              예의있게
            </button>
            <button
              className={conversationTone === "friendly" ? "active" : ""}
              onClick={() => setConversationTone("friendly")}
            >
              친근하게
            </button>
          </div>
          <p>{conversationTone === "friendly" ? "AI의 말투를 친근하게" : "AI의 말투를 정중하게"}</p>
        </section>
        {user.guest && (
          <div className="guest-banner">
            <strong>게스트모드</strong>
            <span>대화와 결과가 저장되지 않아요.</span>
          </div>
        )}
        <div className="profile">
          <strong>{user.name}</strong>
          <span>{user.guest ? "GUEST" : user.role}</span>
          <button onClick={() => {
            setUser(null);
            setGuestAssessment(null);
            setActiveView("chat");
          }}>{user.guest ? "나가기" : "로그아웃"}</button>
        </div>
      </aside>
      <main className="workspace">
        {activeView === "chat" && <ChatPage user={user} tone={conversationTone} onDone={finishAssessment} onOpenSurvey={() => setActiveView("survey")} />}
        {activeView === "survey" && <SurveyPage user={user} onDone={finishAssessment} />}
        {activeView === "result" && <ResultPage user={user} tone={conversationTone} guestAssessment={guestAssessment} />}
        {activeView === "admin" && !user.guest && <AdminPage />}
      </main>
    </div>
  );
}

function questionByKey(key?: string) {
  return surveyQuestions.find((question) => question.key === key);
}

function moveQuestionKeyToNext(order: string[], key: string, nextIndex: number) {
  const currentIndex = order.indexOf(key);
  if (currentIndex < nextIndex || currentIndex === -1 || order[nextIndex] === key) {
    return order;
  }
  const next = [...order];
  const [selected] = next.splice(currentIndex, 1);
  next.splice(nextIndex, 0, selected);
  return next;
}

function ChatPage({
  user,
  tone,
  onDone,
  onOpenSurvey
}: {
  user: AuthUser;
  tone: ConversationTone;
  onDone: (assessment?: Assessment) => void;
  onOpenSurvey: () => void;
}) {
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [riskEvent, setRiskEvent] = useState<RiskEvent | null>(null);
  const [loading, setLoading] = useState(false);
  const [streamingBotId, setStreamingBotId] = useState<number | null>(null);
  const [chatError, setChatError] = useState("");
  const [landingDismissed, setLandingDismissed] = useState(false);
  const [chatStage, setChatStage] = useState<"setup" | "conversation">("setup");
  const [chatMode, setChatMode] = useState<"free" | "survey">("free");
  const [surveyIndex, setSurveyIndex] = useState(0);
  const [surveyOrder, setSurveyOrder] = useState<string[]>(surveyQuestions.map((question) => question.key));
  const [surveyAnswers, setSurveyAnswers] = useState<Record<string, number>>(defaultSurveyAnswers);
  const [demographics, setDemographics] = useState<Record<string, number | string>>({
    age: 25,
    sex: 1,
    region: 1,
    year: CURRENT_SERVICE_YEAR
  });
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  const composingRef = useRef(false);
  const isGuest = Boolean(user.guest);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages, loading, streamingBotId]);

  async function ensureSession() {
    if (sessionId) {
      return sessionId;
    }
    const created = await api.createChatSession(user.id);
    setSessionId(created.id);
    return created.id;
  }

  async function startAiSurvey() {
    if (loading) return;
    setLoading(true);
    setChatError("");
    setRiskEvent(null);
    setMessages([]);
    setSurveyIndex(0);
    setSurveyOrder(surveyQuestions.map((question) => question.key));
    setSurveyAnswers(defaultSurveyAnswers);
    try {
      const response = isGuest ? await api.startGuestSurveyChat({
        questionTitle: surveyQuestions[0].title,
        questionIndex: 0,
        totalQuestions: surveyQuestions.length,
        tone
      }) : await api.startSurveyChat(await ensureSession(), {
        questionTitle: surveyQuestions[0].title,
        questionIndex: 0,
        totalQuestions: surveyQuestions.length,
        tone
      });
      setChatMode("survey");
      setChatStage("conversation");
      setMessages([response.botMessage]);
    } catch (error) {
      setChatError(error instanceof Error ? error.message : "AI 문답을 시작하지 못했습니다.");
    } finally {
      setStreamingBotId(null);
      setLoading(false);
    }
  }

  function backToLanding() {
    if (loading) return;
    setLandingDismissed(false);
    setChatStage("setup");
    setChatError("");
    setStreamingBotId(null);
  }

  function backToChatSetup() {
    if (loading) return;
    setChatStage("setup");
    setChatMode("free");
    setInput("");
    setMessages([]);
    setRiskEvent(null);
    setChatError("");
    setStreamingBotId(null);
    setSurveyIndex(0);
    setSurveyOrder(surveyQuestions.map((question) => question.key));
    setSurveyAnswers(defaultSurveyAnswers);
  }

  function replaceLastBotMessage(botMessage: ChatMessage) {
    setMessages((prev) => {
      const next = [...prev];
      for (let index = next.length - 1; index >= 0; index -= 1) {
        if (next[index].sender === "BOT") {
          next[index] = botMessage;
          return next;
        }
      }
      return [...next, botMessage];
    });
  }

  function appendToBotMessage(messageId: number, chunk: string) {
    setMessages((prev) => prev.map((message) => (
      message.id === messageId
        ? { ...message, content: `${message.content}${chunk}` }
        : message
    )));
  }

  async function regenerateCurrentAiResponse() {
    if (loading || messages.length === 0) return;
    setLoading(true);
    setChatError("");
    try {
      const currentSession = isGuest ? null : await ensureSession();
      if (chatMode === "survey") {
        const currentQuestion = questionByKey(surveyOrder[surveyIndex]) ?? surveyQuestions[surveyIndex];
        const response = isGuest ? await api.startGuestSurveyChat({
          questionTitle: currentQuestion.title,
          questionIndex: surveyIndex,
          totalQuestions: surveyQuestions.length,
          tone
        }) : await api.startSurveyChat(currentSession!, {
          questionTitle: currentQuestion.title,
          questionIndex: surveyIndex,
          totalQuestions: surveyQuestions.length,
          tone
        });
        replaceLastBotMessage(response.botMessage);
        return;
      }

      const lastUserMessage = [...messages].reverse().find((message) => message.sender === "USER");
      if (!lastUserMessage) {
        setChatError("다시 생성할 사용자 메시지가 없습니다.");
        return;
      }
      const tempBotId = -Date.now() - 1;
      setStreamingBotId(tempBotId);
      replaceLastBotMessage({
        id: tempBotId,
        sender: "BOT",
        content: "",
        createdAt: new Date().toISOString()
      });
      const response = isGuest
        ? await api.sendGuestMessageStream(lastUserMessage.content, tone, (chunk) => appendToBotMessage(tempBotId, chunk))
        : await api.regenerateChatStream(currentSession!, lastUserMessage.content, tone, (chunk) => appendToBotMessage(tempBotId, chunk));
      replaceLastBotMessage(response.botMessage);
      setRiskEvent(response.riskEvent ?? null);
    } catch (error) {
      setChatError(error instanceof Error ? error.message : "AI 응답을 다시 생성하지 못했습니다.");
    } finally {
      setStreamingBotId(null);
      setLoading(false);
    }
  }

  async function send() {
    const content = input.trim();
    if (!content || loading) return;
    if (chatMode === "survey") {
      await sendSurveyAnswer(content);
      return;
    }

    const tempId = -Date.now();
    const tempBotId = tempId - 1;
    setLoading(true);
    setStreamingBotId(tempBotId);
    setChatError("");
    setInput("");
    setMessages((prev) => [
      ...prev,
      {
        id: tempId,
        sender: "USER",
        content,
        createdAt: new Date().toISOString()
      },
      {
        id: tempBotId,
        sender: "BOT",
        content: "",
        createdAt: new Date().toISOString()
      }
    ]);
    try {
      const currentSession = isGuest ? null : await ensureSession();
      if (isGuest) {
        const response = await api.sendGuestMessageStream(content, tone, (chunk) => appendToBotMessage(tempBotId, chunk));
        setMessages((prev) => [
          ...prev.filter((message) => message.id !== tempId && message.id !== tempBotId),
          {
            id: tempId,
            sender: "USER",
            content,
            createdAt: new Date().toISOString()
          },
          response.botMessage
        ]);
        setRiskEvent(response.riskEvent ?? null);
      } else {
        const response = await api.sendMessageStream(currentSession!, content, tone, (chunk) => appendToBotMessage(tempBotId, chunk));
        setMessages((prev) => [
          ...prev.filter((message) => message.id !== tempId && message.id !== tempBotId),
          response.userMessage,
          response.botMessage
        ]);
        setRiskEvent(response.riskEvent ?? null);
      }
    } catch (error) {
      setMessages((prev) => prev.filter((message) => message.id !== tempId && message.id !== tempBotId));
      setChatError(error instanceof Error ? error.message : "챗봇 응답을 불러오지 못했습니다.");
    } finally {
      setStreamingBotId(null);
      setLoading(false);
    }
  }

  async function sendSurveyAnswer(content: string) {
    const currentQuestion = questionByKey(surveyOrder[surveyIndex]) ?? surveyQuestions[surveyIndex];
    const remainingQuestions = surveyOrder
      .slice(surveyIndex + 1)
      .map((key) => questionByKey(key))
      .filter((question): question is NonNullable<ReturnType<typeof questionByKey>> => Boolean(question));
    const nextQuestion = remainingQuestions[0];
    const tempId = -Date.now();
    setLoading(true);
    setChatError("");
    setInput("");
    setMessages((prev) => [
      ...prev,
      {
        id: tempId,
        sender: "USER",
        content,
        createdAt: new Date().toISOString()
      }
    ]);

    try {
      const turnPayload = {
        content,
        questionKey: currentQuestion.key,
        questionTitle: currentQuestion.title,
        nextQuestionTitle: nextQuestion?.title,
        remainingQuestions: remainingQuestions.map((question) => ({ key: question.key, title: question.title })),
        questionIndex: surveyIndex,
        totalQuestions: surveyQuestions.length,
        tone
      };
      const response = isGuest
        ? await api.sendGuestSurveyTurn(turnPayload)
        : await api.sendSurveyTurn(await ensureSession(), turnPayload);
      setMessages((prev) => [
        ...prev.filter((message) => message.id !== tempId),
        response.userMessage,
        response.botMessage
      ]);
      setRiskEvent(response.riskEvent ?? null);

      if (response.riskEvent) {
        setChatMode("free");
        return;
      }

      if (!response.score.validAnswer) {
        return;
      }

      const nextAnswers = {
        ...surveyAnswers,
        [currentQuestion.key]: response.score.score
      };
      setSurveyAnswers(nextAnswers);

      if (response.completed) {
        const result = isGuest ? await api.submitGuestSurvey({
          userId: 0,
          answers: nextAnswers,
          extraFeatures: buildModelFeatures(nextAnswers, demographics)
        }) : await api.submitSurvey({
          userId: user.id,
          answers: nextAnswers,
          extraFeatures: buildModelFeatures(nextAnswers, demographics)
        });
        setChatMode("free");
        onDone(result);
        return;
      }
      const selectedNextQuestionKey = response.nextQuestionKey;
      if (selectedNextQuestionKey) {
        setSurveyOrder((order) => moveQuestionKeyToNext(order, selectedNextQuestionKey, surveyIndex + 1));
      }
      setSurveyIndex((index) => Math.min(index + 1, surveyQuestions.length - 1));
    } catch (error) {
      setMessages((prev) => prev.filter((message) => message.id !== tempId));
      setChatError(error instanceof Error ? error.message : "AI 문답 응답을 처리하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  const showLanding = !landingDismissed && chatMode === "free" && messages.length === 0 && !loading && !riskEvent && !chatError;

  if (showLanding) {
    return (
      <section className="view chat-landing-view">
        <section className="chat-landing-hero">
          <div className="chat-landing-copy">
            <h1>대화하듯이<br />검진하고 싶어요</h1>
            <p>
              오늘의 기분, 수면, 스트레스, 생활 변화를 대화하듯 입력하면
              참고용 위험 신호와 다음 행동을 정리해드려요.
            </p>
            <div className="chat-landing-actions">
              <button className="primary" type="button" onClick={() => {
                setLandingDismissed(true);
                setChatStage("setup");
              }}>
                채팅 시작
              </button>

            </div>
          </div>

          <div className="landing-visual" aria-hidden="true">
            <div className="landing-phone">
              <div className="phone-topline" />
              <div className="phone-message bot">
                최근 2주 동안 수면이나 기분 변화가 있었나요?
              </div>
              <div className="phone-message user">
                잠이 얕고 스트레스가 조금 늘었어요.
              </div>
              <div className="phone-message bot">
                답변을 바탕으로 위험 신호를 함께 확인할게요.
              </div>
              <div className="phone-message user">
                생활 변화 흐름도 같이 보고 싶어요.
              </div>
              <div className="phone-score-card">
                <span>현재 상태 요약</span>
                <strong>차분히 확인 중</strong>
                <div className="phone-score-bar"><span /></div>
              </div>
            </div>
          </div>
        </section>

      </section>
    );
  }

  if (chatStage === "setup") {
    return (
      <section className="view chat-setup-view">
        <header className="view-header chat-setup-header">
          <div className="chat-setup-title">
            <h1>기본 정보를 확인하고 시작할게요</h1>
          </div>
          <div className="header-actions chat-setup-actions">
            <button className="secondary back-button" type="button" onClick={backToLanding} disabled={loading}>
              이전
            </button>
            <span className="tone-status">{toneLabel(tone)} 말투 적용 중</span>
          </div>
        </header>
        <div className="chat-mode-panel chat-setup-panel">
          <div>
            <h2>AI가 한 문항씩 차분히 물어볼게요</h2>
            <p>나이와 성별은 모델 입력에 필요한 기본 정보입니다. 시작하면 채팅창 전용 화면으로 전환됩니다.</p>
          </div>
          <div className="chat-demographics">
            <label>
              나이
              <input type="number" value={demographics.age} onChange={(e) => setDemographics({ ...demographics, age: Number(e.target.value) })} />
            </label>
            <label>
              성별
              <select value={demographics.sex} onChange={(e) => setDemographics({ ...demographics, sex: Number(e.target.value) })}>
                <option value={1}>남성</option>
                <option value={2}>여성</option>
              </select>
            </label>
            <button className="primary" onClick={startAiSurvey} disabled={loading}>
              {loading ? "준비 중" : "문답 시작"}
            </button>
          </div>
        </div>
        {chatError && <p className="error">{chatError}</p>}
      </section>
    );
  }

  return (
    <section className="view chat-conversation-view">
      <header className="view-header">
        <div>
          <h1>{chatMode === "survey" ? "AI 문답 검사" : "챗봇 문답"}</h1>
        </div>
        <div className="header-actions">
          <button className="secondary back-button" type="button" onClick={backToChatSetup} disabled={loading}>
            이전
          </button>
          <span className="tone-status">{toneLabel(tone)} 말투 적용 중</span>
  
          <span className="ai-refresh-wrap" data-tooltip="AI가 비정상적인 작동을 할 때 눌러주세요">
            <button
              className="ai-refresh-button"
              onClick={regenerateCurrentAiResponse}
              disabled={loading || messages.length === 0}
              aria-label="AI 응답 다시 생성"
              title="AI가 비정상적인 작동을 할 때 눌러주세요"
            >
              ↻
            </button>
          </span>
        </div>
      </header>
      {riskEvent && (
        <div className="alert">
          <strong>고위험 신호가 감지되었습니다.</strong>
          <p>{riskEvent.actionTaken}</p>
        </div>
      )}
      {chatError && <p className="error">{chatError}</p>}
      <div className="chat-window">
        {messages.length === 0 && (
          <div className="empty-state">
            <p>오늘의 기분, 수면, 스트레스, 생활 변화에 대해 편하게 입력해 주세요.</p>
          </div>
        )}
        {messages.map((message) => (
          <div key={message.id} className={`message-row ${message.sender.toLowerCase()}`}>
            {message.sender === "BOT" && <span className="chat-avatar">AI</span>}
            <div className="message-stack">
              <span className="message-meta">{message.sender === "USER" ? user.name : "AI 상담 챗봇"}</span>
              <div className={`bubble ${message.sender.toLowerCase()}`}>
                {message.content}
              </div>
            </div>
            {message.sender === "USER" && <span className="chat-avatar user-avatar">나</span>}
          </div>
        ))}
        {loading && streamingBotId === null && (
          <div className="message-row bot">
            <span className="chat-avatar">AI</span>
            <div className="message-stack">
              <span className="message-meta">AI 상담 챗봇</span>
              <div className="bubble bot typing-bubble">
                <span />
                <span />
                <span />
              </div>
            </div>
          </div>
        )}
        <div ref={chatEndRef} />
      </div>
      <div className="composer">
        <textarea
          value={input}
          onCompositionStart={() => {
            composingRef.current = true;
          }}
          onCompositionEnd={(event) => {
            composingRef.current = false;
            setInput(event.currentTarget.value);
          }}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing || composingRef.current) {
              return;
            }
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              send();
            }
          }}
          placeholder={chatMode === "survey" ? "편하게 답해주세요. 예: 응, 요즘 그런 편이야." : "예: 요즘 잠을 잘 못 자고 스트레스가 많아요."}
        />
        <button className="primary" onClick={send} disabled={loading}>{loading ? "분석 중" : "전송"}</button>
      </div>
    </section>
  );
}

function SurveyPage({ user, onDone }: { user: AuthUser; onDone: (assessment?: Assessment) => void }) {
  const [surveyStage, setSurveyStage] = useState<"landing" | "setup" | "questions">("landing");
  const [currentIndex, setCurrentIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<string, number>>(defaultSurveyAnswers);
  const [demographics, setDemographics] = useState<Record<string, number | string>>({
    age: 25,
    sex: 1,
    region: 1,
    year: CURRENT_SERVICE_YEAR
  });
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [loading, setLoading] = useState(false);
  const isGuest = Boolean(user.guest);
  const started = surveyStage === "questions";
  const currentQuestion = surveyQuestions[currentIndex];
  const answeredCount = Object.values(answers).filter((value) => value >= 0).length;
  const progress = Math.round((answeredCount / surveyQuestions.length) * 100);
  const isLast = currentIndex === surveyQuestions.length - 1;

  async function submit() {
    setLoading(true);
    try {
      const result = isGuest ? await api.submitGuestSurvey({
        userId: 0,
        answers,
        extraFeatures: buildModelFeatures(answers, demographics)
      }) : await api.submitSurvey({
        userId: user.id,
        answers,
        extraFeatures: buildModelFeatures(answers, demographics)
      });
      setAssessment(result);
      onDone(result);
    } finally {
      setLoading(false);
    }
  }

  function selectAnswer(value: number) {
    setAnswers((prev) => ({ ...prev, [currentQuestion.key]: value }));
  }

  function goNext() {
    if (answers[currentQuestion.key] < 0 || loading) {
      return;
    }
    if (isLast) {
      submit();
      return;
    }
    setCurrentIndex((index) => Math.min(index + 1, surveyQuestions.length - 1));
  }

  useEffect(() => {
    if (!started) {
      return;
    }

    function handleSurveyShortcut(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const isTypingTarget = target
        && (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || target.isContentEditable);
      if (isTypingTarget || event.ctrlKey || event.metaKey || event.altKey || loading) {
        return;
      }

      const selected = answers[currentQuestion.key] ?? -1;
      if (/^[1-5]$/.test(event.key)) {
        event.preventDefault();
        selectAnswer(Number(event.key) - 1);
        return;
      }

      if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) {
        event.preventDefault();
        const delta = event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 1;
        const nextValue = selected < 0 ? 0 : Math.max(0, Math.min(4, selected + delta));
        selectAnswer(nextValue);
        return;
      }

      if ((event.key === "Enter" || event.key === " " || event.key === "Spacebar") && !event.repeat) {
        if (selected >= 0) {
          event.preventDefault();
          goNext();
        }
      }
    }

    window.addEventListener("keydown", handleSurveyShortcut);
    return () => window.removeEventListener("keydown", handleSurveyShortcut);
  }, [answers, currentQuestion.key, isLast, loading, started]);

  if (surveyStage === "landing") {
    return (
      <section className="view chat-landing-view survey-landing-view">
        <section className="chat-landing-hero survey-landing-hero">
          <div className="chat-landing-copy survey-landing-copy">
            <h1>문항을 고르며<br />검진할게요</h1>
            <p>
              다섯 단계 응답으로 기분, 수면, 스트레스, 생활 변화를 입력하면
              AI가 보기 좋게 정리해드려요.
            </p>
            <div className="chat-landing-actions survey-landing-actions">
              <button className="primary" type="button" onClick={() => setSurveyStage("setup")}>설문 시작</button>
            </div>
          </div>

          <div className="landing-visual survey-landing-visual" aria-hidden="true">
            <div className="landing-phone survey-phone">
              <div className="phone-topline" />
              <div className="survey-preview-card">
                <span>문항 08</span>
                <strong>최근 2주 동안 잠을 자도 피곤함이 남았나요?</strong>
                <div className="survey-preview-scale">
                  <span>1</span>
                  <span>2</span>
                  <span className="active">3</span>
                  <span>4</span>
                  <span>5</span>
                </div>
                <small>보통 이에요.</small>
              </div>
              <div className="survey-preview-progress">
                <div>
                  <span>진행률</span>
                  <strong>42%</strong>
                </div>
                <div className="phone-score-bar"><span /></div>
              </div>
              <div className="survey-preview-domains">
                <span>기분·흥미</span>
                <span>수면·에너지</span>
                <span>스트레스·건강</span>
              </div>
            </div>
          </div>
        </section>
      </section>
    );
  }

  if (surveyStage === "setup") {
    return (
      <section className="view chat-setup-view survey-setup-view">
        <header className="view-header chat-setup-header">
          <div className="chat-setup-title">
            <h1>기본 정보를 확인하고 시작할게요</h1>
          </div>
          <div className="header-actions chat-setup-actions">
            <button className="secondary back-button" type="button" onClick={() => setSurveyStage("landing")} disabled={loading}>
              이전
            </button>
          </div>
        </header>
        <div className="chat-mode-panel chat-setup-panel survey-setup-panel">
          <div>
            <h2>선택형 설문에 필요한 정보예요</h2>
            <p>나이와 성별은 모델 입력에 필요한 기본 정보입니다. 시작하면 한 문항씩 선택하는 화면으로 전환됩니다.</p>
          </div>
          <div className="chat-demographics">
            <label>
              나이
              <input type="number" value={demographics.age} onChange={(e) => setDemographics({ ...demographics, age: Number(e.target.value) })} />
            </label>
            <label>
              성별
              <select value={demographics.sex} onChange={(e) => setDemographics({ ...demographics, sex: Number(e.target.value) })}>
                <option value={1}>남성</option>
                <option value={2}>여성</option>
              </select>
            </label>
            <button className="primary" onClick={() => {
              setCurrentIndex(0);
              setSurveyStage("questions");
            }}>
              설문 시작
            </button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="view">
      <header className="view-header">
        <div>
          <h1>설문 기반 검사</h1>
        </div>
        <span className="disclaimer">문항 {currentIndex + 1} / {surveyQuestions.length} · 진행률 {progress}%</span>
      </header>
      <div className="progress-track" aria-label="설문 진행률">
        <span style={{ width: `${progress}%` }} />
      </div>
      <div className="survey-keyboard-hint" aria-label="설문 키보드 단축키 안내">
        <span className="survey-control-pill mouse-control-hint">
          <i className="cursor-mark" aria-hidden="true" />
          클릭 선택
        </span>
        <span className="keyboard-control-hints">
          <span className="survey-control-pill"><kbd>1</kbd>~<kbd>5</kbd> 선택</span>
          <span className="survey-control-pill"><kbd>←</kbd><kbd>→</kbd> 이동</span>
          <span className="survey-control-pill"><kbd>Enter</kbd> 또는 <kbd>Space</kbd> 다음</span>
        </span>
      </div>
      <div className="survey-focus">
        <article className="question-card" key={currentQuestion.key}>
          <h2>{currentQuestion.title}</h2>
          <p>{currentQuestion.helper}</p>
          <div className="likert-row">
            {currentQuestion.options?.map((option) => (
              <button
                key={option.value}
                className={answers[currentQuestion.key] === option.value ? "likert selected" : "likert"}
                onClick={() => selectAnswer(option.value)}
              >
                <strong>{option.value + 1}</strong>
                <span>{option.label}</span>
              </button>
            ))}
          </div>
        </article>
        <footer className="survey-actions">
          <button className="secondary" onClick={() => setCurrentIndex((index) => Math.max(index - 1, 0))} disabled={currentIndex === 0 || loading}>이전</button>
          <button className="secondary" onClick={() => setSurveyStage("setup")} disabled={loading}>기본 정보</button>
          <button className="primary" onClick={goNext} disabled={answers[currentQuestion.key] < 0 || loading}>
            {loading ? "분석 중" : isLast ? "점수 산출" : "다음"}
          </button>
        </footer>
        {assessment && <p className="notice">최근 산출 점수: {assessment.finalScore.toFixed(1)}</p>}
      </div>
    </section>
  );
}

function ResultPage({ user, tone, guestAssessment }: { user: AuthUser; tone: ConversationTone; guestAssessment?: Assessment | null }) {
  const [items, setItems] = useState<Assessment[]>([]);
  const [error, setError] = useState("");
  const [reportMode, setReportMode] = useState<"user" | "expert">("user");
  const [aiSummaryCache, setAiSummaryCache] = useState<Record<string, string>>({});
  const [aiSummary, setAiSummary] = useState("");
  const [typedSummary, setTypedSummary] = useState("");
  const [aiSummaryLoading, setAiSummaryLoading] = useState(false);
  const [aiSummaryReloadKey, setAiSummaryReloadKey] = useState(0);
  const [selectedAssessmentKey, setSelectedAssessmentKey] = useState<string | null>(null);
  const isGuest = Boolean(user.guest);

  useEffect(() => {
    if (isGuest) {
      setItems(guestAssessment ? [guestAssessment] : []);
      setError("");
      return;
    }
    api.getMyAssessments(user.id).then(setItems).catch((err) => setError(err.message));
  }, [user.id, isGuest, guestAssessment]);

  useEffect(() => {
    if (items.length === 0) {
      setSelectedAssessmentKey(null);
      return;
    }
    const exists = selectedAssessmentKey && items.some((item) => assessmentKey(item) === selectedAssessmentKey);
    if (!exists) {
      setSelectedAssessmentKey(assessmentKey(items[0]));
    }
  }, [items, selectedAssessmentKey]);

  const latest = items.find((item) => assessmentKey(item) === selectedAssessmentKey) ?? items[0];
  const activeAssessmentKey = latest ? assessmentKey(latest) : "";
  const chartData = [...items].reverse().map((item, index) => ({
    assessmentKey: assessmentKey(item),
    label: chartTickLabel(item.createdAt, index),
    detailLabel: chartDetailLabel(item.createdAt),
    score: item.finalScore,
    riskLevel: item.riskLevel,
    isSelected: assessmentKey(item) === activeAssessmentKey
  }));
  const risk = latest ? riskMeta(latest.riskLevel) : null;
  const domainScores = latest?.domainScores ?? [];
  const factorContributions = latest?.factorContributions ?? [];
  const globalImportance = latest?.globalImportance ?? [];
  const topDomains = [...domainScores]
    .filter((domain) => domain.key !== "safety")
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);
  const displaySignals = compactSignals(latest?.detectedSignals ?? []);
  const signalGroups = groupSignals(displaySignals);
  const displayFactorContributions = factorContributions.length > 0
    ? factorContributions
    : fallbackFactorContributions(topDomains, displaySignals);
  const normalizedGlobalImportance = normalizeImportance(globalImportance);
  const importanceFallbackUsed = normalizedGlobalImportance.length === 0
    || normalizedGlobalImportance.every((item) => item.label === "생활습관 관련 요인");
  const displayGlobalImportance = importanceFallbackUsed
    ? fallbackGlobalImportance(topDomains)
    : normalizedGlobalImportance;
  const maxImportance = Math.max(...displayGlobalImportance.map((item) => item.importance), 1);
  const priorityFactors = displayFactorContributions.slice(0, 3);
  const phqScoreMeaning = scoreRangeMeaning(latest?.phqLikeScore ?? 0);

  useEffect(() => {
    if (!latest || reportMode !== "user") {
      return;
    }
    const cacheKey = `${latest.id ?? latest.createdAt}:${tone}`;
    const cached = aiSummaryCache[cacheKey];
    if (cached) {
      setAiSummary(cached);
      return;
    }

    let cancelled = false;
    setAiSummary("");
    setTypedSummary("");
    setAiSummaryLoading(true);
    let streamedSummary = "";
    api.getAiSummaryStream(latest, tone, (chunk) => {
      if (cancelled) return;
      streamedSummary += chunk;
      setTypedSummary(cleanAiSummary(streamedSummary));
    })
      .then((response) => {
        if (cancelled) return;
        const cleaned = cleanAiSummary(response.summary || streamedSummary);
        setAiSummaryCache((prev) => ({ ...prev, [cacheKey]: cleaned }));
        setAiSummary(cleaned);
        setTypedSummary(cleaned);
      })
      .catch(() => {
        if (cancelled) return;
        const fallback = cleanAiSummary(toneFallbackSummary(
          `${plainReportSummary(latest, topDomains)}\n\n${plainDomainSummary(topDomains)} ${plainSignalSummary(displaySignals)}`,
          tone
        ));
        setAiSummaryCache((prev) => ({ ...prev, [cacheKey]: fallback }));
        setAiSummary(fallback);
      })
      .finally(() => {
        if (!cancelled) {
          setAiSummaryLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [latest?.id, latest?.createdAt, reportMode, aiSummaryReloadKey, tone]);

  useEffect(() => {
    if (!aiSummary) {
      setTypedSummary("");
      return;
    }
    setTypedSummary(aiSummary);
  }, [aiSummary]);

  function regenerateAiSummary() {
    if (!latest || aiSummaryLoading) return;
    setAiSummaryCache((prev) => {
      const next = { ...prev };
      delete next[`${latest.id ?? latest.createdAt}:${tone}`];
      return next;
    });
    setAiSummary("");
    setTypedSummary("");
    setAiSummaryReloadKey((key) => key + 1);
  }

  return (
    <section className="view">
      <header className="view-header">
        <div>
          <h1>{reportMode === "user" ? "내 상태 보고서" : "전문가용 상세 리포트"}</h1>
        </div>
        <div className="report-toggle" aria-label="결과 화면 전환">
          <button className={reportMode === "user" ? "active" : ""} onClick={() => setReportMode("user")}>요약 보고서</button>
          <button className={reportMode === "expert" ? "active" : ""} onClick={() => setReportMode("expert")}>전문가용</button>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!latest && <div className="empty-state">{isGuest ? "게스트모드 결과는 저장되지 않습니다. 챗봇 문답이나 설문을 완료하면 이 화면에서만 확인할 수 있어요." : "아직 저장된 설문 결과가 없습니다."}</div>}
      {latest && risk && reportMode === "user" && (
        <div className="user-report report-animated">
          <section className={`user-hero risk-${latest.riskLevel.toLowerCase()}`}>
            <div>
              <h2>{risk.title}</h2>
              <p>{plainReportSummary(latest, topDomains)}</p>
            </div>
            <aside className="status-summary">
              <span>종합 상태 점수</span>
              <strong className="score-with-denominator">
                <span>{latest.finalScore.toFixed(1)}</span>
                <em>/ 100점</em>
              </strong>
              <small>{riskLabel(latest.riskLevel)} 단계</small>
            </aside>
          </section>

          <div className="metric-row">
            <div className="metric metric-phq">
              <span>우울 관련 문항 점수</span>
              <strong className="metric-value-with-unit">
                <span>{latest.phqLikeScore}</span>
                <em>/ {PHQ_LIKE_MAX_SCORE}점</em>
              </strong>
              <small>{phqScoreMeaning}</small>
              <small className="metric-help">PHQ-9 참고 9개 문항을 합산한 값입니다.</small>
            </div>
            <div className="metric">
              <span>두드러진 영역</span>
              <strong className="metric-word">{topDomains[0]?.label ?? "없음"}</strong>
              <small>{topDomains[0] ? `${topDomains[0].score.toFixed(1)} / 100점` : "추가 설문 후 표시"}</small>
            </div>
            <div className="metric metric-signal">
              <span>주의 신호 항목</span>
              <strong className="metric-value-with-unit">
                <span>{displaySignals.length}</span>
                <em>/ {SIGNAL_CHECK_ITEM_COUNT}개</em>
              </strong>
              <small>전체 확인 항목 중 주의 신호로 표시된 개수입니다.</small>
              <small className="metric-help">아래 주의 신호 항목에서 어떤 항목인지 확인할 수 있습니다.</small>
            </div>
          </div>

          <div className="user-report-columns">
            <div className="user-report-main-column">
              <section className="plain-report">
                <div className="section-title">
                  <div>
                    <h2>AI 요약</h2>
                    <span className="tone-inline">{toneLabel(tone)} 말투로 작성됩니다</span>
                  </div>
                  <span className="ai-refresh-wrap" data-tooltip="AI가 비정상적인 작동을 할 때 눌러주세요">
                    <button
                      className="ai-refresh-button"
                      onClick={regenerateAiSummary}
                      disabled={aiSummaryLoading}
                      aria-label="AI 요약 다시 생성"
                      title="AI가 비정상적인 작동을 할 때 눌러주세요"
                    >
                      ↻
                    </button>
                  </span>
                </div>
                <div className="ai-summary-box">
                  {aiSummaryLoading && !typedSummary ? (
                    <div className="typing-line">
                      <span />
                      <span />
                      <span />
                    </div>
                  ) : (
                    <p className="ai-summary-text">{typedSummary}<span className="typing-caret" /></p>
                  )}
                </div>
              </section>

              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>영역별 상태</h2>
                  </div>
                  <span>0 낮음 · 100 높음</span>
                </div>
                <div className="domain-bars">
                  {domainScores.map((domain) => (
                    <div className="domain-row" key={domain.key}>
                      <div>
                        <strong>{domain.label}</strong>
                        <small>{domain.description}</small>
                      </div>
                      <div className="bar-track">
                        <span style={{ width: `${domain.score}%` }} />
                      </div>
                      <b>{domain.score.toFixed(1)}</b>
                    </div>
                  ))}
                </div>
              </section>

              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>상태 점수 흐름</h2>
                  </div>
                </div>
                <AssessmentTrendChart data={chartData} onSelect={setSelectedAssessmentKey} />
              </section>
            </div>

            <div className="user-report-side-column">
              <section className="plain-report">
                <div className="section-title">
                  <div>
                    <h2>다음 확인 사항</h2>
                  </div>
                </div>
                <ul className="suggestion-list">
                  {nextSteps(latest.riskLevel).map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ul>
              </section>

              <section className="plain-report">
                <div className="section-title">
                  <div>
                    <h2>주의 신호 항목</h2>
                  </div>
                </div>
                <div className="signal-group-list">
                  {displaySignals.length === 0 && <p className="muted">추가로 표시된 주의 신호 항목이 없습니다.</p>}
                  {signalGroups.map((group) => (
                    <article className="signal-group" key={group.label}>
                      <strong>{group.label}</strong>
                      <div className="signal-list compact">
                        {group.items.map((signal) => (
                          <span key={signal}>{signal}</span>
                        ))}
                      </div>
                    </article>
                  ))}
                </div>
              </section>
            </div>
          </div>
        </div>
      )}
      {latest && risk && reportMode === "expert" && (
        <div className="result-dashboard report-animated">
          <section className={`result-hero risk-${latest.riskLevel.toLowerCase()}`}>
            <div>
              <h2>{risk.title}</h2>
              <div className="expert-brief-grid">
                <article>
                  <span>핵심 관찰</span>
                  <strong>{displaySignals.slice(0, 3).join(" · ") || "뚜렷한 신호 없음"}</strong>
                </article>
                <article>
                  <span>우선 검토 요인</span>
                  <strong>{priorityFactors.map((factor) => factor.label).join(" · ") || "추가 산출 필요"}</strong>
                </article>
                <article>
                  <span>판독 메모</span>
                  <p>{expertBriefMemo(latest, displaySignals, priorityFactors)}</p>
                </article>
              </div>
            </div>
            <aside className="risk-summary">
              <span>종합 점수</span>
              <strong className="score-with-denominator">
                <span>{latest.finalScore.toFixed(1)}</span>
                <em>/ 100점</em>
              </strong>
              <small>{riskLabel(latest.riskLevel)} 단계</small>
            </aside>
          </section>

          <div className="metric-row">
            <div className="metric metric-phq">
              <span>설문 기반 점수</span>
              <strong className="metric-value-with-unit">
                <span>{latest.phqLikeScore}</span>
                <em>/ {PHQ_LIKE_MAX_SCORE}점</em>
              </strong>
              <small>{phqScoreMeaning}</small>
            </div>
            <div className="metric">
              <span>모델 기반 위험도</span>
              <strong>{latest.mlRiskPercent.toFixed(1)}%</strong>
              <small>모델 서버 산출값</small>
            </div>
            <div className="metric metric-signal">
              <span>관찰 신호</span>
              <strong className="metric-value-with-unit">
                <span>{displaySignals.length}</span>
                <em>/ {SIGNAL_CHECK_ITEM_COUNT}개</em>
              </strong>
              <small>주의 신호 항목 중 표시된 개수</small>
            </div>
          </div>

          <div className="expert-main-grid">
            <div className="expert-main-left">
              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>영역별 응답 프로파일</h2>
                  </div>
                  <span>0 낮음 · 100 높음</span>
                </div>
                <div className="domain-bars">
                  {domainScores.map((domain) => (
                    <div className="domain-row" key={domain.key}>
                      <div>
                        <strong>{domain.label}</strong>
                        <small>{domain.description}</small>
                      </div>
                      <div className="bar-track">
                        <span style={{ width: `${domain.score}%` }} />
                      </div>
                      <b>{domain.score.toFixed(1)}</b>
                    </div>
                  ))}
                </div>
              </section>

              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>개인별 변수 기여도</h2>
                  </div>
                  <span>상대 영향도</span>
                </div>
                <div className="factor-list">
                  {displayFactorContributions.length === 0 && <p className="muted">개인별 영향도는 모델 서버 연결 후 표시됩니다.</p>}
                  {displayFactorContributions.map((factor) => (
                    <article className={`factor-row ${factor.direction.toLowerCase()}`} key={`${factor.label}-${factor.direction}`}>
                      <div>
                        <span>{factorBadge(factor)}</span>
                        <h3>{factor.label}</h3>
                        <p>{factorInsight(factor)}</p>
                      </div>
                      <strong>{formatImpact(factor)}</strong>
                    </article>
                  ))}
                </div>
              </section>

              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>종합 점수 흐름</h2>
                  </div>
                </div>
                <AssessmentTrendChart data={chartData} onSelect={setSelectedAssessmentKey} />
              </section>
            </div>

            <div className="expert-main-right">
              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>주요 관찰 신호</h2>
                  </div>
                </div>
                <div className="signal-group-list">
                  {displaySignals.length === 0 && <p className="muted">뚜렷하게 추가 표시된 신호가 없습니다.</p>}
                  {signalGroups.map((group) => (
                    <article className="signal-group" key={group.label}>
                      <strong>{group.label}</strong>
                      <div className="signal-list compact">
                        {group.items.map((signal) => (
                          <span key={signal}>{signal}</span>
                        ))}
                      </div>
                    </article>
                  ))}
                </div>
              </section>

              <section className="report-section">
                <div className="section-title">
                  <div>
                    <h2>전체 데이터 영향 요인</h2>
                    {importanceFallbackUsed && <span className="source-note">저장된 모델 중요도 매핑이 부족해 현재 영역 점수 기준으로 대체 표시 중입니다.</span>}
                  </div>
                </div>
                {displayGlobalImportance.length === 0 ? (
                  <div className="importance-empty">
                    <strong>학습 데이터 기준 영향도 값이 아직 포함되지 않았습니다.</strong>
                    <p>이 그래프는 개인 1명의 응답이 아니라, 학습 데이터 전체에서 결과 산출에 많이 작용한 변수를 보여줍니다. 모델 서버가 켜진 상태에서 새 결과를 저장하면 자동으로 채워집니다.</p>
                  </div>
                ) : (
                  <div className="importance-list">
                    {displayGlobalImportance.map((item, index) => (
                      <div className="importance-row" key={`${item.label}-${index}`}>
                        <div className="importance-row-top">
                          <strong>{item.label}</strong>
                          <span>{item.importance.toFixed(3)}</span>
                        </div>
                        <div className="importance-track">
                          <span style={{ width: `${Math.max(8, item.importance / maxImportance * 100)}%` }} />
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

type AssessmentChartPoint = {
  assessmentKey: string;
  label: string;
  detailLabel: string;
  score: number;
  riskLevel: Assessment["riskLevel"];
  isSelected: boolean;
};

function AssessmentTrendChart({
  data,
  onSelect
}: {
  data: AssessmentChartPoint[];
  onSelect: (key: string) => void;
}) {
  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={data} margin={{ top: 10, right: 20, bottom: 24, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="label" interval={0} minTickGap={10} height={54} angle={-18} textAnchor="end" tick={{ fontSize: 12 }} />
        <YAxis domain={[0, 100]} />
        <Tooltip
          formatter={(value) => [`${Number(value).toFixed(1)}점`, "상태 점수"]}
          labelFormatter={(_, payload) => payload?.[0]?.payload?.detailLabel ?? ""}
        />
        <Line
          type="monotone"
          dataKey="score"
          stroke="#0f766e"
          strokeWidth={3}
          dot={(props: unknown) => (
            <AssessmentChartDot {...(props as AssessmentDotProps)} onSelect={onSelect} />
          )}
          activeDot={(props: unknown) => (
            <AssessmentChartDot {...(props as AssessmentDotProps)} active onSelect={onSelect} />
          )}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}

type AssessmentDotProps = {
  cx?: number;
  cy?: number;
  payload?: AssessmentChartPoint;
  active?: boolean;
  onSelect: (key: string) => void;
};

function AssessmentChartDot({ cx, cy, payload, active = false, onSelect }: AssessmentDotProps) {
  if (typeof cx !== "number" || typeof cy !== "number" || !payload) {
    return null;
  }
  const selected = payload.isSelected;
  const radius = selected ? 7 : active ? 6 : 5;
  return (
    <circle
      cx={cx}
      cy={cy}
      r={radius}
      fill={selected ? "#3182f6" : "#0f766e"}
      stroke="#ffffff"
      strokeWidth={selected ? 3 : 2}
      role="button"
      tabIndex={0}
      aria-label={`${payload.detailLabel} 결과 보기`}
      className="trend-dot"
      onClick={() => onSelect(payload.assessmentKey)}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onSelect(payload.assessmentKey);
        }
      }}
    />
  );
}

function AdminPage() {
  const [assessments, setAssessments] = useState<Assessment[]>([]);
  const [riskEvents, setRiskEvents] = useState<RiskEvent[]>([]);

  useEffect(() => {
    api.getAdminAssessments().then(setAssessments).catch(() => setAssessments([]));
    api.getAdminRiskEvents().then(setRiskEvents).catch(() => setRiskEvents([]));
  }, []);

  return (
    <section className="view">
      <header className="view-header">
        <div>
          <h1>관리자 대시보드</h1>
        </div>
      </header>
      <div className="admin-grid">
        <section className="table-panel">
          <h2>최근 상태 지표</h2>
          <table>
            <thead>
              <tr>
                <th>사용자</th>
                <th>점수</th>
                <th>위험도</th>
                <th>요약</th>
              </tr>
            </thead>
            <tbody>
              {assessments.map((item) => (
                <tr key={item.id}>
                  <td>{item.userName}</td>
                  <td>{item.finalScore.toFixed(1)}</td>
                  <td><span className={`pill ${item.riskLevel.toLowerCase()}`}>{riskLabel(item.riskLevel)}</span></td>
                  <td>{item.summary}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section className="table-panel">
          <h2>고위험 신호</h2>
          <table>
            <thead>
              <tr>
                <th>등급</th>
                <th>근거 문장</th>
                <th>조치 안내</th>
              </tr>
            </thead>
            <tbody>
              {riskEvents.map((event) => (
                <tr key={event.id}>
                  <td>{event.severity}</td>
                  <td>{event.evidenceText}</td>
                  <td>{event.actionTaken}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </section>
  );
}

const PHQ_LIKE_MAX_SCORE = 27;
const SIGNAL_CHECK_ITEM_COUNT = 20;

function riskLabel(level: Assessment["riskLevel"]) {
  return {
    LOW: "낮음",
    WATCH: "주의",
    HIGH: "높음",
    URGENT: "긴급"
  }[level];
}

function scoreRangeMeaning(score: number) {
  if (score >= 20) {
    return "현재 응답에서는 주의 신호가 매우 크게 표시된 범위입니다.";
  }
  if (score >= 15) {
    return "현재 응답에서는 주의 신호가 크게 표시된 범위입니다.";
  }
  if (score >= 10) {
    return "현재 응답에서는 주의 깊게 볼 신호가 표시된 범위입니다.";
  }
  return "현재 응답에서는 비교적 낮은 범위입니다.";
}

function riskMeta(level: Assessment["riskLevel"]) {
  return {
    LOW: { title: "현재 전반적인 상태가 안정적으로 확인됩니다." },
    WATCH: { title: "현재 주의 깊은 경과 확인이 요구됩니다." },
    HIGH: { title: "현재 빠른 상담 연결과 주변 지원 확인이 요구됩니다." },
    URGENT: { title: "현재 즉각적인 안전 확인과 보호 연결이 요구됩니다." }
  }[level];
}

function assessmentKey(item: Assessment) {
  return `${item.id ?? "guest"}:${item.createdAt}`;
}

function chartTickLabel(value: string, index: number) {
  const date = parseAssessmentDate(value);
  if (!date) {
    return `${value.slice(5, 10)} #${index + 1}`;
  }
  return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function chartDetailLabel(value: string) {
  return formatAssessmentDateTime(value);
}

function formatAssessmentDateTime(value: string) {
  const date = parseAssessmentDate(value);
  if (!date) {
    return value.replace("T", " ").slice(0, 16);
  }
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function parseAssessmentDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function pad2(value: number) {
  return value.toString().padStart(2, "0");
}

function compactSignals(signals: string[]) {
  const replacements: Record<string, string> = {
    "스트레스 인지 수준": "높은 스트레스",
    "주관적 건강 상태": "주관적 건강 악화",
    "걷기·운동 실천": "운동 리듬 신호",
    "수면 변화": "수면 신호",
    "식욕 변화": "식욕 신호",
    "초조감 또는 행동 변화": "초조감 또는 행동 신호",
    "초조감·행동 변화": "초조감 또는 행동 신호",
    "흡연 관련 생활습관": "흡연 상태",
    "신체활동 부족": "운동 부족"
  };
  const priority = [
    "고위험 문항 응답",
    "가라앉은 기분",
    "흥미 저하",
    "수면 신호",
    "피로감",
    "식욕 신호",
    "부정적 자기평가",
    "집중 어려움",
    "초조감 또는 행동 신호",
    "높은 스트레스",
    "주관적 건강 악화",
    "식사 리듬 불규칙",
    "운동 부족",
    "경제적 부담"
  ];
  const unique = Array.from(new Set(signals.map((signal) => replacements[signal] ?? signal)));
  return unique
    .filter((signal) => signal !== "생활습관 관련 요인")
    .sort((a, b) => {
      const ai = priority.indexOf(a);
      const bi = priority.indexOf(b);
      return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi);
    });
}

function groupSignals(signals: string[]) {
  const groups = [
    { label: "정서", match: ["흥미", "기분", "자기평가", "고위험"] },
    { label: "수면·신체", match: ["수면", "피로", "식욕", "건강"] },
    { label: "집중·행동", match: ["집중", "초조", "행동"] },
    { label: "생활·환경", match: ["스트레스", "운동", "음주", "흡연", "식사", "경제", "학업", "근로"] }
  ];
  const used = new Set<string>();
  const result = groups
    .map((group) => {
      const items = signals.filter((signal) => group.match.some((token) => signal.includes(token)));
      items.forEach((item) => used.add(item));
      return { label: group.label, items };
    })
    .filter((group) => group.items.length > 0);
  const others = signals.filter((signal) => !used.has(signal));
  if (others.length > 0) {
    result.push({ label: "기타", items: others });
  }
  return result;
}

function fallbackFactorContributions(
  topDomains: NonNullable<Assessment["domainScores"]>,
  signals: string[]
): NonNullable<Assessment["factorContributions"]> {
  if (topDomains.length > 0) {
    return topDomains.slice(0, 4).map((domain) => ({
      label: domain.label,
      impact: Math.round(domain.score) / 100,
      direction: "INFO" as const,
      explanation: `${domain.description}을 우선 확인하는 대체 요인입니다.`
    }));
  }
  return signals.slice(0, 4).map((signal) => ({
    label: signal,
    impact: 0,
    direction: "INFO" as const,
    explanation: "응답에서 관찰된 주의 신호로 우선 확인이 필요한 항목입니다."
  }));
}

function normalizeImportance(items: NonNullable<Assessment["globalImportance"]>) {
  const map = new Map<string, number>();
  items.forEach((item) => {
    const label = item.label?.trim();
    if (!label) return;
    const current = map.get(label) ?? 0;
    map.set(label, Math.max(current, item.importance));
  });
  return Array.from(map.entries())
    .map(([label, importance]) => ({ label, importance }))
    .sort((a, b) => b.importance - a.importance)
    .slice(0, 8);
}

function fallbackGlobalImportance(topDomains: NonNullable<Assessment["domainScores"]>) {
  return topDomains.slice(0, 6).map((domain) => ({
    label: domain.label,
    importance: Math.max(0.05, Math.round(domain.score * 10) / 1000)
  }));
}

function expertBriefMemo(
  assessment: Assessment,
  signals: string[],
  factors: NonNullable<Assessment["factorContributions"]>
) {
  const signalText = signals.length > 0 ? `${signals.length}개 관찰 신호` : "뚜렷한 관찰 신호 없음";
  const factorText = factors.length > 0 ? `${factors[0].label} 중심` : "모델 설명값 추가 필요";
  return `${riskLabel(assessment.riskLevel)} 단계로 정리되며, ${signalText}와 ${factorText}으로 우선 검토하면 됩니다.`;
}

function plainReportSummary(assessment: Assessment, topDomains: NonNullable<Assessment["domainScores"]>) {
  const primary = topDomains[0]?.label;
  const secondary = topDomains[1]?.label;
  if (!primary) {
    return `이번 응답의 종합 상태 점수는 ${assessment.finalScore.toFixed(1)}점이며, ${riskLabel(assessment.riskLevel)} 단계로 정리되었습니다.`;
  }
  return `이번 응답의 종합 상태 점수는 ${assessment.finalScore.toFixed(1)}점이며, ${primary}${secondary ? `와 ${secondary}` : ""} 영역에서 주의 신호가 두드러집니다.`;
}

function plainDomainSummary(topDomains: NonNullable<Assessment["domainScores"]>) {
  if (topDomains.length === 0) {
    return "아직 영역별 상태를 정리할 만큼 충분한 응답이 없습니다.";
  }
  const names = topDomains.map((domain) => domain.label).join(", ");
  return `${names} 영역을 중심으로 현재 상태를 확인했습니다. 점수가 높은 영역은 최근 생활 리듬이나 마음 상태에서 주의 깊게 볼 신호가 표시된 부분입니다.`;
}

function plainSignalSummary(signals: string[]) {
  if (signals.length === 0) {
    return "이번 응답에서 특별히 두드러진 주의 신호 항목은 표시되지 않았습니다. 이후 반복 설문을 통해 상태 흐름을 확인할 수 있습니다.";
  }
  return `응답에서 확인된 주의 신호 항목은 ${signals.slice(0, 6).join(", ")}입니다. 같은 신호가 며칠 이상 이어지거나 일상생활에 영향을 준다면 주변 사람이나 상담 창구와 함께 확인하는 것이 좋습니다.`;
}

function nextSteps(level: Assessment["riskLevel"]) {
  if (level === "URGENT") {
    return [
      "혼자 있기 어렵거나 안전이 걱정된다면 즉시 119, 112 또는 자살예방상담전화 109에 연락하세요.",
      "가까운 보호자, 친구, 상담자에게 지금 상태를 바로 공유하세요.",
      "오늘 안에 전문가 또는 학교·기관 상담 창구와 연결하세요."
    ];
  }
  if (level === "HIGH") {
    return [
      "오늘 또는 내일 중으로 상담 가능한 사람이나 기관에 현재 상태를 공유하세요.",
      "수면, 식사, 학업·근로 부담처럼 점수가 높게 나온 영역을 먼저 점검하세요.",
      "상태가 급격히 나빠지거나 안전 문제가 느껴지면 즉시 도움을 요청하세요."
    ];
  }
  if (level === "WATCH") {
    return [
      "며칠 동안 기분, 수면, 식사, 활동량 상태를 짧게 기록해 보세요.",
      "가까운 사람에게 최근 상태를 공유하고 혼자 감당하지 않도록 해보세요.",
      "같은 상태가 이어지면 학교·기관 상담 창구나 전문가 상담을 예약하세요."
    ];
  }
  return [
    "현재 생활 리듬을 유지하면서 수면, 식사, 활동량 상태를 가볍게 확인하세요.",
    "점수가 높게 나온 영역이 있다면 다음 설문에서 같은 신호가 이어지는지 살펴보세요.",
    "갑작스러운 기분 악화나 안전 문제가 생기면 바로 주변 도움을 요청하세요."
  ];
}

function cleanAiSummary(value: string) {
  const cleaned = value
    .replaceAll("detected된", "확인된")
    .replaceAll("detected 된", "확인된")
    .replaceAll("detected", "확인")
    .replaceAll("LightGBM", "분석 모델")
    .replaceAll("SHAP", "영향 요인 분석")
    .replaceAll("ML", "분석 모델")
    .replaceAll("확인된 변화", "주의 신호 항목")
    .replaceAll("주요 변화", "주의 신호 항목")
    .replaceAll("두드러진 변화", "두드러진 주의 신호")
    .replaceAll("기계학습 모델", "분석 모델")
    .replaceAll("머신러닝 모델", "분석 모델")
    .replace(/습니다(?=현재|이번|특히|또한|수면|주변|오늘|가능한)/g, "습니다. ")
    .replace(/어요(?=현재|이번|특히|또한|수면|주변|오늘|가능한)/g, "어요. ")
    .trim();

  const deduped = dedupeSummarySentences(cleaned);
  if (/[.요다세요]$/.test(deduped)) {
    return deduped;
  }
  const lastPeriod = deduped.lastIndexOf(".");
  return lastPeriod > 80 ? deduped.slice(0, lastPeriod + 1).trim() : deduped;
}

function dedupeSummarySentences(value: string) {
  const sentences = value
    .replace(/\s+/g, " ")
    .split(/(?<=[.!?])\s+/)
    .map((sentence) => sentence.trim())
    .filter(Boolean);
  const kept: string[] = [];
  sentences.forEach((sentence) => {
    const duplicated = kept.some((existing) => summarySimilarity(existing, sentence) >= 0.72);
    if (!duplicated && kept.length < 7) {
      kept.push(sentence);
    }
  });
  return kept.join(" ").trim();
}

function summarySimilarity(left: string, right: string) {
  const leftGrams = charGrams(left);
  const rightGrams = charGrams(right);
  if (leftGrams.size === 0 || rightGrams.size === 0) {
    return 0;
  }
  let overlap = 0;
  leftGrams.forEach((gram) => {
    if (rightGrams.has(gram)) overlap += 1;
  });
  const union = leftGrams.size + rightGrams.size - overlap;
  return union <= 0 ? 0 : overlap / union;
}

function charGrams(value: string) {
  const normalized = value.replace(/[^가-힣A-Za-z0-9]/g, "").toLowerCase();
  const grams = new Set<string>();
  if (normalized.length < 2) {
    if (normalized) grams.add(normalized);
    return grams;
  }
  for (let index = 0; index < normalized.length - 1; index += 1) {
    grams.add(normalized.slice(index, index + 2));
  }
  return grams;
}

function toneLabel(tone: ConversationTone) {
  return tone === "friendly" ? "친근한" : "예의있는";
}

function toneFallbackSummary(value: string, tone: ConversationTone) {
  if (tone === "polite") {
    return value;
  }
  return value
    .replaceAll("확인됩니다", "확인돼")
    .replaceAll("정리되었습니다", "정리됐어")
    .replaceAll("나타납니다", "나타나")
    .replaceAll("부분입니다", "부분이야")
    .replaceAll("좋습니다", "좋아")
    .replaceAll("확인하세요", "확인해보면 좋아")
    .replaceAll("있습니다", "있어")
    .replaceAll("입니다", "이야")
    .replaceAll("합니다", "해");
}

function formatImpact(factor: NonNullable<Assessment["factorContributions"]>[number]) {
  if (factor.direction === "INFO") {
    return "검토 필요";
  }
  const sign = factor.direction === "INCREASE" ? "+" : "-";
  return `${sign}${factor.impact.toFixed(3)}`;
}

function factorBadge(factor: NonNullable<Assessment["factorContributions"]>[number]) {
  if (factor.direction === "INFO") {
    return "참고 요인";
  }
  return factor.direction === "INCREASE" ? "주의해서 볼 항목" : "완충 가능 항목";
}

function factorInsight(factor: NonNullable<Assessment["factorContributions"]>[number]) {
  const label = factor.label;
  const rising = factor.direction === "INCREASE";
  const lowering = factor.direction === "DECREASE";

  if (label.includes("스트레스")) {
    return rising
      ? "최근 부담감이나 압박이 전체 점수에 크게 반영된 항목입니다. 스트레스가 언제 높아지는지 시간대와 상황을 함께 보면 상담자가 맥락을 파악하기 쉽습니다."
      : "스트레스 응답은 전체 점수를 일부 낮추는 쪽으로 작용했습니다. 다만 다른 생활 영역과 함께 볼 때 균형이 유지되는지 확인하는 항목입니다.";
  }
  if (label.includes("건강")) {
    return rising
      ? "스스로 느끼는 건강 상태가 좋지 않게 기록될수록 피로감, 활동 저하와 함께 해석될 수 있습니다."
      : "주관적 건강 상태가 비교적 안정적으로 응답되어 전체 점수를 완화하는 항목으로 잡혔습니다.";
  }
  if (label.includes("사회") || label.includes("외출")) {
    return rising
      ? "사회활동이나 외출 관련 주의 신호가 두드러진 항목입니다. 최근 만남, 등교, 출근, 외부활동이 줄었는지 함께 확인하면 좋습니다."
      : "사회적 접촉이나 외부활동 응답이 비교적 유지되는 쪽으로 나타나 전체 점수를 낮추는 데 기여했습니다.";
  }
  if (label.includes("흡연") || label.includes("음주")) {
    return rising
      ? "생활습관 관련 주의 신호가 점수에 반영된 항목입니다. 빈도 증가가 스트레스 대처 방식과 연결되어 있는지 확인할 필요가 있습니다."
      : "흡연·음주 관련 응답은 이번 결과에서 부담을 키우는 핵심 항목으로 보이지 않았습니다.";
  }
  if (label.includes("식사")) {
    return rising
      ? "식사 리듬의 흔들림이 생활 균형 신호로 반영된 항목입니다. 끼니 횟수와 식욕 상태를 같이 보면 좋습니다."
      : "식사 리듬이 비교적 유지되는 응답으로 해석되어 전체 점수를 일부 낮추는 방향으로 반영되었습니다.";
  }
  if (label.includes("학업") || label.includes("진로") || label.includes("경제") || label.includes("직업")) {
    return rising
      ? "학업, 진로, 경제활동 관련 부담이 현재 상태와 연결되어 보이는 항목입니다. 최근 일정 변화나 압박 요인을 함께 확인하는 데 유용합니다."
      : "학업, 진로, 경제활동 관련 응답은 이번 결과에서 상대적으로 완충 요인으로 나타났습니다.";
  }
  if (label.includes("수면")) {
    return rising
      ? "수면 관련 주의 신호가 피로감과 집중 저하에 이어질 수 있어 점수에 반영된 항목입니다."
      : "수면 관련 응답이 비교적 안정적으로 기록되어 전체 점수를 낮추는 데 일부 기여했습니다.";
  }
  if (lowering) {
    return "이번 응답에서는 전체 점수를 낮추는 쪽으로 작용한 항목입니다. 다른 상승 항목과 함께 비교하면 균형 요인을 찾는 데 도움이 됩니다.";
  }
  return "이번 응답에서 전체 점수와 함께 확인할 필요가 있는 항목입니다. 단독 판단보다 다른 주요 항목과 같이 보는 것이 좋습니다.";
}

export default App;
