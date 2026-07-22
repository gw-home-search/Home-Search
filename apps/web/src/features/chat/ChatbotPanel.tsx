import { type ChangeEvent, useEffect, useRef, useState } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { queryChatbot } from './api/chatbotClient';
import { ChatComposer } from './ChatComposer';
import { ChatHistoryPopover } from './ChatHistoryPopover';
import { ChatPendingMessage, ChatThreadMessage } from './ChatThreadMessage';
import type { ChatAction } from './actionContract';
import type { ChatEvidence } from './chatTypes';
import {
  buildConversationContext,
  IndexedDbChatConversationStore,
  type ChatConversation,
  type ChatMessage,
} from './storage/chatConversationStore';
import { useChatConversationWorkspace } from './useChatConversationWorkspace';

type ChatbotPanelProps = {
  onOpenChange?: (isOpen: boolean) => void;
  onUiAction?: (action: ChatAction) => boolean;
  store?: IndexedDbChatConversationStore;
};

export function ChatbotPanel({ onOpenChange, onUiAction, store }: ChatbotPanelProps) {
  const auth = useAuth();
  const workspace = useChatConversationWorkspace(store);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const historyTriggerRef = useRef<HTMLButtonElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const questionRef = useRef<HTMLTextAreaElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [question, setQuestion] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'sending'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [executedActionIds, setExecutedActionIds] = useState<Set<string>>(
    () => new Set(),
  );
  const requestSequenceRef = useRef(0);
  const { conversations, selected, selectedId } = workspace;

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
    setStatus('loading');
    setError(null);
    try {
      await workspace.load();
    } catch {
      setError('브라우저 대화 저장소를 열지 못했습니다.');
    } finally {
      setStatus('idle');
    }
  }

  function createConversation() {
    if (status === 'sending') return;
    setError(null);
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
      setError('대화를 삭제하지 못했습니다.');
      throw new Error('대화를 삭제하지 못했습니다.');
    }
  }

  async function deleteAll() {
    setError(null);
    try {
      await workspace.deleteAll();
    } catch {
      setError('전체 대화를 삭제하지 못했습니다.');
      throw new Error('전체 대화를 삭제하지 못했습니다.');
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
      setError('대화를 내보내지 못했습니다.');
    }
  }

  async function importConversations(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file == null) return;
    if (file.size > 10 * 1024 * 1024) {
      setError('가져올 대화 파일은 10MB 이하여야 합니다.');
      return;
    }
    setError(null);
    try {
      await workspace.importArchive(await file.text());
    } catch {
      setError('가져올 대화 파일을 확인해주세요.');
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
    setError(null);
    const requestSequence = ++requestSequenceRef.current;
    try {
      await workspace.save(pending, true);
      const response = await queryChatbot(auth.authenticatedRequest, {
        question: content,
        conversationContext: buildConversationContext(selected.messages),
      });
      const answeredAt = new Date().toISOString();
      const evidence: ChatEvidence = {
        requestId: response.requestId,
        citations: response.citations,
        dataAsOf: response.dataAsOf,
        limitations: response.limitations,
        evidenceSummary: response.evidenceSummary,
      };
      await workspace.save({
        ...pending,
        updatedAt: answeredAt,
        messages: [...pending.messages, {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: response.answer,
          createdAt: answeredAt,
          evidence,
          artifacts: response.artifacts,
          actions: response.actions,
          ...(response.fragments.length === 0 ? {} : { fragments: response.fragments }),
          ...(response.summary == null ? {} : { summary: response.summary }),
        }],
      }, false);
    } catch (requestError) {
      if (requestSequence === requestSequenceRef.current) {
        setError(requestError instanceof Error ? requestError.message : '챗봇 요청을 완료하지 못했습니다.');
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
    workspace.select(id);
    setIsHistoryOpen(false);
    setQuestion('');
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
            <div aria-busy={status === 'loading' || status === 'sending'} aria-live="polite" className="chatbot-messages">
              {status === 'loading' ? (
                <p className="chatbot-loading">대화를 불러오는 중입니다.</p>
              ) : selected?.messages.length ? selected.messages.map((message) => (
                <ChatThreadMessage
                  executedActionIds={executedActionIds}
                  key={message.id}
                  message={message}
                  onUiAction={executeUiAction}
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
              {status === 'sending' ? <ChatPendingMessage /> : null}
            </div>

            {error ? <p aria-live="assertive" className="chatbot-error">{error}</p> : null}
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
