import type { ChatConversation } from './storage/chatConversationStore';

export type ChatHistoryDateGroup = '오늘' | '어제' | '최근 7일' | '이전';

export const CHAT_HISTORY_DATE_GROUPS: ChatHistoryDateGroup[] = ['오늘', '어제', '최근 7일', '이전'];

export function groupConversationsByDate(
  conversations: readonly ChatConversation[],
  now = new Date(),
): Map<ChatHistoryDateGroup, ChatConversation[]> {
  const grouped = new Map<ChatHistoryDateGroup, ChatConversation[]>(
    CHAT_HISTORY_DATE_GROUPS.map((group) => [group, []]),
  );
  const today = localDateSerial(now);
  for (const conversation of conversations) {
    const updatedAt = new Date(conversation.updatedAt);
    const ageInDays = Math.floor((today - localDateSerial(updatedAt)) / 86_400_000);
    const group: ChatHistoryDateGroup = ageInDays <= 0
      ? '오늘'
      : ageInDays === 1
        ? '어제'
        : ageInDays <= 6 ? '최근 7일' : '이전';
    grouped.get(group)?.push(conversation);
  }
  return grouped;
}

function localDateSerial(date: Date): number {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
}
