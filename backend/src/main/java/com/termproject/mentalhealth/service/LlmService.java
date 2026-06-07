package com.termproject.mentalhealth.service;

import com.termproject.mentalhealth.dto.AiSummaryRequest;
import com.termproject.mentalhealth.dto.AiSummaryResponse;
import com.termproject.mentalhealth.dto.DomainScoreResponse;
import com.termproject.mentalhealth.dto.FactorContributionResponse;
import com.termproject.mentalhealth.dto.LikertScoreResponse;
import com.termproject.mentalhealth.dto.SurveyQuestionBrief;
import com.termproject.mentalhealth.service.llmcache.LlmPurpose;
import com.termproject.mentalhealth.service.llmcache.SemanticCacheLookupResult;
import com.termproject.mentalhealth.service.llmcache.SemanticLlmCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final String apiKey;
    private final String model;
    private final String promptVersion;
    private final RestClient client;
    private final HttpClient httpClient;
    private final URI chatCompletionsUri;
    private final Duration llmReadTimeout;
    private final SemanticLlmCacheService semanticCache;
    private final boolean logCacheHits;
    private final int supportiveReplyMaxTokens;
    private final int likertScoringMaxTokens;
    private final int surveyTurnMaxTokens;
    private final int resultSummaryMaxTokens;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(
            @Value("${app.llm.api-key}") String apiKey,
            @Value("${app.llm.model}") String model,
            @Value("${app.llm.base-url}") String baseUrl,
            @Value("${app.llm.prompt-version:v1}") String promptVersion,
            @Value("${app.semantic-cache.log-hits:true}") boolean logCacheHits,
            @Value("${app.llm.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.llm.read-timeout-ms:4000}") int readTimeoutMs,
            @Value("${app.llm.max-tokens.supportive-reply:240}") int supportiveReplyMaxTokens,
            @Value("${app.llm.max-tokens.likert-scoring:140}") int likertScoringMaxTokens,
            @Value("${app.llm.max-tokens.survey-turn:360}") int surveyTurnMaxTokens,
            @Value("${app.llm.max-tokens.result-summary:520}") int resultSummaryMaxTokens,
            SemanticLlmCacheService semanticCache
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.promptVersion = promptVersion == null || promptVersion.isBlank() ? "v1" : promptVersion.trim();
        this.semanticCache = semanticCache;
        this.logCacheHits = logCacheHits;
        this.supportiveReplyMaxTokens = positiveOrDefault(supportiveReplyMaxTokens, 240);
        this.likertScoringMaxTokens = positiveOrDefault(likertScoringMaxTokens, 140);
        this.surveyTurnMaxTokens = positiveOrDefault(surveyTurnMaxTokens, 360);
        this.resultSummaryMaxTokens = positiveOrDefault(resultSummaryMaxTokens, 520);

        Duration connectTimeout = Duration.ofMillis(positiveOrDefault(connectTimeoutMs, 3000));
        Duration readTimeout = Duration.ofMillis(positiveOrDefault(readTimeoutMs, 4000));
        this.llmReadTimeout = readTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_2)
                .build();
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        this.chatCompletionsUri = URI.create(normalizedBaseUrl + "/chat/completions");
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(this.httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder()
                .baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost"
                : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public boolean isFriendlyTone(String tone) {
        return "friendly".equalsIgnoreCase(tone);
    }

    public String buildSupportiveReply(String userMessage, String tone) {
        if (apiKey.isBlank()) {
            return buildSupportiveReplyWithoutApi(tone);
        }

        try {
            LlmPurpose purpose = classifySupportiveReplyPurpose(userMessage);
            String output = chatCompletion(List.of(
                    Map.of("role", "system", "content", safetyInstructions(tone)),
                    Map.of("role", "user", "content", userMessage)
            ), 0.45, supportiveReplyMaxTokens, purpose, userMessage, tone);
            return output == null || output.isBlank() ? buildSupportiveReplyWithoutApi(tone) : output;
        } catch (RuntimeException error) {
            return buildSupportiveReplyWithoutApi(tone);
        }
    }

    public String streamSupportiveReply(String userMessage, String tone, Consumer<String> onDelta) {
        if (apiKey.isBlank()) {
            String fallback = buildSupportiveReplyWithoutApi(tone);
            emitDelta(onDelta, fallback);
            return fallback;
        }

        try {
            LlmPurpose purpose = classifySupportiveReplyPurpose(userMessage);
            String output = streamChatCompletion(List.of(
                    Map.of("role", "system", "content", safetyInstructions(tone)),
                    Map.of("role", "user", "content", userMessage)
            ), 0.45, supportiveReplyMaxTokens, purpose, userMessage, tone, onDelta);
            if (output == null || output.isBlank()) {
                String fallback = buildSupportiveReplyWithoutApi(tone);
                emitDelta(onDelta, fallback);
                return fallback;
            }
            return output;
        } catch (RuntimeException error) {
            String fallback = buildSupportiveReplyWithoutApi(tone);
            emitDelta(onDelta, fallback);
            return fallback;
        }
    }

    public LikertScoreResponse scoreLikertAnswer(String question, String answer) {
        if (isBlankSurveyAnswer(answer)) {
            return invalidLikertScore("답변 내용이 비어 있어 다시 확인이 필요합니다.");
        }

        if (apiKey.isBlank()) {
            return heuristicScore(answer);
        }

        try {
            String output = chatCompletion(List.of(
                    Map.of("role", "system", "content", likertScoringInstructions()),
                    Map.of("role", "user", "content", "문항: " + question + "\n사용자 답변: " + answer)
            ), 0.1, likertScoringMaxTokens, LlmPurpose.LIKERT_SCORING, "", "");
            LikertScoreResponse parsed = parseLikertJson(output, question, answer);
            return parsed == null ? heuristicScore(question, answer) : parsed;
        } catch (RuntimeException error) {
            return heuristicScore(question, answer);
        }
    }

    public AiSummaryResponse summarizeAssessment(AiSummaryRequest request) {
        if (apiKey.isBlank()) {
            return new AiSummaryResponse(fallbackSummary(request), true);
        }

        try {
            String payload = objectMapper.writeValueAsString(summaryPayload(request));
            String output = chatCompletion(List.of(
                    Map.of("role", "system", "content", assessmentSummaryInstructions(request.tone())),
                    Map.of("role", "user", "content", payload)
            ), 0.25, resultSummaryMaxTokens, LlmPurpose.RESULT_SUMMARY, "", request.tone());
            if (output == null || output.isBlank()) {
                return new AiSummaryResponse(fallbackSummary(request), true);
            }
            String summary = polishSummary(output);
            if (summary.isBlank() || containsRawFieldName(summary)) {
                return new AiSummaryResponse(fallbackSummary(request), true);
            }
            return new AiSummaryResponse(summary, false);
        } catch (Exception error) {
            return new AiSummaryResponse(fallbackSummary(request), true);
        }
    }

    public AiSummaryResponse streamAssessmentSummary(AiSummaryRequest request, Consumer<String> onDelta) {
        if (apiKey.isBlank()) {
            String fallback = fallbackSummary(request);
            emitDelta(onDelta, fallback);
            return new AiSummaryResponse(fallback, true);
        }

        try {
            String payload = objectMapper.writeValueAsString(summaryPayload(request));
            String output = streamChatCompletion(List.of(
                    Map.of("role", "system", "content", assessmentSummaryInstructions(request.tone())),
                    Map.of("role", "user", "content", payload)
            ), 0.25, resultSummaryMaxTokens, LlmPurpose.RESULT_SUMMARY, "", request.tone(), onDelta);
            if (output == null || output.isBlank()) {
                String fallback = fallbackSummary(request);
                emitDelta(onDelta, fallback);
                return new AiSummaryResponse(fallback, true);
            }
            String summary = polishSummary(output);
            if (summary.isBlank() || containsRawFieldName(summary)) {
                return new AiSummaryResponse(fallbackSummary(request), true);
            }
            return new AiSummaryResponse(summary, false);
        } catch (Exception error) {
            String fallback = fallbackSummary(request);
            emitDelta(onDelta, fallback);
            return new AiSummaryResponse(fallback, true);
        }
    }

    public String buildSurveyQuestion(String questionTitle, int questionIndex, int totalQuestions, String tone) {
        return localSurveyQuestion(questionTitle, questionIndex, tone);
    }

    public String buildSurveyRetryQuestion(String questionTitle, int questionIndex, int totalQuestions, String reason, String tone) {
        return fallbackSurveyRetryQuestion(questionTitle, tone, questionIndex);
    }

    public SurveyTurnAnalysis analyzeSurveyTurn(
            String questionTitle,
            String answer,
            List<SurveyQuestionBrief> remainingQuestions,
            String tone
    ) {
        if (isBlankSurveyAnswer(answer)) {
            return new SurveyTurnAnalysis(
                    invalidLikertScore("답변 내용이 비어 있어 다시 확인이 필요합니다."),
                    null,
                    ""
            );
        }

        List<SurveyQuestionBrief> candidates = remainingQuestions == null ? List.of() : remainingQuestions;
        if (apiKey.isBlank()) {
            return fallbackSurveyTurn(questionTitle, answer, candidates, tone);
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "현재질문", questionTitle,
                    "사용자답변", answer,
                    "다음후보문항", candidates.stream()
                            .map(question -> Map.of("key", question.key(), "title", question.title()))
                            .toList()
            ));
            String output = chatCompletion(List.of(
                    Map.of("role", "system", "content", adaptiveSurveyTurnInstructions(tone)),
                    Map.of("role", "user", "content", payload)
            ), 0.35, surveyTurnMaxTokens, LlmPurpose.CONVERSATIONAL_SURVEY_TURN, "", tone);
            SurveyTurnAnalysis parsed = parseSurveyTurnJson(output, questionTitle, answer, candidates, tone);
            return parsed == null ? fallbackSurveyTurn(questionTitle, answer, candidates, tone) : parsed;
        } catch (Exception error) {
            return fallbackSurveyTurn(questionTitle, answer, candidates, tone);
        }
    }

    private String safetyInstructions(String tone) {
        return """
                당신은 청년 우울 관련 상태 탐지 웹앱의 상담식 문답 AI입니다.
                사용자의 감정, 수면, 스트레스, 식사, 활동 변화에 대해 차분하게 이어서 질문합니다.
                '우울증입니다', '치료가 필요합니다', '진단됩니다' 같은 의료적 해석 문장은 쓰지 않습니다.
                사용자가 말한 내용 밖의 사실을 지어내지 않습니다.
                답변은 한국어 2~3문장으로 작성합니다.
                자해, 극단적 선택, 타해 위험이 보이면 즉시 109, 119, 112와 주변 사람에게 도움 요청 안내를 포함합니다.
                """ + toneInstruction(tone);
    }

    private String likertScoringInstructions() {
        return """
                사용자의 자연어 답변을 5점 리커트 척도로 변환합니다.
                의료 진단을 하지 말고, 문항에 대한 동의 정도만 판단합니다.
                출력은 반드시 JSON 하나만 사용합니다.
                score는 0~4 정수입니다.
                0=전혀 그렇지 않다, 1=그렇지 않은 편이다, 2=보통이다, 3=그런 편이다, 4=매우 그렇다.
                confidence는 0~1 숫자입니다.
                label은 위 다섯 라벨 중 하나입니다.
                validAnswer는 사용자의 답변이 짧거나 말투가 가벼워도 문항에 대한 정도, 빈도, 경험, 부정/긍정 여부를 조금이라도 담고 있으면 true입니다.
                기본 원칙: 사람 상담자가 맥락상 알아들을 수 있는 답변이면 validAnswer=true입니다.
                예: "응", "웅ㅠ", "ㅇㅇ", "응 많아", "좀 그래", "느꼈어", "느껴서", "그랬어", "그랬어 ㅠㅠ", "몰라 그런 느낌이 들긴 해", "그런 것 같기도 해", "ㄴㄴ", "아니 전혀", "모르겠어"는 모두 validAnswer=true입니다.
                예: "가나다라마바사", "asdf", "ㅋㅋㅋㅋ", 문항과 전혀 무관한 식사/날씨/잡담, 의미를 파악할 수 없는 답변은 validAnswer=false입니다.
                "응 많아", "자주 그래", "많이 그래"처럼 높은 정도를 뜻하면 score=4 또는 3으로 판단합니다.
                "응 조금", "좀 그래", "약간"처럼 일부 동의면 score=3으로 판단합니다.
                "응", "웅ㅠ", "ㅇㅇ", "맞아", "느꼈어", "그랬어"처럼 정도 표현 없이 긍정만 있으면 기본적으로 score=3으로 판단합니다.
                "아니", "전혀 아니야", "ㄴㄴ"처럼 부정이면 score=0 또는 1로 판단합니다.
                "모르겠어", "몰라", "애매해", "보통"처럼 판단이 유보된 답변만 있으면 score=2로 판단합니다.
                "몰라 그런 느낌이 들긴 해", "그런 것 같기도 해", "좀 맞는 것 같아"처럼 애매하지만 문항에 동의하는 쪽이면 score=3으로 판단합니다.
                validAnswer=false일 때 score는 2, confidence는 0.1 이하로 둡니다.
                rationale은 한국어 한 문장으로 짧게 작성합니다.
                """;
    }

    private String adaptiveSurveyTurnInstructions(String tone) {
        return """
                당신은 청년 우울 관련 상태 탐지 웹앱의 대화형 문답 분석기입니다.
                할 일은 4가지입니다.
                1. 사용자의 직전 답변이 현재 질문에 대한 유효한 답변인지 판단합니다.
                2. 유효하면 0~4 리커트 척도로 점수화합니다.
                3. 다음후보문항 중 자연스럽게 이어질 문항 key를 하나 고릅니다.
                4. 사용자의 직전 답변에 짧게 반응한 뒤, 선택한 다음 문항을 자연스럽게 묻는 nextMessage를 작성합니다.

                출력은 반드시 JSON 하나만 사용합니다.
                JSON 형식:
                {
                  "score": 0~4 정수,
                  "confidence": 0~1 숫자,
                  "label": "전혀 그렇지 않다|그렇지 않은 편이다|보통이다|그런 편이다|매우 그렇다",
                  "validAnswer": true 또는 false,
                  "rationale": "점수 판단 근거 한 문장",
                  "nextQuestionKey": "다음후보문항 중 하나의 key 또는 null",
                  "nextMessage": "사용자에게 보여줄 다음 질문 문장"
                }

                점수 기준:
                0=전혀 그렇지 않다, 1=그렇지 않은 편이다, 2=보통이다, 3=그런 편이다, 4=매우 그렇다.
                "응", "웅ㅠ", "ㅇㅇ", "맞아", "느꼈어", "그랬어"처럼 짧은 긍정 답변도 validAnswer=true이며 기본 score=3입니다.
                "아니", "전혀 아니야", "ㄴㄴ"처럼 부정이면 score=0 또는 1입니다.
                "모르겠어", "몰라", "애매해", "보통"처럼 판단이 유보된 답변만 있으면 score=2입니다.
                "몰라 그런 느낌이 들긴 해", "그런 것 같기도 해", "좀 맞는 것 같아"처럼 애매하지만 문항에 동의하는 쪽이면 validAnswer=true, score=3입니다.
                현재질문이 '희망이 적다', '기분이 가라앉다'처럼 부정 상태를 묻고 사용자답변이 "희망차지 요즘은", "요즘은 괜찮아", "기분 좋아"처럼 반대 의미이면 validAnswer=true, score=0 또는 1입니다.
                현재질문이 수면 변화를 묻고 사용자답변이 "좀 많이 자는 것 같아 요즘", "잠이 늘었어"이면 validAnswer=true, score=3 또는 4입니다.
                맞춤법이 조금 틀리거나 띄어쓰기가 이상해도 사람 상담자가 알아들을 수 있으면 validAnswer=true입니다.
                "가나다라마바사", "asdf", 의미 없는 반복, 질문과 무관한 잡담은 validAnswer=false입니다.

                다음 질문 선택 규칙:
                nextQuestionKey는 반드시 다음후보문항에 존재하는 key 중 하나여야 합니다.
                사용자의 답변과 자연스럽게 이어지는 후보가 있으면 그 후보를 고릅니다.
                자연스럽게 이어지는 후보가 뚜렷하지 않으면 다음후보문항의 첫 번째 key를 고릅니다.
                후보 문항의 의미를 바꾸거나 새 문항을 만들면 안 됩니다.
                nextMessage에는 선택한 후보 문항의 핵심 의미가 반드시 들어가야 합니다.
                현재 질문에 대한 사용자의 답변을 1문장 이내로 짧게 반영한 뒤 다음 질문으로 이어갑니다.
                '그 흐름과 함께 보면 좋겠습니다', '이어서 확인해보겠습니다', '한 번만 더'처럼 기계적인 연결 문구를 반복하지 않습니다.
                사용자의 표현을 그대로 이해한 듯 짧게 짚습니다. 예: "희망차지 요즘은" -> "요즘은 희망이 있는 쪽에 가깝다는 말로 이해했어요."
                예: "좀 많이 자는 것 같아 요즘" -> "잠이 전보다 늘어난 느낌이 있군요."
                '우울증입니다', '치료가 필요합니다', '진단됩니다' 같은 의료적 판단 표현은 쓰지 않습니다.
                '1/30', '문항', '설문', '점수' 같은 내부 진행 표현은 쓰지 않습니다.
                validAnswer=false이면 nextQuestionKey=null, nextMessage=""로 둡니다.
                """ + toneInstruction(tone);
    }

    private String assessmentSummaryInstructions(String tone) {
        return """
                당신은 청년 우울 관련 상태 탐지 웹앱의 AI 요약 작성자입니다.
                입력으로 설문 점수, 모델 위험도, 영역별 점수, 관찰 신호, SHAP 변수 기여도를 받습니다.
                반드시 입력 JSON에 있는 정보만 근거로 사용합니다.
                일반 사용자가 이해할 수 있는 한국어 보고서 문장으로 작성합니다.
                금지 표현: '우울증입니다', '치료가 필요합니다', '진단됩니다', '확진', '처방'.
                영어 단어, JSON 필드명, 변수명, 모델명, SHAP, LightGBM, ML, detected 같은 표현은 절대 쓰지 않습니다.
                의료적 판단 대신 현재 상태, 주의 신호 항목, 우려되는 패턴, 다음에 해볼 수 있는 행동을 설명합니다.
                위험 단계가 HIGH 또는 URGENT이거나 안전 신호가 있으면 109, 119, 112 또는 주변 사람에게 즉시 도움 요청 안내를 포함합니다.
                수치를 기계적으로 나열하지 말고, 서로 관련 있는 신호를 묶어서 해석합니다.
                점수는 필요할 때만 1번 정도 언급하고, 대부분은 사용자가 체감할 수 있는 주의 신호와 생활 리듬 중심으로 설명합니다.
                반드시 포함할 내용:
                1. 현재 상태를 한 문장으로 자연스럽게 정리
                2. 가장 신경 써서 볼 주의 신호 항목 1~2가지
                3. 오늘 또는 이번 주에 해볼 수 있는 구체적인 행동 2가지
                같은 내용을 다른 표현으로 다시 반복하지 않습니다.
                이미 쓴 문장과 의미가 겹치면 새 문장을 만들지 말고 더 짧게 마무리합니다.
                2~3개 문단, 총 5~7문장, 650자 이내로 작성합니다.
                모든 문장은 반드시 완결된 한국어 문장으로 끝냅니다.
                제목, 점수표, 마크다운 목록, 'A는 B였고 C는 D였습니다'식 반복 나열은 쓰지 않습니다.
                """ + toneInstruction(tone);
    }

    private String surveyRetryInstructions(String tone) {
        return """
                당신은 청년 우울 관련 상태 탐지 웹앱의 대화형 체크 진행자입니다.
                사용자의 직전 답변이 질문에 대한 정도를 판단하기 어려워 같은 내용을 다시 묻습니다.
                사용자를 비난하지 말고, 조금 더 알아들을 수 있게 다시 말해달라고 짧게 안내합니다.
                '우울증입니다', '치료가 필요합니다', '진단' 같은 의료적 판단 표현은 쓰지 않습니다.
                한 번에 하나의 질문만 합니다.
                '1/30', 'n번째', '문항', '설문', '최근 2주 기준' 같은 진행 번호나 검사 표현은 절대 말하지 않습니다.
                한국어 1~2문장으로 작성합니다.
                """ + toneInstruction(tone);
    }

    private String toneInstruction(String tone) {
        if (isFriendlyTone(tone)) {
            return """

                    말투는 친근한 반말입니다. 예: '요즘 잠은 어땠어?', '그랬구나, 조금 더 말해줄래?'
                    너무 장난스럽거나 가볍게 굴지 말고, 따뜻하고 편한 친구 같은 톤을 유지합니다.
                    """;
        }
        return """

                말투는 예의있는 존댓말입니다. 차분하고 정중하게 작성합니다.
                """;
    }

    private String buildSupportiveReplyWithoutApi(String tone) {
        if (isFriendlyTone(tone)) {
            return "문답 검사는 위의 '문답 시작' 버튼으로 할 수 있어. 결과까지 연결하려면 문답형 체크를 시작해줘.";
        }
        return "문답 검사는 위의 '문답 시작' 버튼으로 진행할 수 있습니다. 결과까지 연결하려면 문답형 체크를 시작해 주세요.";
    }

    private String localSurveyQuestion(String questionTitle, int questionIndex, String tone) {
        String[] friendly = {
                questionTitle,
                "요즘은 어땠어? " + questionTitle,
                questionTitle + " 가장 가까운 느낌으로 말해줘.",
                "잠깐 이쪽도 볼게. " + questionTitle,
                questionTitle + " 생각나는 만큼만 말해줘도 괜찮아."
        };
        String[] polite = {
                questionTitle,
                "요즘은 어떠셨나요? " + questionTitle,
                questionTitle + " 가장 가까운 느낌으로 말씀해주세요.",
                "잠깐 이 부분도 확인하겠습니다. " + questionTitle,
                questionTitle + " 떠오르는 만큼만 답해주셔도 괜찮습니다."
        };
        String[] templates = isFriendlyTone(tone) ? friendly : polite;
        return templates[Math.floorMod(questionIndex, templates.length)];
    }

    private String fallbackSurveyRetryQuestion(String questionTitle, String tone, int questionIndex) {
        String[] friendly = {
                "내가 방금 말은 잘 못 잡았어. " + questionTitle + " 쪽으로는 어떤 느낌이야?",
                "그 말만으로는 살짝 헷갈렸어. " + questionTitle + " 여기에 가까워, 아니면 아닌 쪽이야?",
                "미안, 내가 맥락을 놓친 것 같아. " + questionTitle + " 쪽으로 다시 잡아볼게.",
                "조금만 더 정확히 잡아볼게. " + questionTitle + " 어느 쪽에 가까워?"
        };
        String[] polite = {
                "방금 답변만으로는 정확히 잡기 어려웠습니다. " + questionTitle + " 쪽으로는 어떤 느낌에 가까우셨나요?",
                "그 말씀은 조금 더 확인이 필요합니다. " + questionTitle + " 여기에 가까우셨나요, 아니면 아닌 쪽이셨나요?",
                "제가 맥락을 잠시 놓친 것 같습니다. " + questionTitle + " 기준으로 다시 확인하겠습니다.",
                "조금만 더 정확히 확인하겠습니다. " + questionTitle + " 어느 쪽에 가까우셨나요?"
        };
        String[] templates = isFriendlyTone(tone) ? friendly : polite;
        return templates[Math.floorMod(questionIndex, templates.length)];
    }

    private SurveyTurnAnalysis fallbackSurveyTurn(
            String questionTitle,
            String answer,
            List<SurveyQuestionBrief> candidates,
            String tone
    ) {
        LikertScoreResponse score = heuristicScore(questionTitle, answer);
        if (!score.validAnswer()) {
            return new SurveyTurnAnalysis(score, null, "");
        }
        SurveyQuestionBrief selected = chooseLocalNextQuestion(answer, candidates);
        if (selected == null) {
            return new SurveyTurnAnalysis(score, null, "");
        }
        return new SurveyTurnAnalysis(
                score,
                selected.key(),
                contextualNextQuestion(answer, selected.title(), tone)
        );
    }

    private SurveyQuestionBrief chooseLocalNextQuestion(String answer, List<SurveyQuestionBrief> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT).replace(" ", "");
        String[] tokens;
        if (containsAny(normalized, "식욕", "식사", "먹", "밥", "줄었", "늘었", "살", "체중", "외모")) {
            tokens = new String[]{"식욕", "식사", "체중", "외모", "혈당", "대사", "건강"};
        } else if (containsAny(normalized, "잠", "수면", "피곤", "기운", "무기력")) {
            tokens = new String[]{"수면", "피곤", "기운", "건강", "스트레스"};
        } else if (containsAny(normalized, "스트레스", "부담", "공부", "일", "알바", "직장", "학업")) {
            tokens = new String[]{"스트레스", "학업", "근로", "진로", "경제"};
        } else if (containsAny(normalized, "혼자", "외롭", "사람", "관계", "친구", "가족")) {
            tokens = new String[]{"혼자", "주변", "지지", "관계", "사회활동"};
        } else if (containsAny(normalized, "술", "음주", "담배", "흡연", "운동", "걷")) {
            tokens = new String[]{"음주", "흡연", "운동", "걷기", "활동"};
        } else {
            tokens = new String[0];
        }
        for (String token : tokens) {
            for (SurveyQuestionBrief candidate : candidates) {
                if (candidate.title() != null && candidate.title().contains(token)) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private String contextualNextQuestion(String answer, String nextQuestionTitle, String tone) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT).replace(" ", "");
        if (isFriendlyTone(tone)) {
            if (containsAny(normalized, "줄었", "없", "힘들", "피곤", "스트레스", "부담")) {
                return variedBridge(normalized, nextQuestionTitle, tone);
            }
            if (containsAny(normalized, "아니", "괜찮", "없어", "ㄴㄴ")) {
                return variedBridge(normalized, nextQuestionTitle, tone);
            }
            return variedBridge(normalized, nextQuestionTitle, tone);
        }
        if (containsAny(normalized, "줄었", "없", "힘들", "피곤", "스트레스", "부담")) {
            return variedBridge(normalized, nextQuestionTitle, tone);
        }
        if (containsAny(normalized, "아니", "괜찮", "없어", "ㄴㄴ")) {
            return variedBridge(normalized, nextQuestionTitle, tone);
        }
        return variedBridge(normalized, nextQuestionTitle, tone);
    }

    private String variedBridge(String normalizedAnswer, String nextQuestionTitle, String tone) {
        boolean friendly = isFriendlyTone(tone);
        if (containsAny(normalizedAnswer, "희망차", "괜찮", "좋아", "나쁘지", "잘지내", "편해")) {
            return friendly
                    ? "그렇게 느낀다면 다행이야. 그럼 잠깐 다른 쪽도 볼게. " + nextQuestionTitle
                    : "그렇게 느끼셨다면 다행입니다. 그럼 다른 부분도 잠깐 확인해보겠습니다. " + nextQuestionTitle;
        }
        if (containsAny(normalizedAnswer, "많이자", "잠이늘", "너무자", "오래자")) {
            return friendly
                    ? "잠이 늘어난 느낌이 있었구나. 그럴 땐 몸의 기운도 같이 봐두면 좋아. " + nextQuestionTitle
                    : "잠이 늘어난 느낌이 있으셨군요. 이럴 때는 몸의 기운도 함께 확인해보겠습니다. " + nextQuestionTitle;
        }
        if (containsAny(normalizedAnswer, "줄었", "안먹", "입맛", "식욕", "밥")) {
            return friendly
                    ? "먹는 리듬이 달라졌다면 몸 상태도 같이 신경 쓰일 수 있어. " + nextQuestionTitle
                    : "식사 리듬이 달라졌다면 몸 상태도 함께 살펴볼 필요가 있습니다. " + nextQuestionTitle;
        }
        if (containsAny(normalizedAnswer, "아니", "ㄴㄴ", "없어", "전혀")) {
            return friendly
                    ? "그쪽 변화는 크지 않았구나. 이어서 다른 부분도 확인해볼게. " + nextQuestionTitle
                    : "그쪽 변화는 크지 않으셨군요. 이어서 다른 부분도 확인해보겠습니다. " + nextQuestionTitle;
        }
        if (containsAny(normalizedAnswer, "힘들", "피곤", "스트레스", "부담", "무기력")) {
            return friendly
                    ? "그 말만 봐도 꽤 소모가 있었던 것 같아. 이어서 이것도 같이 볼게. " + nextQuestionTitle
                    : "그 말씀만으로도 소모감이 있었던 것으로 보입니다. 이어서 이 부분도 함께 확인하겠습니다. " + nextQuestionTitle;
        }
        return friendly
                ? "알겠어. 그럼 이 부분도 물어볼게. " + nextQuestionTitle
                : "알겠습니다. 그럼 이 부분도 확인하겠습니다. " + nextQuestionTitle;
    }

    private LikertScoreResponse heuristicScore(String answer) {
        return heuristicScore("", answer);
    }

    private LikertScoreResponse heuristicScore(String question, String answer) {
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT).replace(" ", "");
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT).replace(" ", "");
        boolean negativeQuestion = containsAny(
                normalizedQuestion,
                "줄었다", "가라앉", "희망이적", "어렵", "피곤", "기운이부족", "부정적",
                "초조", "포기", "좋지않", "스트레스", "부담", "높", "못했", "신경쓰",
                "불안", "부족", "필요", "문제", "거르는", "불규칙", "많이자", "너무많이"
        );
        boolean positiveQuestion = containsAny(normalizedQuestion, "꾸준히", "규칙적인사회활동", "의지할수있는", "좋은편");

        if (negativeQuestion && containsAny(normalized, "희망차", "괜찮", "좋아", "좋음", "나쁘지", "잘지내", "편해", "잘자", "충분", "안힘들")) {
            return new LikertScoreResponse(0, 0.72, "전혀 그렇지 않다", "질문에서 묻는 어려움과 반대되는 긍정 표현으로 해석했습니다.", true);
        }
        if (positiveQuestion && containsAny(normalized, "응", "웅", "ㅇㅇ", "네", "예", "맞아", "꾸준", "있어", "좋아", "잘")) {
            return new LikertScoreResponse(4, 0.68, "매우 그렇다", "질문에서 묻는 긍정 상태에 동의하는 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalizedQuestion, "수면", "잠들기", "자는") && containsAny(normalized, "많이자", "잠이늘", "너무자", "오래자", "잠많")) {
            return new LikertScoreResponse(4, 0.74, "매우 그렇다", "수면량 증가를 직접 언급한 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "전혀", "아니", "아님", "ㄴㄴ", "노노", "괜찮", "없어", "없음")) {
            return new LikertScoreResponse(0, 0.62, "전혀 그렇지 않다", "부정 또는 없음에 가까운 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "별로", "거의", "딱히", "조금아님")) {
            return new LikertScoreResponse(1, 0.58, "그렇지 않은 편이다", "낮은 동의 정도로 해석했습니다.", true);
        }
        if (containsAny(
                normalized,
                "그런느낌", "느낌이들", "느낌들", "들긴해", "들기는해",
                "그런것같", "그런거같", "그럴것같", "맞는것같", "맞는거같",
                "같기도해", "같긴해", "그런편", "그쪽에가까"
        )) {
            return new LikertScoreResponse(3, 0.56, "그런 편이다", "애매하지만 문항에 동의하는 쪽의 구어체 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "보통", "그냥", "애매", "반반", "가끔", "모르겠", "몰라", "모름", "잘모름", "중간")) {
            return new LikertScoreResponse(2, 0.55, "보통이다", "중간 정도의 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "조금", "약간", "어느정도", "좀", "종종")) {
            return new LikertScoreResponse(3, 0.6, "그런 편이다", "부분적으로 동의하는 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "매우", "많이", "많아", "많음", "많은편", "자주", "계속", "늘", "항상", "매일", "거의매일", "너무", "심해")) {
            return new LikertScoreResponse(4, 0.66, "매우 그렇다", "강한 동의 표현으로 해석했습니다.", true);
        }
        if (containsAny(normalized, "응", "웅", "ㅇㅇ", "네", "예", "맞아", "그렇")) {
            return new LikertScoreResponse(3, 0.48, "그런 편이다", "긍정 응답으로 해석했지만 정도 표현이 약해 중간 이상으로 처리했습니다.", true);
        }
        if (containsAny(normalized, "느껴", "느꼈", "그랬", "그래", "그런듯", "맞는듯")) {
            return new LikertScoreResponse(3, 0.5, "그런 편이다", "질문에 동의하는 구어체 답변으로 해석했습니다.", true);
        }
        if (hasMeaningfulKorean(normalized) && sharesQuestionKeyword(normalizedQuestion, normalized)) {
            return new LikertScoreResponse(2, 0.42, "보통이다", "문항과 관련된 표현은 있으나 정도가 뚜렷하지 않아 중간값으로 처리했습니다.", true);
        }
        return invalidLikertScore("문항에 대한 정도나 빈도가 드러나지 않아 다시 확인이 필요합니다.");
    }

    private boolean hasMeaningfulKorean(String value) {
        return value != null
                && value.matches(".*[가-힣].*")
                && !containsAny(value, "가나다", "아자차", "ㅋㅋㅋㅋ", "ㅎㅎㅎㅎ");
    }

    private boolean sharesQuestionKeyword(String question, String answer) {
        String[] tokens = {
                "희망", "기분", "흥미", "즐거움", "잠", "수면", "피곤", "기운", "식욕", "식사", "집중", "초조",
                "건강", "스트레스", "학업", "근로", "흡연", "음주", "운동", "체중", "외모",
                "경제", "진로", "혼자", "관계", "혈압", "혈당"
        };
        for (String token : tokens) {
            if (question.contains(token) && answer.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private LikertScoreResponse parseLikertJson(String output, String question, String answer) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            int start = output.indexOf('{');
            int end = output.lastIndexOf('}');
            String json = start >= 0 && end >= start ? output.substring(start, end + 1) : output;
            JsonNode node = objectMapper.readTree(json);
            int score = Math.max(0, Math.min(4, node.path("score").asInt(2)));
            double confidence = Math.max(0, Math.min(1, node.path("confidence").asDouble(0.5)));
            String label = node.path("label").asText(labelForScore(score));
            String rationale = node.path("rationale").asText("자연어 응답을 리커트 척도로 변환했습니다.");
            boolean validAnswer = node.has("validAnswer")
                    ? node.path("validAnswer").asBoolean(true)
                    : node.path("valid_answer").asBoolean(true);
            if (!validAnswer) {
                LikertScoreResponse fallback = heuristicScore(question, answer);
                return fallback.validAnswer() ? fallback : invalidLikertScore(rationale);
            }
            return new LikertScoreResponse(score, confidence, label, rationale, true);
        } catch (Exception error) {
            return null;
        }
    }

    private SurveyTurnAnalysis parseSurveyTurnJson(
            String output,
            String questionTitle,
            String answer,
            List<SurveyQuestionBrief> candidates,
            String tone
    ) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            int start = output.indexOf('{');
            int end = output.lastIndexOf('}');
            String json = start >= 0 && end >= start ? output.substring(start, end + 1) : output;
            JsonNode node = objectMapper.readTree(json);
            int scoreValue = Math.max(0, Math.min(4, node.path("score").asInt(2)));
            double confidence = Math.max(0, Math.min(1, node.path("confidence").asDouble(0.5)));
            String label = node.path("label").asText(labelForScore(scoreValue));
            String rationale = node.path("rationale").asText("자연어 응답을 리커트 척도로 변환했습니다.");
            boolean validAnswer = node.has("validAnswer")
                    ? node.path("validAnswer").asBoolean(true)
                    : node.path("valid_answer").asBoolean(true);
            if (!validAnswer) {
                LikertScoreResponse fallback = heuristicScore(questionTitle, answer);
                if (fallback.validAnswer()) {
                    return fallbackSurveyTurn(questionTitle, answer, candidates, tone);
                }
                return new SurveyTurnAnalysis(invalidLikertScore(rationale), null, "");
            }

            LikertScoreResponse score = new LikertScoreResponse(scoreValue, confidence, label, rationale, true);
            if (candidates == null || candidates.isEmpty()) {
                return new SurveyTurnAnalysis(score, null, "");
            }

            String requestedKey = node.path("nextQuestionKey").asText("");
            SurveyQuestionBrief selected = candidates.stream()
                    .filter(candidate -> candidate.key().equals(requestedKey))
                    .findFirst()
                    .orElseGet(() -> chooseLocalNextQuestion(answer, candidates));
            if (selected == null) {
                return new SurveyTurnAnalysis(score, null, "");
            }

            String nextMessage = node.path("nextMessage").asText("");
            if (nextMessage == null || nextMessage.isBlank() || isUnsafeNextMessage(nextMessage)) {
                nextMessage = contextualNextQuestion(answer, selected.title(), tone);
            }
            return new SurveyTurnAnalysis(score, selected.key(), sanitizeSummary(nextMessage));
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isUnsafeNextMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("1/30")
                || normalized.contains("문항")
                || normalized.contains("설문")
                || normalized.contains("score")
                || normalized.contains("nextquestion")
                || normalized.contains("흐름과 함께")
                || normalized.contains("보면 좋을 것")
                || normalized.contains("보면 좋겠습니다")
                || normalized.contains("우울증입니다")
                || normalized.contains("치료가 필요합니다")
                || normalized.contains("진단됩니다");
    }

    private LikertScoreResponse invalidLikertScore(String rationale) {
        return new LikertScoreResponse(
                2,
                0.1,
                "답변 재확인 필요",
                rationale == null || rationale.isBlank() ? "답변 의미를 판단하기 어려웠습니다." : rationale,
                false
        );
    }

    private boolean isBlankSurveyAnswer(String answer) {
        return answer == null || answer.trim().isBlank();
    }

    private String labelForScore(int score) {
        return switch (score) {
            case 0 -> "전혀 그렇지 않다";
            case 1 -> "그렇지 않은 편이다";
            case 2 -> "보통이다";
            case 3 -> "그런 편이다";
            default -> "매우 그렇다";
        };
    }

    @SuppressWarnings("unchecked")
    private String chatCompletion(
            List<Map<String, String>> messages,
            double temperature,
            int maxTokens,
            LlmPurpose purpose,
            String cacheInput,
            String tone
    ) {
        String requestId = UUID.randomUUID().toString();
        long totalStart = System.nanoTime();
        long llmLatencyMs = 0;
        SemanticCacheLookupResult cacheResult = semanticCache.lookup(purpose, cacheInput, tone, model, promptVersion);
        if (cacheResult.cacheHit() && cacheResult.responseText().isPresent()) {
            logLlmMetrics(requestId, purpose, cacheResult, 0, elapsedMs(totalStart), false);
            return cacheResult.responseText().get();
        }

        try {
            long llmStart = System.nanoTime();
            Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(Map.of(
                            "model", model,
                            "messages", messages,
                            "temperature", temperature,
                            "max_tokens", maxTokens
                    ))
                    .retrieve()
                    .body(Map.class);
            llmLatencyMs = elapsedMs(llmStart);
            String output = extractChatCompletionText(response);
            semanticCache.store(cacheResult, purpose, output, tone, model, promptVersion);
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), false);
            return output;
        } catch (RuntimeException error) {
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), true);
            throw error;
        }
    }

    private String streamChatCompletion(
            List<Map<String, String>> messages,
            double temperature,
            int maxTokens,
            LlmPurpose purpose,
            String cacheInput,
            String tone,
            Consumer<String> onDelta
    ) {
        String requestId = UUID.randomUUID().toString();
        long totalStart = System.nanoTime();
        long llmLatencyMs = 0;
        SemanticCacheLookupResult cacheResult = semanticCache.lookup(purpose, cacheInput, tone, model, promptVersion);
        if (cacheResult.cacheHit() && cacheResult.responseText().isPresent()) {
            String cached = cacheResult.responseText().get();
            emitDelta(onDelta, cached);
            logLlmMetrics(requestId, purpose, cacheResult, 0, elapsedMs(totalStart), false);
            return cached;
        }

        try {
            long llmStart = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri)
                    .timeout(llmReadTimeout)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                            "model", model,
                            "messages", messages,
                            "temperature", temperature,
                            "max_tokens", maxTokens,
                            "stream", true
                    )), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LLM stream request failed: " + response.statusCode());
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if (data.isBlank()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String delta = extractChatCompletionDelta(data);
                    if (delta == null || delta.isEmpty()) {
                        continue;
                    }
                    output.append(delta);
                    emitDelta(onDelta, delta);
                }
            }

            llmLatencyMs = elapsedMs(llmStart);
            String text = output.toString();
            semanticCache.store(cacheResult, purpose, text, tone, model, promptVersion);
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), false);
            return text;
        } catch (IOException error) {
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), true);
            throw new IllegalStateException("LLM stream request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), true);
            throw new IllegalStateException("LLM stream request interrupted", error);
        } catch (RuntimeException error) {
            logLlmMetrics(requestId, purpose, cacheResult, llmLatencyMs, elapsedMs(totalStart), true);
            throw error;
        }
    }

    private String extractChatCompletionDelta(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode delta = choices.get(0).path("delta").path("content");
            if (!delta.isMissingNode() && !delta.isNull()) {
                return delta.asText("");
            }
            JsonNode message = choices.get(0).path("message").path("content");
            return message.isMissingNode() || message.isNull() ? "" : message.asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void emitDelta(Consumer<String> onDelta, String text) {
        if (onDelta != null && text != null && !text.isEmpty()) {
            onDelta.accept(text);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractChatCompletionText(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        Object first = items.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return null;
        }
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return null;
        }
        Object content = messageMap.get("content");
        return content instanceof String text ? text : null;
    }

    private LlmPurpose classifySupportiveReplyPurpose(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return LlmPurpose.UNKNOWN;
        }
        String normalized = userMessage.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (containsAny(normalized,
                "자해", "죽고싶", "삶을포기", "사라지고싶", "극단적", "끝내고싶",
                "해치고싶", "죽이고싶", "폭력을")) {
            return LlmPurpose.SAFETY_RESPONSE;
        }
        boolean serviceQuestion = containsAny(normalized, "이앱", "이서비스", "서비스", "앱", "사용법", "개인정보", "정책");
        boolean resultQuestion = containsAny(normalized,
                "점수", "위험도", "단계", "low", "watch", "high", "urgent", "결과", "해석", "참고", "의미", "뜻");
        boolean generalYouthMoodQuestion = containsAny(normalized,
                "청년우울", "우울관련", "phq", "문항", "검사", "설문");
        boolean questionLike = userMessage.contains("?") || containsAny(normalized, "뭐야", "무슨", "어떻게", "알려", "설명", "맞아");

        if ((serviceQuestion || resultQuestion) && questionLike) {
            return LlmPurpose.FAQ;
        }
        if (generalYouthMoodQuestion && questionLike) {
            return LlmPurpose.GENERAL_GUIDANCE;
        }
        return LlmPurpose.UNKNOWN;
    }

    private void logLlmMetrics(
            String requestId,
            LlmPurpose purpose,
            SemanticCacheLookupResult cacheResult,
            long llmLatencyMs,
            long totalLatencyMs,
            boolean fallbackUsed
    ) {
        if (cacheResult.cacheHit() && !logCacheHits) {
            return;
        }
        log.info(
                "llm_call_metrics requestId={} llmPurpose={} cacheEnabled={} cacheHit={} cacheSkipReason={} embeddingMs={} cacheLookupMs={} llmLatencyMs={} totalLatencyMs={} similarityScore={} fallbackUsed={}",
                requestId,
                purpose == null ? LlmPurpose.UNKNOWN : purpose,
                cacheResult.cacheEnabled(),
                cacheResult.cacheHit(),
                cacheResult.skipReason(),
                cacheResult.embeddingMs(),
                cacheResult.lookupMs(),
                llmLatencyMs,
                totalLatencyMs,
                String.format(Locale.ROOT, "%.4f", cacheResult.similarityScore()),
                fallbackUsed
        );
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private Map<String, Object> summaryPayload(AiSummaryRequest request) {
        List<Map<String, Object>> topDomains = request.domainScores() == null ? List.of() : request.domainScores().stream()
                .sorted(Comparator.comparingDouble(DomainScoreResponse::score).reversed())
                .limit(5)
                .map(domain -> Map.<String, Object>of(
                        "영역명", domain.label(),
                        "점수", Math.round(domain.score() * 10.0) / 10.0,
                        "설명", domain.description()
                ))
                .toList();
        List<Map<String, Object>> topFactors = request.factorContributions() == null ? List.of() : request.factorContributions().stream()
                .limit(6)
                .map(factor -> Map.<String, Object>of(
                        "요인명", factor.label(),
                        "영향크기", Math.round(factor.impact() * 1000.0) / 1000.0,
                        "방향", factor.direction().equals("INCREASE") ? "상승 방향" : factor.direction().equals("DECREASE") ? "완화 방향" : "검토 요인"
                ))
                .toList();
        return Map.of(
                "상태단계", koreanRiskLevel(request.riskLevel()),
                "상태메시지", riskMessage(request.riskLevel(), request.tone()),
                "말투", isFriendlyTone(request.tone()) ? "친근하게" : "예의있게",
                "종합상태점수", Math.round(request.finalScore() * 10.0) / 10.0,
                "설문점수", request.phqLikeScore(),
                "모델위험도퍼센트", Math.round(request.mlRiskPercent() * 10.0) / 10.0,
                "주의신호항목", request.detectedSignals() == null ? List.of() : request.detectedSignals().stream().limit(8).toList(),
                "두드러진영역", topDomains,
                "개인별영향요인", topFactors
        );
    }

    private String fallbackSummary(AiSummaryRequest request) {
        List<String> signals = request.detectedSignals() == null ? List.of() : request.detectedSignals();
        String signalText = signals.isEmpty()
                ? "특별히 두드러진 주의 신호 항목은 표시되지 않았습니다"
                : String.join(", ", signals.stream().limit(4).toList());
        List<String> domains = request.domainScores() == null ? List.of() : request.domainScores().stream()
                .sorted(Comparator.comparingDouble(DomainScoreResponse::score).reversed())
                .limit(2)
                .map(DomainScoreResponse::label)
                .toList();
        String domainText = domains.isEmpty() ? "영역별 상태" : String.join(", ", domains);
        String summary = riskMessage(request.riskLevel()) + "\n\n"
                + domainText + " 쪽에서 주의 신호가 먼저 보입니다. "
                + "특히 " + signalText + " 같은 신호가 함께 나타나면 수면, 식사, 일정처럼 하루 리듬이 흔들리는지 같이 살펴보는 것이 좋습니다. "
                + "오늘은 무리해서 결론을 내리기보다 가장 부담이 큰 일을 하나만 줄이고, 기분과 수면 상태를 짧게 기록해보세요. "
                + "같은 흐름이 이어지거나 혼자 감당하기 어렵다면 주변 사람이나 상담 창구와 현재 상태를 공유하는 것이 좋습니다.";
        return isFriendlyTone(request.tone()) ? friendlyFallbackSummary(summary) : summary;
    }

    private String friendlyFallbackSummary(String summary) {
        return summary
                .replace("현재 전반적인 상태가 안정적으로 확인됩니다.", "지금은 전반적으로 안정적인 편으로 보여.")
                .replace("현재 주의 깊은 경과 확인이 요구됩니다.", "지금은 주의 신호를 조금 더 살펴보면 좋아.")
                .replace("현재 빠른 상담 연결과 주변 지원 확인이 요구됩니다.", "지금은 혼자 버티기보다 주변 도움을 빨리 연결해보는 게 좋아.")
                .replace("현재 즉각적인 안전 확인과 보호 연결이 요구됩니다.", "지금은 안전 확인과 주변 도움 연결이 바로 필요해.")
                .replace("확인됩니다", "확인돼")
                .replace("입니다", "이야")
                .replace("하세요", "해보면 좋아");
    }

    private String riskMessage(String riskLevel) {
        return riskMessage(riskLevel, "polite");
    }

    private String riskMessage(String riskLevel, String tone) {
        if (isFriendlyTone(tone)) {
            return switch (riskLevel == null ? "" : riskLevel) {
                case "LOW" -> "지금은 전반적으로 안정적인 편으로 보여.";
                case "WATCH" -> "지금은 주의 신호를 조금 더 살펴보면 좋아.";
                case "HIGH" -> "지금은 혼자 버티기보다 주변 도움을 빨리 연결해보는 게 좋아.";
                case "URGENT" -> "지금은 안전 확인과 주변 도움 연결이 바로 필요해.";
                default -> "지금은 현재 상태를 한 번 더 확인해보면 좋아.";
            };
        }
        return switch (riskLevel == null ? "" : riskLevel) {
            case "LOW" -> "현재 전반적인 상태가 안정적으로 확인됩니다.";
            case "WATCH" -> "현재 주의 깊은 경과 확인이 요구됩니다.";
            case "HIGH" -> "현재 빠른 상담 연결과 주변 지원 확인이 요구됩니다.";
            case "URGENT" -> "현재 즉각적인 안전 확인과 보호 연결이 요구됩니다.";
            default -> "현재 상태 확인이 요구됩니다.";
        };
    }

    private String koreanRiskLevel(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel) {
            case "LOW" -> "낮음";
            case "WATCH" -> "주의";
            case "HIGH" -> "높음";
            case "URGENT" -> "긴급";
            default -> "확인 필요";
        };
    }

    private String polishSummary(String value) {
        String sanitized = sanitizeSummary(value);
        sanitized = sanitized
                .replace("detected된", "확인된")
                .replace("detected 된", "확인된")
                .replace("detected", "확인")
                .replace("LightGBM", "분석 모델")
                .replace("SHAP", "영향 요인 분석")
                .replace("ML", "분석 모델")
                .replace("기계학습 모델", "분석 모델")
                .replace("머신러닝 모델", "분석 모델")
                .trim();
        sanitized = removeRepeatedSummarySentences(sanitized);
        return trimToCompleteSentence(sanitized);
    }

    private String removeRepeatedSummarySentences(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replace("\r\n", "\n")
                .replaceAll("습니다(?=현재|이번|특히|또한|수면|주변|오늘|가능한)", "습니다. ")
                .replaceAll("어요(?=현재|이번|특히|또한|수면|주변|오늘|가능한)", "어요. ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] sentences = normalized.split("(?<=[.!?])\\s+");
        List<String> kept = new ArrayList<>();
        for (String raw : sentences) {
            String sentence = raw.trim();
            if (sentence.isBlank()) {
                continue;
            }
            boolean duplicated = kept.stream()
                    .anyMatch(existing -> summarySimilarity(existing, sentence) >= 0.72);
            if (!duplicated) {
                kept.add(sentence);
            }
            if (kept.size() >= 7) {
                break;
            }
        }
        return String.join(" ", kept).trim();
    }

    private double summarySimilarity(String left, String right) {
        Set<String> leftGrams = charGrams(left);
        Set<String> rightGrams = charGrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0.0;
        }
        long overlap = leftGrams.stream().filter(rightGrams::contains).count();
        int union = leftGrams.size() + rightGrams.size() - (int) overlap;
        return union <= 0 ? 0.0 : overlap / (double) union;
    }

    private Set<String> charGrams(String value) {
        String normalized = value
                .replaceAll("[^가-힣A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        Set<String> grams = new HashSet<>();
        if (normalized.length() < 2) {
            if (!normalized.isBlank()) {
                grams.add(normalized);
            }
            return grams;
        }
        for (int index = 0; index < normalized.length() - 1; index++) {
            grams.add(normalized.substring(index, index + 2));
        }
        return grams;
    }

    private String trimToCompleteSentence(String value) {
        if (value.isBlank()) {
            return value;
        }
        String normalized = value.replaceAll("\\s+\\n", "\n").trim();
        if (normalized.endsWith(".") || normalized.endsWith("요") || normalized.endsWith("다") || normalized.endsWith("세요")) {
            return normalized;
        }
        int lastPeriod = normalized.lastIndexOf('.');
        if (lastPeriod > 80) {
            return normalized.substring(0, lastPeriod + 1).trim();
        }
        return normalized;
    }

    private boolean containsRawFieldName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("detectedsignals")
                || lower.contains("risklevel")
                || lower.contains("riskmessage")
                || lower.contains("finalscore")
                || lower.contains("mlrisk")
                || lower.contains("topdomains")
                || lower.contains("individualfactors");
    }

    private String sanitizeSummary(String value) {
        return value
                .replace("확인된 변화", "주의 신호 항목")
                .replace("주요 변화", "주의 신호 항목")
                .replace("두드러진 변화", "두드러진 주의 신호")
                .replace("우울증입니다", "우울 관련 주의 신호가 크게 확인됩니다")
                .replace("치료가 필요합니다", "상담 창구와 함께 확인하는 것이 좋습니다")
                .replace("진단됩니다", "확인됩니다")
                .replace("확진", "확인")
                .replace("처방", "안내")
                .trim();
    }

    public record SurveyTurnAnalysis(
            LikertScoreResponse score,
            String nextQuestionKey,
            String nextMessage
    ) {
    }
}
