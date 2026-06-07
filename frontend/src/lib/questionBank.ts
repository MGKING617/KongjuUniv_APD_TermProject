export type SurveyOption = {
  label: string;
  value: number;
};

export type SurveyQuestion = {
  key: string;
  modelKey?: string;
  title: string;
  source: "PHQ-9 참고" | "국건영 변수";
  helper: string;
  options?: SurveyOption[];
  toModelValue?: (value: number) => number;
};

export const fivePointOptions: SurveyOption[] = [
  { label: "전혀 그렇지 않다", value: 0 },
  { label: "그렇지 않은 편이다", value: 1 },
  { label: "보통이다", value: 2 },
  { label: "그런 편이다", value: 3 },
  { label: "매우 그렇다", value: 4 }
];

const yesNoLikertToBinary = (value: number) => (value >= 3 ? 1 : 0);
const reverseIncome = (value: number) => [4, 4, 3, 2, 1][value] ?? 3;
const subjectiveHealth = (value: number) => value + 1;
const stressCode = (value: number) => [4, 3, 3, 2, 1][value] ?? 3;
const activityCode = (value: number) => (value >= 3 ? 1 : 2);
const smokingCode = (value: number) => [8, 8, 3, 2, 1][value] ?? 8;
const drinkingCode = (value: number) => [8, 6, 4, 2, 1][value] ?? 4;
const exerciseNegativeDays = (value: number) => [6, 5, 3, 2, 1][value] ?? 3;
const exercisePositiveDays = (value: number) => [8, 6, 4, 2, 1][value] ?? 4;
const bodyConcern = (value: number) => value + 1;
const mealSkip = (value: number) => [1, 1, 2, 3, 4][value] ?? 2;
const welfareNeed = (value: number) => (value >= 3 ? 10 : 20);
const relationshipStable = (value: number) => (value >= 3 ? 1 : 2);
const workHours = (value: number) => [20, 30, 40, 52, 60][value] ?? 40;

const rawSurveyQuestions: Omit<SurveyQuestion, "options">[] = [
  {
    key: "BP_PHQ_1",
    title: "평소 즐기던 일에 흥미나 즐거움이 줄었다고 느꼈나요?",
    source: "PHQ-9 참고",
    helper: "작은 변화도 괜찮아요. 예전보다 덜 끌렸다면 표시해 주세요."
  },
  {
    key: "BP_PHQ_2",
    title: "기분이 가라앉거나 희망이 적다고 느낀 적이 있었나요?",
    source: "PHQ-9 참고",
    helper: "요즘 마음의 온도를 가볍게 확인해요."
  },
  {
    key: "BP_PHQ_3",
    title: "잠들기 어렵거나 너무 많이 자는 등 수면 변화가 있었나요?",
    source: "PHQ-9 참고",
    helper: "평소보다 잠드는 시간이나 깨어나는 느낌이 달라졌다면 담아주세요."
  },
  {
    key: "BP_PHQ_4",
    title: "피곤하거나 기운이 부족하다고 느낀 날이 많았나요?",
    source: "PHQ-9 참고",
    helper: "무리했던 날까지 함께 생각해 주세요."
  },
  {
    key: "BP_PHQ_5",
    title: "식욕이 줄거나 늘어나는 변화가 있었나요?",
    source: "PHQ-9 참고",
    helper: "체중의 변화로도 신호가 될 수 있어요"
  },
  {
    key: "BP_PHQ_6",
    title: "스스로를 부정적으로 평가하는 생각이 자주 들었나요?",
    source: "PHQ-9 참고",
    helper: "혼자 넘기기 쉬운 생각일수록 차분히 체크해 볼게요."
  },
  {
    key: "BP_PHQ_7",
    title: "공부나 일에 집중하기 어렵다고 느꼈나요?",
    source: "PHQ-9 참고",
    helper: "책상 앞에 있던 시간보다 실제로 집중이 됐는지가 더 중요해요."
  },
  {
    key: "BP_PHQ_8",
    title: "몸이 처지거나 반대로 초조해서 가만히 있기 어려웠나요?",
    source: "PHQ-9 참고",
    helper: "느려진 느낌과 안절부절한 느낌 모두 여기에 포함돼요."
  },
  {
    key: "BP_PHQ_9",
    title: "삶을 포기하고 싶다는 생각이 문득 든 적이 있었나요?",
    source: "PHQ-9 참고",
    helper: "스쳐 지나간 생각도 놓치지 말고 신중히 생각해주세요."
  },
  {
    key: "D_1_1",
    modelKey: "D_1_1",
    title: "지금 자신의 전반적인 건강상태가 좋지 않다고 느끼나요?",
    source: "국건영 변수",
    helper: "몸 상태가 마음에 영향을 주기도 해요. 지금 느낌에 가깝게 골라주세요.",
    toModelValue: subjectiveHealth
  },
  {
    key: "BP1",
    modelKey: "BP1",
    title: "평소 스트레스를 많이 느끼는 편인가요?",
    source: "국건영 변수",
    helper: "크고 작은 부담을 모두 포함해서 생각해 주세요.",
    toModelValue: stressCode
  },
  {
    key: "EC_wht_23",
    modelKey: "EC_wht_23",
    title: "학업이나 근로 시간이 길어 부담이 컸나요?",
    source: "국건영 변수",
    helper: "바빴던 날들이 몸과 마음에 남긴 무게를 확인해요.",
    toModelValue: workHours
  },
  {
    key: "EC1_1",
    modelKey: "EC1_1",
    title: "현재 학업, 근로, 구직 등 규칙적인 사회활동을 하고 있나요?",
    source: "국건영 변수",
    helper: "꾸준히 이어가는 활동이 있다면 그 흐름을 기준으로 골라주세요.",
    toModelValue: activityCode
  },
  {
    key: "BS3_1",
    modelKey: "BS3_1",
    title: "흡연을 하고 있거나 흡연 빈도가 높은 편인가요?",
    source: "국건영 변수",
    helper: "최근 패턴이 달라졌다면 그 변화까지 함께 생각해 주세요.",
    toModelValue: smokingCode
  },
  {
    key: "BD1_11",
    modelKey: "BD1_11",
    title: "음주 빈도가 높은 편이라고 느끼나요?",
    source: "국건영 변수",
    helper: "술자리 횟수나 마시는 양이 늘었는지 가볍게 확인해요.",
    toModelValue: drinkingCode
  },
  {
    key: "BE3_31",
    modelKey: "BE3_31",
    title: "땀이 날 정도의 운동을 거의 하지 못하고 있나요?",
    source: "국건영 변수",
    helper: "운동을 못 한 날이 많았다면 편하게 표시해 주세요.",
    toModelValue: exerciseNegativeDays
  },
  {
    key: "BE5_1",
    modelKey: "BE5_1",
    title: "일주일에 걷기나 가벼운 운동을 꾸준히 하는 편인가요?",
    source: "국건영 변수",
    helper: "짧은 산책도 좋아요. 생활 속 움직임을 떠올려 주세요.",
    toModelValue: exercisePositiveDays
  },
  {
    key: "BO1",
    modelKey: "BO1",
    title: "현재 체형이나 몸 상태 때문에 신경 쓰이는 일이 많나요?",
    source: "국건영 변수",
    helper: "남에게 말하기 애매한 신경 쓰임도 여기에 포함돼요.",
    toModelValue: bodyConcern
  },
  {
    key: "BO1_1",
    modelKey: "BO1_1",
    title: "체중이나 외모 변화가 마음 상태에 영향을 준다고 느끼나요?",
    source: "국건영 변수",
    helper: "작은 변화가 기분에 남았다면 그대로 체크해 주세요.",
    toModelValue: bodyConcern
  },
  {
    key: "BO2_1",
    modelKey: "BO2_1",
    title: "체중 조절이나 식단 관리가 현재 부담으로 느껴지나요?",
    source: "국건영 변수",
    helper: "잘하고 못하고보다 부담감이 얼마나 있는지만 보면 돼요.",
    toModelValue: bodyConcern
  },
  {
    key: "incm",
    modelKey: "incm",
    title: "현재 경제적 여건이 심리적 부담으로 느껴지나요?",
    source: "국건영 변수",
    helper: "지출, 생활비, 미래 걱정까지 함께 떠올려 주세요.",
    toModelValue: reverseIncome
  },
  {
    key: "edu",
    modelKey: "edu",
    title: "학업이나 진로 준비 상태가 불안하게 느껴지나요?",
    source: "국건영 변수",
    helper: "막막함이 있었다면 그 정도를 편하게 표시해 주세요.",
    toModelValue: (value: number) => [4, 4, 3, 2, 1][value] ?? 3
  },
  {
    key: "cfam",
    modelKey: "cfam",
    title: "혼자 있는 시간이 많거나 주변 지지가 부족하다고 느끼나요?",
    source: "국건영 변수",
    helper: "연락할 사람이 있어도 외롭게 느껴질 수 있어요.",
    toModelValue: (value: number) => [5, 4, 3, 2, 1][value] ?? 3
  },
  {
    key: "allownc",
    modelKey: "allownc",
    title: "경제적 지원이나 생활 지원이 필요한 상황이라고 느끼나요?",
    source: "국건영 변수",
    helper: "지금 버겁다고 느끼는 부분이 있다면 숨기지 않아도 괜찮아요.",
    toModelValue: welfareNeed
  },
  {
    key: "marri_1",
    modelKey: "marri_1",
    title: "현재 안정적으로 의지할 수 있는 가까운 관계가 있다고 느끼나요?",
    source: "국건영 변수",
    helper: "가족, 친구, 동료처럼 떠오르는 사람이 있는지 생각해 주세요.",
    toModelValue: relationshipStable
  },
  {
    key: "DI1_dg",
    modelKey: "DI1_dg",
    title: "혈압이나 심혈관 관련 건강 문제가 신경 쓰이나요?",
    source: "국건영 변수",
    helper: "진단 여부보다 현재 마음에 부담으로 남아있는지도 함께 봐요.",
    toModelValue: yesNoLikertToBinary
  },
  {
    key: "DI2_dg",
    modelKey: "DI2_dg",
    title: "대사·혈액 관련 건강 문제가 신경 쓰이나요?",
    source: "국건영 변수",
    helper: "검진 결과나 관리 부담이 있었다면 여기에 표시해 주세요.",
    toModelValue: yesNoLikertToBinary
  },
  {
    key: "DE1_dg",
    modelKey: "DE1_dg",
    title: "혈당이나 대사질환 관련 건강 문제가 신경 쓰이나요?",
    source: "국건영 변수",
    helper: "몸 관리가 마음에 주는 부담까지 함께 확인해요.",
    toModelValue: yesNoLikertToBinary
  },
  {
    key: "L_BR_FQ",
    modelKey: "L_BR_FQ",
    title: "아침 식사를 거르는 날이 많은 편인가요?",
    source: "국건영 변수",
    helper: "바빠서 넘긴 날도 괜찮아요. 평소 리듬을 떠올려 주세요.",
    toModelValue: mealSkip
  },
  {
    key: "L_DN_FQ",
    modelKey: "L_DN_FQ",
    title: "저녁 식사나 하루 식사 리듬이 불규칙한 편인가요?",
    source: "국건영 변수",
    helper: "하루 식사가 흔들렸다면 그 흐름을 가볍게 표시해 주세요.",
    toModelValue: mealSkip
  }
];

export const surveyQuestions: SurveyQuestion[] = rawSurveyQuestions.map((question) => ({
  ...question,
  options: fivePointOptions
}));

export const defaultSurveyAnswers = Object.fromEntries(
  surveyQuestions.map((question) => [question.key, -1])
);

export function buildModelFeatures(
  answers: Record<string, number>,
  demographics: Record<string, number | string>
) {
  const selectedSex = Number(demographics.sex ?? 2);
  const modelFeatures: Record<string, number | string> = {
    year: new Date().getFullYear(),
    region: 1,
    age: demographics.age ?? 24,
    L_LN_FQ: 1,
    L_BR_TO: 1,
    L_LN_TO: 1,
    L_DN_TO: 1,
    ...demographics,
    sex: selectedSex === 2 ? 1 : 0
  };

  surveyQuestions.forEach((question) => {
    const answer = answers[question.key];
    if (answer === undefined || answer < 0 || !question.modelKey) {
      return;
    }
    modelFeatures[question.modelKey] = question.toModelValue ? question.toModelValue(answer) : answer;
  });

  const hasAnswer = (key: string) => answers[key] !== undefined && answers[key] >= 0;
  const highConcern = (key: string, threshold = 3) => hasAnswer(key) && answers[key] >= threshold;
  const moderateConcern = (key: string) => hasAnswer(key) && answers[key] >= 2;

  if (hasAnswer("D_1_1")) modelFeatures.subjective_health = moderateConcern("D_1_1") ? 1 : 0;
  if (hasAnswer("BP1")) modelFeatures.stress_level = moderateConcern("BP1") ? 0 : 1;
  if (hasAnswer("EC1_1")) modelFeatures.economic_activity = highConcern("EC1_1") ? 0 : 1;
  if (hasAnswer("BS3_1")) modelFeatures.smoking_status = moderateConcern("BS3_1") ? 0 : 1;
  if (hasAnswer("BD1_11")) modelFeatures.alcohol_frequency = moderateConcern("BD1_11") ? 0 : 1;
  if (hasAnswer("BE5_1")) {
    modelFeatures.walking_days = highConcern("BE5_1") ? 1 : 0;
  } else if (hasAnswer("BE3_31")) {
    modelFeatures.walking_days = highConcern("BE3_31") ? 0 : 1;
  }
  if (hasAnswer("BO2_1")) modelFeatures.weight_control = moderateConcern("BO2_1") ? 0 : 1;
  if (hasAnswer("allownc")) modelFeatures.basic_livelihood_recipient = highConcern("allownc") ? 0 : 1;
  if (hasAnswer("DI1_dg")) modelFeatures.hypertension_diagnosis = highConcern("DI1_dg") ? 0 : 1;
  if (hasAnswer("DE1_dg")) modelFeatures.diabetes_diagnosis = highConcern("DE1_dg") ? 0 : 1;
  if (hasAnswer("L_BR_FQ")) modelFeatures.breakfast_frequency = moderateConcern("L_BR_FQ") ? 1 : 0;

  return modelFeatures;
}
