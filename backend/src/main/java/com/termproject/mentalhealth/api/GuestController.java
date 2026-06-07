package com.termproject.mentalhealth.api;

import com.termproject.mentalhealth.domain.RiskSeverity;
import com.termproject.mentalhealth.domain.SenderType;
import com.termproject.mentalhealth.dto.AssessmentRequest;
import com.termproject.mentalhealth.dto.AssessmentResponse;
import com.termproject.mentalhealth.dto.ChatMessageDto;
import com.termproject.mentalhealth.dto.RegenerateChatResponse;
import com.termproject.mentalhealth.dto.RiskEventResponse;
import com.termproject.mentalhealth.dto.SendChatMessageRequest;
import com.termproject.mentalhealth.dto.StartSurveyChatRequest;
import com.termproject.mentalhealth.dto.SurveyChatStartResponse;
import com.termproject.mentalhealth.dto.SurveyChatTurnRequest;
import com.termproject.mentalhealth.dto.SurveyChatTurnResponse;
import com.termproject.mentalhealth.dto.SurveyQuestionBrief;
import com.termproject.mentalhealth.service.AssessmentService;
import com.termproject.mentalhealth.service.LlmService;
import com.termproject.mentalhealth.service.LlmService.SurveyTurnAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/guest")
public class GuestController {
    private static final String SAFETY_GUIDE = "지금 매우 힘든 상태일 수 있습니다. 이 서비스는 응급 대응을 대신할 수 없습니다. 즉시 주변의 신뢰할 수 있는 사람, 학교 상담센터, 지역 정신건강복지센터 또는 전문가에게 도움을 요청해 주세요. 한국에서는 자살예방상담전화 109를 이용할 수 있고, 긴급한 위험이 있다면 119 또는 112에 연락해 주세요.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmService llmService;
    private final AssessmentService assessmentService;

    public GuestController(LlmService llmService, AssessmentService assessmentService) {
        this.llmService = llmService;
        this.assessmentService = assessmentService;
    }

    @PostMapping("/chat")
    public RegenerateChatResponse chat(@Valid @RequestBody SendChatMessageRequest request) {
        RiskEventResponse riskEvent = detectRisk(request.content());
        String botContent = riskEvent == null
                ? llmService.buildSupportiveReply(request.content(), request.tone())
                : SAFETY_GUIDE;
        return new RegenerateChatResponse(botMessage(botContent), riskEvent);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody chatStream(@Valid @RequestBody SendChatMessageRequest request) {
        return outputStream -> {
            RiskEventResponse riskEvent = detectRisk(request.content());
            String botContent;
            if (riskEvent != null) {
                botContent = SAFETY_GUIDE;
                writeSse(outputStream, "delta", Map.of("content", botContent));
            } else {
                botContent = llmService.streamSupportiveReply(
                        request.content(),
                        request.tone(),
                        chunk -> writeSseUnchecked(outputStream, "delta", Map.of("content", chunk))
                );
            }
            writeSse(outputStream, "done", new RegenerateChatResponse(botMessage(botContent), riskEvent));
        };
    }

    @PostMapping("/survey/start")
    public SurveyChatStartResponse startSurveyChat(@Valid @RequestBody StartSurveyChatRequest request) {
        String botContent = llmService.buildSurveyQuestion(
                request.questionTitle(),
                request.questionIndex(),
                request.totalQuestions(),
                request.tone()
        );
        return new SurveyChatStartResponse(botMessage(botContent));
    }

    @PostMapping("/survey/turn")
    public SurveyChatTurnResponse surveyTurn(@Valid @RequestBody SurveyChatTurnRequest request) {
        RiskEventResponse riskEvent = detectRisk(request.content());
        List<SurveyQuestionBrief> candidates = normalizeRemainingQuestions(request.remainingQuestions(), request.nextQuestionTitle());
        SurveyTurnAnalysis analysis = riskEvent == null
                ? llmService.analyzeSurveyTurn(request.questionTitle(), request.content(), candidates, request.tone())
                : new SurveyTurnAnalysis(
                        new com.termproject.mentalhealth.dto.LikertScoreResponse(4, 1.0, "매우 그렇다", "고위험 표현이 감지되었습니다.", true),
                        null,
                        ""
                );

        boolean completed = candidates.isEmpty();
        String nextQuestionKey = null;
        String botContent;
        if (riskEvent != null) {
            botContent = SAFETY_GUIDE;
            completed = false;
        } else if (!analysis.score().validAnswer()) {
            botContent = llmService.buildSurveyRetryQuestion(
                    request.questionTitle(),
                    request.questionIndex(),
                    request.totalQuestions(),
                    analysis.score().rationale(),
                    request.tone()
            );
            completed = false;
        } else if (completed) {
            botContent = llmService.isFriendlyTone(request.tone())
                    ? "문답은 여기까지야. 지금까지 답해준 내용을 바탕으로 결과를 정리하고 있어."
                    : "문답이 모두 끝났습니다. 지금까지의 답변을 바탕으로 상태 결과를 정리하고 있어요.";
        } else {
            nextQuestionKey = analysis.nextQuestionKey();
            botContent = analysis.nextMessage();
        }

        return new SurveyChatTurnResponse(
                userMessage(request.content()),
                botMessage(botContent),
                analysis.score(),
                riskEvent,
                nextQuestionKey,
                completed
        );
    }

    @PostMapping("/assessment")
    public AssessmentResponse guestAssessment(@Valid @RequestBody AssessmentRequest request) {
        return assessmentService.evaluateGuestSurvey(request);
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

    private RiskEventResponse detectRisk(String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT).replace(" ", "");
        List<String> selfHarmSignals = List.of("자해", "죽고싶", "죽고싶다", "삶을포기", "사라지고싶", "극단적", "끝내고싶");
        List<String> emergencySignals = List.of("오늘", "지금", "방법", "계획", "약을", "뛰어내", "목을", "유서");
        List<String> harmSignals = List.of("해치고싶", "죽이고싶", "폭력을");

        boolean selfHarm = selfHarmSignals.stream().anyMatch(normalized::contains);
        boolean emergency = selfHarm && emergencySignals.stream().anyMatch(normalized::contains);
        boolean harmToOthers = harmSignals.stream().anyMatch(normalized::contains);
        if (!selfHarm && !harmToOthers) {
            return null;
        }
        return new RiskEventResponse(
                null,
                harmToOthers ? "harm_to_others" : "self_harm_or_suicide_signal",
                emergency ? RiskSeverity.EMERGENCY : RiskSeverity.HIGH,
                content,
                SAFETY_GUIDE,
                LocalDateTime.now()
        );
    }

    private ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(-System.nanoTime(), SenderType.USER, content, LocalDateTime.now());
    }

    private ChatMessageDto botMessage(String content) {
        return new ChatMessageDto(-System.nanoTime(), SenderType.BOT, content, LocalDateTime.now());
    }

    private void writeSseUnchecked(OutputStream outputStream, String event, Object data) {
        try {
            writeSse(outputStream, event, data);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeSse(OutputStream outputStream, String event, Object data) throws IOException {
        String payload = "event: " + event + "\n"
                + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
}
