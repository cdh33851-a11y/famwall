import {initializeApp} from "firebase-admin/app";
import {FieldValue, getFirestore} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {defineSecret} from "firebase-functions/params";
import {setGlobalOptions} from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";

initializeApp();
setGlobalOptions({region: "asia-northeast3", maxInstances: 10});

const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");

type ScheduleNotificationAction = "added" | "updated" | "deleted";

interface ScheduleNotificationDocument {
  action?: ScheduleNotificationAction;
  actorUserName?: string;
  scheduleId?: string;
  scheduleTitle?: string;
  scheduleContent?: string;
  scheduleCategory?: string;
  scheduleType?: string;
  startDate?: string;
  endDate?: string;
  selectedDates?: string[];
  selectedDateCategories?: Record<string, string>;
  occurrenceDates?: string[];
  createdAt?: number;
}

interface DeviceTokenDocument {
  token?: string;
  userName?: string;
  platform?: string;
}

interface OrganizeScheduleRequest {
  title: string;
  selectedContent: string;
  baseContent: string;
  category: string;
  userName: string;
  selectedDate: string;
  scheduleType: string;
  startDate: string;
  endDate: string;
  occurrenceDates: string[];
  dateContents: Record<string, string>;
}

interface OpenAiResponseBody {
  output_text?: unknown;
  output?: unknown;
}

interface OrganizedScheduleContent {
  formattedText: string;
  warnings: string[];
}

interface VoiceScheduleAssistantRequest {
  query: string;
  today: string;
  currentUserName: string;
  schedules: VoiceScheduleSummary[];
}

interface VoiceScheduleSummary {
  id: string;
  title: string;
  category: string;
  userName: string;
  scheduleType: string;
  startDate: string;
  endDate: string;
  occurrenceDates: string[];
  baseContent: string;
  baseOriginalContent: string;
  occurrences: VoiceScheduleOccurrence[];
}

interface VoiceScheduleOccurrence {
  date: string;
  category: string;
  content: string;
  originalContent: string;
  materialOrdered: boolean;
}

interface VoiceAssistantAnswer {
  answerText: string;
}

export const sendScheduleNotificationPush = onDocumentCreated(
  "scheduleNotifications/{notificationId}",
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
      logger.warn("Schedule notification trigger fired without data.");
      return;
    }

    const notificationId = event.params.notificationId;
    const notification = snapshot.data() as ScheduleNotificationDocument;
    const actorUserName = notification.actorUserName?.trim();
    if (!actorUserName) {
      logger.warn("Notification has no actor user.", {notificationId});
      return;
    }

    const tokenSnapshot = await getFirestore().collection("deviceTokens").get();
    const tokenDocuments = tokenSnapshot.docs
      .map((document) => ({
        ref: document.ref,
        data: document.data() as DeviceTokenDocument,
      }))
      .filter(({data}) => data.platform === "android")
      .filter(({data}) => Boolean(data.token?.trim()))
      .filter(({data}) => data.userName !== actorUserName);

    const uniqueTokenDocuments = deduplicateTokenDocuments(tokenDocuments);
    if (uniqueTokenDocuments.length === 0) {
      logger.info("No target FCM tokens for schedule notification.", {
        notificationId,
        actorUserName,
      });
      return;
    }

    const data = buildFcmData(notificationId, notification);
    const tokenChunks = chunk(uniqueTokenDocuments, FCM_MULTICAST_LIMIT);

    for (const tokenChunk of tokenChunks) {
      const response = await getMessaging().sendEachForMulticast({
        tokens: tokenChunk.map(({data: tokenData}) => tokenData.token ?? ""),
        data,
        android: {
          priority: "high",
        },
      });

      const invalidTokenRefs = response.responses
        .map((sendResponse, index) => ({sendResponse, index}))
        .filter(({sendResponse}) => isInvalidTokenError(sendResponse.error?.code))
        .map(({index}) => tokenChunk[index].ref);

      if (invalidTokenRefs.length > 0) {
        const batch = getFirestore().batch();
        invalidTokenRefs.forEach((ref) => {
          batch.update(ref, {
            token: FieldValue.delete(),
            updatedAt: Date.now(),
          });
        });
        await batch.commit();
      }

      logger.info("Sent schedule FCM chunk.", {
        notificationId,
        successCount: response.successCount,
        failureCount: response.failureCount,
      });
    }
  },
);

export const organizeScheduleContent = onCall(
  {
    secrets: [OPENAI_API_KEY],
    timeoutSeconds: 60,
    memory: "512MiB",
  },
  async (request): Promise<OrganizedScheduleContent> => {
    const apiKey = readOpenAiApiKey();

    const scheduleRequest = parseOrganizeScheduleRequest(request.data);
    const rawContent = [
      scheduleRequest.title,
      scheduleRequest.selectedContent,
      scheduleRequest.baseContent,
      ...Object.values(scheduleRequest.dateContents),
    ].join("\n").trim();
    if (!rawContent) {
      throw new HttpsError("invalid-argument", "No schedule content to organize.");
    }
    if (rawContent.length > MAX_ORGANIZE_INPUT_CHARS) {
      throw new HttpsError("invalid-argument", "Schedule content is too long to organize safely.");
    }

    const openAiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-4.1-mini",
        instructions: buildScheduleOrganizerInstructions(),
        input: JSON.stringify(scheduleRequest, null, 2),
        temperature: 0.1,
        text: {
          format: {
            type: "json_schema",
            name: "organized_schedule_content",
            strict: true,
            schema: {
              type: "object",
              additionalProperties: false,
              required: ["formattedText", "warnings"],
              properties: {
                formattedText: {
                  type: "string",
                  description: "Korean plain text organized with the required headings.",
                },
                warnings: {
                  type: "array",
                  items: {type: "string"},
                  description: "Keep this empty. Do not report missing optional values.",
                },
              },
            },
          },
        },
      }),
    });

    const responseBody = await openAiResponse.text();
    if (!openAiResponse.ok) {
      logger.error("OpenAI schedule organizer request failed.", {
        status: openAiResponse.status,
      });
      throw new HttpsError("internal", "OpenAI request failed.");
    }

    const parsedResponse = parseJsonObject<OpenAiResponseBody>(responseBody);
    const outputText = extractOpenAiOutputText(parsedResponse);
    const organizedContent = parseJsonObject<OrganizedScheduleContent>(outputText);
    if (!organizedContent.formattedText?.trim()) {
      throw new HttpsError("internal", "OpenAI returned empty organized text.");
    }

    return {
      formattedText: organizedContent.formattedText.trim(),
      warnings: [],
    };
  },
);

export const answerScheduleAssistant = onCall(
  {
    secrets: [OPENAI_API_KEY],
    timeoutSeconds: 60,
    memory: "512MiB",
  },
  async (request): Promise<VoiceAssistantAnswer> => {
    const apiKey = readOpenAiApiKey();
    const assistantRequest = parseVoiceScheduleAssistantRequest(request.data);
    if (!assistantRequest.query) {
      throw new HttpsError("invalid-argument", "Voice assistant query is empty.");
    }

    const input = JSON.stringify(assistantRequest, null, 2);
    if (input.length > MAX_VOICE_ASSISTANT_INPUT_CHARS) {
      throw new HttpsError("invalid-argument", "Schedule data is too large for the voice assistant.");
    }

    const openAiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-4.1-mini",
        instructions: buildVoiceScheduleAssistantInstructions(),
        input,
        temperature: 0.1,
        text: {
          format: {
            type: "json_schema",
            name: "voice_schedule_assistant_answer",
            strict: true,
            schema: {
              type: "object",
              additionalProperties: false,
              required: ["answerText"],
              properties: {
                answerText: {
                  type: "string",
                  description: "A concise Korean answer suitable for text-to-speech.",
                },
              },
            },
          },
        },
      }),
    });

    const responseBody = await openAiResponse.text();
    if (!openAiResponse.ok) {
      logger.error("OpenAI voice assistant request failed.", {
        status: openAiResponse.status,
      });
      throw new HttpsError("internal", "OpenAI voice assistant request failed.");
    }

    const parsedResponse = parseJsonObject<OpenAiResponseBody>(responseBody);
    const outputText = extractOpenAiOutputText(parsedResponse);
    const answer = parseJsonObject<VoiceAssistantAnswer>(outputText);
    if (!answer.answerText?.trim()) {
      throw new HttpsError("internal", "OpenAI returned an empty voice assistant answer.");
    }

    return {
      answerText: answer.answerText.trim().slice(0, MAX_VOICE_ASSISTANT_OUTPUT_CHARS),
    };
  },
);

function readOpenAiApiKey(): string {
  const apiKey = OPENAI_API_KEY.value().trim();
  if (!apiKey) {
    logger.error("OPENAI_API_KEY secret is not configured.");
    throw new HttpsError("failed-precondition", "OpenAI API key is not configured.");
  }

  if (!apiKey.startsWith("sk-") || /\s/.test(apiKey)) {
    logger.error("OPENAI_API_KEY secret has an invalid format.", {
      length: apiKey.length,
      startsWithSk: apiKey.startsWith("sk-"),
    });
    throw new HttpsError("failed-precondition", "OpenAI API key setting is invalid.");
  }

  return apiKey;
}

function buildFcmData(
  notificationId: string,
  notification: ScheduleNotificationDocument,
): Record<string, string> {
  return {
    id: notificationId,
    action: notification.action ?? "added",
    actorUserName: notification.actorUserName ?? "",
    scheduleId: notification.scheduleId ?? "",
    scheduleTitle: notification.scheduleTitle ?? "",
    scheduleContent: notification.scheduleContent ?? "",
    scheduleCategory: notification.scheduleCategory ?? "",
    scheduleType: notification.scheduleType ?? "",
    startDate: notification.startDate ?? "",
    endDate: notification.endDate ?? "",
    selectedDates: (notification.selectedDates ?? []).join(","),
    selectedDateCategories: Object.entries(notification.selectedDateCategories ?? {})
      .map(([date, category]) => `${date}=${category}`)
      .join(","),
    occurrenceDates: (notification.occurrenceDates ?? []).join(","),
    createdAt: String(notification.createdAt ?? Date.now()),
  };
}

function deduplicateTokenDocuments<T extends {data: DeviceTokenDocument}>(
  tokenDocuments: T[],
): T[] {
  const seenTokens = new Set<string>();
  return tokenDocuments.filter(({data}) => {
    const token = data.token?.trim();
    if (!token || seenTokens.has(token)) {
      return false;
    }

    seenTokens.add(token);
    return true;
  });
}

function chunk<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}

function isInvalidTokenError(errorCode?: string): boolean {
  return errorCode === "messaging/registration-token-not-registered" ||
    errorCode === "messaging/invalid-registration-token";
}

function parseOrganizeScheduleRequest(rawData: unknown): OrganizeScheduleRequest {
  const data = isRecord(rawData) ? rawData : {};
  return {
    title: readString(data.title),
    selectedContent: readString(data.selectedContent),
    baseContent: readString(data.baseContent),
    category: readString(data.category),
    userName: readString(data.userName),
    selectedDate: readString(data.selectedDate),
    scheduleType: readString(data.scheduleType),
    startDate: readString(data.startDate),
    endDate: readString(data.endDate),
    occurrenceDates: readStringArray(data.occurrenceDates),
    dateContents: readStringRecord(data.dateContents),
  };
}

function parseVoiceScheduleAssistantRequest(rawData: unknown): VoiceScheduleAssistantRequest {
  const data = isRecord(rawData) ? rawData : {};
  return {
    query: readString(data.query),
    today: readString(data.today),
    currentUserName: readString(data.currentUserName),
    schedules: readVoiceScheduleSummaries(data.schedules),
  };
}

function readVoiceScheduleSummaries(value: unknown): VoiceScheduleSummary[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .filter(isRecord)
    .map((schedule) => ({
      id: readString(schedule.id),
      title: readString(schedule.title),
      category: readString(schedule.category),
      userName: readString(schedule.userName),
      scheduleType: readString(schedule.scheduleType),
      startDate: readString(schedule.startDate),
      endDate: readString(schedule.endDate),
      occurrenceDates: readStringArray(schedule.occurrenceDates),
      baseContent: readString(schedule.baseContent),
      baseOriginalContent: readString(schedule.baseOriginalContent),
      occurrences: readVoiceScheduleOccurrences(schedule.occurrences),
    }));
}

function readVoiceScheduleOccurrences(value: unknown): VoiceScheduleOccurrence[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .filter(isRecord)
    .map((occurrence) => ({
      date: readString(occurrence.date),
      category: readString(occurrence.category),
      content: readString(occurrence.content),
      originalContent: readString(occurrence.originalContent),
      materialOrdered: occurrence.materialOrdered === true,
    }));
}

function buildVoiceScheduleAssistantInstructions(): string {
  return [
    "너는 FamWall 앱의 한국어 일정 음성 비서다.",
    "사용자는 운전 중일 수 있으므로 답변은 짧고 분명한 구어체로 말한다.",
    "입력은 JSON이며 query, today, currentUserName, schedules가 들어 있다.",
    "오늘, 내일, 모레, 이번 주, 다음 주 같은 상대 날짜는 반드시 today 값을 기준으로 해석한다.",
    "일정 포함 여부는 schedules[].occurrences[].date 또는 occurrenceDates만 기준으로 판단한다.",
    "사용자 이름, 카테고리, 현장명, 자재 주문 여부, 비밀번호, 금액, 메모를 질문 의도에 맞게 필터링한다.",
    "질문이 애매하면 가장 가능성이 높은 의도를 기준으로 답하되, 모르면 짧게 다시 물어본다.",
    "스케줄 데이터에 없는 일정이나 내용을 절대 만들어내지 않는다.",
    "일정이 없으면 예를 들어 '내일 일정은 없어요.'처럼 간단히 답한다.",
    "일정이 여러 개면 최대 5개까지만 날짜, 제목, 카테고리 중심으로 요약하고 나머지는 '외 N건'으로 말한다.",
    "사용자가 비밀번호를 묻거나 현장 확인에 필요한 질문을 하면 비밀번호 정보를 포함해도 된다.",
    "비밀번호나 금액은 데이터에 있을 때만 말하고, 추측하지 않는다.",
    "자재 주문 여부를 물으면 materialOrdered 값을 기준으로 알려준다.",
    "답변에는 마크다운, 대괄호 제목, 번호 목록을 쓰지 않는다.",
    "문장은 TextToSpeech로 읽기 좋게 작성하고, 한 답변은 보통 2~6문장 안에 끝낸다.",
  ].join("\n");
}

function buildScheduleOrganizerInstructions(): string {
  return [
    "너는 한국 인테리어/도배 일정 메모를 보기 좋게 정리하는 보조자다.",
    "입력은 JSON이며, 날짜/금액/비밀번호/자재코드/이름을 절대 임의로 만들지 않는다.",
    "애매한 값은 추측해서 확정하지 말고 원문을 최대한 보존한다.",
    "warnings는 항상 빈 배열로 둔다. formattedText에도 '확인 필요', '정보 없음', '없음' 같은 결핍 보고 섹션을 만들지 않는다.",
    "'내용 없음', '없음', '미정', 빈값처럼 실제 현장 정보가 아닌 플레이스홀더는 출력하지 않는다.",
    "금액은 원문 숫자를 보존하되 보기 좋게 쉼표와 원 단위로 정리한다.",
    "작업 일정은 입력의 occurrenceDates, startDate, endDate, selectedDate를 참고해 원문 날짜를 정리한다.",
    "formattedText는 한국어 일반 텍스트로 작성하고, 모바일 화면에서 빠르게 훑어볼 수 있게 줄바꿈을 충분히 사용한다.",
    "섹션 제목은 대괄호 형식으로 한 줄에 단독 배치한다. 예: [현장명]",
    "섹션 사이에는 반드시 빈 줄을 한 줄 넣는다.",
    "가능한 경우 아래 제목 순서를 사용한다: [현장명], [비밀번호], [작업 일정], [시공 내용], [견적], [총합계], [계약금], [자재], [메모].",
    "해당 정보가 전혀 없으면 그 제목은 생략해도 된다.",
    "정보가 적은 일정은 억지로 섹션을 늘리지 말고 [현장명]과 [작업 일정] 중심으로 짧게 정리한다.",
    "각 섹션 안에서는 한 항목을 한 줄에만 적고, 서로 다른 정보를 한 줄에 뭉쳐 쓰지 않는다.",
    "현장명은 주소/아파트/동/호수를 보기 좋게 한 줄 또는 두 줄로 정리한다.",
    "비밀번호는 '공동현관: 0215', '현관: 0503'처럼 용도와 값을 분리한다.",
    "비밀번호 후보는 절대 누락하지 않는다. 현관/공동현관/도어락/비번/비밀번호 주변의 숫자, 단독 줄에 있는 4자리 이상 숫자, 숫자에 * 또는 #이 붙은 값은 [비밀번호]에 포함한다.",
    "'9999확인'처럼 숫자 뒤에 확인이 붙으면 확인 단어는 빼고 '9999'만 적는다.",
    "1층 현관, 공동현관, 건물현관 비밀번호가 먼저 나오고 그 다음에 라벨 없는 비밀번호 후보가 나오면, 다음 비밀번호는 '세대 현관' 또는 '세대 비밀번호'로 분류한다.",
    "'201028*'처럼 용도를 알 수 없는 비밀번호 후보라도 앞에 1층 현관/공동현관 비밀번호가 있으면 '세대 현관: 201028*'로 적는다.",
    "앞뒤 맥락이 전혀 없어도 비밀번호 후보는 삭제하지 말고 마지막 수단으로만 '기타 비밀번호: 값'으로 적는다.",
    "비밀번호가 여러 개면 모두 한 줄씩 적는다. 용도가 애매해도 누락보다 보존을 우선한다.",
    "하이픈이 들어간 벽지/자재 코드(예: 87467-2, ZJ34851-11)는 비밀번호가 아니라 [자재]로 분류한다.",
    "작업 일정은 '4월 27일: 거실 마루 철거', '4월 28일 ~ 29일: 도배'처럼 날짜와 작업을 분리한다.",
    "작업 일정에 이미 날짜와 작업 범위가 충분히 들어 있으면 [시공 내용]은 생략한다.",
    "[시공 내용]은 작업 일정과 중복되지 않는 상세 범위나 특이사항이 있을 때만 쓴다.",
    "예를 들어 작업 일정이 '4월 27일: 거실 주방 복도 천장만 시공'이면 같은 내용을 [시공 내용]에 다시 나누어 쓰지 않는다.",
    "견적은 '도배: 1,430,000원'처럼 항목명과 금액을 분리하고, 총합계와 계약금은 견적 섹션에 섞지 않는다.",
    "자재는 제품명/코드별로 한 줄씩 적는다.",
    "원문에 있는 중요한 메모가 위 섹션에 들어가지 않으면 [메모]에 짧게 남긴다.",
    "출력 예시 1:\n[현장명]\n평택 제일풍경채 212동 2605호\n\n[작업 일정]\n4월 27일: 거실 주방 복도 천장만 시공",
    "출력 예시 2:\n[현장명]\n아산 신창 삼부르네상스 105동 1102호 (33평)\n\n[비밀번호]\n1층 현관: 9999\n세대 현관: 201028*\n\n[작업 일정]\n5월 1일: 거실벽 전체, 안방벽 전체 도배\n\n[견적]\n도배: 1,000,000원\n\n[자재]\n87467-2",
    "출력 예시 3:\n[현장명]\n우남두정마을1단지 103동 1006호\n\n[비밀번호]\n공동현관: 0215\n현관: 0503\n\n[작업 일정]\n4월 27일: 거실 마루 철거\n4월 28일 ~ 29일: 도배\n5월 1일: 장판\n\n[견적]\n도배: 1,430,000원\n장판 2.2T: 1,940,000원\n\n[총합계]\n3,830,000원",
    "출력은 반드시 JSON 스키마를 따른다.",
  ].join("\n");
}

function extractOpenAiOutputText(responseBody: OpenAiResponseBody): string {
  if (typeof responseBody.output_text === "string") {
    return responseBody.output_text;
  }

  if (!Array.isArray(responseBody.output)) {
    return "";
  }

  for (const outputItem of responseBody.output) {
    if (!isRecord(outputItem) || !Array.isArray(outputItem.content)) {
      continue;
    }

    for (const contentItem of outputItem.content) {
      if (isRecord(contentItem) && typeof contentItem.text === "string") {
        return contentItem.text;
      }
    }
  }

  return "";
}

function parseJsonObject<T>(rawJson: string): T {
  try {
    return JSON.parse(rawJson) as T;
  } catch (error) {
    logger.error("Failed to parse JSON.", {rawJson, error});
    throw new HttpsError("internal", "Invalid JSON response.");
  }
}

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.map(readString).filter((item) => item.length > 0);
}

function readStringRecord(value: unknown): Record<string, string> {
  if (!isRecord(value)) {
    return {};
  }

  return Object.entries(value).reduce<Record<string, string>>((result, [key, rawValue]) => {
    const safeValue = readString(rawValue);
    if (safeValue) {
      result[key] = safeValue;
    }
    return result;
  }, {});
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const FCM_MULTICAST_LIMIT = 500;
const MAX_ORGANIZE_INPUT_CHARS = 8_000;
const MAX_VOICE_ASSISTANT_INPUT_CHARS = 80_000;
const MAX_VOICE_ASSISTANT_OUTPUT_CHARS = 900;
