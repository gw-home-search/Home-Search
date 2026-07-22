import type { ChatAction } from './actionContract';
import type { ChatArtifact } from './artifactContract';
import type { ChatUiSummary } from './summaryContract';
import type { ChatFragment } from './fragmentContract';

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

export type ChatbotResponse = ChatEvidence & {
  success: boolean;
  status: 'success' | 'partial_success' | 'failed';
  answer: string;
  artifacts: ChatArtifact[];
  actions: ChatAction[];
  summary: ChatUiSummary | null;
  fragments: ChatFragment[];
};
