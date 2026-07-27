import type { ChatAction } from './actionContract';
import type { ChatArtifact } from './artifactContract';
import type { ChatUiSummary } from './summaryContract';
import type { ChatFragment } from './fragmentContract';
import type {
  ChatConversationResolution,
  ConversationMemory,
} from './conversationContract';
import type { ChatUiReport } from './reportContract';

export type ChatCitation = {
  citationId: string;
  sourceId: string;
  sourceName: string;
  sourceUrl: string | null;
  evidenceGrade: 'A' | 'B' | 'C' | 'D';
  datasetVersion: string | null;
  dataAsOf: string | null;
  observedAt: string | null;
  factIds: string[];
};

export type ChatEvidenceSummary = {
  status: 'supported' | 'partial' | 'unavailable';
  capabilities: string[];
  factCount: number;
  citationCount: number;
};

export type ChatEvidence = {
  requestId: string;
  citations: ChatCitation[];
  dataAsOf: string | null;
  limitations: string[];
  evidenceSummary: ChatEvidenceSummary;
};

export type ChatTerminalOutcome = {
  version: 1;
  status: 'ANSWERED' | 'PARTIAL' | 'CLARIFICATION' | 'UNAVAILABLE';
  reason: 'COMPLETED' | 'PARTIAL_EVIDENCE' | 'AMBIGUOUS_ENTITY'
    | 'INSUFFICIENT_EVIDENCE' | 'OUT_OF_SCOPE' | 'TEMPORARY_FAILURE';
  retryable: boolean;
};

export function readChatTerminalOutcome(value: unknown): ChatTerminalOutcome | null {
  if (typeof value !== 'object' || value == null || Array.isArray(value)) return null;
  const candidate = value as Record<string, unknown>;
  if (candidate.version !== 1 || typeof candidate.retryable !== 'boolean') return null;
  const reasonsByStatus: Record<string, readonly string[]> = {
    ANSWERED: ['COMPLETED'],
    PARTIAL: ['PARTIAL_EVIDENCE'],
    CLARIFICATION: ['AMBIGUOUS_ENTITY'],
    UNAVAILABLE: ['INSUFFICIENT_EVIDENCE', 'OUT_OF_SCOPE', 'TEMPORARY_FAILURE'],
  };
  const reasons = reasonsByStatus[String(candidate.status)];
  if (reasons == null || !reasons.includes(String(candidate.reason))) return null;
  if (candidate.reason === 'TEMPORARY_FAILURE') {
    if (!candidate.retryable) return null;
  } else if (candidate.reason !== 'PARTIAL_EVIDENCE' && candidate.retryable) return null;
  return candidate as ChatTerminalOutcome;
}

export type ChatbotResponse = ChatEvidence & {
  success: boolean;
  status: 'success' | 'partial_success' | 'failed';
  answer: string;
  artifacts: ChatArtifact[];
  actions: ChatAction[];
  summary: ChatUiSummary | null;
  fragments: ChatFragment[];
  conversationResolution: ChatConversationResolution | null;
  conversationMemoryPatch: ConversationMemory | null;
  report: ChatUiReport | null;
  terminalOutcome: ChatTerminalOutcome | null;
};
