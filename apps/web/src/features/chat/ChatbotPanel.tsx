import { type ChangeEvent, useLayoutEffect, useEffect, useRef, useState } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { queryChatbot } from './api/chatbotClient';
import { ChatComposer } from './ChatComposer';
import { ChatHistoryPopover } from './ChatHistoryPopover';
import { ChatPendingMessage, ChatThreadMessage } from './ChatThreadMessage';
import type { ChatAction } from './actionContract';
import type { ChatEvidence } from './chatTypes';
import type { ChatUiContext } from './conversationContract';
import {
  buildConversationContext,
  IndexedDbChatConversationStore,
  type ChatConversation,
  type ChatMessage,
} from './storage/chatConversationStore';
import { useChatConversationWorkspace } from './useChatConversationWorkspace';
import { RequestStateNotice } from '../../shared/RequestStateNotice';
import {
  getUserFeedback,
  type UserFeedbackId,
} from '../../shared/feedback/feedbackCatalog';
import {
  isCancelledFailure,
  toRequestFailure,
} from '../../shared/http/requestFailure';

type ChatbotPanelProps = {
  onOpenChange?: (isOpen: boolean) => void;
  onUiAction?: (action: ChatAction) => boolean;
  store?: IndexedDbChatConversationStore;
  uiContext?: ChatUiContext;
};

export function ChatbotPanel({ onOpenChange, onUiAction, store, uiContext }: ChatbotPanelProps) {
  const auth = useAuth();
  const workspace = useChatConversationWorkspace(store);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const historyTriggerRef = useRef<HTMLButtonElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const questionRef = useRef<HTMLTextAreaElement>(null);
  const messagesRef = useRef<HTMLDivElement>(null);
  const latestTurnRef = useRef<HTMLElement>(null);
  const questionToRevealRef = useRef<string | null>(null);
  const answerToRevealRef = useRef<string | null>(null);
  const revealLatestTurnRef = useRef(false);
  const followAnswerRef = useRef(true);
  const [isOpen, setIsOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [question, setQuestion] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'sending'>('idle');
  const [progressMessage, setProgressMessage] = useState('질문 해석');
  const [error, setError] = useState<UserFeedbackId | null>(null);
  const [hasUnseenAnswer, setHasUnseenAnswer] = useState(false);
  const [executedActionIds, setExecutedActionIds] = useState<Set<string>>(
    () => new Set(),
  );
  const requestSequenceRef = useRef(0);
  const { conversations, selected, selectedId } = workspace;
  const latestMessage = selected?.messages[selected.messages.length - 1];

  useLayoutEffect(() => {
    if (latestMessage?.id === questionToRevealRef.current && latestMessage.role === 'user') {
      const questionElement = latestTurnRef.current;
      if (typeof questionElement?.scrollIntoView === 'function') {
        questionElement.scrollIntoView({ behavior: 'auto', block: 'start' });
      }
      questionToRevealRef.current = null;
      revealLatestTurnRef.current = false;
      return;
    }
    if (latestMessage?.id === answerToRevealRef.current && latestMessage.role === 'assistant') {
      const answerElement = latestTurnRef.current;
      if (typeof answerElement?.scrollIntoView === 'function') {
        answerElement.scrollIntoView({ behavior: 'auto', block: 'end' });
      }
      answerToRevealRef.current = null;
      revealLatestTurnRef.current = false;
      setHasUnseenAnswer(false);
      return;
    }
    if (latestMessage != null && revealLatestTurnRef.current) {
      const latestElement = latestTurnRef.current;
      if (typeof latestElement?.scrollIntoView === 'function') {
        latestElement.scrollIntoView({ behavior: 'auto', block: 'end' });
      }
      revealLatestTurnRef.current = false;
    }
  }, [latestMessage]);

  useEffect(() => {
    if (isOpen && status === 'idle' && selected != null) questionRef.current?.focus();
  }, [isOpen, selected, status]);

  async function openPanel() {
    if (auth.status !== 'authenticated') {
      auth.openDialog(launcherRef.current ?? undefined);
      return;
    }
    setIsOpen(true);
    onOpenChange?.(true);
    if (status === 'sending') return;
    if (selected != null && question.trim().length > 0) {
      setError(null);
      setHasUnseenAnswer(false);
      revealLatestTurnRef.current = false;
      return;
    }
    setStatus('loading');
    setError(null);
    setHasUnseenAnswer(false);
    revealLatestTurnRef.current = true;
    try {
      await workspace.load(true);
    } catch {
      setError('CHAT_STORAGE_UNAVAILABLE');
    } finally {
      setStatus('idle');
    }
  }

  function createConversation() {
    if (status === 'sending') return;
    setError(null);
    setHasUnseenAnswer(false);
    workspace.startDraft();
    setQuestion('');
    setIsHistoryOpen(false);
    questionRef.current?.focus();
  }

  async function deleteConversation(id: string) {
    setError(null);
    try {
      await workspace.deleteConversation(id);
    } catch {
      setError('CHAT_HISTORY_UPDATE_FAILED');
      throw new Error('Chat history update failed');
    }
  }

  async function deleteAll() {
    setError(null);
    try {
      await workspace.deleteAll();
    } catch {
      setError('CHAT_HISTORY_UPDATE_FAILED');
      throw new Error('Chat history update failed');
    }
  }

  async function exportConversations() {
    setError(null);
    try {
      const archive = await workspace.exportArchive();
      const url = URL.createObjectURL(new Blob([archive], { type: 'application/json' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `home-search-chat-${new Date().toISOString().slice(0, 10)}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      setError('CHAT_EXPORT_FAILED');
    }
  }

  async function importConversations(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file == null) return;
    if (file.size > 10 * 1024 * 1024) {
      setError('CHAT_ARCHIVE_INVALID');
      return;
    }
    setError(null);
    try {
      await workspace.importArchive(await file.text());
    } catch {
      setError('CHAT_ARCHIVE_INVALID');
    }
  }

  async function submit() {
    const content = question.trim();
    if (selected == null || content.length === 0 || content.length > 2_000 || status === 'sending') return;
    const now = new Date().toISOString();
    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content,
      createdAt: now,
    };
    const pending: ChatConversation = {
      ...selected,
      title: selected.messages.length === 0 ? content.slice(0, 40) : selected.title,
      updatedAt: now,
      messages: [...selected.messages, userMessage],
    };
    setQuestion('');
    setStatus('sending');
    setProgressMessage('질문 해석');
    setError(null);
    followAnswerRef.current = isNearBottom(messagesRef.current);
    questionToRevealRef.current = userMessage.id;
    const requestSequence = ++requestSequenceRef.current;
    try {
      await workspace.save(pending, true);
      await generateAndStoreAnswer(pending, selected.messages, content);
    } catch (requestError) {
      if (requestSequence === requestSequenceRef.current) {
        const failure = toRequestFailure(requestError, {
          service: 'chatbot',
          operation: 'chatbot-query',
        });
        if (!isCancelledFailure(failure) && failure.kind !== 'authentication-required') {
          setError(chatFeedbackId(failure.kind));
        }
      }
    } finally {
      if (requestSequence === requestSequenceRef.current) setStatus('idle');
    }
  }

  async function generateAndStoreAnswer(
    pending: ChatConversation,
    contextMessages: ChatMessage[],
    submittedQuestion: string,
    replaceAssistantId?: string,
  ) {
    const response = await queryChatbot(auth.authenticatedRequest, {
      question: submittedQuestion,
      conversationContext: buildConversationContext(contextMessages, pending.memory),
      uiContext,
    }, (_code, message) => setProgressMessage(message));
    const answeredAt = new Date().toISOString();
    const assistantMessageId = replaceAssistantId ?? crypto.randomUUID();
    if (followAnswerRef.current) answerToRevealRef.current = assistantMessageId;
    else setHasUnseenAnswer(true);
    const evidence: ChatEvidence = {
      requestId: response.requestId,
      citations: response.citations,
      dataAsOf: response.dataAsOf,
      limitations: response.limitations,
      evidenceSummary: response.evidenceSummary,
    };
    const assistantMessage: ChatMessage = {
      id: assistantMessageId,
      role: 'assistant',
      content: response.answer,
      createdAt: answeredAt,
      evidence,
      artifacts: response.artifacts,
      actions: response.actions,
      ...(response.fragments.length === 0 ? {} : { fragments: response.fragments }),
      ...(response.summary == null ? {} : { summary: response.summary }),
      ...(response.conversationResolution == null ? {} : { resolution: response.conversationResolution }),
      ...(response.report == null ? {} : { report: response.report }),
      ...(response.terminalOutcome == null ? {} : { terminalOutcome: response.terminalOutcome }),
    };
    await workspace.save({
      ...pending,
      ...(response.conversationMemoryPatch ? { memory: response.conversationMemoryPatch } : {}),
      updatedAt: answeredAt,
      messages: replaceAssistantId == null
        ? [...pending.messages, assistantMessage]
        : pending.messages.map((message) =>
          message.id === replaceAssistantId ? assistantMessage : message),
    }, false);
  }

  async function retryLastQuestion() {
    if (selected == null || status === 'sending') return;
    const latest = selected.messages[selected.messages.length - 1];
    const targetIndex = latest?.role === 'assistant' && latest.terminalOutcome?.retryable
      ? selected.messages.length - 1
      : -1;
    const targetQuestion = targetIndex > 0 ? selected.messages[targetIndex - 1] : latest;
    if (targetQuestion?.role !== 'user') return;
    setStatus('sending');
    setProgressMessage('질문 해석');
    setError(null);
    followAnswerRef.current = isNearBottom(messagesRef.current);
    const requestSequence = ++requestSequenceRef.current;
    try {
      await generateAndStoreAnswer(
        selected,
        selected.messages.slice(0, targetIndex > 0 ? targetIndex - 1 : -1),
        targetQuestion.content,
        targetIndex > 0 ? latest?.id : undefined,
      );
    } catch (requestError) {
      if (requestSequence === requestSequenceRef.current) {
        const failure = toRequestFailure(requestError, {
          service: 'chatbot',
          operation: 'chatbot-query',
        });
        if (!isCancelledFailure(failure) && failure.kind !== 'authentication-required') {
          setError(chatFeedbackId(failure.kind));
        }
      }
    } finally {
      if (requestSequence === requestSequenceRef.current) setStatus('idle');
    }
  }

  function closePanel() {
    setIsOpen(false);
    onOpenChange?.(false);
    setIsHistoryOpen(false);
    requestAnimationFrame(() => launcherRef.current?.focus());
  }

  function selectConversation(id: string) {
    if (status === 'sending') return;
    revealLatestTurnRef.current = true;
    workspace.select(id);
    setIsHistoryOpen(false);
    setQuestion('');
    setHasUnseenAnswer(false);
  }

  function selectExampleQuestion(example: string) {
    setQuestion(example);
    questionRef.current?.focus();
  }

  function executeUiAction(action: ChatAction) {
    if (executedActionIds.has(action.actionId) || onUiAction == null) return;
    if (!onUiAction(action)) return;
    setExecutedActionIds((current) => new Set(current).add(action.actionId));
  }

  return (
    <>
      <button
        aria-label={isOpen ? '홈서치 AI 닫기' : '홈서치 AI 열기'}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        className="chatbot-launcher"
        disabled={auth.status === 'checking'}
        onClick={() => isOpen ? closePanel() : void openPanel()}
        ref={launcherRef}
        type="button"
      >
        <HomeSearchAiMark />
        <span className="chatbot-launcher-label">AI</span>
      </button>
      {isOpen ? (
        <aside
          aria-labelledby="chatbot-panel-title"
          className="chatbot-panel"
          data-ui-component="chatbot-workspace"
          role="dialog"
        >
          <header className="chatbot-panel-toolbar">
            <div className="chatbot-panel-brand">
              <HomeSearchAiMark />
              <strong id="chatbot-panel-title">홈서치 AI</strong>
              <span aria-label="베타 버전" className="chatbot-beta-badge">Beta</span>
            </div>
            <div className="chatbot-toolbar-actions">
              <button aria-label="새 대화" className="chatbot-new-conversation" disabled={status === 'sending'} onClick={createConversation} type="button">
                <EditIcon /><span>새 대화</span>
              </button>
              <div className="chatbot-history-switcher">
                <button
                  aria-expanded={isHistoryOpen}
                  aria-haspopup="menu"
                  aria-label={isHistoryOpen ? '대화 목록 닫기' : '대화 목록 열기'}
                  className="chatbot-history-trigger"
                  disabled={status === 'sending'}
                  onClick={() => setIsHistoryOpen((open) => !open)}
                  ref={historyTriggerRef}
                  type="button"
                >
                  <MenuIcon />
                </button>
                {isHistoryOpen ? (
                  <ChatHistoryPopover
                    conversations={conversations}
                    disabled={status === 'sending'}
                    onClose={() => setIsHistoryOpen(false)}
                    onDelete={deleteConversation}
                    onDeleteAll={deleteAll}
                    onExport={() => void exportConversations()}
                    onImport={() => importInputRef.current?.click()}
                    onSelect={selectConversation}
                    selectedId={selectedId}
                    trigger={historyTriggerRef.current}
                  />
                ) : null}
                <input
                  accept="application/json,.json"
                  aria-label="대화 파일 가져오기"
                  hidden
                  onChange={(event) => void importConversations(event)}
                  ref={importInputRef}
                  type="file"
                />
              </div>
              <button aria-label="챗봇 닫기" className="chatbot-close" onClick={closePanel} type="button">
                <CloseIcon />
              </button>
            </div>
          </header>

          <section aria-label="선택한 대화" className="chatbot-thread">
            <div
              aria-busy={status === 'loading' || status === 'sending'}
              aria-live="polite"
              className="chatbot-messages"
              onScroll={(event) => {
                const atBottom = isNearBottom(event.currentTarget);
                followAnswerRef.current = atBottom;
                if (atBottom) setHasUnseenAnswer(false);
              }}
              ref={messagesRef}
            >
              {status === 'loading' ? (
                <p className="chatbot-loading">대화를 불러오는 중입니다.</p>
              ) : selected?.messages.length ? selected.messages.map((message) => (
                <ChatThreadMessage
                  executedActionIds={executedActionIds}
                  key={message.id}
                  message={message}
                  messageRef={message.id === latestMessage?.id ? latestTurnRef : undefined}
                  onUiAction={executeUiAction}
                  onRetry={message.role === 'assistant' && message.terminalOutcome?.retryable
                    ? () => void retryLastQuestion()
                    : undefined}
                  retrying={status === 'sending'}
                />
              )) : (
                <div className="chatbot-empty">
                  <div className="chatbot-empty-intro">
                    <span>안녕하세요!</span>
                    <strong>어떤 집을 찾고 계세요?</strong>
                    <p>단지와 면적, 궁금한 생활 조건을 알려주시면<br />검증된 데이터 범위에서 답해드릴게요.</p>
                  </div>
                  <div className="chatbot-example-section">
                    <strong>이런 질문은 어때요?</strong>
                    <div aria-label="지원 질문 예시" className="chatbot-example-questions">
                      {EXAMPLE_QUESTIONS.map((example) => (
                        <button
                          aria-label={example.question}
                          key={example.kind}
                          onClick={() => selectExampleQuestion(example.question)}
                          type="button"
                        >
                          <span className="chatbot-example-kind">{example.label}</span>
                          <span className="chatbot-example-copy">{example.question}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}
              {status === 'sending' ? <ChatPendingMessage message={progressMessage} /> : null}
            </div>

            {hasUnseenAnswer ? (
              <button
                className="chatbot-new-answer"
                onClick={() => {
                  latestTurnRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
                  setHasUnseenAnswer(false);
                }}
                type="button"
              >
                새 답변 보기
              </button>
            ) : null}

            {error ? (
              <RequestStateNotice
                className="chatbot-error"
                state="error"
                loadingMessage=""
                emptyMessage=""
                feedback={getUserFeedback(error)}
                onRetry={isRetryableChatFeedback(error) ? () => void retryLastQuestion() : undefined}
              />
            ) : null}
            <ChatComposer
              disabled={status === 'sending' || selected == null}
              isSending={status === 'sending'}
              onChange={setQuestion}
              onSubmit={() => void submit()}
              ref={questionRef}
              value={question}
            />
          </section>
        </aside>
      ) : null}
    </>
  );
}

function chatFeedbackId(kind: ReturnType<typeof toRequestFailure>['kind']): UserFeedbackId {
  if (kind === 'authentication-required') return 'AUTH_EXPIRED';
  if (kind === 'timeout') return 'CHAT_TIMEOUT';
  if (kind === 'rate-limited') return 'CHAT_RATE_LIMITED';
  if (kind === 'invalid-response') return 'CHAT_INVALID_RESPONSE';
  return 'CHAT_UNAVAILABLE';
}

function isRetryableChatFeedback(id: UserFeedbackId): boolean {
  return id === 'CHAT_TIMEOUT'
    || id === 'CHAT_RATE_LIMITED'
    || id === 'CHAT_UNAVAILABLE'
    || id === 'CHAT_INVALID_RESPONSE';
}

function isNearBottom(element: HTMLElement | null): boolean {
  if (element == null) return true;
  return element.scrollHeight - element.scrollTop - element.clientHeight <= 48;
}

const EXAMPLE_QUESTIONS = [
  {
    kind: 'recent-trade',
    label: '최근 실거래',
    question: '마포래미안푸르지오 전용 84㎡의 최근 실거래 5건을 거래일과 층까지 알려줘',
  },
  {
    kind: 'price-trend',
    label: '가격 흐름',
    question: '헬리오시티 전용 59㎡의 최근 1년 월별 가격 흐름과 거래량을 보여줘',
  },
  {
    kind: 'lifestyle-infrastructure',
    label: '생활 인프라',
    question: '잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘',
  },
];

function HomeSearchAiMark() {
  return (
    <svg aria-hidden="true" className="chatbot-ai-mark" fill="none" viewBox="0 0 24 24">
      <rect className="chatbot-ai-mark-bar" height="10" rx="2" width="4" x="4" y="10" />
      <rect className="chatbot-ai-mark-bar" height="16" rx="2" width="4" x="10" y="4" />
      <rect className="chatbot-ai-mark-bar" height="13" rx="2" width="4" x="16" y="7" />
    </svg>
  );
}

function EditIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="m4 20 4.2-1 10.9-10.9-3.2-3.2L5 15.8 4 20Z" /><path d="m14.8 6 3.2 3.2M4 20h16" /></svg>;
}

function CloseIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18" /></svg>;
}

function MenuIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="M5 7h14M5 12h14M5 17h14" /></svg>;
}
