import {initializeApp} from "firebase-admin/app";
import {FieldValue, getFirestore} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {setGlobalOptions} from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";

initializeApp();
setGlobalOptions({region: "asia-northeast3", maxInstances: 10});

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

const FCM_MULTICAST_LIMIT = 500;
