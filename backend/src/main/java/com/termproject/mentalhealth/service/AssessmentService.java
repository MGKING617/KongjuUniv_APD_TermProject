package com.termproject.mentalhealth.service;

import com.termproject.mentalhealth.domain.AppUser;
import com.termproject.mentalhealth.domain.Assessment;
import com.termproject.mentalhealth.domain.RiskLevel;
import com.termproject.mentalhealth.dto.AssessmentRequest;
import com.termproject.mentalhealth.dto.AssessmentResponse;
import com.termproject.mentalhealth.dto.DomainScoreResponse;
import com.termproject.mentalhealth.dto.FactorContributionResponse;
import com.termproject.mentalhealth.dto.GlobalImportanceResponse;
import com.termproject.mentalhealth.repository.AssessmentRepository;
import com.termproject.mentalhealth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class AssessmentService {
    private static final List<String> PHQ_KEYS = List.of(
            "BP_PHQ_1", "BP_PHQ_2", "BP_PHQ_3", "BP_PHQ_4", "BP_PHQ_5",
            "BP_PHQ_6", "BP_PHQ_7", "BP_PHQ_8", "BP_PHQ_9"
    );

    private final UserRepository userRepository;
    private final AssessmentRepository assessmentRepository;
    private final RestClient mlClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssessmentService(
            UserRepository userRepository,
            AssessmentRepository assessmentRepository,
            @Value("${app.ml-api-url}") String mlApiUrl
    ) {
        this.userRepository = userRepository;
        this.assessmentRepository = assessmentRepository;
        this.mlClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .baseUrl(mlApiUrl)
                .build();
    }

    @Transactional
    public AssessmentResponse submitSurvey(AssessmentRequest request) {
        AppUser user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        AssessmentDraft draft = evaluate(request);
        Assessment assessment = new Assessment();
        assessment.setUser(user);
        assessment.setPhqLikeScore(draft.phqScore());
        assessment.setMlRiskPercent(draft.mlRiskPercent());
        assessment.setFinalScore(draft.finalScore());
        assessment.setRiskLevel(draft.riskLevel());
        assessment.setDetectedSignals(draft.signals());
        assessment.setSummary(draft.summary());
        assessment.setDomainScoresJson(writeJson(draft.domainScores()));
        assessment.setFactorContributionsJson(writeJson(draft.factorContributions()));
        assessment.setGlobalImportanceJson(writeJson(draft.globalImportance()));
        return AssessmentResponse.from(assessmentRepository.save(assessment));
    }

    public AssessmentResponse evaluateGuestSurvey(AssessmentRequest request) {
        AssessmentDraft draft = evaluate(request);
        return new AssessmentResponse(
                null,
                0L,
                "게스트",
                draft.phqScore(),
                draft.mlRiskPercent(),
                draft.finalScore(),
                draft.riskLevel(),
                draft.signals(),
                draft.domainScores(),
                draft.factorContributions(),
                draft.globalImportance(),
                draft.summary(),
                LocalDateTime.now()
        );
    }

    private AssessmentDraft evaluate(AssessmentRequest request) {
        int phqScore = PHQ_KEYS.stream()
                .mapToInt(key -> fivePointToPhqScore(request.answers().getOrDefault(key, 0)))
                .sum();
        Map<String, Object> features = new LinkedHashMap<>();
        request.answers().forEach(features::put);
        features.putAll(request.extraFeatures() == null ? Map.of() : request.extraFeatures());

        MlPrediction prediction = predictWithFallback(features, phqScore);
        double phqScaled = phqScore / 27.0 * 100.0;
        double finalScore = clampDouble(phqScaled * 0.65 + prediction.riskPercent() * 0.35, 0, 100);
        RiskLevel riskLevel = riskLevel(finalScore, phqScore);
        List<String> signals = detectedSignals(request.answers(), prediction.topFactors());
        List<DomainScoreResponse> domainScores = domainScores(request.answers());
        List<FactorContributionResponse> factorContributions = factorContributions(prediction);
        List<GlobalImportanceResponse> globalImportance = globalImportance(prediction);
        String summary = buildSummary(phqScore, prediction.riskPercent(), finalScore, riskLevel, signals, factorContributions);
        return new AssessmentDraft(
                phqScore,
                prediction.riskPercent(),
                finalScore,
                riskLevel,
                signals,
                domainScores,
                factorContributions,
                globalImportance,
                summary
        );
    }

    @Transactional(readOnly = true)
    public List<AssessmentResponse> getUserAssessments(Long userId) {
        return assessmentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AssessmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssessmentResponse> getRecentAssessments() {
        return assessmentRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AssessmentResponse::from)
                .toList();
    }

    private MlPrediction predictWithFallback(Map<String, Object> features, int phqScore) {
        try {
            MlPrediction response = mlClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("features", features))
                    .retrieve()
                    .body(MlPrediction.class);
            if (response != null) {
                return response;
            }
        } catch (RuntimeException ignored) {
            // The MVP remains usable before a trained model server is running.
        }

        double heuristicRisk = phqScore / 27.0 * 100.0;
        double sleepHours = number(features.get("sleepHours"), 6);
        double stressCode = number(features.get("BP1"), 3);
        double exerciseCode = number(features.get("BE5_1"), 4);
        double subjectiveHealth = number(features.get("D_1_1"), 3);
        if (sleepHours <= 4) {
            heuristicRisk += 12;
        }
        if (stressCode <= 2) {
            heuristicRisk += 10;
        }
        if (subjectiveHealth >= 4) {
            heuristicRisk += 8;
        }
        if (exerciseCode <= 2) {
            heuristicRisk -= 5;
        }
        return new MlPrediction(
                clampDouble(heuristicRisk, 0, 100),
                List.of("BP1", "D_1_1", "BE5_1"),
                List.of(
                        new MlContribution("BP1", stressCode <= 2 ? 0.18 : 0.02),
                        new MlContribution("D_1_1", subjectiveHealth >= 4 ? 0.14 : 0.01),
                        new MlContribution("BE5_1", exerciseCode <= 2 ? -0.08 : 0.03)
                ),
                List.of()
        );
    }

    private List<String> detectedSignals(Map<String, Integer> answers, List<String> topFactors) {
        Map<String, String> labels = signalLabels();
        Map<String, Boolean> reversed = Map.of(
                "BE5_1", true,
                "BE3_31", true,
                "EC1_1", true,
                "marri_1", true
        );
        List<String> signals = new ArrayList<>();
        answers.forEach((key, value) -> {
            if (value == null || !labels.containsKey(key)) {
                return;
            }
            int riskValue = reversed.getOrDefault(key, false) ? 4 - value : value;
            if (riskValue >= 3) {
                signals.add(labels.get(key));
            }
        });
        return signals;
    }

    private String buildSummary(
            int phqScore,
            double mlRiskPercent,
            double finalScore,
            RiskLevel riskLevel,
            List<String> signals,
            List<FactorContributionResponse> factorContributions
    ) {
        String factorSummary = factorContributions.isEmpty()
                ? "모델 설명 요인은 충분히 산출되지 않았습니다"
                : factorContributions.stream()
                .limit(3)
                .map(FactorContributionResponse::label)
                .reduce((a, b) -> a + ", " + b)
                .orElse("모델 설명 요인은 충분히 산출되지 않았습니다");
        String signalSummary = signals.isEmpty()
                ? "추가로 뚜렷하게 표시된 신호는 없습니다"
                : String.join(", ", signals.stream().limit(4).toList());
        return "30문항 설문 기반 점수는 " + phqScore + "점이며, 모델 위험도 추정값은 "
                + String.format("%.1f", mlRiskPercent) + "%입니다. 종합 점수는 "
                + String.format("%.1f", finalScore) + "점으로 " + koreanRisk(riskLevel)
                + " 단계에 해당합니다. 우선 관찰 신호는 " + signalSummary
                + "이며, 모델 설명 관점의 우선 검토 요인은 " + factorSummary
                + "입니다.";
    }

    private List<DomainScoreResponse> domainScores(Map<String, Integer> answers) {
        return List.of(
                domain("mood", "기분·흥미", "흥미 저하, 가라앉은 기분, 자기평가 관련 응답", answers,
                        Map.of("BP_PHQ_1", false, "BP_PHQ_2", false, "BP_PHQ_6", false)),
                domain("sleep_energy", "수면·에너지", "수면 신호, 피로감, 식욕 신호 관련 응답", answers,
                        Map.of("BP_PHQ_3", false, "BP_PHQ_4", false, "BP_PHQ_5", false)),
                domain("cognition_behavior", "집중·행동", "집중 어려움과 초조감 또는 행동 신호 관련 응답", answers,
                        Map.of("BP_PHQ_7", false, "BP_PHQ_8", false)),
                domain("stress_health", "스트레스·건강", "주관적 스트레스와 건강 부담 관련 응답", answers,
                        Map.of("BP1", false, "D_1_1", false, "EC_wht_23", false)),
                domain("lifestyle", "생활습관", "운동, 흡연, 음주, 식사 리듬 관련 응답", answers,
                        Map.of("BS3_1", false, "BD1_11", false, "BE3_31", false, "BE5_1", true, "L_BR_FQ", false, "L_DN_FQ", false)),
                domain("social_economic", "사회·경제", "사회활동, 경제 부담, 지지 환경 관련 응답", answers,
                        Map.of("EC1_1", true, "incm", false, "edu", false, "cfam", false, "allownc", false, "marri_1", true)),
                domain("safety", "고위험 신호", "삶을 포기하고 싶다는 생각 관련 응답", answers,
                        Map.of("BP_PHQ_9", false))
        );
    }

    private DomainScoreResponse domain(String key, String label, String description, Map<String, Integer> answers, Map<String, Boolean> items) {
        double score = items.entrySet().stream()
                .mapToDouble(entry -> {
                    int value = clamp(answers.getOrDefault(entry.getKey(), 0), 0, 4);
                    int riskValue = entry.getValue() ? 4 - value : value;
                    return riskValue / 4.0 * 100.0;
                })
                .average()
                .orElse(0.0);
        return new DomainScoreResponse(key, label, Math.round(score * 10.0) / 10.0, description);
    }

    private List<FactorContributionResponse> factorContributions(MlPrediction prediction) {
        if (prediction.topContributions() == null || prediction.topContributions().isEmpty()) {
            return prediction.topFactors() == null ? List.of() : prediction.topFactors().stream()
                    .map(this::factorLabel)
                    .filter(this::isDisplayableFactor)
                    .distinct()
                    .limit(5)
                    .map(label -> new FactorContributionResponse(label, 0.0, "INFO", "모델의 전체 중요 요인으로 확인되었습니다."))
                    .toList();
        }

        return prediction.topContributions().stream()
                .filter(item -> isDisplayableRawFactor(item.feature()))
                .map(item -> {
                    String label = factorLabel(item.feature());
                    String direction = item.contribution() >= 0 ? "INCREASE" : "DECREASE";
                    String explanation = factorExplanation(label, direction);
                    return new FactorContributionResponse(label, Math.round(Math.abs(item.contribution()) * 1000.0) / 1000.0, direction, explanation);
                })
                .filter(item -> isDisplayableFactor(item.label()))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                FactorContributionResponse::label,
                                item -> item,
                                (a, b) -> a.impact() >= b.impact() ? a : b,
                                LinkedHashMap::new
                        ),
                        map -> map.values().stream()
                                .sorted(Comparator.comparingDouble(FactorContributionResponse::impact).reversed())
                                .limit(8)
                                .toList()
                ));
    }

    private String factorExplanation(String label, String direction) {
        boolean increase = "INCREASE".equals(direction);
        if (label.contains("스트레스")) {
            return increase
                    ? "최근 부담감이나 압박이 전체 점수에 크게 반영된 항목입니다."
                    : "스트레스 응답은 전체 점수를 일부 낮추는 쪽으로 반영되었습니다.";
        }
        if (label.contains("건강")) {
            return increase
                    ? "스스로 느끼는 건강 상태가 좋지 않게 기록될수록 피로감, 활동 저하와 함께 해석될 수 있습니다."
                    : "주관적 건강 상태가 비교적 안정적으로 응답된 항목입니다.";
        }
        if (label.contains("사회") || label.contains("외출")) {
            return increase
                    ? "사회활동이나 외출 관련 주의 신호가 두드러진 항목입니다."
                    : "사회적 접촉이나 외부활동 응답이 비교적 유지되는 쪽으로 나타났습니다.";
        }
        if (label.contains("흡연") || label.contains("음주")) {
            return increase
                    ? "생활습관 관련 주의 신호가 점수에 반영된 항목입니다."
                    : "흡연·음주 관련 응답은 이번 결과에서 부담을 키우는 핵심 항목으로 보이지 않았습니다.";
        }
        if (label.contains("식사")) {
            return increase
                    ? "식사 리듬의 흔들림이 생활 균형 신호로 반영된 항목입니다."
                    : "식사 리듬이 비교적 유지되는 응답으로 해석되었습니다.";
        }
        if (label.contains("학업") || label.contains("진로") || label.contains("경제") || label.contains("직업")) {
            return increase
                    ? "학업, 진로, 경제활동 관련 부담이 현재 상태와 연결되어 보이는 항목입니다."
                    : "학업, 진로, 경제활동 관련 응답은 이번 결과에서 상대적으로 완충 요인으로 나타났습니다.";
        }
        if (label.contains("수면")) {
            return increase
                    ? "수면 관련 주의 신호가 피로감과 집중 저하에 이어질 수 있어 점수에 반영된 항목입니다."
                    : "수면 관련 응답이 비교적 안정적으로 기록된 항목입니다.";
        }
        return increase
                ? "이번 응답에서 전체 점수와 함께 확인할 필요가 있는 항목입니다."
                : "이번 응답에서는 다른 상승 항목과 비교해 균형 요인으로 볼 수 있는 항목입니다.";
    }

    private List<GlobalImportanceResponse> globalImportance(MlPrediction prediction) {
        if (prediction.globalImportance() == null) {
            return List.of();
        }
        return prediction.globalImportance().stream()
                .filter(item -> isDisplayableRawFactor(item.feature()))
                .map(item -> new GlobalImportanceResponse(factorLabel(item.feature()), Math.round(item.importance() * 1000.0) / 1000.0))
                .filter(item -> isDisplayableFactor(item.label()))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                GlobalImportanceResponse::label,
                                item -> item,
                                (a, b) -> a.importance() >= b.importance() ? a : b,
                                LinkedHashMap::new
                        ),
                        map -> map.values().stream()
                                .sorted(Comparator.comparingDouble(GlobalImportanceResponse::importance).reversed())
                                .limit(8)
                                .toList()
                ));
    }

    private RiskLevel riskLevel(double finalScore, int phqScore) {
        if (phqScore >= 20 || finalScore >= 75) {
            return RiskLevel.URGENT;
        }
        if (phqScore >= 15 || finalScore >= 60) {
            return RiskLevel.HIGH;
        }
        if (phqScore >= 10 || finalScore >= 35) {
            return RiskLevel.WATCH;
        }
        return RiskLevel.LOW;
    }

    private String koreanRisk(RiskLevel level) {
        return switch (level) {
            case LOW -> "낮음";
            case WATCH -> "주의";
            case HIGH -> "높음";
            case URGENT -> "긴급";
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int fivePointToPhqScore(int value) {
        int clamped = clamp(value, 0, 4);
        return switch (clamped) {
            case 0, 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            default -> 3;
        };
    }

    private Map<String, String> signalLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("BP_PHQ_1", "흥미 저하");
        labels.put("BP_PHQ_2", "가라앉은 기분");
        labels.put("BP_PHQ_3", "수면 신호");
        labels.put("BP_PHQ_4", "피로감");
        labels.put("BP_PHQ_5", "식욕 신호");
        labels.put("BP_PHQ_6", "부정적 자기평가");
        labels.put("BP_PHQ_7", "집중 어려움");
        labels.put("BP_PHQ_8", "초조감 또는 행동 신호");
        labels.put("BP_PHQ_9", "고위험 문항 응답");
        labels.put("D_1_1", "주관적 건강 악화");
        labels.put("BP1", "높은 스트레스");
        labels.put("EC_wht_23", "긴 학업/근로 시간");
        labels.put("BS3_1", "흡연 관련 생활습관");
        labels.put("BD1_11", "잦은 음주");
        labels.put("BE3_31", "신체활동 부족");
        labels.put("BE5_1", "걷기·운동 부족");
        labels.put("BO1", "체형 관련 부담");
        labels.put("incm", "경제적 부담");
        labels.put("L_BR_FQ", "아침 식사 불규칙");
        labels.put("L_DN_FQ", "식사 리듬 불규칙");
        return labels;
    }

    private String factorLabel(String factor) {
        if (factor == null || factor.isBlank()) {
            return "생활습관 관련 요인";
        }
        String normalized = factor
                .replace("num__", "")
                .replace("cat__", "");
        if (normalized.startsWith("sex_")) {
            normalized = "sex";
        }
        if (normalized.startsWith("region_")) {
            normalized = "region";
        }

        return factorLabels().getOrDefault(normalized, "생활습관 관련 요인");
    }

    private Map<String, String> factorLabels() {
        return Map.ofEntries(
                Map.entry("year", "조사연도 관련 차이"),
                Map.entry("region", "거주 지역 관련 차이"),
                Map.entry("region_group", "거주 지역 관련 차이"),
                Map.entry("sex", "성별 관련 차이"),
                Map.entry("age", "연령 관련 차이"),
                Map.entry("stress_level", "스트레스 인지 수준"),
                Map.entry("subjective_health", "주관적 건강 상태"),
                Map.entry("subjective_oral_health", "구강 건강 인식"),
                Map.entry("body_image", "체형 인식"),
                Map.entry("body_self_thin", "마른 쪽 체형 인식"),
                Map.entry("body_self_overweight", "비만 쪽 체형 인식"),
                Map.entry("household_size", "가구·동거 환경"),
                Map.entry("marital_status", "가까운 관계 지지 상태"),
                Map.entry("alcohol_frequency", "음주 빈도"),
                Map.entry("breakfast_frequency", "아침 식사 리듬"),
                Map.entry("smoking_status", "흡연 상태"),
                Map.entry("unmet_medical_care", "의료 이용 어려움"),
                Map.entry("economic_activity", "경제활동 상태"),
                Map.entry("occupation_category", "직업·진로 상태"),
                Map.entry("education_level", "학업·진로 상태"),
                Map.entry("walking_days", "걷기·운동 실천"),
                Map.entry("weight_control", "체중 조절 관련 부담"),
                Map.entry("accident_poisoning_experience", "최근 사고·손상 경험"),
                Map.entry("basic_livelihood_recipient", "경제적 지원 필요"),
                Map.entry("hypertension_diagnosis", "혈압 관련 건강 요인"),
                Map.entry("diabetes_diagnosis", "대사질환 관련 건강 요인"),
                Map.entry("BP_PHQ_1", "흥미·즐거움 수준"),
                Map.entry("BP_PHQ_2", "기분 상태"),
                Map.entry("BP_PHQ_3", "수면 상태"),
                Map.entry("BP_PHQ_4", "에너지 수준"),
                Map.entry("BP_PHQ_5", "식욕 신호"),
                Map.entry("BP_PHQ_6", "자기평가 경향"),
                Map.entry("BP_PHQ_7", "집중 상태"),
                Map.entry("BP_PHQ_8", "초조감·행동 신호"),
                Map.entry("BP_PHQ_9", "안전 확인 문항"),
                Map.entry("D_1_1", "주관적 건강 상태"),
                Map.entry("BP1", "스트레스 인지 수준"),
                Map.entry("EC_wht_23", "학업·근로 시간"),
                Map.entry("BS3_1", "흡연 상태"),
                Map.entry("BD1_11", "음주 빈도"),
                Map.entry("BE3_31", "신체활동 빈도"),
                Map.entry("BE5_1", "걷기·운동 실천"),
                Map.entry("BO1", "체형 인식"),
                Map.entry("incm", "경제적 여건"),
                Map.entry("L_BR_FQ", "아침 식사 리듬"),
                Map.entry("L_DN_FQ", "저녁 식사 리듬"),
                Map.entry("edu", "학업·진로 상태"),
                Map.entry("cfam", "가구·동거 환경"),
                Map.entry("allownc", "경제적 지원 필요"),
                Map.entry("marri_1", "가까운 관계 지지 상태"),
                Map.entry("DI1_dg", "혈압 관련 건강 요인"),
                Map.entry("DI2_dg", "대사·혈액 관련 건강 요인"),
                Map.entry("DE1_dg", "대사질환 관련 건강 요인"),
                Map.entry("EC1_1", "사회활동 상태"),
                Map.entry("BO1_1", "체중·체형 관련 인식"),
                Map.entry("BO2_1", "체중 조절 관련 부담"),
                Map.entry("L_LN_FQ", "점심 식사 리듬"),
                Map.entry("L_BR_TO", "아침 식사 동반 여부"),
                Map.entry("L_LN_TO", "점심 식사 동반 여부"),
                Map.entry("L_DN_TO", "저녁 식사 동반 여부")
        );
    }

    private boolean isDisplayableRawFactor(String factor) {
        if (factor == null) {
            return false;
        }
        String normalized = factor.replace("num__", "").replace("cat__", "");
        if (normalized.startsWith("sex_")) {
            normalized = "sex";
        }
        if (normalized.startsWith("region_")) {
            normalized = "region";
        }
        if (normalized.startsWith("region_group")) {
            normalized = "region_group";
        }
        return !List.of("year", "region", "region_group", "sex", "age").contains(normalized);
    }

    private boolean isDisplayableFactor(String label) {
        return label != null
                && !label.isBlank()
                && !label.equals("조사연도 관련 차이")
                && !label.equals("거주 지역 관련 차이")
                && !label.equals("성별 관련 차이")
                && !label.equals("연령 관련 차이");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    public record MlPrediction(
            double riskPercent,
            List<String> topFactors,
            List<MlContribution> topContributions,
            List<MlImportance> globalImportance
    ) {
    }

    public record MlContribution(String feature, double contribution) {
    }

    public record MlImportance(String feature, double importance) {
    }

    private record AssessmentDraft(
            int phqScore,
            double mlRiskPercent,
            double finalScore,
            RiskLevel riskLevel,
            List<String> signals,
            List<DomainScoreResponse> domainScores,
            List<FactorContributionResponse> factorContributions,
            List<GlobalImportanceResponse> globalImportance,
            String summary
    ) {
    }
}
