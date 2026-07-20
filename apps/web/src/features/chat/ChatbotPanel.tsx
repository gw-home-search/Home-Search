import { type ChangeEvent, type FormEvent, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { queryChatbot } from './api/chatbotClient';
import type { ChatEvidence } from './chatTypes';
import {
  buildConversationContext,
  createChatConversation,
  IndexedDbChatConversationStore,
  type ChatConversation,
  type ChatMessage,
} from './storage/chatConversationStore';

type ChatbotPanelProps = {
  onOpenChange?: (isOpen: boolean) => void;
  store?: IndexedDbChatConversationStore;
};

const QUESTION_MIN_HEIGHT_PX = 24;
const QUESTION_MAX_HEIGHT_PX = 96;

export function ChatbotPanel({ onOpenChange, store }: ChatbotPanelProps) {
  const auth = useAuth();
  const storeRef = useRef(store);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const questionRef = useRef<HTMLTextAreaElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [conversations, setConversations] = useState<ChatConversation[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [question, setQuestion] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'sending'>('idle');
  const [error, setError] = useState<string | null>(null);
  const selected = useMemo(
    () => conversations.find(({ id }) => id === selectedId) ?? null,
    [conversations, selectedId],
  );

  useLayoutEffect(() => {
    const textarea = questionRef.current;
    if (textarea == null) return;

    textarea.style.height = 'auto';
    const contentHeight = Math.max(textarea.scrollHeight, QUESTION_MIN_HEIGHT_PX);
    textarea.style.height = `${Math.min(contentHeight, QUESTION_MAX_HEIGHT_PX)}px`;
    textarea.style.overflowY = contentHeight > QUESTION_MAX_HEIGHT_PX ? 'auto' : 'hidden';
  }, [isOpen, question]);

  useEffect(() => {
    if (isOpen && status === 'idle' && selectedId != null) questionRef.current?.focus();
  }, [isOpen, selectedId, status]);

  async function openPanel() {
    if (auth.status !== 'authenticated') {
      auth.openDialog(launcherRef.current ?? undefined);
      return;
    }
    setIsOpen(true);
    onOpenChange?.(true);
    setStatus('loading');
    setError(null);
    try {
      await reloadConversations(true);
    } catch {
      setError('브라우저 대화 저장소를 열지 못했습니다.');
    } finally {
      setStatus('idle');
    }
  }

  function requiredStore(): IndexedDbChatConversationStore {
    storeRef.current ??= new IndexedDbChatConversationStore();
    return storeRef.current;
  }

  async function reloadConversations(createWhenEmpty: boolean) {
    let next = await requiredStore().list();
    if (next.length === 0 && createWhenEmpty) {
      const conversation = createChatConversation();
      await requiredStore().save(conversation);
      next = [conversation];
    }
    setConversations(next);
    setSelectedId((current) => next.some(({ id }) => id === current) ? current : (next[0]?.id ?? null));
  }

  async function createConversation() {
    setError(null);
    try {
      const conversation = createChatConversation();
      await requiredStore().save(conversation);
      await reloadConversations(false);
      setSelectedId(conversation.id);
      setQuestion('');
      setIsHistoryOpen(false);
      questionRef.current?.focus();
    } catch {
      setError('새 대화를 만들지 못했습니다.');
    }
  }

  async function deleteSelected() {
    if (selected == null) return;
    setError(null);
    try {
      await requiredStore().delete(selected.id);
      await reloadConversations(false);
    } catch {
      setError('대화를 삭제하지 못했습니다.');
    }
  }

  async function deleteAll() {
    if (!window.confirm('브라우저에 저장된 모든 챗봇 대화를 삭제할까요?')) return;
    setError(null);
    try {
      await requiredStore().clear();
      await reloadConversations(false);
    } catch {
      setError('전체 대화를 삭제하지 못했습니다.');
    }
  }

  async function exportConversations() {
    setError(null);
    try {
      const archive = await requiredStore().exportArchive();
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
      await requiredStore().importArchive(await file.text(), 'merge');
      await reloadConversations(true);
    } catch {
      setError('가져올 대화 파일을 확인해주세요.');
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
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
    try {
      await saveAndRefresh(pending);
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
      await saveAndRefresh({
        ...pending,
        updatedAt: answeredAt,
        messages: [...pending.messages, {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: response.answer,
          createdAt: answeredAt,
          evidence,
        }],
      });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '챗봇 요청을 완료하지 못했습니다.');
    } finally {
      setStatus('idle');
    }
  }

  async function saveAndRefresh(conversation: ChatConversation) {
    await requiredStore().save(conversation);
    const next = await requiredStore().list();
    setConversations(next);
    setSelectedId(conversation.id);
  }

  function closePanel() {
    setIsOpen(false);
    onOpenChange?.(false);
    setIsHistoryOpen(false);
    requestAnimationFrame(() => launcherRef.current?.focus());
  }

  function selectConversation(id: string) {
    setSelectedId(id);
    setIsHistoryOpen(false);
  }

  function selectExampleQuestion(example: string) {
    setQuestion(example);
    questionRef.current?.focus();
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
              <button aria-label="새 대화" className="chatbot-new-conversation" onClick={() => void createConversation()} type="button">
                <EditIcon /><span>새 대화</span>
              </button>
              <div className="chatbot-history-switcher">
                <button
                  aria-expanded={isHistoryOpen}
                  aria-label={isHistoryOpen ? '대화 목록 닫기' : '대화 목록 열기'}
                  className="chatbot-history-trigger"
                  onClick={() => setIsHistoryOpen((open) => !open)}
                  type="button"
                >
                  <MenuIcon />
                </button>
                {isHistoryOpen ? (
                  <nav aria-label="저장된 대화" className="chatbot-history-popover">
                    <div className="chatbot-history-heading">
                      <strong>내 대화</strong>
                    </div>
                    <div className="chatbot-conversation-list">
                      {conversations.length > 0 ? conversations.map((conversation) => (
                        <button
                          aria-pressed={conversation.id === selectedId}
                          key={conversation.id}
                          onClick={() => selectConversation(conversation.id)}
                          title={conversation.title}
                          type="button"
                        >
                          {conversation.title}
                        </button>
                      )) : <p>저장된 대화가 없습니다.</p>}
                    </div>
                    <div className="chatbot-history-tools">
                      <button aria-label="현재 대화 삭제" disabled={selected == null} onClick={() => void deleteSelected()} type="button"><TrashIcon />현재 삭제</button>
                      <button onClick={() => void exportConversations()} type="button"><DownloadIcon />내보내기</button>
                      <button onClick={() => importInputRef.current?.click()} type="button"><UploadIcon />가져오기</button>
                      <button onClick={() => void deleteAll()} type="button"><TrashIcon />전체 삭제</button>
                    </div>
                  </nav>
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
            <div aria-busy={status === 'loading'} aria-live="polite" className="chatbot-messages">
              {status === 'loading' ? (
                <p className="chatbot-loading">대화를 불러오는 중입니다.</p>
              ) : selected?.messages.length ? selected.messages.map((message) => (
                <article className={`chatbot-message chatbot-message-${message.role}`} key={message.id}>
                  <span aria-hidden="true" className="chatbot-message-avatar">
                    {message.role === 'user' ? '나' : 'AI'}
                  </span>
                  <div className="chatbot-message-content">
                    <strong>{message.role === 'user' ? '나' : '홈서치 AI'}</strong>
                    <p>{message.content}</p>
                    {message.evidence ? <Evidence evidence={message.evidence} /> : null}
                  </div>
                </article>
              )) : (
                <div className="chatbot-empty">
                  <div className="chatbot-empty-intro">
                    <span>안녕하세요!</span>
                    <strong>어떤 집을 찾고 계세요?</strong>
                    <p>지역과 예산, 면적을 알려주시면<br />검증된 부동산 데이터로 비교해드릴게요.</p>
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
            </div>

            {error ? <p aria-live="assertive" className="chatbot-error">{error}</p> : null}
            <form className="chatbot-form" onSubmit={(event) => void submit(event)}>
              <div className="chatbot-form-heading">
                <label htmlFor="chatbot-question">홈서치 AI에게 질문하기</label>
              </div>
              <div className="chatbot-composer">
                <textarea
                  disabled={status === 'sending' || selected == null}
                  id="chatbot-question"
                  maxLength={2_000}
                  name="chatbot-question"
                  onChange={(event) => setQuestion(event.target.value)}
                  placeholder="원하는 지역과 조건을 입력해 보세요."
                  ref={questionRef}
                  rows={1}
                  value={question}
                />
                <button aria-label="질문 보내기" disabled={question.trim().length === 0 || status === 'sending'} type="submit">
                  {status === 'sending' ? <span className="chatbot-sending">확인 중</span> : <SendIcon />}
                </button>
              </div>
              <p>답변은 신고 지연 등으로 실제와 다를 수 있으니 출처와 기준일을 확인해 주세요.</p>
            </form>
          </section>
        </aside>
      ) : null}
    </>
  );
}

function Evidence({ evidence }: { evidence: ChatEvidence }) {
  return (
    <details className="chatbot-evidence">
      <summary>
        <span>답변 근거</span>
        <span>{evidence.dataAsOf ? `기준일 ${evidence.dataAsOf}` : '조회 시점 근거'}</span>
        <span>출처 {evidence.citations.length}개</span>
        <span>근거 {evidence.evidenceSummary.factCount}개</span>
      </summary>
      <div>
        <ul aria-label="답변 출처">
          {evidence.citations.map((citation) => (
            <li key={citation.citationId}>
              {citation.sourceUrl ? (
                <a href={citation.sourceUrl} rel="noreferrer noopener" target="_blank">
                  {citation.sourceName}
                </a>
              ) : citation.sourceName}
              <span>근거 등급 {citation.evidenceGrade}</span>
            </li>
          ))}
        </ul>
        {evidence.limitations.map((limitation) => <small key={limitation}>{limitation}</small>)}
      </div>
    </details>
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
    kind: 'complex-identity',
    label: '단지 정보',
    question: '래미안원베일리의 정확한 주소와 단지 기본 정보를 확인해줘',
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

function TrashIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="M4 7h16M9 7V4h6v3M6.5 7l.7 13h9.6l.7-13M10 11v5M14 11v5" /></svg>;
}

function DownloadIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="M12 4v11m0 0 4-4m-4 4-4-4M5 20h14" /></svg>;
}

function UploadIcon() {
  return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><path d="M12 16V5m0 0 4 4m-4-4L8 9M5 20h14" /></svg>;
}

function SendIcon() {
  return <svg aria-hidden="true" className="chatbot-send-icon" fill="none" viewBox="0 0 24 24"><path d="M12 18V6M6.5 11.5 12 6l5.5 5.5" /></svg>;
}
