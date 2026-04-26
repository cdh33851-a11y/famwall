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
    const apiKey = OPENAI_API_KEY.value();
    if (!apiKey) {
      logger.error("OPENAI_API_KEY secret is not configured.");
      throw new HttpsError("failed-precondition", "OpenAI API key is not configured.");
    }

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
                  description: "Short Korean warnings for ambiguous or missing values.",
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
        body: responseBody,
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
      warnings: Array.isArray(organizedContent.warnings) ? organizedContent.warnings : [],
    };
  },
);

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

function buildScheduleOrganizerInstructions(): string {
  return [
    "너는 한국 인테리어/도배 일정 메모를 보기 좋게 정리하는 보조자다.",
    "입력은 JSON이며, 날짜/금액/비밀번호/자재코드/이름을 절대 임의로 만들지 않는다.",
    "애매한 값은 추측해서 확정하지 말고 원문을 최대한 보존하고 warnings에 짧게 적는다.",
    "금액은 원문 숫자를 보존하되 보기 좋게 쉼표와 원 단위로 정리한다.",
    "작업 일정은 입력의 occurrenceDates, startDate, endDate, selectedDate를 참고해 원문 날짜를 정리한다.",
    "formattedText는 마크다운 없이 한국어 일반 텍스트로 작성한다.",
    "가능한 경우 아래 제목 순서를 사용한다: 현장명, 비밀번호, 작업 일정, 시공 내용, 견적, 총합계, 계약금, 자재.",
    "해당 정보가 전혀 없으면 그 제목은 생략해도 된다.",
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
