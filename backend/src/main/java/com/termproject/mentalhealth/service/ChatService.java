package com.termproject.mentalhealth.service;

import com.termproject.mentalhealth.domain.AppUser;
import com.termproject.mentalhealth.domain.ChatMessage;
import com.termproject.mentalhealth.domain.ChatSession;
import com.termproject.mentalhealth.domain.RiskEvent;
import com.termproject.mentalhealth.domain.RiskSeverity;
import com.termproject.mentalhealth.domain.SenderType;
import com.termproject.mentalhealth.dto.ChatMessageDto;
import com.termproject.mentalhealth.dto.ChatMessageResponse;
import com.termproject.mentalhealth.dto.ChatSessionResponse;
import com.termproject.mentalhealth.dto.LikertScoreResponse;
import com.termproject.mentalhealth.dto.RegenerateChatResponse;
import com.termproject.mentalhealth.dto.RiskEventResponse;
import com.termproject.mentalhealth.dto.SurveyChatStartResponse;
import com.termproject.mentalhealth.dto.SurveyChatTurnResponse;
import com.termproject.mentalhealth.dto.SurveyQuestionBrief;
import com.termproject.mentalhealth.repository.ChatMessageRepository;
import com.termproject.mentalhealth.repository.ChatSessionRepository;
import com.termproject.mentalhealth.repository.RiskEventRepository;
import com.termproject.mentalhealth.repository.UserRepository;
import com.termproject.mentalhealth.service.LlmService.SurveyTurnAnalysis;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    private static final String SAFETY_GUIDE = "지금 매우 힘든 상태일 수 있습니다. 이 서비스는 응급 대응을 대신할 수 없습니다. 즉시 주변의 신뢰할 수 있는 사람, 학교 상담센터, 지역 정신건강복지센터 또는 전문가에게 도움을 요청해 주세요. 한국에서는 자살예방상담전화 109를 이용할 수 있고, 긴급한 위험이 있다면 119 또는 112에 연락해 주세요.";

    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RiskEventRepository riskEventRepository;
    private final LlmService llmService;

    public ChatService(
            UserRepository userRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            RiskEventRepository riskEventRepository,
            LlmService llmService
    ) {
        this.userRepository = userRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.riskEventRepository = riskEventRepository;
        this.llmService = llmService;
    }

    public record StreamedChatStart(ChatMessageDto userMessage, RiskEventResponse riskEvent) {
    }

    @Transactional
    public ChatSessionResponse createSession(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        ChatSession session = new ChatSession();
        session.setUser(user);
        return ChatSessionResponse.from(chatSessionRepository.save(session));
    }

    @Transactional
    public ChatMessageResponse sendMessage(Long sessionId, String content, String tone) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));

        ChatMessage savedUserMessage = saveMessage(session, SenderType.USER, content);

        RiskEvent riskEvent = detectRisk(session, content);
        String botContent = buildBotReply(content, riskEvent != null, tone);

        ChatMessage savedBotMessage = saveMessage(session, SenderType.BOT, botContent);

        return new ChatMessageResponse(
                ChatMessageDto.from(savedUserMessage),
                ChatMessageDto.from(savedBotMessage),
                RiskEventResponse.from(riskEvent)
        );
    }

    @Transactional
    public StreamedChatStart beginStreamedMessage(Long sessionId, String content) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("????몄뀡??李얠쓣 ???놁뒿?덈떎."));
        ChatMessage savedUserMessage = saveMessage(session, SenderType.USER, content);
        RiskEvent riskEvent = detectRisk(session, content);
        return new StreamedChatStart(ChatMessageDto.from(savedUserMessage), RiskEventResponse.from(riskEvent));
    }

    @Transactional
    public StreamedChatStart beginStreamedRegenerate(Long sessionId, String content) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("????몄뀡??李얠쓣 ???놁뒿?덈떎."));
        RiskEvent riskEvent = detectRisk(session, content);
        return new StreamedChatStart(null, RiskEventResponse.from(riskEvent));
    }

    @Transactional
    public ChatMessageDto saveStreamedBotMessage(Long sessionId, String content) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("????몄뀡??李얠쓣 ???놁뒿?덈떎."));
        return ChatMessageDto.from(saveMessage(session, SenderType.BOT, content));
    }

    @Transactional
    public SurveyChatStartResponse startSurveyChat(Long sessionId, String questionTitle, int questionIndex, int totalQuestions, String tone) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));
        String botContent = llmService.buildSurveyQuestion(questionTitle, questionIndex, totalQuestions, tone);
        ChatMessage savedBotMessage = saveMessage(session, SenderType.BOT, botContent);
        return new SurveyChatStartResponse(ChatMessageDto.from(savedBotMessage));
    }

    @Transactional
    public SurveyChatTurnResponse sendSurveyTurn(
            Long sessionId,
            String content,
            String questionTitle,
            String nextQuestionTitle,
            List<SurveyQuestionBrief> remainingQuestions,
            int questionIndex,
            int totalQuestions,
            String tone
    ) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));

        ChatMessage savedUserMessage = saveMessage(session, SenderType.USER, content);
        RiskEvent riskEvent = detectRisk(session, content);
        List<SurveyQuestionBrief> candidates = normalizeRemainingQuestions(remainingQuestions, nextQuestionTitle);
        SurveyTurnAnalysis analysis = riskEvent == null
                ? llmService.analyzeSurveyTurn(questionTitle, content, candidates, tone)
                : new SurveyTurnAnalysis(new LikertScoreResponse(4, 1.0, "매우 그렇다", "고위험 표현이 감지되었습니다.", true), null, "");
        LikertScoreResponse score = analysis.score();
        boolean completed = candidates.isEmpty();
        String nextQuestionKey = null;
        String botContent;
        if (riskEvent != null) {
            botContent = SAFETY_GUIDE;
            completed = false;
        } else if (!score.validAnswer()) {
            botContent = llmService.buildSurveyRetryQuestion(questionTitle, questionIndex, totalQuestions, score.rationale(), tone);
            completed = false;
        } else if (completed) {
            botContent = llmService.isFriendlyTone(tone)
                    ? "문답은 여기까지야. 지금까지 답해준 내용을 바탕으로 결과를 정리하고 있어."
                    : "문답이 모두 끝났습니다. 지금까지의 답변을 바탕으로 상태 결과를 정리하고 있어요.";
        } else {
            nextQuestionKey = analysis.nextQuestionKey();
            botContent = analysis.nextMessage();
        }
        ChatMessage savedBotMessage = saveMessage(session, SenderType.BOT, botContent);
        return new SurveyChatTurnResponse(
                ChatMessageDto.from(savedUserMessage),
                ChatMessageDto.from(savedBotMessage),
                score,
                RiskEventResponse.from(riskEvent),
                nextQuestionKey,
                completed
        );
    }

    private List<SurveyQuestionBrief> normalizeRemainingQuestions(List<SurveyQuestionBrief> remainingQuestions, String nextQuestionTitle) {
        if (remainingQuestions != null && !remainingQuestions.isEmpty()) {
            return remainingQuestions.stream()
                    .filter(question -> question != null && question.key() != null && !question.key().isBlank()
                            && question.title() != null && !question.title().isBlank())
                    .toList();
        }
        if (nextQuestionTitle == null || nextQuestionTitle.isBlank()) {
            return List.of();
        }
        return List.of(new SurveyQuestionBrief("legacy-next", nextQuestionTitle));
    }

    @Transactional
    public RegenerateChatResponse regenerateReply(Long sessionId, String content, String tone) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));
        RiskEvent riskEvent = detectRisk(session, content);
        String botContent = buildBotReply(content, riskEvent != null, tone);
        ChatMessage savedBotMessage = saveMessage(session, SenderType.BOT, botContent);
        return new RegenerateChatResponse(
                ChatMessageDto.from(savedBotMessage),
                RiskEventResponse.from(riskEvent)
        );
    }

    private RiskEvent detectRisk(ChatSession session, String content) {
        String normalized = content.toLowerCase(Locale.ROOT).replace(" ", "");
        List<String> selfHarmSignals = List.of("자해", "죽고싶", "죽고싶다", "삶을포기", "사라지고싶", "극단적", "끝내고싶");
        List<String> emergencySignals = List.of("오늘", "지금", "방법", "계획", "약을", "뛰어내", "목을", "유서");
        List<String> harmSignals = List.of("해치고싶", "죽이고싶", "폭력을");

        boolean selfHarm = selfHarmSignals.stream().anyMatch(normalized::contains);
        boolean emergency = selfHarm && emergencySignals.stream().anyMatch(normalized::contains);
        boolean harmToOthers = harmSignals.stream().anyMatch(normalized::contains);

        if (!selfHarm && !harmToOthers) {
            return null;
        }

        RiskEvent event = new RiskEvent();
        event.setUser(session.getUser());
        event.setSession(session);
        event.setRiskType(harmToOthers ? "harm_to_others" : "self_harm_or_suicide_signal");
        event.setSeverity(emergency ? RiskSeverity.EMERGENCY : RiskSeverity.HIGH);
        event.setEvidenceText(content);
        event.setActionTaken(SAFETY_GUIDE);
        return riskEventRepository.save(event);
    }

    private String buildBotReply(String content, boolean riskDetected, String tone) {
        if (riskDetected) {
            return SAFETY_GUIDE;
        }
        return llmService.buildSupportiveReply(content, tone);
    }

    private ChatMessage saveMessage(ChatSession session, SenderType sender, String content) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setSender(sender);
        message.setContent(content);
        return chatMessageRepository.save(message);
    }
}
