import { useEffect, useMemo, useRef, useState } from 'react';

import { CHAT_HISTORY_DATE_GROUPS, groupConversationsByDate } from './chatHistoryGroups';
import type { ChatConversation } from './storage/chatConversationStore';

type ChatHistoryPopoverProps = {
  conversations: ChatConversation[];
  disabled?: boolean;
  selectedId: string | null;
  trigger: HTMLButtonElement | null;
  onClose: () => void;
  onDelete: (id: string) => Promise<void>;
  onDeleteAll: () => Promise<void>;
  onExport: () => void;
  onImport: () => void;
  onSelect: (id: string) => void;
};

type Confirmation =
  | { kind: 'conversation'; id: string; title: string }
  | { kind: 'all' };

export function ChatHistoryPopover({
  conversations,
  disabled = false,
  selectedId,
  trigger,
  onClose,
  onDelete,
  onDeleteAll,
  onExport,
  onImport,
  onSelect,
}: ChatHistoryPopoverProps) {
  const [rowMenuId, setRowMenuId] = useState<string | null>(null);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const popoverRef = useRef<HTMLElement>(null);
  const confirmationRef = useRef<HTMLButtonElement>(null);
  const grouped = useMemo(() => groupConversationsByDate(conversations), [conversations]);

  useEffect(() => {
    popoverRef.current?.focus();
  }, []);

  useEffect(() => {
    if (confirmation != null) confirmationRef.current?.focus();
  }, [confirmation]);

  function closeHistory() {
    onClose();
    requestAnimationFrame(() => trigger?.focus());
  }

  function handleEscape() {
    if (confirmation != null) {
      const focusTargetId = confirmation.kind === 'all'
        ? 'chat-history-delete-all'
        : `chat-history-delete-${confirmation.id}`;
      setConfirmation(null);
      requestAnimationFrame(() => document.getElementById(focusTargetId)?.focus());
      return;
    }
    if (rowMenuId != null) {
      const menuId = rowMenuId;
      setRowMenuId(null);
      requestAnimationFrame(() => document.getElementById(`chat-history-row-menu-${menuId}`)?.focus());
      return;
    }
    if (toolsOpen) {
      setToolsOpen(false);
      requestAnimationFrame(() => document.getElementById('chat-history-tools-trigger')?.focus());
      return;
    }
    closeHistory();
  }

  async function confirmDelete() {
    if (confirmation == null || isDeleting) return;
    setIsDeleting(true);
    try {
      if (confirmation.kind === 'all') await onDeleteAll();
      else await onDelete(confirmation.id);
      setConfirmation(null);
      setRowMenuId(null);
      setToolsOpen(false);
    } catch {
      // The parent renders the actionable storage error and keeps this dialog open.
    } finally {
      setIsDeleting(false);
    }
  }

  return (
    <nav
      aria-label="저장된 대화"
      className="chatbot-history-popover"
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        handleEscape();
      }}
      ref={popoverRef}
      tabIndex={-1}
    >
      <div className="chatbot-history-heading">
        <strong>내 대화</strong>
        <div className="chatbot-history-tools-menu">
          <button
            aria-expanded={toolsOpen}
            aria-haspopup="menu"
            aria-label="대화 기록 관리"
            disabled={disabled}
            id="chat-history-tools-trigger"
            onClick={() => {
              setRowMenuId(null);
              setToolsOpen((open) => !open);
            }}
            type="button"
          >
            <MoreIcon />
          </button>
          {toolsOpen ? (
            <div aria-label="대화 기록 관리 메뉴" className="chatbot-history-menu" role="menu">
              <button onClick={onImport} role="menuitem" type="button">가져오기</button>
              <button disabled={conversations.length === 0} onClick={onExport} role="menuitem" type="button">내보내기</button>
              <button
                className="chatbot-history-danger"
                disabled={conversations.length === 0}
                id="chat-history-delete-all"
                onClick={() => setConfirmation({ kind: 'all' })}
                role="menuitem"
                type="button"
              >
                전체 삭제
              </button>
            </div>
          ) : null}
        </div>
      </div>

      <div className="chatbot-conversation-list">
        {conversations.length === 0 ? <p>저장된 대화가 없습니다.</p> : CHAT_HISTORY_DATE_GROUPS.map((group) => {
          const items = grouped.get(group) ?? [];
          if (items.length === 0) return null;
          return (
            <section className="chatbot-history-group" key={group}>
              <h3>{group}</h3>
              {items.map((conversation) => (
                <div className="chatbot-history-row" data-selected={conversation.id === selectedId} key={conversation.id}>
                  <button
                    aria-current={conversation.id === selectedId ? 'page' : undefined}
                    className="chatbot-history-select"
                    disabled={disabled}
                    onClick={() => onSelect(conversation.id)}
                    title={conversation.title}
                    type="button"
                  >
                    {conversation.title}
                  </button>
                  <button
                    aria-expanded={rowMenuId === conversation.id}
                    aria-haspopup="menu"
                    aria-label={`${conversation.title} 대화 관리`}
                    className="chatbot-history-row-menu-trigger"
                    disabled={disabled}
                    id={`chat-history-row-menu-${conversation.id}`}
                    onClick={() => {
                      setToolsOpen(false);
                      setRowMenuId((current) => current === conversation.id ? null : conversation.id);
                    }}
                    type="button"
                  >
                    <MoreIcon />
                  </button>
                  {rowMenuId === conversation.id ? (
                    <div aria-label={`${conversation.title} 메뉴`} className="chatbot-history-menu chatbot-history-row-menu" role="menu">
                      <button
                        className="chatbot-history-danger"
                        id={`chat-history-delete-${conversation.id}`}
                        onClick={() => setConfirmation({
                          kind: 'conversation',
                          id: conversation.id,
                          title: conversation.title,
                        })}
                        role="menuitem"
                        type="button"
                      >
                        대화 삭제
                      </button>
                    </div>
                  ) : null}
                </div>
              ))}
            </section>
          );
        })}
      </div>

      {confirmation != null ? (
        <div
          aria-labelledby="chat-history-confirm-title"
          aria-modal="true"
          className="chatbot-confirm-backdrop"
          onKeyDown={(event) => {
            if (event.key !== 'Tab') return;
            const controls = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
            const first = controls[0];
            const last = controls.at(-1);
            if (first == null || last == null) return;
            if (event.shiftKey && document.activeElement === first) {
              event.preventDefault();
              last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault();
              first.focus();
            }
          }}
          role="dialog"
        >
          <div className="chatbot-confirm-dialog">
            <strong id="chat-history-confirm-title">
              {confirmation.kind === 'all' ? '모든 대화를 삭제할까요?' : '이 대화를 삭제할까요?'}
            </strong>
            <p>
              {confirmation.kind === 'all'
                ? '브라우저에 저장된 모든 대화가 삭제되며 복구할 수 없습니다.'
                : `“${confirmation.title}” 대화는 삭제 후 복구할 수 없습니다.`}
            </p>
            <div>
              <button disabled={isDeleting} onClick={() => setConfirmation(null)} ref={confirmationRef} type="button">취소</button>
              <button className="chatbot-confirm-delete" disabled={isDeleting} onClick={() => void confirmDelete()} type="button">
                {isDeleting ? '삭제 중' : '삭제'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </nav>
  );
}

function MoreIcon() {
  return <svg aria-hidden="true" fill="currentColor" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="19" cy="12" r="1.5" /></svg>;
}
