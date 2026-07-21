import type { ConversationContext } from '../storage/chatConversationStore';
import { readChatArtifacts } from '../artifactContract';
import { readChatActions } from '../actionContract';
import type { ChatbotResponse, ChatCitation, ChatEvidenceSummary } from '../chatTypes';

type ChatbotWireResponse = Omit<ChatbotResponse, 'artifacts' | 'actions'> & {
  uiArtifacts?: unknown;
  uiActions?: unknown;
};

export type AuthenticatedChatbotRequest = (
  path: string,
  init?: RequestInit,
  target?: 'user' | 'public',
) => Promise<Response>;

export async function queryChatbot(
  authenticatedRequest: AuthenticatedChatbotRequest,
  request: { question: string; conversationContext?: ConversationContext },
): Promise<ChatbotResponse> {
  const question = request.question.trim();
  if (question.length === 0 || question.length > 2_000) throw new Error('질문을 확인해주세요.');
  const payload = request.conversationContext?.messages.length
    ? { question, conversationContext: request.conversationContext }
    : { question };
  const response = await authenticatedRequest('/api/v1/chatbot/query', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }, 'public');
  if (!response.ok) throw new Error(errorMessage(response.status));
  try {
    const body: unknown = await response.json();
    if (!isChatbotResponse(body)) throw new Error();
    const factIds = new Set(body.citations.flatMap((citation) => citation.factIds));
    return {
      ...body,
      artifacts: readChatArtifacts(body.uiArtifacts, factIds),
      actions: readChatActions(body.uiActions, factIds),
    };
  } catch {
    throw new Error('챗봇 응답을 확인하지 못했습니다.');
  }
}

function errorMessage(status: number): string {
  if (status === 401) return '로그인이 만료되었습니다.';
  if (status === 429) return '요청이 많습니다. 잠시 후 다시 시도해주세요.';
  if (status === 503 || status === 504) return '챗봇을 잠시 사용할 수 없습니다.';
  return '챗봇 요청을 완료하지 못했습니다.';
}

function isChatbotResponse(value: unknown): value is ChatbotWireResponse {
  if (!isRecord(value)
    || typeof value.success !== 'boolean'
    || !['success', 'partial_success', 'failed'].includes(String(value.status))
    || typeof value.answer !== 'string'
    || value.answer.trim().length === 0
    || value.answer.length > 20_000
    || !isIdentifier(value.requestId)
    || !Array.isArray(value.citations)
    || value.citations.length > 100
    || !value.citations.every(isCitation)
    || !isOptionalDate(value.dataAsOf)
    || !Array.isArray(value.limitations)
    || value.limitations.length > 50
    || !value.limitations.every((item) => typeof item === 'string' && item.length <= 2_000)
    || !isEvidenceSummary(value.evidenceSummary)) {
    return false;
  }
  return value.evidenceSummary.citationCount === value.citations.length;
}

function isCitation(value: unknown): value is ChatCitation {
  return isRecord(value)
    && isIdentifier(value.citationId)
    && isIdentifier(value.sourceId)
    && typeof value.sourceName === 'string'
    && value.sourceName.length > 0
    && value.sourceName.length <= 200
    && (value.sourceUrl === null || isSafeSourceUrl(value.sourceUrl))
    && ['A', 'B', 'C', 'D'].includes(String(value.evidenceGrade))
    && (value.datasetVersion === null || isIdentifier(value.datasetVersion))
    && isOptionalDate(value.dataAsOf)
    && (value.observedAt === null || isIsoTimestamp(value.observedAt))
    && Array.isArray(value.factIds)
    && value.factIds.length > 0
    && value.factIds.length <= 100
    && value.factIds.every(isIdentifier);
}

function isEvidenceSummary(value: unknown): value is ChatEvidenceSummary {
  return isRecord(value)
    && ['supported', 'partial', 'unavailable'].includes(String(value.status))
    && Array.isArray(value.capabilities)
    && value.capabilities.length <= 20
    && value.capabilities.every(isIdentifier)
    && Number.isSafeInteger(value.factCount)
    && Number(value.factCount) >= 0
    && Number.isSafeInteger(value.citationCount)
    && Number(value.citationCount) >= 0;
}

function isSafeSourceUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 2_000) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.username === '' && url.password === '';
  } catch {
    return false;
  }
}

function isOptionalDate(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value));
}

function isIsoTimestamp(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value));
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
