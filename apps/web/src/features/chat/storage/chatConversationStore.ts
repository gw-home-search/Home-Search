import type { ChatCitation, ChatEvidence } from '../chatTypes';
import { readChatArtifacts, type ChatArtifact } from '../artifactContract';

export type ChatMessageRole = 'user' | 'assistant';

export type ChatMessage = {
  id: string;
  role: ChatMessageRole;
  content: string;
  createdAt: string;
  evidence?: ChatEvidence;
  artifacts?: ChatArtifact[];
};

export type ChatConversation = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: ChatMessage[];
};

export type ConversationContext = {
  messages: Array<Pick<ChatMessage, 'role' | 'content'>>;
};

type ChatArchive = {
  version: 1;
  exportedAt: string;
  conversations: ChatConversation[];
};

const DATABASE_VERSION = 1;
const STORE_NAME = 'conversations';
const MAX_ARCHIVE_CONVERSATIONS = 100;
const MAX_ARCHIVE_BYTES = 10 * 1024 * 1024;
const MAX_MESSAGES_PER_CONVERSATION = 500;
const MAX_STORED_CONTENT_LENGTH = 20_000;
const MAX_CONTEXT_MESSAGES = 12;
const MAX_CONTEXT_MESSAGE_LENGTH = 2_000;
const MAX_CONTEXT_CONTENT_LENGTH = 12_000;

export class IndexedDbChatConversationStore {
  private databasePromise: Promise<IDBDatabase> | null = null;

  constructor(
    private readonly indexedDB: IDBFactory = globalThis.indexedDB,
    private readonly databaseName = 'home-search-chat',
  ) {
    if (indexedDB == null) throw new Error('IndexedDB is unavailable');
    if (databaseName.trim().length === 0) throw new Error('Database name is required');
  }

  async get(id: string): Promise<ChatConversation | null> {
    requireIdentifier(id, 'conversation id');
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readonly');
    const completion = transactionComplete(transaction);
    const value = await requestResult<ChatConversation | undefined>(transaction.objectStore(STORE_NAME).get(id));
    await completion;
    return value ?? null;
  }

  async list(): Promise<ChatConversation[]> {
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readonly');
    const completion = transactionComplete(transaction);
    const values = await requestResult<ChatConversation[]>(transaction.objectStore(STORE_NAME).getAll());
    await completion;
    return values.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
  }

  async save(conversation: ChatConversation): Promise<void> {
    const validated = validateConversation(conversation);
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).put(validated);
    await transactionComplete(transaction);
  }

  async delete(id: string): Promise<void> {
    requireIdentifier(id, 'conversation id');
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).delete(id);
    await transactionComplete(transaction);
  }

  async clear(): Promise<void> {
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).clear();
    await transactionComplete(transaction);
  }

  async exportArchive(exportedAt = new Date().toISOString()): Promise<string> {
    requireIsoTimestamp(exportedAt, 'exportedAt');
    const archive: ChatArchive = {
      version: 1,
      exportedAt,
      conversations: await this.list(),
    };
    return JSON.stringify(archive);
  }

  async importArchive(serialized: string, mode: 'merge' | 'replace'): Promise<void> {
    const archive = parseArchive(serialized);
    const database = await this.open();
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    const store = transaction.objectStore(STORE_NAME);
    if (mode === 'replace') store.clear();
    for (const conversation of archive.conversations) store.put(conversation);
    await transactionComplete(transaction);
  }

  private open(): Promise<IDBDatabase> {
    this.databasePromise ??= new Promise((resolve, reject) => {
      const request = this.indexedDB.open(this.databaseName, DATABASE_VERSION);
      request.onupgradeneeded = () => {
        const database = request.result;
        if (!database.objectStoreNames.contains(STORE_NAME)) {
          database.createObjectStore(STORE_NAME, { keyPath: 'id' });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('Failed to open chat database'));
      request.onblocked = () => reject(new Error('Chat database upgrade is blocked'));
    });
    return this.databasePromise;
  }
}

export function createChatConversation({
  id = crypto.randomUUID(),
  now = new Date().toISOString(),
  title = '새 대화',
}: {
  id?: string;
  now?: string;
  title?: string;
} = {}): ChatConversation {
  requireIdentifier(id, 'conversation id');
  requireIsoTimestamp(now, 'conversation timestamp');
  const normalizedTitle = requireText(title, 100, 'conversation title');
  return {
    id,
    title: normalizedTitle,
    createdAt: now,
    updatedAt: now,
    messages: [],
  };
}

export function buildConversationContext(messages: readonly ChatMessage[]): ConversationContext {
  const selected: ConversationContext['messages'] = [];
  let totalLength = 0;
  for (let index = messages.length - 1; index >= 0 && selected.length < MAX_CONTEXT_MESSAGES; index -= 1) {
    const message = messages[index];
    if (message == null || (message.role !== 'user' && message.role !== 'assistant')) continue;
    const content = message.content.trim().slice(0, MAX_CONTEXT_MESSAGE_LENGTH);
    const remaining = MAX_CONTEXT_CONTENT_LENGTH - totalLength;
    if (content.length === 0 || remaining === 0) continue;
    const boundedContent = content.slice(0, remaining);
    selected.push({ role: message.role, content: boundedContent });
    totalLength += boundedContent.length;
  }
  selected.reverse();
  return { messages: selected };
}

function parseArchive(serialized: string): ChatArchive {
  try {
    if (new Blob([serialized]).size > MAX_ARCHIVE_BYTES) throw new Error();
    const candidate: unknown = JSON.parse(serialized);
    if (!isRecord(candidate)
      || candidate.version !== 1
      || !Array.isArray(candidate.conversations)
      || candidate.conversations.length > MAX_ARCHIVE_CONVERSATIONS) {
      throw new Error();
    }
    const exportedAt = requireIsoTimestamp(candidate.exportedAt, 'exportedAt');
    const conversations = candidate.conversations.map(validateConversation);
    if (new Set(conversations.map(({ id }) => id)).size !== conversations.length) throw new Error();
    return { version: 1, exportedAt, conversations };
  } catch {
    throw new Error('Invalid chat archive');
  }
}

function validateConversation(candidate: unknown): ChatConversation {
  try {
    if (!isRecord(candidate) || !Array.isArray(candidate.messages)) throw new Error();
    const id = requireIdentifier(candidate.id, 'conversation id');
    const title = requireText(candidate.title, 100, 'conversation title');
    const createdAt = requireIsoTimestamp(candidate.createdAt, 'createdAt');
    const updatedAt = requireIsoTimestamp(candidate.updatedAt, 'updatedAt');
    if (candidate.messages.length > MAX_MESSAGES_PER_CONVERSATION) throw new Error();
    const messages = candidate.messages.map(validateMessage);
    if (new Set(messages.map((message) => message.id)).size !== messages.length) throw new Error();
    return { id, title, createdAt, updatedAt, messages };
  } catch {
    throw new Error('Invalid chat archive');
  }
}

function validateMessage(candidate: unknown): ChatMessage {
  if (!isRecord(candidate) || (candidate.role !== 'user' && candidate.role !== 'assistant')) throw new Error();
  const message: ChatMessage = {
    id: requireIdentifier(candidate.id, 'message id'),
    role: candidate.role,
    content: requireText(candidate.content, MAX_STORED_CONTENT_LENGTH, 'message content'),
    createdAt: requireIsoTimestamp(candidate.createdAt, 'message createdAt'),
  };
  if (candidate.evidence !== undefined) message.evidence = validateEvidence(candidate.evidence);
  if (candidate.artifacts !== undefined) {
    const factIds = new Set(message.evidence?.citations.flatMap((citation) => citation.factIds) ?? []);
    const artifacts = readChatArtifacts(candidate.artifacts, factIds);
    if (artifacts.length > 0) message.artifacts = artifacts;
  }
  return message;
}

function validateEvidence(candidate: unknown): ChatEvidence {
  if (!isRecord(candidate)
    || !Array.isArray(candidate.citations)
    || candidate.citations.length > 100
    || !Array.isArray(candidate.limitations)
    || candidate.limitations.length > 50
    || !candidate.limitations.every((limitation) => typeof limitation === 'string' && limitation.length <= 2_000)
    || !isRecord(candidate.evidenceSummary)
    || !['supported', 'partial', 'unavailable'].includes(String(candidate.evidenceSummary.status))
    || !Array.isArray(candidate.evidenceSummary.capabilities)
    || candidate.evidenceSummary.capabilities.length > 20
    || !candidate.evidenceSummary.capabilities.every(isEvidenceIdentifier)
    || !Number.isSafeInteger(candidate.evidenceSummary.factCount)
    || Number(candidate.evidenceSummary.factCount) < 0
    || !Number.isSafeInteger(candidate.evidenceSummary.citationCount)
    || Number(candidate.evidenceSummary.citationCount) < 0
    || !isOptionalDate(candidate.dataAsOf)) {
    throw new Error();
  }
  const citations = candidate.citations.map(validateCitation);
  if (Number(candidate.evidenceSummary.citationCount) !== citations.length) throw new Error();
  return {
    requestId: requireEvidenceIdentifier(candidate.requestId, 'request id'),
    citations,
    dataAsOf: candidate.dataAsOf,
    limitations: candidate.limitations,
    evidenceSummary: {
      status: candidate.evidenceSummary.status as ChatEvidence['evidenceSummary']['status'],
      capabilities: candidate.evidenceSummary.capabilities,
      factCount: Number(candidate.evidenceSummary.factCount),
      citationCount: Number(candidate.evidenceSummary.citationCount),
    },
  };
}

function validateCitation(candidate: unknown): ChatCitation {
  if (!isRecord(candidate)
    || typeof candidate.sourceName !== 'string'
    || candidate.sourceName.trim().length === 0
    || candidate.sourceName.length > 200
    || (candidate.sourceUrl !== null && !isSafeSourceUrl(candidate.sourceUrl))
    || !['A', 'B', 'C', 'D'].includes(String(candidate.evidenceGrade))
    || (candidate.datasetVersion !== null && !isEvidenceIdentifier(candidate.datasetVersion))
    || !isOptionalDate(candidate.dataAsOf)
    || (candidate.observedAt !== null && !isIsoTimestamp(candidate.observedAt))
    || !Array.isArray(candidate.factIds)
    || candidate.factIds.length === 0
    || candidate.factIds.length > 100
    || !candidate.factIds.every(isEvidenceIdentifier)) {
    throw new Error();
  }
  return {
    citationId: requireEvidenceIdentifier(candidate.citationId, 'citation id'),
    sourceId: requireEvidenceIdentifier(candidate.sourceId, 'source id'),
    sourceName: candidate.sourceName.trim(),
    sourceUrl: candidate.sourceUrl,
    evidenceGrade: candidate.evidenceGrade as ChatCitation['evidenceGrade'],
    datasetVersion: candidate.datasetVersion,
    dataAsOf: candidate.dataAsOf,
    observedAt: candidate.observedAt,
    factIds: candidate.factIds,
  };
}

function requireIdentifier(value: unknown, label: string): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
    throw new Error(`${label} is invalid`);
  }
  return value;
}

function requireEvidenceIdentifier(value: unknown, label: string): string {
  if (!isEvidenceIdentifier(value)) throw new Error(`${label} is invalid`);
  return value;
}

function isEvidenceIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
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
  return typeof value === 'string'
    && !Number.isNaN(Date.parse(value))
    && new Date(value).toISOString() === value;
}

function requireText(value: unknown, maximumLength: number, label: string): string {
  if (typeof value !== 'string') throw new Error(`${label} is invalid`);
  const normalized = value.trim();
  if (normalized.length === 0 || normalized.length > maximumLength) throw new Error(`${label} is invalid`);
  return normalized;
}

function requireIsoTimestamp(value: unknown, label: string): string {
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value)) || new Date(value).toISOString() !== value) {
    throw new Error(`${label} is invalid`);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
  });
}

function transactionComplete(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('IndexedDB transaction failed'));
    transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB transaction aborted'));
  });
}
