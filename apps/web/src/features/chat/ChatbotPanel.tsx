import { type ChangeEvent, type FormEvent, useMemo, useRef, useState } from 'react';

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
  store?: IndexedDbChatConversationStore;
};

export function ChatbotPanel({ store }: ChatbotPanelProps) {
  const auth = useAuth();
  const storeRef = useRef(store);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [conversations, setConversations] = useState<ChatConversation[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [question, setQuestion] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'sending'>('idle');
  const [error, setError] = useState<string | null>(null);
  const selected = useMemo(
    () => conversations.find(({ id }) => id === selectedId) ?? null,
    [conversations, selectedId],
  );

  async function openPanel() {
    if (auth.status !== 'authenticated') {
      auth.openDialog(launcherRef.current ?? undefined);
      return;
    }
    setIsOpen(true);
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

  return (
    <>
      <button
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        className="chatbot-launcher"
        disabled={auth.status === 'checking'}
        onClick={() => isOpen ? setIsOpen(false) : void openPanel()}
        ref={launcherRef}
        type="button"
      >
        AI 챗봇
      </button>
      {isOpen ? (
        <aside aria-label="근거 기반 부동산 챗봇" className="chatbot-panel" role="dialog">
          <header className="chatbot-panel-header">
            <div>
              <strong>근거 기반 챗봇</strong>
              <span>대화는 이 브라우저에만 저장됩니다.</span>
            </div>
            <button aria-label="챗봇 닫기" onClick={() => setIsOpen(false)} type="button">×</button>
          </header>

          <div className="chatbot-conversation-tools">
            <select
              aria-label="대화 선택"
              onChange={(event) => setSelectedId(event.target.value)}
              value={selectedId ?? ''}
            >
              {conversations.map((conversation) => (
                <option key={conversation.id} value={conversation.id}>{conversation.title}</option>
              ))}
            </select>
            <button onClick={() => void createConversation()} type="button">새 대화</button>
            <button disabled={selected == null} onClick={() => void deleteSelected()} type="button">현재 삭제</button>
            <button onClick={() => void deleteAll()} type="button">전체 삭제</button>
            <button onClick={() => void exportConversations()} type="button">내보내기</button>
            <button onClick={() => importInputRef.current?.click()} type="button">가져오기</button>
            <input
              accept="application/json,.json"
              aria-label="대화 파일 가져오기"
              hidden
              onChange={(event) => void importConversations(event)}
              ref={importInputRef}
              type="file"
            />
          </div>

          <div aria-busy={status === 'loading'} aria-live="polite" className="chatbot-messages">
            {selected?.messages.length ? selected.messages.map((message) => (
              <article className={`chatbot-message chatbot-message-${message.role}`} key={message.id}>
                <strong>{message.role === 'user' ? '나' : '홈서치'}</strong>
                <p>{message.content}</p>
                {message.evidence ? <Evidence evidence={message.evidence} /> : null}
              </article>
            )) : <p className="chatbot-empty">단지명, 기간, 면적을 포함해 질문해보세요.</p>}
          </div>

          {error ? <p aria-live="assertive" className="chatbot-error">{error}</p> : null}
          <form className="chatbot-form" onSubmit={(event) => void submit(event)}>
            <label htmlFor="chatbot-question">질문</label>
            <textarea
              disabled={status === 'sending' || selected == null}
              id="chatbot-question"
              maxLength={2_000}
              name="chatbot-question"
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="예: 잠실엘스 전용 84㎡ 최근 거래"
              rows={3}
              value={question}
            />
            <button disabled={question.trim().length === 0 || status === 'sending'} type="submit">
              {status === 'sending' ? '확인 중' : '질문하기'}
            </button>
          </form>
        </aside>
      ) : null}
    </>
  );
}

function Evidence({ evidence }: { evidence: ChatEvidence }) {
  return (
    <div className="chatbot-evidence">
      <span>{evidence.dataAsOf ? `기준일 ${evidence.dataAsOf}` : '조회 시점 근거'}</span>
      <span>출처 {evidence.citations.length}개</span>
      <span>근거 {evidence.evidenceSummary.factCount}개</span>
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
  );
}
